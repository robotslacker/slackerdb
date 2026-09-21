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
 */
public final class RowEncoder {

    // ---- 内部列类型编码：避免在热路径上做字符串比较 ----
    /** 未识别的类型，按文本（UTF-8）返回，并在构造阶段告警一次 */
    public static final int T_UNKNOWN = 0;
    /** TINYINT / SMALLINT，二进制 2 字节 */
    public static final int T_SMALLINT = 1;
    /** INTEGER，二进制 4 字节 */
    public static final int T_INTEGER = 2;
    /** BIGINT，二进制 8 字节 */
    public static final int T_BIGINT = 3;
    /** VARCHAR / INTERVAL，文本 UTF-8 */
    public static final int T_VARCHAR = 4;
    /** DATE，二进制 4 字节（距 2000-01-01 的天数） */
    public static final int T_DATE = 5;
    /** BOOLEAN，二进制 1 字节 */
    public static final int T_BOOLEAN = 6;
    /** FLOAT，二进制 4 字节 */
    public static final int T_FLOAT = 7;
    /** DOUBLE，二进制 8 字节 */
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
            field.objectIdOfTable = 0;
            field.attributeNumberOfColumn = 0;
            field.dataTypeId = PostgresTypeOids.getTypeOidFromTypeName(columnTypeName);
            field.dataTypeSize = (short) 2147483647;
            field.dataTypeModifier = -1;
            field.formatCode = this.formatCodes[i - 1];
            fieldList.add(field);
        }
        this.fields = fieldList;
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
                } else {
                    setValue(column, Utils.int16ToBytes(value));
                }
            }
            case T_INTEGER -> {
                int value = rs.getInt(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else {
                    setValue(column, Utils.int32ToBytes(value));
                }
            }
            case T_BIGINT -> {
                long value = rs.getLong(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else {
                    setValue(column, Utils.int64ToBytes(value));
                }
            }
            case T_BOOLEAN -> {
                boolean value = rs.getBoolean(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else {
                    setValue(column, new byte[]{value ? (byte) 0x01 : (byte) 0x00});
                }
            }
            case T_FLOAT -> {
                float value = rs.getFloat(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else {
                    setValue(column, Utils.int32ToBytes(Float.floatToIntBits(value)));
                }
            }
            case T_DOUBLE -> {
                double value = rs.getDouble(columnIndex);
                if (rs.wasNull()) {
                    setNull(column);
                } else {
                    setValue(column, Utils.int64ToBytes(Double.doubleToLongBits(value)));
                }
            }
            case T_DATE -> {
                Date value = rs.getDate(columnIndex);
                if (value == null) {
                    setNull(column);
                } else {
                    long days = ChronoUnit.DAYS.between(PG_EPOCH_DATE, value.toLocalDate());
                    setValue(column, Utils.int32ToBytes((int) days));
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
            case T_SMALLINT, T_INTEGER, T_BIGINT, T_DATE, T_BOOLEAN, T_FLOAT, T_DOUBLE -> true;
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
            case "DATE" -> T_DATE;
            case "BOOLEAN" -> T_BOOLEAN;
            case "FLOAT" -> T_FLOAT;
            case "DOUBLE" -> T_DOUBLE;
            case "TIMESTAMP" -> T_TIMESTAMP;
            case "TIME" -> T_TIME;
            case "TIMESTAMP WITH TIME ZONE" -> T_TIMESTAMP_TZ;
            case "BIT" -> T_BIT;
            case "UINTEGER", "HUGEINT", "UBIGINT" -> T_NUMERIC_TEXT;
            default -> typeName.startsWith("DECIMAL") ? T_DECIMAL : T_UNKNOWN;
        };
    }
}
