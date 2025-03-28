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
        postgresTypeAndOid.put("HUGEINT", 20);
        postgresTypeAndOid.put("UBIGINT", 20);
        postgresTypeAndOid.put("UHUGEINT", 20);

        postgresTypeAndOid.put("INTEGER", 23);
        postgresTypeAndOid.put("UINTEGER", 23);

        postgresTypeAndOid.put("SMALLINT", 21);
        postgresTypeAndOid.put("USMALLINT", 21);
        postgresTypeAndOid.put("TINYINT", 21);
        postgresTypeAndOid.put("UTINYINT", 21);

        postgresTypeAndOid.put("BOOLEAN", 16);

        postgresTypeAndOid.put("BIT", 1563);

        postgresTypeAndOid.put("DATE", 1082);

        postgresTypeAndOid.put("DECIMAL", 1700);

        postgresTypeAndOid.put("INTERVAL", 1186);

        postgresTypeAndOid.put("FLOAT", 700);

        postgresTypeAndOid.put("REAL", 700);

        postgresTypeAndOid.put("DOUBLE", 701);

        postgresTypeAndOid.put("TIME", 1083);

        postgresTypeAndOid.put("TIMESTAMP", 1114);

        postgresTypeAndOid.put("TIMESTAMP WITH TIME ZONE", 1184);

        postgresTypeAndOid.put("UUID", 2950);

        postgresTypeAndOid.put("VARCHAR", 1043);

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
