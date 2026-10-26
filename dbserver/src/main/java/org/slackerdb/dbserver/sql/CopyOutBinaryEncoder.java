package org.slackerdb.dbserver.sql;

import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code COPY ... TO STDOUT WITH (FORMAT binary)} 的行编码器（PostgreSQL 二进制 COPY 格式）。
 *
 * <p><b>为什么单独一个类</b>：{@link CopyOutEncoder} 编码的是 COPY 的<b>文本</b>形态
 * （TEXT/CSV），它的转义规则是 {@link CopyCsvReader} 的逆运算；二进制形态没有分隔符、没有转义，
 * 每个值按 PostgreSQL 各类型的 {@code *send} 函数逐列编码。两者共享的只有"逐行推、按帧 flush"
 * 的调用方式，混在一个类里只会让两套字节规则相互污染。</p>
 *
 * <p><b>字节格式</b>（与 PostgreSQL 的 {@code COPY ... TO STDOUT (FORMAT binary)} 一致）：</p>
 * <pre>
 *   Header: 11 字节签名 "PGCOPY\n\377\r\n\0" + Int32 flags(0) + Int32 header extension(0)
 *   Row   : Int16 列数 + 每列 [Int32 长度 + 内容]（NULL 的长度为 -1）
 *   Trailer:Int16 -1
 * </pre>
 * <p>这里不写 OID（flags 的 bit16 为 0）：PostgreSQL 12 起已不再支持带 OID 的 COPY。</p>
 *
 * <p><b>支持的类型</b>：DuckDB 的标量类型，只要它在 PostgreSQL 里有等价的二进制表示。
 * 映射时优先保证<b>值域无损</b>：</p>
 * <ul>
 *   <li>{@code BOOLEAN} → bool（1 字节 0/1）；</li>
 *   <li>{@code TINYINT} → int2，{@code SMALLINT} → int2，{@code INTEGER} → int4，
 *       {@code BIGINT} → int8（PostgreSQL 没有 int1，TINYINT 必须升宽）；</li>
 *   <li>{@code UTINYINT} → int2、{@code USMALLINT} → int4、{@code UINTEGER} → int8
 *       （宽到能装下无符号值域），{@code UBIGINT} / {@code HUGEINT} / {@code UHUGEINT} → numeric；</li>
 *   <li>{@code FLOAT} → float4，{@code DOUBLE} → float8，{@code DECIMAL} → numeric；</li>
 *   <li>{@code VARCHAR}/{@code JSON}/{@code ENUM} → text（PostgreSQL 的 json 二进制就是原文）；</li>
 *   <li>{@code BLOB} → bytea（原始字节），{@code UUID} → uuid（16 字节）；</li>
 *   <li>{@code DATE} → date（自 2000-01-01 的天数）、{@code TIME} → time（自午夜的微秒）、
 *       {@code TIMETZ} → timetz（微秒 + 秒为单位的时区偏移）；</li>
 *   <li>{@code TIMESTAMP*} → timestamp、{@code TIMESTAMPTZ} → timestamptz
 *       （自 2000-01-01 00:00:00 UTC 的微秒；纳秒精度按 PostgreSQL 的微秒截断）。</li>
 * </ul>
 *
 * <p><b>明确不支持</b>：{@code INTERVAL}、{@code BIT}、{@code TIME_NS}、{@code BIGNUM}、
 * {@code VARIANT}、{@code GEOMETRY}，以及 {@code LIST/ARRAY/STRUCT/MAP/UNION} 等复合类型
 * —— PostgreSQL 要么没有对应类型，要么二进制布局是本项目尚未实现的数组/复合编码。
 * 这些列在<b>发出 CopyOutResponse 之前</b>就报 {@code 0A000}，绝不会让客户端收到半截数据流。</p>
 *
 * <p><b>内存</b>：逐行、逐列直接写进调用方的 {@link ByteArrayOutputStream}，不整表缓冲，
 * 也不为每个单元格分配临时数组（定长类型先写长度再写值；变长类型先算长度再写）。</p>
 */
public final class CopyOutBinaryEncoder {

    // ---- 内部类型编码：构造时按列算一次，行循环里只做整数分派 ----
    private static final int K_BOOLEAN = 1;
    private static final int K_INT2 = 2;
    private static final int K_INT4 = 3;
    private static final int K_INT8 = 4;
    private static final int K_NUMERIC = 5;
    private static final int K_FLOAT4 = 6;
    private static final int K_FLOAT8 = 7;
    private static final int K_TEXT = 8;
    private static final int K_BYTEA = 9;
    private static final int K_UUID = 10;
    private static final int K_DATE = 11;
    private static final int K_TIME = 12;
    private static final int K_TIMETZ = 13;
    private static final int K_TIMESTAMP = 14;
    private static final int K_TIMESTAMPTZ = 15;

    private static final LocalDate PG_EPOCH_DATE = LocalDate.of(2000, 1, 1);
    private static final long MICROS_PER_SECOND = 1_000_000L;

    /** 列类型在 PostgreSQL 二进制 COPY 里没有表示（错误码 {@code 0A000}）。 */
    public static final class UnsupportedTypeException extends SQLException {
        UnsupportedTypeException(String message) {
            super(message, "0A000");
        }
    }

    private final int columnCount;
    private final int[] kinds;

    /**
     * @param metaData 结果集列元数据（来自 {@code ResultSet} 或
     *                 {@code PreparedStatement} 均可）
     * @throws UnsupportedTypeException 任一列的类型没有 PostgreSQL 二进制表示
     */
    public CopyOutBinaryEncoder(ResultSetMetaData metaData) throws SQLException {
        if (metaData == null) {
            throw new SQLException("COPY OUT metadata is not available");
        }
        this.columnCount = metaData.getColumnCount();
        if (columnCount <= 0) {
            throw new UnsupportedTypeException(
                    "COPY ... TO STDOUT with (FORMAT binary) requires at least one output column");
        }
        this.kinds = new int[columnCount + 1];
        for (int i = 1; i <= columnCount; i++) {
            String typeName = metaData.getColumnTypeName(i);
            int kind = kindOf(typeName);
            if (kind == 0) {
                throw new UnsupportedTypeException(
                        unsupportedMessage(metaData.getColumnName(i), typeName));
            }
            kinds[i] = kind;
        }
    }

    public int getColumnCount() {
        return columnCount;
    }

    /** 写入 19 字节的 PostgreSQL 二进制 COPY 头（标志位与扩展区都为 0）。 */
    public void writeHeader(ByteArrayOutputStream out) {
        out.writeBytes(PostgresSQLUtil.copyBinaryHeader());
    }

    /** 写入行尾的结束标志（Int16 -1）。 */
    public void writeTrailer(ByteArrayOutputStream out) {
        out.writeBytes(Utils.int16ToBytes((short) -1));
    }

    /**
     * 把结果集当前行编码进 {@code out}（列数 + 每列的长度与内容）。
     *
     * <p>只做一次 {@code getObject} 取 NULL 判定，再按列类型调用对应的强类型 getter：
     * BLOB 必须用 {@code getBytes}（驱动返回的是内部 Blob 对象，不是 byte[]），
     * JSON 必须用 {@code getString}（{@code getObject} 返回的是驱动自己的 JsonNode）。</p>
     */
    public void encodeRow(ResultSet rs, ByteArrayOutputStream out) throws SQLException {
        out.writeBytes(Utils.int16ToBytes((short) columnCount));
        for (int i = 1; i <= columnCount; i++) {
            encodeColumn(rs, i, out);
        }
    }

    private void encodeColumn(ResultSet rs, int column, ByteArrayOutputStream out) throws SQLException {
        int kind = kinds[column];

        // BLOB / JSON 的 getObject 返回驱动内部对象，不能走统一的 Object 分支
        if (kind == K_BYTEA) {
            writeByteArray(out, rs.getBytes(column));
            return;
        }
        if (kind == K_TEXT) {
            writeText(out, rs.getString(column));
            return;
        }

        Object value = rs.getObject(column);
        if (value == null) {
            out.writeBytes(Utils.int32ToBytes(-1));
            return;
        }

        switch (kind) {
            case K_BOOLEAN -> {
                out.writeBytes(Utils.int32ToBytes(1));
                out.write(((Boolean) value) ? 1 : 0);
            }
            case K_INT2 -> {
                out.writeBytes(Utils.int32ToBytes(2));
                out.writeBytes(Utils.int16ToBytes((short) ((Number) value).intValue()));
            }
            case K_INT4 -> {
                out.writeBytes(Utils.int32ToBytes(4));
                out.writeBytes(Utils.int32ToBytes(((Number) value).intValue()));
            }
            case K_INT8 -> {
                out.writeBytes(Utils.int32ToBytes(8));
                out.writeBytes(Utils.int64ToBytes(((Number) value).longValue()));
            }
            case K_FLOAT4 -> {
                out.writeBytes(Utils.int32ToBytes(4));
                out.writeBytes(Utils.int32ToBytes(
                        Float.floatToIntBits(((Number) value).floatValue())));
            }
            case K_FLOAT8 -> {
                out.writeBytes(Utils.int32ToBytes(8));
                out.writeBytes(Utils.int64ToBytes(
                        Double.doubleToLongBits(((Number) value).doubleValue())));
            }
            case K_NUMERIC -> writeByteArray(out,
                    PostgresSQLUtil.convertPGBigDecimalToByte(asBigDecimal(value)));
            case K_UUID -> {
                UUID uuid = (UUID) value;
                out.writeBytes(Utils.int32ToBytes(16));
                out.writeBytes(Utils.int64ToBytes(uuid.getMostSignificantBits()));
                out.writeBytes(Utils.int64ToBytes(uuid.getLeastSignificantBits()));
            }
            case K_DATE -> {
                int days = Math.toIntExact(ChronoUnit.DAYS.between(
                        PG_EPOCH_DATE, (LocalDate) value));
                out.writeBytes(Utils.int32ToBytes(4));
                out.writeBytes(Utils.int32ToBytes(days));
            }
            case K_TIME -> {
                long micros = ((LocalTime) value).toNanoOfDay() / 1_000L;
                out.writeBytes(Utils.int32ToBytes(8));
                out.writeBytes(Utils.int64ToBytes(micros));
            }
            case K_TIMETZ -> {
                OffsetTime time = (OffsetTime) value;
                long micros = time.toLocalTime().toNanoOfDay() / 1_000L;
                // PostgreSQL 的 TimeTzADT.zone 是"西为正"的秒数（UTC+08:00 → -28800），
                // 与 java.time 的 ZoneOffset（东为正）符号相反。
                int zone = -time.getOffset().getTotalSeconds();
                out.writeBytes(Utils.int32ToBytes(12));
                out.writeBytes(Utils.int64ToBytes(micros));
                out.writeBytes(Utils.int32ToBytes(zone));
            }
            case K_TIMESTAMP -> {
                // 不带时区：按墙钟时间与 2000-01-01 的差算微秒（不能用 Timestamp.getTime()，
                // 那会依赖 JVM 默认时区，同一值在不同机器上编出不同字节）。
                java.sql.Timestamp timestamp = (java.sql.Timestamp) value;
                long micros = localDateTimeToPgMicros(timestamp.toLocalDateTime());
                out.writeBytes(Utils.int32ToBytes(8));
                out.writeBytes(Utils.int64ToBytes(micros));
            }
            case K_TIMESTAMPTZ -> {
                OffsetDateTime timestamp = (OffsetDateTime) value;
                long epochMicros = timestamp.toEpochSecond() * MICROS_PER_SECOND
                        + timestamp.getNano() / 1_000L;
                out.writeBytes(Utils.int32ToBytes(8));
                out.writeBytes(Utils.int64ToBytes(epochMicros - PostgresSQLUtil.PG_EPOCH_MICROS));
            }
            default -> throw new IllegalStateException(
                    "Unsupported COPY OUT binary kind " + kind + " for column " + column);
        }
    }

    private static void writeText(ByteArrayOutputStream out, String value) {
        if (value == null) {
            out.writeBytes(Utils.int32ToBytes(-1));
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(Utils.int32ToBytes(bytes.length));
        out.writeBytes(bytes);
    }

    private static void writeByteArray(ByteArrayOutputStream out, byte[] value) {
        if (value == null) {
            out.writeBytes(Utils.int32ToBytes(-1));
            return;
        }
        out.writeBytes(Utils.int32ToBytes(value.length));
        out.writeBytes(value);
    }

    /** LocalDateTime（墙钟）→ 自 2000-01-01 的微秒；纳秒按 PostgreSQL 精度截断。 */
    private static long localDateTimeToPgMicros(LocalDateTime value) {
        long epochMicros = value.toEpochSecond(ZoneOffset.UTC) * MICROS_PER_SECOND
                + value.getNano() / 1_000L;
        return epochMicros - PostgresSQLUtil.PG_EPOCH_MICROS;
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger bigInteger) {
            return new BigDecimal(bigInteger);
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.longValue());
        }
        return new BigDecimal(value.toString());
    }

    /** DuckDB 类型名 → 内部编码；0 表示 PostgreSQL 二进制 COPY 没有对应表示。 */
    private static int kindOf(String duckDbTypeName) {
        if (duckDbTypeName == null) {
            return 0;
        }
        String typeName = duckDbTypeName.trim().toUpperCase(Locale.ROOT);
        // DECIMAL / VARCHAR 可能带精度/长度（如 DECIMAL(18,3)）
        if (typeName.startsWith("DECIMAL") || typeName.startsWith("NUMERIC")) {
            return K_NUMERIC;
        }
        if (typeName.startsWith("VARCHAR") || typeName.startsWith("CHAR")
                || typeName.startsWith("BPCHAR") || typeName.startsWith("TEXT")
                || typeName.startsWith("STRING")) {
            return K_TEXT;
        }
        return switch (typeName) {
            case "BOOLEAN", "BOOL", "LOGICAL" -> K_BOOLEAN;
            // PostgreSQL 没有 int1：TINYINT 升宽成 int2（UTINYINT 同样升宽，保证 0..255 可表示）
            case "TINYINT", "INT1", "UTINYINT" -> K_INT2;
            case "SMALLINT", "INT2", "SHORT" -> K_INT2;
            case "USMALLINT" -> K_INT4;
            case "INTEGER", "INT4", "INT", "SIGNED" -> K_INT4;
            case "UINTEGER" -> K_INT8;
            case "BIGINT", "INT8", "LONG" -> K_INT8;
            // 无符号 64 位与 128 位整数超过 int8：用 numeric（PG numeric 支持任意精度）
            case "UBIGINT", "HUGEINT", "UHUGEINT" -> K_NUMERIC;
            case "FLOAT", "FLOAT4", "REAL" -> K_FLOAT4;
            case "DOUBLE", "FLOAT8" -> K_FLOAT8;
            case "JSON", "ENUM" -> K_TEXT;
            case "BLOB", "BYTEA", "BINARY", "VARBINARY" -> K_BYTEA;
            case "UUID" -> K_UUID;
            case "DATE" -> K_DATE;
            case "TIME" -> K_TIME;
            case "TIME WITH TIME ZONE", "TIMETZ" -> K_TIMETZ;
            case "TIMESTAMP", "DATETIME", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS" -> K_TIMESTAMP;
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> K_TIMESTAMPTZ;
            default -> 0;
        };
    }

    private static String unsupportedMessage(String columnName, String typeName) {
        String type = typeName == null ? "" : typeName.trim().toUpperCase(Locale.ROOT);
        return "COPY ... TO STDOUT with (FORMAT binary) does not support column ["
                + columnName + "] of type [" + type + "]: "
                + "this type has no PostgreSQL binary representation. "
                + "Use (FORMAT csv) or (FORMAT text) for this query.";
    }
}
