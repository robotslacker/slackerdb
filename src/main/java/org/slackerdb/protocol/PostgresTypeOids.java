package org.slackerdb.protocol;

// 数据来源： pg_type
public class PostgresTypeOids {
    public static int getTypeOid(String columnTypeName)
    {
        switch (columnTypeName.trim().toUpperCase())
        {
            case "BIGINT":
            case "HUGEINT":
            case "UBIGINT":
            case "UHUGEINT":
                // PG并不支持HUGEINT, UBIGINT,UHUGEINT
                // int8
                return 20;
            case "INTEGER":
            case "UINTEGER":
                // int4
                return 23;
            case "SMALLINT":
            case "USMALLINT":
                // int2
                return 21;
            case "TINYINT":
            case "UTINYINT":
                // int1
                // PG不支持TINYINT
                return 21;
            case "BOOLEAN":
                // bool
                return 16;
            case "BIT":
                // var bit
                return 1563;
            case "DATE":
                // date
                return 1082;
            case "DECIMAL":
                // numeric
                return 1700;
            case "INTERVAL":
                // interval
                return 1186;
            case "REAL":
                // float4
                return 700;
            case "DOUBLE":
                // float8
                return 701;
            case "TIME":
                // time
                return 1083;
            case "TIMESTAMP":
                // timestamp
                return 1114;
            case "TIMESTAMP WITH TIME ZONE":
                // timestamptz
                return 1184;
            case "UUID":
                return 2950;
            case "VARCHAR":
                return 1043;
            case "ARRAY":
                // anyArray
                return 2277;
        }
        return 0;
    }
}
