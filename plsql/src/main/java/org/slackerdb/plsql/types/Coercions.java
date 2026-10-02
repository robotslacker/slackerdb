package org.slackerdb.plsql.types;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 隐式类型转换。
 *
 * <p>策略（冻结）：</p>
 * <ul>
 *   <li>NULL 与所有类型兼容；</li>
 *   <li>数值族之间可转换：收窄按后端语义四舍五入，溢出/精度超限报 {@code 22003}；</li>
 *   <li>字符串 ↔ 数值/日期时间可双向转换：字符串必须能按目标类型解析，
 *       否则 {@code 22P02} / {@code 22007}；</li>
 *   <li>字符串写入 {@code VARCHAR(n)}/{\@code CHAR(n)} 超长报 {@code 22001}（<b>不截断</b>）；</li>
 *   <li>涉及 BOOLEAN 的隐式转换（除 NULL）一律报 {@code 42804}；</li>
 *   <li>其余组合报 {@code 42804}。</li>
 * </ul>
 */
public final class Coercions {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss[.SSSSSSSSS]");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd[ HH:mm:ss[.SSSSSSSSS]]");

    private Coercions() {
    }

    /**
     * 按目标类型转换值。
     *
     * @throws PlSqlTypeException 无法转换（携带 SQLSTATE）
     */
    public static Object convert(Object value, PlType target) {
        return convert(value, target, null);
    }

    /**
     * @param variableName 非空时用于 NOT NULL 变量赋 NULL 的报错消息
     */
    public static Object convert(Object value, PlType target, String variableName) {
        if (value == null) {
            return null;
        }
        if (target == null || target.isUnknown()) {
            return value;
        }
        return switch (target.kind()) {
            case INTEGER, SMALLINT, BIGINT -> toInteger(value, target);
            case DECIMAL -> toDecimal(value, target);
            case REAL, DOUBLE -> toFloating(value, target);
            case VARCHAR, CHAR -> toStringType(value, target);
            case TEXT -> toText(value);
            case BOOLEAN -> toBoolean(value, target);
            case DATE -> toDate(value, target);
            case TIME -> toTime(value, target);
            case TIMESTAMP, TIMESTAMP_TZ -> toTimestamp(value, target);
            case INTERVAL -> toInterval(value, target);
            case UNKNOWN -> value;
        };
    }

    /** 转换（不做 NOT NULL 检查，由解释器在赋值时按声明处理）。 */
    public static PlValue assign(PlType declared, Object value) {
        return new PlValue(declared, convert(value, declared));
    }

    // ---------- 数值 ----------
    private static BigDecimal toBigDecimal(Object value, PlType target) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof Boolean) {
            throw PlSqlTypeException.mismatch(value, target);
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                throw PlSqlTypeException.invalidNumber(text, target);
            }
        }
        throw PlSqlTypeException.mismatch(value, target);
    }

    private static Object toInteger(Object value, PlType target) {
        BigDecimal decimal = toBigDecimal(value, target);
        BigDecimal rounded = decimal.setScale(0, RoundingMode.HALF_UP);
        long asLong;
        try {
            asLong = rounded.longValueExact();
        } catch (ArithmeticException e) {
            throw PlSqlTypeException.outOfRange(value, target);
        }
        return switch (target.kind()) {
            case SMALLINT -> {
                if (asLong < Short.MIN_VALUE || asLong > Short.MAX_VALUE) {
                    throw PlSqlTypeException.outOfRange(value, target);
                }
                yield (short) asLong;
            }
            case INTEGER -> {
                if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
                    throw PlSqlTypeException.outOfRange(value, target);
                }
                yield (int) asLong;
            }
            default -> asLong;
        };
    }

    private static Object toDecimal(Object value, PlType target) {
        BigDecimal decimal = toBigDecimal(value, target);
        BigDecimal scaled = decimal.setScale(target.scale(), RoundingMode.HALF_UP);
        int integerDigits = target.precision() - target.scale();
        if (scaled.abs().compareTo(BigDecimal.TEN.pow(Math.max(integerDigits, 0))) >= 0) {
            throw PlSqlTypeException.outOfRange(value, target);
        }
        return scaled;
    }

    private static Object toFloating(Object value, PlType target) {
        BigDecimal decimal = toBigDecimal(value, target);
        double result = decimal.doubleValue();
        if (Double.isInfinite(result)) {
            throw PlSqlTypeException.outOfRange(value, target);
        }
        return target.kind() == PlType.Kind.REAL ? (Object) (float) result : (Object) result;
    }

    // ---------- 字符串 ----------
    private static Object toStringType(Object value, PlType target) {
        String text = asText(value, target);
        int limit = target.length();
        if (limit > 0 && text.length() > limit) {
            throw PlSqlTypeException.tooLong(text, target);
        }
        return text;
    }

    private static Object toText(Object value) {
        return asText(value, PlType.TEXT);
    }

    private static String asText(Object value, PlType target) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Boolean bool) {
            throw PlSqlTypeException.mismatch(value, target);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Number || value instanceof java.util.Date
                || value instanceof LocalDate || value instanceof LocalDateTime || value instanceof LocalTime) {
            return String.valueOf(value);
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        throw PlSqlTypeException.mismatch(value, target);
    }

    // ---------- 布尔 ----------
    private static Object toBoolean(Object value, PlType target) {
        if (value instanceof Boolean) {
            return value;
        }
        // 策略：BOOLEAN 不与其它类型隐式转换（字符串请用显式函数/CAST）
        throw PlSqlTypeException.mismatch(value, target);
    }

    // ---------- 时间 ----------
    private static Object toDate(Object value, PlType target) {
        if (value instanceof Date) {
            return value;
        }
        if (value instanceof Timestamp timestamp) {
            return new Date(timestamp.getTime());
        }
        if (value instanceof LocalDate localDate) {
            return Date.valueOf(localDate);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return Date.valueOf(localDateTime.toLocalDate());
        }
        if (value instanceof String text) {
            try {
                return Date.valueOf(LocalDate.parse(text.trim(), DATE_FORMAT));
            } catch (DateTimeParseException e) {
                // 也接受带时间的字符串：取日期部分
                try {
                    return Date.valueOf(LocalDateTime.parse(text.trim(), TIMESTAMP_FORMAT).toLocalDate());
                } catch (DateTimeParseException ignored) {
                    throw PlSqlTypeException.invalidDateTime(text, target);
                }
            }
        }
        throw PlSqlTypeException.mismatch(value, target);
    }

    private static Object toTime(Object value, PlType target) {
        if (value instanceof Time) {
            return value;
        }
        if (value instanceof Timestamp timestamp) {
            return new Time(timestamp.getTime());
        }
        if (value instanceof LocalTime localTime) {
            return Time.valueOf(localTime);
        }
        if (value instanceof String text) {
            try {
                return Time.valueOf(LocalTime.parse(text.trim(), TIME_FORMAT));
            } catch (DateTimeParseException e) {
                throw PlSqlTypeException.invalidDateTime(text, target);
            }
        }
        throw PlSqlTypeException.mismatch(value, target);
    }

    private static Object toTimestamp(Object value, PlType target) {
        if (value instanceof Timestamp) {
            return value;
        }
        if (value instanceof Date date) {
            return new Timestamp(date.getTime());
        }
        if (value instanceof LocalDateTime localDateTime) {
            return Timestamp.valueOf(localDateTime);
        }
        if (value instanceof LocalDate localDate) {
            return Timestamp.valueOf(localDate.atStartOfDay());
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            try {
                return Timestamp.valueOf(LocalDateTime.parse(trimmed, TIMESTAMP_FORMAT));
            } catch (DateTimeParseException e) {
                // 只有日期的字符串也接受（等价于当天 00:00:00）
                try {
                    return Timestamp.valueOf(LocalDate.parse(trimmed, DATE_FORMAT).atStartOfDay());
                } catch (DateTimeParseException ignored) {
                    throw PlSqlTypeException.invalidDateTime(text, target);
                }
            }
        }
        throw PlSqlTypeException.mismatch(value, target);
    }

    private static Object toInterval(Object value, PlType target) {
        if (value instanceof String text) {
            return text.trim().toUpperCase(Locale.ROOT);
        }
        throw PlSqlTypeException.mismatch(value, target);
    }
}
