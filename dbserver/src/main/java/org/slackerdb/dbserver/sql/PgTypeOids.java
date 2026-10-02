package org.slackerdb.dbserver.sql;

import java.sql.Types;

/**
 * JDBC 类型码（{@link Types}）→ PostgreSQL 类型 OID 的映射。
 *
 * <p>用于 {@code ParameterDescription}：当客户端在 Parse 中没有给出参数类型
 * （PG 协议允许写 0 表示"由服务端推断"）时，我们从 JDBC 的
 * {@code ParameterMetaData.getParameterType(i)} 拿到 JDBC 类型码，
 * 再用本类换算成 PG OID 回给客户端。</p>
 *
 * <p>只覆盖能确定的常见类型；<b>无法确定的类型一律回 0（unspecified）</b>。
 * 0 是 PG 的合法取值，语义是"服务端也定不下来，请客户端自己声明"——
 * 这比猜一个具体类型更安全：猜错会让驱动按错的类型编码参数（例如把 LIST/STRUCT 当 JSON、
 * 把 NULL 参数当 VARCHAR），而 0 只是回到"客户端必须声明"的老路，驱动本来就能处理。</p>
 */
public final class PgTypeOids {

    public static final int BOOL = 16;
    public static final int BYTEA = 17;
    public static final int INT8 = 20;
    public static final int INT2 = 21;
    public static final int INT4 = 23;
    public static final int TEXT = 25;
    public static final int FLOAT4 = 700;
    public static final int FLOAT8 = 701;
    public static final int VARCHAR = 1043;
    public static final int DATE = 1082;
    public static final int TIME = 1083;
    public static final int TIMESTAMP = 1114;
    public static final int TIMESTAMPTZ = 1184;
    public static final int INTERVAL = 1186;
    public static final int NUMERIC = 1700;
    public static final int UUID = 2950;
    public static final int JSON = 114;
    public static final int JSONB = 3802;

    /** PG 的"未指定类型"（{@code unknown}）：能在 ParameterDescription 里合法出现，表示要求客户端自行声明。 */
    public static final int UNSPECIFIED = 0;

    private PgTypeOids() {
    }

    /**
     * JDBC 类型码 → PG 类型 OID；无法确定时回 {@link #UNSPECIFIED}。
     */
    public static int fromJdbcType(int jdbcType) {
        return switch (jdbcType) {
            case Types.BOOLEAN, Types.BIT -> BOOL;
            case Types.TINYINT, Types.SMALLINT -> INT2;
            case Types.INTEGER -> INT4;
            case Types.BIGINT -> INT8;
            case Types.REAL -> FLOAT4;
            case Types.FLOAT, Types.DOUBLE -> FLOAT8;
            case Types.NUMERIC, Types.DECIMAL -> NUMERIC;
            case Types.DATE -> DATE;
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> TIME;
            case Types.TIMESTAMP -> TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE -> TIMESTAMPTZ;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR -> VARCHAR;
            case Types.CLOB, Types.NCLOB -> TEXT;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> BYTEA;
            // 定不下来的（复杂类型 / 对象 / 数组 / 空类型）：交给客户端声明
            default -> UNSPECIFIED;
        };
    }
}
