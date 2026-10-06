package org.slackerdb.dbserver.sql;

import ch.qos.logback.classic.Logger;
import org.slackerdb.dbserver.entity.Column;
import org.slackerdb.dbserver.entity.Field;
import org.slackerdb.dbserver.entity.PostgresTypeOids;
import org.slackerdb.common.utils.Utils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 结果集行编码器：把 JDBC 的 {@link ResultSet} 当前行转换成 PostgreSQL 协议的数据行。
 *
 * <p>之所以要有这个类：行编码是"行 × 列"级别的热路径，而原先的实现是在每个单元格里
 * 反复做列级的工作 —— {@code getColumnTypeName}、{@code toUpperCase}、{@code new SimpleDateFormat}、
 * {@code DateTimeFormatter.ofPattern}、{@code getObject} 判空，甚至对未识别类型每个单元格打一条 WARN。
 * 本类把这些"每个列只需要算一次"的信息在构造时预解析为 {@code int} 类型码，
 * 行循环里只剩取值与编码；同时让 {@code RowDescription} 与数据行使用<b>同一份</b>列元数据，
 * 避免两边对 formatCode / 编码方式的理解产生漂移。</p>
 *
 * <p>{@link #encode(ResultSet)} 返回的 {@link Column} 列表与内部对象<b>是复用的</b>，
 * 调用方必须在写出这一行之后才能再调用一次 {@code encode}（现有的两处调用点都是
 * {@code dataRow.process(...)} 之后立刻写出，符合该约定）。</p>
 *
 * <p><b>不变量（必须一直保持）</b>：每个列在 {@code RowDescription} 里声明的 format code
 * 必须与 {@code DataRow} 里实际发出去的字节一致 —— 声明 0 就必须是文本，声明 1 就必须是
 * 对应宽度的二进制。客户端（psql / libpq / 各种驱动）就是按这个声明去解码的。
 * 定长类型（int2/int4/int8/bool/float4/float8/date）此前是<b>无条件</b>按二进制写的，
 * 于是简单查询路径（声明全文本）把它们发成了二进制：{@code getInt()} 直接报
 * "Bad value for type int"，而 {@code SELECT TRUE} 更糟 —— 不报错、静默读成 {@code false}。
 * 现在这 7 个类型与 {@code T_BYTEA} 一样，按 {@link #isBinary(int)} 分支编码。</p>
 */
public final class RowEncoder {

    // ---- 内部列类型编码：避免在热路径上做字符串比较 ----
    /** 未识别的类型，按文本（UTF-8）返回，并在构造阶段告警一次 */
    public static final int T_UNKNOWN = 0;
    /** TINYINT / SMALLINT：二进制 2 字节 / 文本十进制 */
    public static final int T_SMALLINT = 1;
    /** INTEGER：二进制 4 字节 / 文本十进制 */
    public static final int T_INTEGER = 2;
    /** BIGINT：二进制 8 字节 / 文本十进制 */
    public static final int T_BIGINT = 3;
    /**
     * 文本原样返回（UTF-8）的类型：VARCHAR / INTERVAL，以及 UUID / JSON / JSONB / TIMETZ。
     *
     * <p>后四个的文本形式与 PG 的文本表示一致（UUID 是规范小写十六进制、JSON 就是原文、
     * TIMETZ 是 {@code HH:mm:ss+HH:MM}），因此按文本发值即可被客户端正确解析。
     * 把它们显式登记在这里、而不是落进 {@link #T_UNKNOWN}，是为了让"类型名 → OID"与
     * "类型名 → 值编码"两套映射保持一致：否则会出现"RowDescription 声称 UUID(2950)、
     * 值却走未识别分支"的组合，并且每次查询都刷一条 WARN 把真问题淹掉。</p>
     */
    public static final int T_VARCHAR = 4;
    /** DATE：二进制 4 字节（距 2000-01-01 的天数）/ 文本 ISO yyyy-MM-dd */
    public static final int T_DATE = 5;
    /** BOOLEAN：二进制 1 字节 / 文本 't' | 'f' */
    public static final int T_BOOLEAN = 6;
    /** FLOAT：二进制 4 字节 / 文本（Float.toString） */
    public static final int T_FLOAT = 7;
    /** DOUBLE：二进制 8 字节 / 文本（Double.toString） */
    public static final int T_DOUBLE = 8;
    /** TIMESTAMP，文本 "yyyy-MM-dd HH:mm:ss.SSS" */
    public static final int T_TIMESTAMP = 9;
    /** TIME，文本 "HH:mm:ss[.fff...]" */
    public static final int T_TIME = 10;
    /** TIMESTAMP WITH TIME ZONE，文本 "yyyy-MM-dd HH:mm:ss.SSSx" */
    public static final int T_TIMESTAMP_TZ = 11;
    /** BIT，文本位串 */
    public static final int T_BIT = 12;
    /** DECIMAL，文本（toPlainString） */
    public static final int T_DECIMAL = 13;
    /** UINTEGER / HUGEINT / UBIGINT，按文本（ASCII）返回 */
    public static final int T_NUMERIC_TEXT = 14;
    /** BLOB / BYTEA：二进制格式下原样发字节；文本格式下按 PG 的 {@code \x} + 十六进制发 */
    public static final int T_BYTEA = 15;

    // 线程安全且可复用的格式化器（DateTimeFormatter 是不可变对象）
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter TIMESTAMP_TZ_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSx");
    private static final LocalDate PG_EPOCH_DATE = LocalDate.of(2000, 1, 1);

    private final Logger logger;
    private final int[] typeCodes;
    private final short[] formatCodes;
    private final List<Field> fields;
    private final Column[] columns;
    private final List<Column> columnList;

    /**
     * @param metaData     结果集列元数据；只会读取一次，不参与行循环
     * @param binaryAllowed 是否允许对定长类型使用二进制格式。
     *                      extended 协议路径为 true；simple query 路径为 false（一律文本返回）
     * @param logger       用于对未识别类型告警（每个列一次）
     */
    public RowEncoder(ResultSetMetaData metaData, boolean binaryAllowed, Logger logger) throws SQLException {
        this.logger = logger;
        int columnCount = metaData.getColumnCount();
        this.typeCodes = new int[columnCount];
        this.formatCodes = new short[columnCount];
        this.columns = new Column[columnCount];
        this.columnList = Arrays.asList(this.columns);

        List<Field> fieldList = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String columnTypeName = metaData.getColumnTypeName(i);
            int typeCode = typeCodeOf(columnTypeName);
            if (typeCode == T_UNKNOWN) {
                // 注意：这里每个列只告警一次。原实现放在单元格循环内，日志量会与结果集大小成正比。
                logger.warn("Not implemented column type: {}", columnTypeName);
            }
            this.typeCodes[i - 1] = typeCode;
            this.formatCodes[i - 1] = (binaryAllowed && isBinaryType(typeCode)) ? (short) 1 : (short) 0;
            this.columns[i - 1] = new Column();

            Field field = new Field();
            field.name = metaData.getColumnName(i);
            // 列归属：DuckDB 不暴露"这一列来自哪张表"，也没有稳定的表 OID，
            // 因此按协议允许的方式留 0（PG 对表达式列同样发 0）。
            field.objectIdOfTable = 0;
            field.attributeNumberOfColumn = 0;
            field.dataTypeId = PostgresTypeOids.getTypeOidFromTypeName(columnTypeName);
            // typeSize 是 pg_type.typlen（定长类型的内部宽度），变长类型一律 -1。
            // 改造前这里写的是 (short) 2147483647（线上即 0xFFFF/-1）：变长类型碰巧对了，
            // 但 bool/int2/int4/int8/float/date/time/uuid… 这些定长类型也全被报成 -1。
            field.dataTypeSize = PostgresTypeOids.getTypeSizeFromTypeOid(field.dataTypeId);
            field.dataTypeModifier = typeModifierOf(typeCode, metaData, i);
            field.formatCode = this.formatCodes[i - 1];
            fieldList.add(field);
        }
        this.fields = fieldList;
    }

    /**
     * 计算 {@code atttypmod}（类型的声明修饰符）。
     *
     * <p>PG 的编码：{@code numeric(p,s)} → {@code ((p << 16) | s) + 4}（4 = VARHDRSZ）、
     * {@code varchar(n)} → {@code n + 4}、没有修饰符 → -1。</p>
     *
     * <p>DuckDB 侧能拿到什么（实测）：<b>DECIMAL 的 p/s 完全可以拿到</b>
     * （类型名是 {@code DECIMAL(18,4)}，{@code getPrecision/getScale} 也给 18/4），所以数值类型
     * 这一项要填对，否则客户端 {@code getPrecision()/getScale()} 恒为 0/0，DBeaver/ORM 看不到
     * {@code numeric(18,4)}；而 <b>VARCHAR 的声明长度拿不到</b>（DuckDB 的 {@code VARCHAR(n)}
     * 只是运行期校验，连 {@code information_schema} 里都是 null），
     * TIME/TIMESTAMP 的精度也拿不到（DuckDB 固定微秒），这两类只能保持 -1（= PG 的默认/无界）。</p>
     */
    private static int typeModifierOf(int typeCode, ResultSetMetaData metaData, int columnIndex)
            throws SQLException {
        if (typeCode != T_DECIMAL) {
            return -1;
        }
        int precision = metaData.getPrecision(columnIndex);
        int scale = metaData.getScale(columnIndex);
        // precision 拿不到时 DuckDB/JDBC 会给 0 或 Integer.MAX_VALUE，别把它当成真值编进 typmod
        if (precision <= 0 || precision > 1000 || scale < 0 || scale > precision) {
            return -1;
        }
        return ((precision << 16) | scale) + 4;
    }

    /**
     * RowDescription 的字段列表。与 {@link #encode(ResultSet)} 使用同一份列类型信息，
     * 保证 formatCode 与实际写出的编码永远一致。
     */
    public List<Field> describe() {
        return this.fields;
    }

    /**
     * 把结果集当前行编码为数据行。
     *
     * @return 与内部对象复用的 Column 列表，必须在写出后才能再次调用本方法
     */
    public List<Column> encode(ResultSet rs) throws SQLException {
        for (int i = 0; i < typeCodes.length; i++) {
            encodeColumn(rs, i + 1, columns[i], typeCodes[i]);
        }
        return columnList;
    }

    private void encodeColumn(ResultSet rs, int columnIndex, Column column, int typeCode)
            throws SQLException {
        switch (typeCode) {
            case T_SMALLINT -> {
                short value = rs.getShort(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else if (isBinary(columnIndex - 1)) {
                    setValue(column, Utils.int16ToBytes(value));
                } else {
                    setValue(column, text(String.valueOf(value)));
                }
            }
            case T_INTEGER -> {
                int value = rs.getInt(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else if (isBinary(columnIndex - 1)) {
                    setValue(column, Utils.int32ToBytes(value));
                } else {
                    setValue(column, text(String.valueOf(value)));
                }
            }
            case T_BIGINT -> {
                long value = rs.getLong(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else if (isBinary(columnIndex - 1)) {
                    setValue(column, Utils.int64ToBytes(value));
                } else {
                    setValue(column, text(String.valueOf(value)));
                }
            }
            case T_BOOLEAN -> {
                boolean value = rs.getBoolean(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else if (isBinary(columnIndex - 1)) {
                    setValue(column, new byte[]{value ? (byte) 0x01 : (byte) 0x00});
                } else {
                    // PG 的 bool 文本形式就是 't' / 'f'
                    setValue(column, text(value ? "t" : "f"));
                }
            }
            case T_FLOAT -> {
                float value = rs.getFloat(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else if (isBinary(columnIndex - 1)) {
                    setValue(column, Utils.int32ToBytes(Float.floatToIntBits(value)));
                } else {
                    setValue(column, text(Float.toString(value)));
                }
            }
            case T_DOUBLE -> {
                double value = rs.getDouble(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else if (isBinary(columnIndex - 1)) {
                    setValue(column, Utils.int64ToBytes(Double.doubleToLongBits(value)));
                } else {
                    setValue(column, text(Double.toString(value)));
                }
            }
            case T_DATE -> {
                Date value = rs.getDate(columnIndex);
                if (value == null) {
                    setNull(column);
                } else if (isBinary(columnIndex - 1)) {
                    long days = ChronoUnit.DAYS.between(PG_EPOCH_DATE, value.toLocalDate());
                    setValue(column, Utils.int32ToBytes((int) days));
                } else {
                    // PG 的 date 文本形式是 ISO 的 yyyy-MM-dd
                    setValue(column, text(value.toLocalDate().toString()));
                }
            }
            case T_TIMESTAMP -> {
                Timestamp value = rs.getTimestamp(columnIndex);
                if (value == null) {
                    setNull(column);
                } else {
                    setValue(column, TIMESTAMP_FORMATTER
                            .format(value.toLocalDateTime())
                            .getBytes(StandardCharsets.UTF_8));
                }
            }
            case T_TIME -> {
                Time value = rs.getTime(columnIndex);
                if (value == null) {
                    setNull(column);
                } else {
                    String text = value.toLocalTime().toString();
                    // LocalTime.toString() 在"秒与纳秒都为 0"时输出 "HH:mm"。
                    // 它既不是合法的 PG time 文本（客户端会解析失败），
                    // 也会与下面按实际字节数计算的长度不一致（旧实现固定声明 8 字节，
                    // 造成 DataRow 长度与实际内容不符，客户端会一直等待缺失的字节而挂住），
                    // 所以补齐秒并按实际字节数声明长度。
                    if (text.length() == 5) {
                        text = text + ":00";
                    }
                    setValue(column, text.getBytes(StandardCharsets.US_ASCII));
                }
            }
            case T_TIMESTAMP_TZ -> {
                Timestamp value = rs.getTimestamp(columnIndex);
                if (value == null) {
                    setNull(column);
                } else {
                    ZonedDateTime zonedDateTime = value.toInstant().atZone(ZoneId.systemDefault());
                    setValue(column, TIMESTAMP_TZ_FORMATTER
                            .format(zonedDateTime)
                            .getBytes(StandardCharsets.US_ASCII));
                }
            }
            case T_BIT -> {
                String value = rs.getString(columnIndex);
                if (value == null) {
                    setNull(column);
                } else {
                    setValue(column, value.getBytes(StandardCharsets.US_ASCII));
                }
            }
            case T_NUMERIC_TEXT -> {
                String value = rs.getString(columnIndex);
                if (value == null) {
                    setNull(column);
                } else {
                    setValue(column, value.getBytes(StandardCharsets.US_ASCII));
                }
            }
            case T_DECIMAL -> {
                BigDecimal value = rs.getBigDecimal(columnIndex);
                if (value == null) {
                    setNull(column);
                } else {
                    setValue(column, value.toPlainString().getBytes(StandardCharsets.US_ASCII));
                }
            }
            case T_VARCHAR -> {
                String value = rs.getString(columnIndex);
                if (value == null) {
                    setNull(column);
                } else {
                    setValue(column, value.getBytes(StandardCharsets.UTF_8));
                }
            }
            case T_BYTEA -> {
                byte[] value = rs.getBytes(columnIndex);
                if (value == null) {
                    setNull(column);
                } else if (formatCodes[columnIndex - 1] == 1) {
                    // 二进制结果格式：原样发字节
                    setValue(column, value);
                } else {
                    // 文本结果格式：PG 的 bytea 文本表示是 "\x" + 十六进制
                    byte[] hex = new byte[2 + value.length * 2];
                    hex[0] = '\\';
                    hex[1] = 'x';
                    final char[] digits = "0123456789abcdef".toCharArray();
                    for (int i = 0; i < value.length; i++) {
                        hex[2 + i * 2] = (byte) digits[(value[i] >> 4) & 0x0F];
                        hex[2 + i * 2 + 1] = (byte) digits[value[i] & 0x0F];
                    }
                    setValue(column, hex);
                }
            }
            default -> {
                // 未识别类型：按字符串处理（构造阶段已经告警过一次）
                String value = rs.getString(columnIndex);
                if (value == null) {
                    setNull(column);
                } else {
                    setValue(column, value.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private static void setNull(Column column) {
        column.columnLength = -1;
        column.columnValue = null;
    }

    /** 文本格式的列值（数字 / 布尔 / 日期都是 ASCII）。 */
    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void setValue(Column column, byte[] value) {
        column.columnValue = value;
        column.columnLength = value.length;
    }

    /**
     * 该列是否使用二进制的结果格式。只有 extended 协议路径会返回 true。
     */
    public boolean isBinary(int zeroBasedColumnIndex) {
        return formatCodes[zeroBasedColumnIndex] == 1;
    }

    /**
     * 定长类型集合，与 RowDescription 中 formatCode=1 的集合保持一致。
     */
    private static boolean isBinaryType(int typeCode) {
        return switch (typeCode) {
            case T_SMALLINT, T_INTEGER, T_BIGINT, T_DATE, T_BOOLEAN, T_FLOAT, T_DOUBLE, T_BYTEA -> true;
            default -> false;
        };
    }

    /**
     * 把 JDBC 的列类型名映射为内部类型码。
     * 每个列只调用一次（构造阶段），因此这里的字符串比较不构成热路径。
     */
    public static int typeCodeOf(String columnTypeName) {
        if (columnTypeName == null) {
            return T_UNKNOWN;
        }
        String typeName = columnTypeName.toUpperCase(Locale.ROOT).trim();
        return switch (typeName) {
            case "TINYINT", "SMALLINT" -> T_SMALLINT;
            case "INTEGER" -> T_INTEGER;
            case "BIGINT" -> T_BIGINT;
            case "VARCHAR", "INTERVAL" -> T_VARCHAR;
            // 文本形式与 PG 一致的类型：显式登记，避免落进 T_UNKNOWN 触发误报 WARN。
            // OID 侧（PostgresTypeOids）早就认识它们（2950 / 114 / 3802 / 1266），这里补齐值编码侧。
            // TIMETZ 两种写法都收：DuckDB 的 getColumnTypeName 给的是 "TIME WITH TIME ZONE"，
            // 而映射表里的键是 "TIMETZ"，两侧名字不一致会让告警继续出现。
            case "UUID", "JSON", "JSONB", "TIMETZ", "TIME WITH TIME ZONE" -> T_VARCHAR;
            case "DATE" -> T_DATE;
            case "BOOLEAN" -> T_BOOLEAN;
            case "FLOAT" -> T_FLOAT;
            case "DOUBLE" -> T_DOUBLE;
            case "TIMESTAMP" -> T_TIMESTAMP;
            case "TIME" -> T_TIME;
            case "TIMESTAMP WITH TIME ZONE" -> T_TIMESTAMP_TZ;
            case "BIT" -> T_BIT;
            case "BLOB", "BYTEA" -> T_BYTEA;
            // DuckDB 的 128 位/无符号整数：编码宽度必须与 PostgresTypeOids 里声明的 OID 一致，
            // 否则客户端按声明的类型解析就会溢出（UINTEGER 声明 int8、USMALLINT 声明 int4）。
            // HUGEINT/UBIGINT/UHUGEINT 声明为 NUMERIC，只能按十进制文本发（T_NUMERIC_TEXT）。
            case "UINTEGER" -> T_BIGINT;
            case "USMALLINT" -> T_INTEGER;
            case "UTINYINT" -> T_SMALLINT;
            case "HUGEINT", "UBIGINT", "UHUGEINT" -> T_NUMERIC_TEXT;
            default -> typeName.startsWith("DECIMAL") ? T_DECIMAL : T_UNKNOWN;
        };
    }
}
