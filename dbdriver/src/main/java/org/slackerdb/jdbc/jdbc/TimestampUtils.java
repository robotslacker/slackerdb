/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.jdbc;

import static org.slackerdb.jdbc.util.internal.Nullness.castNonNull;

import org.slackerdb.jdbc.PGStatement;
import org.slackerdb.jdbc.core.JavaVersion;
import org.slackerdb.jdbc.core.Oid;
import org.slackerdb.jdbc.core.Provider;
import org.slackerdb.jdbc.util.ByteConverter;
import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.chrono.IsoEra;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Objects;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/**
 * Misc utils for handling time and date values.
 */
public class TimestampUtils {
  /**
   * Number of milliseconds in one day.
   */
  private static final int ONEDAY = 24 * 3600 * 1000;
  private static final char[] ZEROS = {'0', '0', '0', '0', '0', '0', '0', '0', '0'};
  private static final char[][] NUMBERS;
  private static final HashMap<String, TimeZone> GMT_ZONES = new HashMap<>();
  private static final int MAX_NANOS_BEFORE_WRAP_ON_ROUND = 999999500;
  private static final Duration ONE_MICROSECOND = Duration.ofNanos(1000);
  // LocalTime.MAX is 23:59:59.999_999_999, and it wraps to 24:00:00 when nanos exceed 999_999_499
  // since PostgreSQL has microsecond resolution only
  private static final LocalTime MAX_TIME = LocalTime.MAX.minus(Duration.ofNanos(500));
  private static final long MAX_TIME_NANOS = MAX_TIME.toNanoOfDay();
  private static final OffsetDateTime MAX_OFFSET_DATETIME = OffsetDateTime.MAX.minus(Duration.ofMillis(500));
  private static final LocalDateTime MAX_LOCAL_DATETIME = LocalDateTime.MAX.minus(Duration.ofMillis(500));
  // low value for dates is   4713 BC
  private static final LocalDate MIN_LOCAL_DATE = LocalDate.of(4713, 1, 1).with(ChronoField.ERA, IsoEra.BCE.getValue());
  private static final LocalDateTime MIN_LOCAL_DATETIME = MIN_LOCAL_DATE.atStartOfDay();
  private static final OffsetDateTime MIN_OFFSET_DATETIME = MIN_LOCAL_DATETIME.atOffset(ZoneOffset.UTC);
  private static final Duration PG_EPOCH_DIFF =
      Duration.between(Instant.EPOCH, LocalDate.of(2000, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC));

  private static final @Nullable Field DEFAULT_TIME_ZONE_FIELD;

  private static final TimeZone UTC_TIMEZONE = TimeZone.getTimeZone(ZoneOffset.UTC);

  private static final byte []INFINITY = "infinity".getBytes();
  private static final byte []NEGATIVE_INFINITY = "-infinity".getBytes();

  private static final byte [] MAX_OFFSET = "24:00:00".getBytes();

  private @Nullable TimeZone prevDefaultZoneFieldValue;
  private @Nullable TimeZone defaultTimeZoneCache;

  static {
    // The expected maximum value is 60 (seconds), so 64 is used "just in case"
    NUMBERS = new char[64][];
    for (int i = 0; i < NUMBERS.length; i++) {
      NUMBERS[i] = ((i < 10 ? "0" : "") + Integer.toString(i)).toCharArray();
    }

    // Backend's gmt-3 means GMT+03 in Java. Here a map is created so gmt-3 can be converted to
    // java TimeZone
    for (int i = -12; i <= 14; i++) {
      TimeZone timeZone;
      String pgZoneName;
      if (i == 0) {
        timeZone = TimeZone.getTimeZone("GMT");
        pgZoneName = "GMT";
      } else {
        timeZone = TimeZone.getTimeZone("GMT" + (i <= 0 ? "+" : "-") + Math.abs(i));
        pgZoneName = "GMT" + (i >= 0 ? "+" : "-");
      }

      if (i == 0) {
        GMT_ZONES.put(pgZoneName, timeZone);
        continue;
      }
      GMT_ZONES.put(pgZoneName + Math.abs(i), timeZone);
      GMT_ZONES.put(pgZoneName + new String(NUMBERS[Math.abs(i)]), timeZone);
    }
    // Fast path to getting the default timezone.
    // Accessing the default timezone over and over creates a clone with regular API.
    // Because we don't mutate that object in our use of it, we can access the field directly.
    // This saves the creation of a clone everytime, and the memory associated to all these clones.
    Field tzField;
    try {
      tzField = null;
      // Avoid reflective access in Java 9+
      if (JavaVersion.getRuntimeVersion().compareTo(JavaVersion.v1_8) <= 0) {
        tzField = TimeZone.class.getDeclaredField("defaultTimeZone");
        tzField.setAccessible(true);
        TimeZone defaultTz = TimeZone.getDefault();
        Object tzFromField = tzField.get(null);
        if (defaultTz == null || !defaultTz.equals(tzFromField)) {
          tzField = null;
        }
      }
    } catch (Exception e) {
      tzField = null;
    }
    DEFAULT_TIME_ZONE_FIELD = tzField;
  }

  private final StringBuilder sbuf = new StringBuilder();

  // This calendar is used when user provides calendar in setX(, Calendar) method.
  // It ensures calendar is Gregorian.
  private final Calendar calendarWithUserTz = new GregorianCalendar();

  private @Nullable Calendar calCache;
  private @Nullable ZoneOffset calCacheZone;

  /**
   * True if the backend uses doubles for time values. False if long is used.
   */
  private final boolean usesDouble;
  private final Provider<TimeZone> timeZoneProvider;
  private final ResourceLock lock = new ResourceLock();

  public TimestampUtils(boolean usesDouble, Provider<TimeZone> timeZoneProvider) {
    this.usesDouble = usesDouble;
    this.timeZoneProvider = timeZoneProvider;
  }

  private Calendar getCalendar(ZoneOffset offset) {
    if (calCache != null && Objects.equals(offset, calCacheZone)) {
      return calCache;
    }

    // normally we would use:
    // calCache = new GregorianCalendar(TimeZone.getTimeZone(offset));
    // But this seems to cause issues for some crazy offsets as returned by server for BC dates!
    final String tzid = offset.getTotalSeconds() == 0 ? "UTC" : "GMT".concat(offset.getId());
    final TimeZone syntheticTZ = new SimpleTimeZone(offset.getTotalSeconds() * 1000, tzid);
    calCache = new GregorianCalendar(syntheticTZ);
    calCacheZone = offset;
    return calCache;
  }

  private static class ParsedTimestamp {
    boolean hasDate;
    int era = GregorianCalendar.AD;
    int year = 1970;
    int month = 1;

    boolean hasTime;
    int day = 1;
    int hour;
    int minute;
    int second;
    int nanos;

    boolean hasOffset;
    ZoneOffset offset = ZoneOffset.UTC;
  }

  private static class ParsedBinaryTimestamp {
    @Nullable Infinity infinity;
    long millis;
    int nanos;
  }

  enum Infinity {
    POSITIVE,
    NEGATIVE
  }

  /**
   * Load date/time information into the provided calendar returning the fractional seconds.
   */
  private ParsedTimestamp parseBackendTimestamp(byte[] s) throws SQLException {
    int slen = s.length;

    // This is pretty gross..
    ParsedTimestamp result = new ParsedTimestamp();

    // We try to parse these fields in order; all are optional
    // (but some combinations don't make sense, e.g. if you have
    // both date and time then they must be whitespace-separated).
    // At least one of date and time must be present.

    // leading whitespace
    // yyyy-mm-dd
    // whitespace
    // hh:mm:ss
    // whitespace
    // timezone in one of the formats: +hh, -hh, +hh:mm, -hh:mm
    // whitespace
    // if date is present, an era specifier: AD or BC
    // trailing whitespace

    try {
      int start = skipWhitespace(s, 0); // Skip leading whitespace
      int end = firstNonDigit(s, start);
      int num;
      byte sep;

      // Possibly read date.
      if (end < slen && s[end] == '-') {
        //
        // Date
        //
        result.hasDate = true;

        // year
        result.year = number(s, start, end);
        start = end + 1; // Skip '-'

        // month
        end = firstNonDigit(s, start);
        result.month = number(s, start, end);

        if (end < slen) {
          sep = s[end];

          if (sep != '-') {
            throw new NumberFormatException(
                "Expected date to be dash-separated, got '" + sep + "'");
          }
        }
        start = end + 1; // Skip '-'

        // day of month
        end = firstNonDigit(s, start);
        result.day = number(s, start, end);

        start = skipWhitespace(s, end); // Skip trailing whitespace
      }

      // Possibly read time.
      if (start < slen && Character.isDigit(s[start])) {
        //
        // Time.
        //

        result.hasTime = true;

        // Hours

        end = firstNonDigit(s, start);
        result.hour = number(s, start, end);

        if (end < slen) {
          sep = s[end];
          if (sep != ':') {
            throw new NumberFormatException(
                "Expected time to be colon-separated, got '" + sep + "'");
          }
        }
        start = end + 1; // Skip ':'

        // minutes

        end = firstNonDigit(s, start);
        result.minute = number(s, start, end);

        if (end < slen) {
          sep = s[end];
          if (sep != ':') {
            throw new NumberFormatException(
                "Expected time to be colon-separated, got '" + sep + "'");
          }
        }

        start = end + 1; // Skip ':'

        // seconds

        end = firstNonDigit(s, start);
        result.second = number(s, start, end);
        start = end;

        // Fractional seconds.
        if (((start < slen) ? s[start] : 0) == '.') {
          end = firstNonDigit(s, start + 1); // Skip '.'
          num = number(s, start + 1, end);

          for (int numlength = end - (start + 1); numlength < 9; numlength++) {
            num *= 10;
          }

          result.nanos = num;
          start = end;
        }

        start = skipWhitespace(s, start); // Skip trailing whitespace
      }

      // Possibly read timezone.
      sep = (start < slen) ? s[start] : 0;
      if (sep == '-' || sep == '+') {
        result.hasOffset = true;

        int tzsign = sep == '-' ? -1 : 1;
        int tzhr;
        int tzmin;
        int tzsec;

        end = firstNonDigit(s, start + 1); // Skip +/-
        tzhr = number(s, start + 1, end);
        start = end;

        sep = (start < slen) ? s[start] : 0;
        if (sep == ':') {
          end = firstNonDigit(s, start + 1); // Skip ':'
          tzmin = number(s, start + 1, end);
          start = end;
        } else {
          tzmin = 0;
        }

        tzsec = 0;
        sep = (start < slen) ? s[start] : 0;
        if (sep == ':') {
          end = firstNonDigit(s, start + 1); // Skip ':'
          tzsec = number(s, start + 1, end);
          start = end;
        }

        result.offset = ZoneOffset.ofHoursMinutesSeconds(tzsign * tzhr, tzsign * tzmin, tzsign * tzsec);

        start = skipWhitespace(s, start); // Skip trailing whitespace
      }

      if (result.hasDate && start < slen) {
        if (slen - start >= 2) {
          if (s[start] == 'A' && s[start + 1] == 'D') {
            result.era = GregorianCalendar.AD;
            start += 2;
          } else if (s[start] == 'B' && s[start + 1] == 'C') {
            result.era = GregorianCalendar.BC;
            start += 2;
          }
        }
      }

      if (start < slen) {
        throw new NumberFormatException(
            "Trailing junk on timestamp: '" + new String(s, start, slen - start) + "'");
      }

      if (!result.hasTime && !result.hasDate) {
        throw new NumberFormatException("Timestamp has neither date nor time");
      }

    } catch (NumberFormatException nfe) {
      throw new PSQLException(
          GT.tr("Bad value for type timestamp/date/time: {0}", new String(s)),
          PSQLState.BAD_DATETIME_FORMAT, nfe);
    }

    return result;
  }

  ParsedTimestamp parseDate(byte[]dateBytes) {
    ParsedTimestamp parsedTimestamp = new ParsedTimestamp();
    int length = dateBytes.length;

    if (dateBytes[length - 2] == 'B' && dateBytes[length - 1] == 'C') {
      length = length - 3;
      parsedTimestamp.era = GregorianCalendar.BC;
    }
    int var1 = 0;
    for (parsedTimestamp.year = 0; dateBytes[var1] != 45; parsedTimestamp.year = parsedTimestamp.year * 10 + (dateBytes[var1++] - 48)) {
    }

    ++var1;

    for (parsedTimestamp.month = 0; dateBytes[var1] != 45; parsedTimestamp.month = parsedTimestamp.month * 10 + (dateBytes[var1++] - 48)) {
    }

    ++var1;

    for (parsedTimestamp.day = 0; var1 < length; parsedTimestamp.day = parsedTimestamp.day * 10 + (dateBytes[var1++] - 48)) {

    }

    return parsedTimestamp;
  }

  /**
   * Parse a string and return a timestamp representing its value.
   *
   * @param cal calendar to be used to parse the input string
   * @param s The ISO formatted date string to parse.
   * @return null if s is null or a timestamp of the parsed string s.
   * @throws SQLException if there is a problem parsing s.
   * @deprecated use {@link #toTimestamp(Calendar, byte[])}
   */
  @Deprecated
  public @PolyNull Timestamp toTimestamp(@Nullable Calendar cal,
      @PolyNull String s) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (s == null) {
        return null;
      }
      return toTimestamp(cal, s.getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * Parse an array of bytes and return a timestamp representing its value.
   *
   * @param cal calendar to be used to parse the input bytes
   * @param bytes The ISO formatted date to parse.
   * @return null if bytes is null or a timestamp of the parsed bytes.
   * @throws SQLException if there is a problem parsing bytes.
   */
  public @PolyNull Timestamp toTimestamp(@Nullable Calendar cal,
      byte @PolyNull []bytes) throws SQLException {

    try (ResourceLock ignore = lock.obtain()) {
      if (bytes == null) {
        return null;
      }

      int blen = bytes.length;

      // convert postgres's infinity values to internal infinity magic value
      if (bytes[0] == 'i' && Arrays.equals(bytes,INFINITY)) {
        return new Timestamp(PGStatement.DATE_POSITIVE_INFINITY);
      }

      if (bytes[0] == '-' && Arrays.equals(bytes,NEGATIVE_INFINITY)) {
        return new Timestamp(PGStatement.DATE_NEGATIVE_INFINITY);
      }

      ParsedTimestamp ts = parseBackendTimestamp(bytes);
      Calendar useCal = ts.hasOffset ? getCalendar(ts.offset) : setupCalendar(cal);
      useCal.set(Calendar.ERA, ts.era);
      useCal.set(Calendar.YEAR, ts.year);
      useCal.set(Calendar.MONTH, ts.month - 1);
      useCal.set(Calendar.DAY_OF_MONTH, ts.day);
      useCal.set(Calendar.HOUR_OF_DAY, ts.hour);
      useCal.set(Calendar.MINUTE, ts.minute);
      useCal.set(Calendar.SECOND, ts.second);
      useCal.set(Calendar.MILLISECOND, 0);

      Timestamp result = new Timestamp(useCal.getTimeInMillis());
      result.setNanos(ts.nanos);
      return result;
    }
  }

  /**
   * Parse a string and return a LocalTime representing its value.
   *
   * @param s The ISO formatted time string to parse.
   * @return null if s is null or a LocalTime of the parsed string s.
   * @throws SQLException if there is a problem parsing s.
   */
  public @PolyNull LocalTime toLocalTime(@PolyNull String s) throws SQLException {
    if (s == null) {
      return null;
    }

    if ("24:00:00".equals(s)) {
      return LocalTime.MAX;
    }

    try {
      return LocalTime.parse(s);
    } catch (DateTimeParseException nfe) {
      throw new PSQLException(
          GT.tr("Bad value for type timestamp/date/time: {0}", s),
          PSQLState.BAD_DATETIME_FORMAT, nfe);
    }

  }

  /**
   * Returns the offset time object matching the given bytes with Oid#TIMETZ or Oid#TIME.
   *
   * @param bytes The binary encoded TIMETZ/TIME value.
   * @return The parsed offset time object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public OffsetTime toOffsetTimeBin(byte[] bytes) throws PSQLException {
    if (bytes.length != 12) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.", "time"),
          PSQLState.BAD_DATETIME_FORMAT);
    }

    final long micros;

    if (usesDouble) {
      double seconds = ByteConverter.float8(bytes, 0);
      micros = (long) (seconds * 1_000_000d);
    } else {
      micros = ByteConverter.int8(bytes, 0);
    }

    // postgres offset is negative, so we have to flip sign:
    final ZoneOffset timeOffset = ZoneOffset.ofTotalSeconds(-ByteConverter.int4(bytes, 8));

    return OffsetTime.of(LocalTime.ofNanoOfDay(Math.multiplyExact(micros, 1000L)), timeOffset);
  }

  /**
   * Parse a string and return a OffsetTime representing its value.
   *
   * @param s The ISO formatted time string to parse.
   * @return null if s is null or a OffsetTime of the parsed string s.
   * @throws SQLException if there is a problem parsing s.
   * @deprecated  in use {@link #toOffsetTime(byte[])} instead
   */
  @Deprecated
  public @PolyNull OffsetTime toOffsetTime(@PolyNull String s) throws SQLException {
    if (s == null) {
      return null;
    }
    return toOffsetTime(s.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Parse an array of bytes and return a OffsetTime representing its value.
   *
   * @param bytes The ISO time formatted array of bytes time to parse.
   * @return null if bytes are null or a OffsetTime of the parsed string .
   * @throws SQLException if there is a problem parsing bytes.
   */
  public @PolyNull OffsetTime toOffsetTime(byte @PolyNull []bytes) throws SQLException {
    if (bytes == null) {
      return null;
    }
    // Not sure how to do this. There is no 24:00:00 in java, the largest time is 23:59:59.999999999-18:00
    for ( int i = 0; i < 8; i++ ) {
      if (bytes[i] != MAX_OFFSET[i]) {
        break;
      } else if (i == 7) {
        return OffsetTime.MAX;
      }
    }

    final ParsedTimestamp ts = parseBackendTimestamp(bytes);
    return OffsetTime.of(ts.hour, ts.minute, ts.second, ts.nanos, ts.offset);
  }

  /**
   * @param s The ISO formatted date string to parse.
   * @return null if s is null or a LocalDateTime of the parsed string s.
   * @throws SQLException if there is a problem parsing s.
   * @deprecated use {@link #toLocalDateTime(byte[])}
  */
  @Deprecated
  public @PolyNull LocalDateTime toLocalDateTime(@PolyNull String s) throws SQLException {
    if (s == null) {
      return null;
    }
    return toLocalDateTime(s.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Parse an array of bytes and return a LocalDateTime representing its value.
   *
   * @param bytes The ISO formatted date array of bytes to parse.
   * @return null if s is null or a LocalDateTime of the parsed string s.
   * @throws SQLException if there is a problem parsing bytes.
   */
  public @PolyNull LocalDateTime toLocalDateTime(byte @PolyNull []bytes) throws SQLException {
    if (bytes == null) {
      return null;
    }

    if (bytes[0] == 'i' && Arrays.equals(INFINITY, bytes)) {
      return LocalDateTime.MAX;
    }

    if (bytes[0] == '-' && Arrays.equals(NEGATIVE_INFINITY, bytes)) {
      return LocalDateTime.MIN;
    }

    ParsedTimestamp ts = parseBackendTimestamp(bytes);

    // intentionally ignore time zone
    // 2004-10-19 10:23:54+03:00 is 2004-10-19 10:23:54 locally
    LocalDateTime result = LocalDateTime.of(ts.year, ts.month, ts.day, ts.hour, ts.minute, ts.second, ts.nanos);
    if (ts.era == GregorianCalendar.BC) {
      return result.with(ChronoField.ERA, IsoEra.BCE.getValue());
    } else {
      return result;
    }
  }

  /**
   * Returns the offset date time object matching the given bytes with Oid#TIMETZ.
   * Not used internally anymore, function is here to retain compatibility with previous versions
   *
   * @param t the time value
   * @return the matching offset date time
   * @deprecated was used internally, and not used anymore
   */
  @Deprecated
  public OffsetDateTime toOffsetDateTime(Time t) {
    // hardcode utc because the backend does not provide us the timezone
    // hardcode UNIX epoch, JDBC requires OffsetDateTime but doesn't describe what date should be used
    return t.toLocalTime().atDate(LocalDate.of(1970, 1, 1)).atOffset(ZoneOffset.UTC);
  }

  /**
   * Parse a string and return a OffsetDateTime representing its value.
   *
   * @param s The ISO formatted date string to parse.
   * @return null if s is null or a OffsetDateTime of the parsed string s.
   * @throws SQLException if there is a problem parsing s.
   * @deprecated  use {@link #toOffsetDateTimeBin(byte[])}
   */
  @Deprecated
  public @PolyNull OffsetDateTime toOffsetDateTime(
      @PolyNull String s) throws SQLException {
    if (s == null) {
      return null;
    }
    return toOffsetDateTime(s.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Parse an array of bytes and return a OffsetDateTime representing its value.
   *
   * @param bytes The ISO formatted date string to parse.
   * @return null if bytes is null or an OffsetDateTime of the parsed array of bytes.
   * @throws SQLException if there is a problem parsing bytes.
   */
  public @PolyNull OffsetDateTime toOffsetDateTime(
      byte @PolyNull[]bytes) throws SQLException {

    if (bytes == null) {
      return null;
    }

    // convert postgres's infinity values to internal infinity magic value
    if (bytes[0] == 'i' && Arrays.equals(INFINITY, bytes)) {
      return OffsetDateTime.MAX;
    }

    if (bytes[0] == '-' && Arrays.equals(NEGATIVE_INFINITY, bytes)) {
      return OffsetDateTime.MIN;
    }

    final ParsedTimestamp ts = parseBackendTimestamp(bytes);
    OffsetDateTime result =
        OffsetDateTime.of(ts.year, ts.month, ts.day, ts.hour, ts.minute, ts.second, ts.nanos, ts.offset);
    if (ts.era == GregorianCalendar.BC) {
      return result.with(ChronoField.ERA, IsoEra.BCE.getValue());
    } else {
      return result;
    }
  }

  /**
   * Returns the offset date time object matching the given bytes with Oid#TIMESTAMPTZ.
   *
   * @param bytes The binary encoded local date time value.
   * @return The parsed local date time object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public OffsetDateTime toOffsetDateTimeBin(byte[] bytes) throws PSQLException {
    ParsedBinaryTimestamp parsedTimestamp = this.toProlepticParsedTimestampBin(bytes);
    if (parsedTimestamp.infinity == Infinity.POSITIVE) {
      return OffsetDateTime.MAX;
    } else if (parsedTimestamp.infinity == Infinity.NEGATIVE) {
      return OffsetDateTime.MIN;
    }

    // hardcode utc because the backend does not provide us the timezone
    // Postgres is always UTC
    Instant instant = Instant.ofEpochSecond(parsedTimestamp.millis / 1000L, parsedTimestamp.nanos);
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  @Deprecated
  public @PolyNull Time toTime(
      @Nullable Calendar cal, @PolyNull String s) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      // 1) Parse backend string
      if (s == null) {
        return null;
      }
      return toTime(cal, s.getBytes(StandardCharsets.UTF_8));
    }
  }

  public @PolyNull Time toTime(
      @Nullable Calendar cal, byte @PolyNull []bytes) throws SQLException {

    try (ResourceLock ignore = lock.obtain()) {
      // 1) Parse backend string
      if (bytes == null) {
        return null;
      }
      ParsedTimestamp ts = parseBackendTimestamp(bytes);
      Calendar useCal = ts.hasOffset ? getCalendar(ts.offset) : setupCalendar(cal);
      if (!ts.hasOffset) {
        // When no time zone provided (e.g. time or timestamp)
        // We get the year-month-day from the string, then truncate the day to 1970-01-01
        // This is used for timestamp -> time conversion
        // Note: this cannot be merged with "else" branch since
        // timestamps at which the time flips to/from DST depend on the date
        // For instance, 2000-03-26 02:00:00 is invalid timestamp in Europe/Moscow time zone
        // and the valid one is 2000-03-26 03:00:00. That is why we parse full timestamp
        // then set year to 1970 later
        useCal.set(Calendar.ERA, ts.era);
        useCal.set(Calendar.YEAR, ts.year);
        useCal.set(Calendar.MONTH, ts.month - 1);
        useCal.set(Calendar.DAY_OF_MONTH, ts.day);
      } else {
        // When time zone is given, we just pick the time part and assume date to be 1970-01-01
        // this is used for time, timez, and timestamptz parsing
        useCal.set(Calendar.ERA, GregorianCalendar.AD);
        useCal.set(Calendar.YEAR, 1970);
        useCal.set(Calendar.MONTH, Calendar.JANUARY);
        useCal.set(Calendar.DAY_OF_MONTH, 1);
      }
      useCal.set(Calendar.HOUR_OF_DAY, ts.hour);
      useCal.set(Calendar.MINUTE, ts.minute);
      useCal.set(Calendar.SECOND, ts.second);
      useCal.set(Calendar.MILLISECOND, 0);

      long timeMillis = useCal.getTimeInMillis() + ts.nanos / 1000000;
      if (ts.hasOffset || (ts.year == 1970 && ts.era == GregorianCalendar.AD)) {
        // time with time zone has proper time zone, so the value can be returned as is
        return new Time(timeMillis);
      }

      // 2) Truncate date part so in given time zone the date would be formatted as 01/01/1970
      return convertToTime(timeMillis, useCal.getTimeZone());
    }
  }

  @Deprecated
  public @PolyNull Date toDate(@Nullable Calendar cal,
      @PolyNull String s) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (s == null) {
        return null;
      }
      return toDate(cal, s.getBytes(StandardCharsets.UTF_8));
    }
  }

  public @PolyNull Date toDate(@Nullable Calendar cal,
      byte @PolyNull []dateBytes) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (dateBytes == null) {
        return null;
      }
      if (dateBytes[0] == 'i' && Arrays.equals(INFINITY, dateBytes)) {
        return new Date(PGStatement.DATE_POSITIVE_INFINITY);
      }
      if (dateBytes[0] == '-' && Arrays.equals(NEGATIVE_INFINITY, dateBytes)) {
        return new Date(PGStatement.DATE_NEGATIVE_INFINITY);
      }
      if ( cal == null ) {
        cal = Calendar.getInstance();
      }

      ParsedTimestamp pt;
      if ( dateBytes.length > 13 ) {
        // this is a timestamp
        pt = parseBackendTimestamp(dateBytes);

        Calendar useCal = pt.hasOffset ? getCalendar(pt.offset) : setupCalendar(cal);
        useCal.set(Calendar.ERA, pt.era);
        useCal.set(Calendar.YEAR, pt.year);
        useCal.set(Calendar.MONTH, pt.month - 1);
        useCal.set(Calendar.DAY_OF_MONTH, pt.day);
        useCal.set(Calendar.HOUR_OF_DAY, pt.hour);
        useCal.set(Calendar.MINUTE, pt.minute);
        useCal.set(Calendar.SECOND, pt.second);
        useCal.set(Calendar.MILLISECOND, 0);

        return convertToDate(useCal.getTimeInMillis(), cal == null ? null : cal.getTimeZone());
      } else {
        pt = parseDate(dateBytes);
        // dates without time don't require timezone adjustment
        cal.clear();

        cal.set(Calendar.YEAR, pt.year);
        cal.set(Calendar.DAY_OF_MONTH, pt.day);
        cal.set(Calendar.MONTH, pt.month - 1);
        cal.set(Calendar.ERA, pt.era);

        return new Date(cal.getTimeInMillis());
      }
    }
  }

  public @PolyNull LocalDate toLocalDate( byte @PolyNull []dateBytes) throws SQLException {

    try (ResourceLock ignore = lock.obtain()) {
      if (dateBytes == null) {
        return null;
      }
      if (dateBytes[0] == 'i' && Arrays.equals(INFINITY, dateBytes)) {
        return LocalDateTime.MAX.toLocalDate();
      }
      if (dateBytes[0] == '-' && Arrays.equals(NEGATIVE_INFINITY, dateBytes)) {
        return LocalDateTime.MIN.toLocalDate();
      }
      ParsedTimestamp pt = parseDate(dateBytes);
      LocalDateTime ldt = LocalDateTime.of(pt.year, pt.month, pt.day, pt.hour, pt.minute, pt.second, pt.nanos);
      if (pt.era == GregorianCalendar.BC) {
        return ldt.toLocalDate().with(ChronoField.ERA, IsoEra.BCE.getValue());
      } else {
        return ldt.toLocalDate();
      }
    }
  }

  private Calendar setupCalendar(@Nullable Calendar cal) {
    TimeZone timeZone = cal == null ? null : cal.getTimeZone();
    return getSharedCalendar(timeZone);
  }

  /**
   * Get a shared calendar, applying the supplied time zone or the default time zone if null.
   *
   * @param timeZone time zone to be set for the calendar
   * @return The shared calendar.
   */
  public Calendar getSharedCalendar(@Nullable TimeZone timeZone) {
    if (timeZone == null) {
      timeZone = getDefaultTz();
    }
    Calendar tmp = calendarWithUserTz;
    tmp.setTimeZone(timeZone);
    return tmp;
  }

  /**
   * Returns true when microsecond part of the time should be increased
   * when rounding to microseconds
   * @param nanos nanosecond part of the time
   * @return true when microsecond part of the time should be increased when rounding to microseconds
   */
  private static boolean nanosExceed499(int nanos) {
    return nanos % 1000 > 499;
  }

  public String toString(@Nullable Calendar cal, Timestamp x) {
    return toString(cal, x, true);
  }

  public String toString(@Nullable Calendar cal, Timestamp x,
      boolean withTimeZone) {
    try (ResourceLock ignore = lock.obtain()) {
      if (x.getTime() == PGStatement.DATE_POSITIVE_INFINITY) {
        return "infinity";
      } else if (x.getTime() == PGStatement.DATE_NEGATIVE_INFINITY) {
        return "-infinity";
      }

      cal = setupCalendar(cal);
      long timeMillis = x.getTime();

      // Round to microseconds
      int nanos = x.getNanos();
      if (nanos >= MAX_NANOS_BEFORE_WRAP_ON_ROUND) {
        nanos = 0;
        timeMillis++;
      } else if (nanosExceed499(nanos)) {
        // PostgreSQL does not support nanosecond resolution yet, and appendTime will just ignore
        // 0..999 part of the nanoseconds, however we subtract nanos % 1000 to make the value
        // a little bit saner for debugging reasons
        nanos += 1000 - nanos % 1000;
      }
      cal.setTimeInMillis(timeMillis);

      sbuf.setLength(0);

      appendDate(sbuf, cal);
      sbuf.append(' ');
      appendTime(sbuf, cal, nanos);
      if (withTimeZone) {
        appendTimeZone(sbuf, cal);
      }
      appendEra(sbuf, cal);

      return sbuf.toString();
    }
  }

  public String toString(@Nullable Calendar cal, Date x) {
    return toString(cal, x, true);
  }

  public String toString(@Nullable Calendar cal, Date x,
      boolean withTimeZone) {
    try (ResourceLock ignore = lock.obtain()) {
      if (x.getTime() == PGStatement.DATE_POSITIVE_INFINITY) {
        return "infinity";
      } else if (x.getTime() == PGStatement.DATE_NEGATIVE_INFINITY) {
        return "-infinity";
      }

      cal = setupCalendar(cal);
      cal.setTime(x);

      sbuf.setLength(0);

      appendDate(sbuf, cal);
      appendEra(sbuf, cal);
      if (withTimeZone) {
        sbuf.append(' ');
        appendTimeZone(sbuf, cal);
      }

      return sbuf.toString();
    }
  }

  public String toString(@Nullable Calendar cal, Time x) {
    return toString(cal, x, true);
  }

  public String toString(@Nullable Calendar cal, Time x,
      boolean withTimeZone) {
    try (ResourceLock ignore = lock.obtain()) {
      cal = setupCalendar(cal);
      cal.setTime(x);

      sbuf.setLength(0);

      appendTime(sbuf, cal, cal.get(Calendar.MILLISECOND) * 1000000);

      // The 'time' parser for <= 7.3 doesn't like timezones.
      if (withTimeZone) {
        appendTimeZone(sbuf, cal);
      }

      return sbuf.toString();
    }
  }

  private static void appendDate(StringBuilder sb, Calendar cal) {
    int year = cal.get(Calendar.YEAR);
    int month = cal.get(Calendar.MONTH) + 1;
    int day = cal.get(Calendar.DAY_OF_MONTH);
    appendDate(sb, year, month, day);
  }

  private static void appendDate(StringBuilder sb, int year, int month, int day) {
    // always use at least four digits for the year so very
    // early years, like 2, don't get misinterpreted
    //
    int prevLength = sb.length();
    sb.append(year);
    int leadingZerosForYear = 4 - (sb.length() - prevLength);
    if (leadingZerosForYear > 0) {
      sb.insert(prevLength, ZEROS, 0, leadingZerosForYear);
    }

    sb.append('-');
    sb.append(NUMBERS[month]);
    sb.append('-');
    sb.append(NUMBERS[day]);
  }

  private static void appendTime(StringBuilder sb, Calendar cal, int nanos) {
    int hours = cal.get(Calendar.HOUR_OF_DAY);
    int minutes = cal.get(Calendar.MINUTE);
    int seconds = cal.get(Calendar.SECOND);
    appendTime(sb, hours, minutes, seconds, nanos);
  }

  /**
   * Appends time part to the {@code StringBuilder} in PostgreSQL-compatible format.
   * The function truncates {@param nanos} to microseconds. The value is expected to be rounded
   * beforehand.
   * @param sb destination
   * @param hours hours
   * @param minutes minutes
   * @param seconds seconds
   * @param nanos nanoseconds
   */
  private static void appendTime(StringBuilder sb, int hours, int minutes, int seconds, int nanos) {
    sb.append(NUMBERS[hours]);

    sb.append(':');
    sb.append(NUMBERS[minutes]);

    sb.append(':');
    sb.append(NUMBERS[seconds]);

    // Add nanoseconds.
    // This won't work for server versions < 7.2 which only want
    // a two digit fractional second, but we don't need to support 7.1
    // anymore and getting the version number here is difficult.
    //
    if (nanos < 1000) {
      return;
    }
    sb.append('.');
    int len = sb.length();
    sb.append(nanos / 1000); // append microseconds
    int needZeros = 6 - (sb.length() - len);
    if (needZeros > 0) {
      sb.insert(len, ZEROS, 0, needZeros);
    }

    int end = sb.length() - 1;
    while (sb.charAt(end) == '0') {
      sb.deleteCharAt(end);
      end--;
    }
  }

  private void appendTimeZone(StringBuilder sb, Calendar cal) {
    int offset = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 1000;

    appendTimeZone(sb, offset);
  }

  private void appendTimeZone(StringBuilder sb, int offset) {
    int absoff = Math.abs(offset);
    int hours = absoff / 60 / 60;
    int mins = (absoff - hours * 60 * 60) / 60;
    int secs = absoff - hours * 60 * 60 - mins * 60;

    sb.append(offset >= 0 ? "+" : "-");

    sb.append(NUMBERS[hours]);

    if (mins == 0 && secs == 0) {
      return;
    }
    sb.append(':');

    sb.append(NUMBERS[mins]);

    if (secs != 0) {
      sb.append(':');
      sb.append(NUMBERS[secs]);
    }
  }

  private static void appendEra(StringBuilder sb, Calendar cal) {
    if (cal.get(Calendar.ERA) == GregorianCalendar.BC) {
      sb.append(" BC");
    }
  }

  public String toString(LocalDate localDate) {
    try (ResourceLock ignore = lock.obtain()) {
      if (LocalDate.MAX.equals(localDate)) {
        return "infinity";
      } else if (localDate.isBefore(MIN_LOCAL_DATE)) {
        return "-infinity";
      }

      sbuf.setLength(0);

      appendDate(sbuf, localDate);
      appendEra(sbuf, localDate);

      return sbuf.toString();
    }
  }

  public String toString(LocalTime localTime) {
    try (ResourceLock ignore = lock.obtain()) {
      sbuf.setLength(0);

      if (localTime.isAfter(MAX_TIME)) {
        return "24:00:00";
      }

      int nano = localTime.getNano();
      if (nanosExceed499(nano)) {
        // Technically speaking this is not a proper rounding, however
        // it relies on the fact that appendTime just truncates 000..999 nanosecond part
        localTime = localTime.plus(ONE_MICROSECOND);
      }
      appendTime(sbuf, localTime);

      return sbuf.toString();
    }
  }

  public String toString(OffsetTime offsetTime) {
    try (ResourceLock ignore = lock.obtain()) {
      sbuf.setLength(0);

      final LocalTime localTime = offsetTime.toLocalTime();
      if (localTime.isAfter(MAX_TIME)) {
        sbuf.append("24:00:00");
        appendTimeZone(sbuf, offsetTime.getOffset());
        return sbuf.toString();
      }

      int nano = offsetTime.getNano();
      if (nanosExceed499(nano)) {
        // Technically speaking this is not a proper rounding, however
        // it relies on the fact that appendTime just truncates 000..999 nanosecond part
        offsetTime = offsetTime.plus(ONE_MICROSECOND);
      }
      appendTime(sbuf, localTime);
      appendTimeZone(sbuf, offsetTime.getOffset());

      return sbuf.toString();
    }
  }

  /**
   * Converts {@code timetz} to string taking client time zone ({@link #timeZoneProvider})
   * into account.
   * @param value binary representation of {@code timetz}
   * @return string representation of {@code timetz}
   */
  public String toStringOffsetTimeBin(byte[] value) throws PSQLException {
    OffsetTime offsetTimeBin = toOffsetTimeBin(value);
    return toString(withClientOffsetSameInstant(offsetTimeBin));
  }

  /**
   * PostgreSQL does not store the time zone in the binary representation of timetz.
   * However, we want to preserve the output of {@code getString()} in both binary and text formats
   * So we try a client time zone when serializing {@link OffsetTime} to string.
   * @param input input offset time
   * @return adjusted offset time (it represents the same instant as the input one)
   */
  public OffsetTime withClientOffsetSameInstant(OffsetTime input) {
    if (input == OffsetTime.MAX || input == OffsetTime.MIN) {
      return input;
    }
    TimeZone timeZone = timeZoneProvider.get();
    int offsetMillis = timeZone.getRawOffset();
    return input.withOffsetSameInstant(
        offsetMillis == 0
            ? ZoneOffset.UTC
            : ZoneOffset.ofTotalSeconds(offsetMillis / 1000));
  }

  public String toString(OffsetDateTime offsetDateTime) {
    try (ResourceLock ignore = lock.obtain()) {
      if (offsetDateTime.isAfter(MAX_OFFSET_DATETIME)) {
        return "infinity";
      } else if (offsetDateTime.isBefore(MIN_OFFSET_DATETIME)) {
        return "-infinity";
      }

      sbuf.setLength(0);

      int nano = offsetDateTime.getNano();
      if (nanosExceed499(nano)) {
        // Technically speaking this is not a proper rounding, however
        // it relies on the fact that appendTime just truncates 000..999 nanosecond part
        offsetDateTime = offsetDateTime.plus(ONE_MICROSECOND);
      }
      LocalDateTime localDateTime = offsetDateTime.toLocalDateTime();
      LocalDate localDate = localDateTime.toLocalDate();
      appendDate(sbuf, localDate);
      sbuf.append(' ');
      appendTime(sbuf, localDateTime.toLocalTime());
      appendTimeZone(sbuf, offsetDateTime.getOffset());
      appendEra(sbuf, localDate);

      return sbuf.toString();
    }
  }

  /**
   * Converts {@code timestamptz} to string taking client time zone ({@link #timeZoneProvider})
   * into account.
   * @param value binary representation of {@code timestamptz}
   * @return string representation of {@code timestamptz}
   */
  public String toStringOffsetDateTime(byte[] value) throws PSQLException {
    OffsetDateTime offsetDateTime = toOffsetDateTimeBin(value);
    return toString(withClientOffsetSameInstant(offsetDateTime));
  }

  /**
   * PostgreSQL does not store the time zone in the binary representation of timestamptz.
   * However, we want to preserve the output of {@code getString()} in both binary and text formats
   * So we try a client time zone when serializing {@link OffsetDateTime} to string.
   * @param input input offset date time
   * @return adjusted offset date time (it represents the same instant as the input one)
   */
  public OffsetDateTime withClientOffsetSameInstant(OffsetDateTime input) {
    if (input == OffsetDateTime.MAX || input == OffsetDateTime.MIN) {
      return input;
    }
    int offsetMillis;
    TimeZone timeZone = timeZoneProvider.get();
    if (isSimpleTimeZone(timeZone.getID())) {
      offsetMillis = timeZone.getRawOffset();
    } else {
      offsetMillis = timeZone.getOffset(input.toEpochSecond() * 1000L);
    }
    return input.withOffsetSameInstant(
        offsetMillis == 0
            ? ZoneOffset.UTC
            : ZoneOffset.ofTotalSeconds(offsetMillis / 1000));
  }

  /**
   * Formats {@link LocalDateTime} to be sent to the backend, thus it adds time zone.
   * Do not use this method in {@link java.sql.ResultSet#getString(int)}
   * @param localDateTime The local date to format as a String
   * @return The formatted local date
   */
  public String toString(LocalDateTime localDateTime) {
    try (ResourceLock ignore = lock.obtain()) {
      if (localDateTime.isAfter(MAX_LOCAL_DATETIME)) {
        return "infinity";
      } else if (localDateTime.isBefore(MIN_LOCAL_DATETIME)) {
        return "-infinity";
      }

      sbuf.setLength(0);

      if (nanosExceed499(localDateTime.getNano())) {
        localDateTime = localDateTime.plus(ONE_MICROSECOND);
      }

      LocalDate localDate = localDateTime.toLocalDate();
      appendDate(sbuf, localDate);
      sbuf.append(' ');
      appendTime(sbuf, localDateTime.toLocalTime());
      appendEra(sbuf, localDate);

      return sbuf.toString();
    }
  }

  private static void appendDate(StringBuilder sb, LocalDate localDate) {
    int year = localDate.get(ChronoField.YEAR_OF_ERA);
    int month = localDate.getMonthValue();
    int day = localDate.getDayOfMonth();
    appendDate(sb, year, month, day);
  }

  private static void appendTime(StringBuilder sb, LocalTime localTime) {
    int hours = localTime.getHour();
    int minutes = localTime.getMinute();
    int seconds = localTime.getSecond();
    int nanos = localTime.getNano();
    appendTime(sb, hours, minutes, seconds, nanos);
  }

  private void appendTimeZone(StringBuilder sb, ZoneOffset offset) {
    int offsetSeconds = offset.getTotalSeconds();

    appendTimeZone(sb, offsetSeconds);
  }

  private static void appendEra(StringBuilder sb, LocalDate localDate) {
    if (localDate.get(ChronoField.ERA) == IsoEra.BCE.getValue()) {
      sb.append(" BC");
    }
  }

  private static int skipWhitespace(byte[] bytes, int start) {
    int slen = bytes.length;
    for (int i = start; i < slen; i++) {
      if (!Character.isWhitespace(bytes[i])) {
        return i;
      }
    }
    return slen;
  }

  private static int firstNonDigit(byte[] bytes, int start) {
    int slen = bytes.length;
    for (int i = start; i < slen; i++) {
      if (!Character.isDigit(bytes[i])) {
        return i;
      }
    }
    return slen;
  }

  private static int number(byte[] bytes, int start, int end) {
    if (start >= end) {
      throw new NumberFormatException();
    }
    int n = 0;
    for (int i = start; i < end; i++) {
      n = 10 * n + (bytes[i] - '0');
    }
    return n;
  }

  /**
   * Returns the SQL Date object matching the given bytes with {@link Oid#DATE}.
   *
   * @param tz The timezone used.
   * @param bytes The binary encoded date value.
   * @return The parsed date object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public Date toDateBin(@Nullable TimeZone tz, byte[] bytes) throws PSQLException {
    if (bytes.length != 4) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.", "date"),
          PSQLState.BAD_DATETIME_FORMAT);
    }
    int days = ByteConverter.int4(bytes, 0);
    if (tz == null) {
      tz = getDefaultTz();
    }
    long secs = toJavaSecs(days * 86400L);
    long millis = secs * 1000L;

    if (millis <= PGStatement.DATE_NEGATIVE_SMALLER_INFINITY) {
      millis = PGStatement.DATE_NEGATIVE_INFINITY;
    } else if (millis >= PGStatement.DATE_POSITIVE_SMALLER_INFINITY) {
      millis = PGStatement.DATE_POSITIVE_INFINITY;
    } else {
      // Here be dragons: backend did not provide us the timezone, so we guess the actual point in
      // time

      millis = guessTimestamp(millis, tz);
    }
    return new Date(millis);
  }

  private TimeZone getDefaultTz() {
    // Fast path to getting the default timezone.
    if (DEFAULT_TIME_ZONE_FIELD != null) {
      try {
        TimeZone defaultTimeZone = (TimeZone) DEFAULT_TIME_ZONE_FIELD.get(null);
        if (defaultTimeZone == prevDefaultZoneFieldValue) {
          return castNonNull(defaultTimeZoneCache);
        }
        prevDefaultZoneFieldValue = defaultTimeZone;
      } catch (Exception e) {
        // If this were to fail, fallback on slow method.
      }
    }
    TimeZone tz = TimeZone.getDefault();
    defaultTimeZoneCache = tz;
    return tz;
  }

  public boolean hasFastDefaultTimeZone() {
    return DEFAULT_TIME_ZONE_FIELD != null;
  }

  /**
   * Returns the SQL Time object matching the given bytes with {@link Oid#TIME} or
   * {@link Oid#TIMETZ}.
   *
   * @param tz The timezone used when received data is {@link Oid#TIME}, ignored if data already
   *        contains {@link Oid#TIMETZ}.
   * @param bytes The binary encoded time value.
   * @return The parsed time object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public Time toTimeBin(@Nullable TimeZone tz, byte[] bytes) throws PSQLException {
    if (bytes.length != 8 && bytes.length != 12) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.", "time"),
          PSQLState.BAD_DATETIME_FORMAT);
    }

    long millis;
    int timeOffset;

    if (usesDouble) {
      double time = ByteConverter.float8(bytes, 0);

      millis = (long) (time * 1000);
    } else {
      long time = ByteConverter.int8(bytes, 0);

      millis = time / 1000;
    }

    if (bytes.length == 12) {
      timeOffset = ByteConverter.int4(bytes, 8);
      timeOffset *= -1000;
      millis -= timeOffset;
      return new Time(millis);
    }

    if (tz == null) {
      tz = getDefaultTz();
    }

    // Here be dragons: backend did not provide us the timezone, so we guess the actual point in
    // time
    millis = guessTimestamp(millis, tz);

    return convertToTime(millis, tz); // Ensure date part is 1970-01-01
  }

  /**
   * Returns the SQL Time object matching the given bytes with {@link Oid#TIME}.
   *
   * @param bytes The binary encoded time value.
   * @return The parsed time object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public LocalTime toLocalTimeBin(byte[] bytes) throws PSQLException {
    if (bytes.length != 8) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.", "time"),
          PSQLState.BAD_DATETIME_FORMAT);
    }

    long micros;

    if (usesDouble) {
      double seconds = ByteConverter.float8(bytes, 0);

      micros = (long) (seconds * 1000000d);
    } else {
      micros = ByteConverter.int8(bytes, 0);
    }

    long nanos = Math.multiplyExact(micros, 1000L);

    if (nanos > MAX_TIME_NANOS) {
      return LocalTime.MAX;
    } else {
      return LocalTime.ofNanoOfDay(nanos);
    }
  }

  /**
   * Returns the SQL Timestamp object matching the given bytes with {@link Oid#TIMESTAMP} or
   * {@link Oid#TIMESTAMPTZ}.
   *
   * @param tz The timezone used when received data is {@link Oid#TIMESTAMP}, ignored if data
   *        already contains {@link Oid#TIMESTAMPTZ}.
   * @param bytes The binary encoded timestamp value.
   * @param timestamptz True if the binary is in GMT.
   * @return The parsed timestamp object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public Timestamp toTimestampBin(@Nullable TimeZone tz, byte[] bytes, boolean timestamptz)
      throws PSQLException {

    ParsedBinaryTimestamp parsedTimestamp = this.toParsedTimestampBin(tz, bytes, timestamptz);
    if (parsedTimestamp.infinity == Infinity.POSITIVE) {
      return new Timestamp(PGStatement.DATE_POSITIVE_INFINITY);
    } else if (parsedTimestamp.infinity == Infinity.NEGATIVE) {
      return new Timestamp(PGStatement.DATE_NEGATIVE_INFINITY);
    }

    Timestamp ts = new Timestamp(parsedTimestamp.millis);
    ts.setNanos(parsedTimestamp.nanos);
    return ts;
  }

  private ParsedBinaryTimestamp toParsedTimestampBinPlain(byte[] bytes)
      throws PSQLException {

    if (bytes.length != 8) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.", "timestamp"),
              PSQLState.BAD_DATETIME_FORMAT);
    }

    long secs;
    int nanos;

    if (usesDouble) {
      double time = ByteConverter.float8(bytes, 0);
      if (time == Double.POSITIVE_INFINITY) {
        ParsedBinaryTimestamp ts = new ParsedBinaryTimestamp();
        ts.infinity = Infinity.POSITIVE;
        return ts;
      } else if (time == Double.NEGATIVE_INFINITY) {
        ParsedBinaryTimestamp ts = new ParsedBinaryTimestamp();
        ts.infinity = Infinity.NEGATIVE;
        return ts;
      }

      secs = (long) time;
      nanos = (int) ((time - secs) * 1000000);
    } else {
      long time = ByteConverter.int8(bytes, 0);

      // compatibility with text based receiving, not strictly necessary
      // and can actually be confusing because there are timestamps
      // that are larger than infinite
      if (time == Long.MAX_VALUE) {
        ParsedBinaryTimestamp ts = new ParsedBinaryTimestamp();
        ts.infinity = Infinity.POSITIVE;
        return ts;
      } else if (time == Long.MIN_VALUE) {
        ParsedBinaryTimestamp ts = new ParsedBinaryTimestamp();
        ts.infinity = Infinity.NEGATIVE;
        return ts;
      }

      secs = time / 1000000;
      nanos = (int) (time - secs * 1000000);
    }
    if (nanos < 0) {
      secs--;
      nanos += 1000000;
    }
    nanos *= 1000;

    long millis = secs * 1000L;

    ParsedBinaryTimestamp ts = new ParsedBinaryTimestamp();
    ts.millis = millis;
    ts.nanos = nanos;
    return ts;
  }

  private ParsedBinaryTimestamp toParsedTimestampBin(@Nullable TimeZone tz, byte[] bytes,
      boolean timestamptz)
      throws PSQLException {

    ParsedBinaryTimestamp ts = toParsedTimestampBinPlain(bytes);
    if (ts.infinity != null) {
      return ts;
    }

    long secs = ts.millis / 1000L;

    secs = toJavaSecs(secs);
    long millis = secs * 1000L;
    if (!timestamptz) {
      // Here be dragons: backend did not provide us the timezone, so we guess the actual point in
      // time
      millis = guessTimestamp(millis, tz);
    }

    ts.millis = millis;
    return ts;
  }

  private ParsedBinaryTimestamp toProlepticParsedTimestampBin(byte[] bytes)
      throws PSQLException {

    ParsedBinaryTimestamp ts = toParsedTimestampBinPlain(bytes);
    if (ts.infinity != null) {
      return ts;
    }

    long secs = ts.millis / 1000L;

    // postgres epoc to java epoc
    secs += PG_EPOCH_DIFF.getSeconds();
    long millis = secs * 1000L;

    ts.millis = millis;
    return ts;
  }

  /**
   * Returns the local date time object matching the given bytes with {@link Oid#TIMESTAMP} or
   * {@link Oid#TIMESTAMPTZ}.
   * @param bytes The binary encoded local date time value.
   *
   * @return The parsed local date time object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public LocalDateTime toLocalDateTimeBin(byte[] bytes) throws PSQLException {

    ParsedBinaryTimestamp parsedTimestamp = this.toProlepticParsedTimestampBin(bytes);
    if (parsedTimestamp.infinity == Infinity.POSITIVE) {
      return LocalDateTime.MAX;
    } else if (parsedTimestamp.infinity == Infinity.NEGATIVE) {
      return LocalDateTime.MIN;
    }

    // hardcode utc because the backend does not provide us the timezone
    // Postgres is always UTC
    return LocalDateTime.ofEpochSecond(parsedTimestamp.millis / 1000L, parsedTimestamp.nanos, ZoneOffset.UTC);
  }

  /**
   * Returns the local date time object matching the given bytes with {@link Oid#DATE} or
   * {@link Oid#TIMESTAMP}.
   * @param bytes The binary encoded local date value.
   *
   * @return The parsed local date object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public LocalDate toLocalDateBin(byte[] bytes) throws PSQLException {
    if (bytes.length != 4) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.", "date"),
          PSQLState.BAD_DATETIME_FORMAT);
    }
    int days = ByteConverter.int4(bytes, 0);
    if (days == Integer.MAX_VALUE) {
      return LocalDate.MAX;
    } else if (days == Integer.MIN_VALUE) {
      return LocalDate.MIN;
    }
    // adapt from different Postgres Epoch and convert to LocalDate:
    return LocalDate.ofEpochDay(PG_EPOCH_DIFF.toDays() + days);
  }

  /**
   * Given a UTC timestamp {@code millis} finds another point in time that is rendered in given time
   * zone {@code tz} exactly as "millis in UTC".
   *
   * <p>For instance, given 7 Jan 16:00 UTC and tz=GMT+02:00 it returns 7 Jan 14:00 UTC == 7 Jan 16:00
   * GMT+02:00 Note that is not trivial for timestamps near DST change. For such cases, we rely on
   * {@link Calendar} to figure out the proper timestamp.</p>
   *
   * @param millis source timestamp
   * @param tz desired time zone
   * @return timestamp that would be rendered in {@code tz} like {@code millis} in UTC
   */
  private long guessTimestamp(long millis, @Nullable TimeZone tz) {
    if (tz == null) {
      // If client did not provide us with time zone, we use system default time zone
      tz = getDefaultTz();
    }
    // The story here:
    // Backend provided us with something like '2015-10-04 13:40' and it did NOT provide us with a
    // time zone.
    // On top of that, user asked us to treat the timestamp as if it were in GMT+02:00.
    //
    // The code below creates such a timestamp that is rendered as '2015-10-04 13:40 GMT+02:00'
    // In other words, its UTC value should be 11:40 UTC == 13:40 GMT+02:00.
    // It is not sufficient to just subtract offset as you might cross DST change as you subtract.
    //
    // For instance, on 2000-03-26 02:00:00 Moscow went to DST, thus local time became 03:00:00
    // Suppose we deal with 2000-03-26 02:00:01
    // If you subtract offset from the timestamp, the time will be "a hour behind" since
    // "just a couple of hours ago the OFFSET was different"
    //
    // To make a long story short: we have UTC timestamp that looks like "2000-03-26 02:00:01" when
    // rendered in UTC tz.
    // We want to know another timestamp that will look like "2000-03-26 02:00:01" in Europe/Moscow
    // time zone.

    if (isSimpleTimeZone(tz.getID())) {
      // For well-known non-DST time zones, just subtract offset
      return millis - tz.getRawOffset();
    }
    // For all the other time zones, enjoy debugging Calendar API
    // Here we do a straight-forward implementation that splits original timestamp into pieces and
    // composes it back.
    // Note: cal.setTimeZone alone is not sufficient as it would alter hour (it will try to keep the
    // same time instant value)
    Calendar cal = calendarWithUserTz;
    cal.setTimeZone(UTC_TIMEZONE);
    cal.setTimeInMillis(millis);
    int era = cal.get(Calendar.ERA);
    int year = cal.get(Calendar.YEAR);
    int month = cal.get(Calendar.MONTH);
    int day = cal.get(Calendar.DAY_OF_MONTH);
    int hour = cal.get(Calendar.HOUR_OF_DAY);
    int min = cal.get(Calendar.MINUTE);
    int sec = cal.get(Calendar.SECOND);
    int ms = cal.get(Calendar.MILLISECOND);
    cal.setTimeZone(tz);
    cal.set(Calendar.ERA, era);
    cal.set(Calendar.YEAR, year);
    cal.set(Calendar.MONTH, month);
    cal.set(Calendar.DAY_OF_MONTH, day);
    cal.set(Calendar.HOUR_OF_DAY, hour);
    cal.set(Calendar.MINUTE, min);
    cal.set(Calendar.SECOND, sec);
    cal.set(Calendar.MILLISECOND, ms);
    return cal.getTimeInMillis();
  }

  private static boolean isSimpleTimeZone(String id) {
    return id.startsWith("GMT") || id.startsWith("UTC");
  }

  /**
   * Extracts the date part from a timestamp.
   *
   * @param millis The timestamp from which to extract the date.
   * @param tz The time zone of the date.
   * @return The extracted date.
   */
  public Date convertToDate(long millis, @Nullable TimeZone tz) {

    // no adjustments for the infinity hack values
    if (millis <= PGStatement.DATE_NEGATIVE_INFINITY
        || millis >= PGStatement.DATE_POSITIVE_INFINITY) {
      return new Date(millis);
    }
    if (tz == null) {
      tz = getDefaultTz();
    }
    if (isSimpleTimeZone(tz.getID())) {
      // Truncate to 00:00 of the day.
      // Suppose the input date is 7 Jan 15:40 GMT+02:00 (that is 13:40 UTC)
      // We want it to become 7 Jan 00:00 GMT+02:00
      // 1) Make sure millis becomes 15:40 in UTC, so add offset
      int offset = tz.getRawOffset();
      millis += offset;
      // 2) Truncate hours, minutes, etc. Day is always 86400 seconds, no matter what leap seconds
      // are
      millis = floorDiv(millis, ONEDAY) * ONEDAY;
      // 2) Now millis is 7 Jan 00:00 UTC, however we need that in GMT+02:00, so subtract some
      // offset
      millis -= offset;
      // Now we have brand-new 7 Jan 00:00 GMT+02:00
      return new Date(millis);
    }

    Calendar cal = calendarWithUserTz;
    cal.setTimeZone(tz);
    cal.setTimeInMillis(millis);
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);

    return new Date(cal.getTimeInMillis());
  }

  /**
   * Extracts the time part from a timestamp. This method ensures the date part of output timestamp
   * looks like 1970-01-01 in given timezone.
   *
   * @param millis The timestamp from which to extract the time.
   * @param tz timezone to use.
   * @return The extracted time.
   */
  public Time convertToTime(long millis, TimeZone tz) {
    if (tz == null) {
      tz = getDefaultTz();
    }
    if (isSimpleTimeZone(tz.getID())) {
      // Leave just time part of the day.
      // Suppose the input date is 2015 7 Jan 15:40 GMT+02:00 (that is 13:40 UTC)
      // We want it to become 1970 1 Jan 15:40 GMT+02:00
      // 1) Make sure millis becomes 15:40 in UTC, so add offset
      int offset = tz.getRawOffset();
      millis += offset;
      // 2) Truncate year, month, day. Day is always 86400 seconds, no matter what leap seconds are
      millis = floorMod(millis, ONEDAY);
      // 2) Now millis is 1970 1 Jan 15:40 UTC, however we need that in GMT+02:00, so subtract some
      // offset
      millis -= offset;
      // Now we have brand-new 1970 1 Jan 15:40 GMT+02:00
      return new Time(millis);
    }
    Calendar cal = calendarWithUserTz;
    cal.setTimeZone(tz);
    cal.setTimeInMillis(millis);
    cal.set(Calendar.ERA, GregorianCalendar.AD);
    cal.set(Calendar.YEAR, 1970);
    cal.set(Calendar.MONTH, 0);
    cal.set(Calendar.DAY_OF_MONTH, 1);

    return new Time(cal.getTimeInMillis());
  }

  /**
   * Returns the given time value as String matching what the current postgresql server would send
   * in text mode.
   *
   * @param time time value
   * @param withTimeZone whether timezone should be added
   * @return given time value as String
   */
  public String timeToString(java.util.Date time, boolean withTimeZone) {
    Calendar cal = null;
    if (withTimeZone) {
      cal = calendarWithUserTz;
      cal.setTimeZone(timeZoneProvider.get());
    }
    if (time instanceof Timestamp) {
      return toString(cal, (Timestamp) time, withTimeZone);
    }
    if (time instanceof Time) {
      return toString(cal, (Time) time, withTimeZone);
    }
    return toString(cal, (Date) time, withTimeZone);
  }

  /**
   * Converts the given postgresql seconds to java seconds. Reverse engineered by inserting varying
   * dates to postgresql and tuning the formula until the java dates matched. See {@link #toPgSecs}
   * for the reverse operation.
   *
   * @param secs Postgresql seconds.
   * @return Java seconds.
   */
  private static long toJavaSecs(long secs) {
    // postgres epoc to java epoc
    secs += PG_EPOCH_DIFF.getSeconds();

    // Julian/Gregorian calendar cutoff point
    if (secs < -12219292800L) { // October 4, 1582 -> October 15, 1582
      secs += 86400 * 10;
      if (secs < -14825808000L) { // 1500-02-28 -> 1500-03-01
        int extraLeaps = (int) ((secs + 14825808000L) / 3155760000L);
        extraLeaps--;
        extraLeaps -= extraLeaps / 4;
        secs += extraLeaps * 86400L;
      }
    }
    return secs;
  }

  /**
   * Converts the given java seconds to postgresql seconds. See {@link #toJavaSecs} for the reverse
   * operation. The conversion is valid for any year 100 BC onwards.
   *
   * @param secs Postgresql seconds.
   * @return Java seconds.
   */
  private static long toPgSecs(long secs) {
    // java epoc to postgres epoc
    secs -= PG_EPOCH_DIFF.getSeconds();

    // Julian/Gregorian calendar cutoff point
    if (secs < -13165977600L) { // October 15, 1582 -> October 4, 1582
      secs -= 86400 * 10;
      if (secs < -15773356800L) { // 1500-03-01 -> 1500-02-28
        int years = (int) ((secs + 15773356800L) / -3155823050L);
        years++;
        years -= years / 4;
        secs += years * 86400L;
      }
    }

    return secs;
  }

  /**
   * Converts the SQL Date to binary representation for {@link Oid#DATE}.
   *
   * @param tz The timezone used.
   * @param bytes The binary encoded date value.
   * @param value value
   * @throws PSQLException If binary format could not be parsed.
   */
  public void toBinDate(@Nullable TimeZone tz, byte[] bytes, Date value) throws PSQLException {
    long millis = value.getTime();

    if (tz == null) {
      tz = getDefaultTz();
    }
    // It "getOffset" is UNTESTED
    // See org.postgresql.jdbc.AbstractJdbc2Statement.setDate(int, java.sql.Date,
    // java.util.Calendar)
    // The problem is we typically do not know for sure what is the exact required date/timestamp
    // type
    // Thus pgjdbc sticks to text transfer.
    millis += tz.getOffset(millis);

    long secs = toPgSecs(millis / 1000);
    ByteConverter.int4(bytes, 0, (int) (secs / 86400));
  }

  /**
   * Converts backend's TimeZone parameter to java format.
   * Notable difference: backend's gmt-3 is GMT+03 in Java.
   *
   * @param timeZone time zone to use
   * @return java TimeZone
   */
  public static TimeZone parseBackendTimeZone(String timeZone) {
    if (timeZone.startsWith("GMT")) {
      TimeZone tz = GMT_ZONES.get(timeZone);
      if (tz != null) {
        return tz;
      }
    }
    return TimeZone.getTimeZone(timeZone);
  }

  private static long floorDiv(long x, long y) {
    long r = x / y;
    // if the signs are different and modulo not zero, round down
    if ((x ^ y) < 0 && (r * y != x)) {
      r--;
    }
    return r;
  }

  private static long floorMod(long x, long y) {
    return x - floorDiv(x, y) * y;
  }

}
