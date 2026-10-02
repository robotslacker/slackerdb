package org.slackerdb.dbserver.entity;

import org.slackerdb.dbserver.server.DBInstance;

import java.util.Map;
import java.util.HashMap;

// 数据来源： pg_type
public class PostgresTypeOids {
    private static final Map<String, Integer> postgresTypeAndOid = new HashMap<>();
    private static final Map<Integer, String> postgresOidAndType = new HashMap<>();

    static
    {
        postgresTypeAndOid.put("BIGINT", 20);
        // PG 没有 128 位整数类型：能装下 HUGEINT/UBIGINT/UHUGEINT 值域的只有 NUMERIC(1700)。
        // 改造前这三个都声明成 BIGINT(20) 却按十进制文本发送，值一旦超过 int64，
        // 客户端按数字取就报 "Bad value for type long"。
        postgresTypeAndOid.put("HUGEINT", 1700);
        postgresTypeAndOid.put("UBIGINT", 1700);
        postgresTypeAndOid.put("UHUGEINT", 1700);

        postgresTypeAndOid.put("INTEGER", 23);
        // UINTEGER 最大值 4294967295 超出 int4 → 声明成 int8
        postgresTypeAndOid.put("UINTEGER", 20);

        postgresTypeAndOid.put("SMALLINT", 21);
        // USMALLINT 最大值 65535 超出 int2 → 声明成 int4
        postgresTypeAndOid.put("USMALLINT", 23);
        postgresTypeAndOid.put("TINYINT", 21);
        postgresTypeAndOid.put("UTINYINT", 21);

        postgresTypeAndOid.put("BOOLEAN", 16);

        // PG 的 bit(1560) 是变长类型（varbit 系列），typlen = -1。
        // 此前写的是 1563（= PG 的 _varbit，**数组** OID）：客户端按数组解析，
        // getColumnTypeName 得到 "_varbit"、getObject 得到 PgArray，与 BIT 语义不符。
        postgresTypeAndOid.put("BIT", 1560);

        postgresTypeAndOid.put("DATE", 1082);

        postgresTypeAndOid.put("DECIMAL", 1700);

        // INTERVAL 声明成 PG 的 interval(1186)，值按文本发（RowEncoder 把它算作 T_VARCHAR）。
        //
        // 这里曾经降级成 1043，因为客户端拿这个 OID 时 getPGType(1186) 返回 null，
        // 在 PgResultSet.initSqlType() 的 castNonNull 上直接抛，整列连 getColumnTypeName() 都不可用。
        // 根因不在这个 OID，而在服务端 fake catalog：客户端"按 OID 取类型名"的兜底查询
        //   SELECT ... FROM pg_type t JOIN pg_namespace n ON t.typnamespace = n.oid WHERE t.oid = ?
        // 打不通。而且这不是某一种客户端的问题 —— 官方 pgjdbc 42.7.2 与本项目 dbdriver 的
        // 内置类型表里都没有 interval，两边都走这条兜底查询，所以"只给某一个客户端补内置类型"
        // 解决不了（会救了一个、漏了标准驱动）。
        // 现在修在服务端（SlackerCatalog 的 pg_namespace 以 pg_type.typnamespace 为准），
        // 声明回真实类型：两个驱动各自拿到 PGInterval，其它 PG 客户端也不再被降级。
        //
        // 硬规则（新增类型映射时必须遵守）：服务端声明的 OID 必须能被客户端解析 ——
        // 要么在客户端内置类型表里，要么 fake catalog 的兜底查询查得到。
        postgresTypeAndOid.put("INTERVAL", 1186);

        postgresTypeAndOid.put("FLOAT", 700);

        postgresTypeAndOid.put("REAL", 700);

        postgresTypeAndOid.put("DOUBLE", 701);

        postgresTypeAndOid.put("TIME", 1083);

        postgresTypeAndOid.put("TIMESTAMP", 1114);

        postgresTypeAndOid.put("TIMESTAMP WITH TIME ZONE", 1184);

        postgresTypeAndOid.put("UUID", 2950);

        postgresTypeAndOid.put("VARCHAR", 1043);

        postgresTypeAndOid.put("BYTEA", 17);
        postgresTypeAndOid.put("BLOB", 17);
        postgresTypeAndOid.put("TEXT", 25);
        postgresTypeAndOid.put("JSON", 114);
        postgresTypeAndOid.put("JSONB", 3802);
        postgresTypeAndOid.put("TIMETZ", 1266);

        postgresTypeAndOid.put("ARRAY", 2277);

        // VARCHAR[] 目前按照字符串返回，原1015，目前返回1043
        postgresTypeAndOid.put("VARCHAR[]", 1043);

        postgresTypeAndOid.put("UNKNOWN", 0);

        postgresOidAndType.put(0, "UNKNOWN");

        postgresOidAndType.put(20, "BIGINT");

        postgresOidAndType.put(23, "INTEGER");

        postgresOidAndType.put(21, "SMALLINT");

        postgresOidAndType.put(16, "BOOLEAN");

        postgresOidAndType.put(1082, "DATE");

        // 入参方向：客户端显式声明这些 OID 时仍按对应类型解码。
        // 1560 = bit、1562 = varbit、1563 = _varbit（本服务端曾用它声明 BIT，旧客户端还会发）：
        // 三者在 PG 里的二进制载荷格式相同（int4 位长 + 字节），统一按位串解码。
        // 1186 = interval：列与入参两个方向都是 interval（客户端已登记该 OID）。
        postgresOidAndType.put(1560, "BIT");
        postgresOidAndType.put(1562, "BIT");
        postgresOidAndType.put(1563, "BIT");

        postgresOidAndType.put(1700, "DECIMAL");

        postgresOidAndType.put(1186, "INTERVAL");

        postgresOidAndType.put(700, "FLOAT");

        postgresOidAndType.put(701, "DOUBLE");

        postgresOidAndType.put(1083, "TIME");

        postgresOidAndType.put(1114, "TIMESTAMP");

        postgresOidAndType.put(1184, "TIMESTAMP WITH TIME ZONE");

        postgresOidAndType.put(2950, "UUID");

        postgresOidAndType.put(1015, "VARCHAR[]");

        postgresOidAndType.put(1043, "VARCHAR");

        postgresOidAndType.put(17, "BYTEA");
        postgresOidAndType.put(25, "TEXT");
        postgresOidAndType.put(114, "JSON");
        postgresOidAndType.put(3802, "JSONB");
        postgresOidAndType.put(1266, "TIMETZ");

        postgresOidAndType.put(2277, "ARRAY");
    }

    public static int getTypeOidFromTypeName(String columnTypeName)
    {
        if (columnTypeName.startsWith("DECIMAL"))
        {
            columnTypeName = "DECIMAL";
        }

        if (postgresTypeAndOid.containsKey(columnTypeName)) {
            return postgresTypeAndOid.get(columnTypeName);
        }
        else
        {
            // 对于不能识别的类型目前返回VARCHAR
            return postgresTypeAndOid.get("VARCHAR");
        }
    }

    /**
     * 定长类型在 PG 里的内部宽度（{@code pg_type.typlen}）。
     *
     * <p>RowDescription 的 {@code typeSize} 就是这个值；<b>不在表里的类型一律按变长处理（-1）</b>
     * —— text/varchar/numeric/bytea/json/bit/数组/复合类型在 PG 里都是变长。
     */
    private static final Map<Integer, Short> postgresOidAndTypeSize = new HashMap<>();

    static
    {
        postgresOidAndTypeSize.put(16, (short) 1);     // BOOLEAN
        postgresOidAndTypeSize.put(20, (short) 8);     // BIGINT
        postgresOidAndTypeSize.put(21, (short) 2);     // SMALLINT
        postgresOidAndTypeSize.put(23, (short) 4);     // INTEGER
        postgresOidAndTypeSize.put(700, (short) 4);    // FLOAT4
        postgresOidAndTypeSize.put(701, (short) 8);    // FLOAT8
        postgresOidAndTypeSize.put(1082, (short) 4);   // DATE
        postgresOidAndTypeSize.put(1083, (short) 8);   // TIME
        postgresOidAndTypeSize.put(1114, (short) 8);   // TIMESTAMP
        postgresOidAndTypeSize.put(1184, (short) 8);   // TIMESTAMP WITH TIME ZONE
        postgresOidAndTypeSize.put(1186, (short) 16);  // INTERVAL
        postgresOidAndTypeSize.put(2950, (short) 16);  // UUID
    }

    /**
     * 取该 PG 类型的固定宽度（{@code pg_type.typlen}）；变长类型返回 -1。
     */
    public static short getTypeSizeFromTypeOid(int columnTypeOid)
    {
        return postgresOidAndTypeSize.getOrDefault(columnTypeOid, (short) -1);
    }

    public static String getTypeNameFromTypeOid(DBInstance dbInstance, int columnTypeOid)
    {
        if (postgresOidAndType.containsKey(columnTypeOid)) {
            return postgresOidAndType.get(columnTypeOid);
        }
        else
        {
            dbInstance.logger.error("Could not find postgres type for type id {}", columnTypeOid);
            return "";
        }
    }
}
