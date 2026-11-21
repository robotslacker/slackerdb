package org.slackerdb.dbsyncer.model;


import java.sql.Types;
import java.util.Locale;

/**
 * 源类型 → DuckDB 类型。只输出受控的白名单类型，绝不直接拼接源库类型字符串。
 */
public final class TypeMapper {

    private TypeMapper() {
    }

    public static String map(String dbType, SourceTable.SourceColumn column) {
        String base = column.typeName == null ? "" : column.typeName.trim().toLowerCase(Locale.ROOT);
        int paren = base.indexOf('(');
        if (paren > 0) {
            base = base.substring(0, paren).trim();
        }
        boolean unsigned = column.typeDefinition != null
                && column.typeDefinition.toLowerCase(Locale.ROOT).contains("unsigned");
        boolean mysql = "mysql".equalsIgnoreCase(dbType);

        if (unsigned) {
            return switch (base) {
                case "tinyint" -> "SMALLINT";
                case "smallint", "mediumint" -> "INTEGER";
                case "int", "integer" -> "BIGINT";
                case "bigint" -> "DECIMAL(20,0)";
                default -> fallback(column.jdbcType);
            };
        }

        return switch (base) {
            case "bool", "boolean" -> "BOOLEAN";
            case "bit" -> bitType(column);
            case "tinyint" -> "TINYINT";
            case "int2", "smallint", "smallserial", "year" -> "SMALLINT";
            case "int4", "integer", "int", "serial", "mediumint" -> "INTEGER";
            case "int8", "bigint", "bigserial" -> "BIGINT";
            case "numeric", "decimal", "number" -> decimal(column);
            case "float4", "real", "float" -> "FLOAT";
            case "float8", "double", "double precision" -> "DOUBLE";
            case "money" -> "DECIMAL(19,2)";
            case "char", "bpchar", "character", "varchar", "character varying", "text", "tinytext",
                 "mediumtext", "longtext", "name", "citext", "enum", "set", "xml", "inet", "cidr",
                 "macaddr", "macaddr8", "tsvector", "tsquery", "hstore", "ltree", "unknown", "timetz",
                 "time with time zone", "interval" -> "VARCHAR";
            case "date" -> "DATE";
            case "time" -> mysql ? "VARCHAR" : "TIME";
            case "timestamp", "datetime" -> "TIMESTAMP";
            case "timestamptz", "timestamp with time zone" -> "TIMESTAMP WITH TIME ZONE";
            case "bytea", "binary", "varbinary", "blob", "tinyblob", "mediumblob", "longblob", "geometry", "geography",
                 "point", "linestring", "polygon", "multipoint", "multilinestring", "multipolygon",
                 "geometrycollection" -> "BLOB";
            case "json", "jsonb" -> "JSON";
            case "uuid", "uniqueidentifier" -> "UUID";
            default -> {
                if (base.startsWith("_") || base.endsWith("[]") || base.contains("array")) {
                    yield "VARCHAR";
                }
                yield fallback(column.jdbcType);
            }
        };
    }

    private static String decimal(SourceTable.SourceColumn column) {
        int precision = column.size;
        int scale = Math.max(column.decimalDigits, 0);
        if (precision <= 0) {
            return "DECIMAL(38,9)";
        }
        if (precision > 38) {
            precision = 38;
        }
        if (scale > precision) {
            scale = precision;
        }
        return "DECIMAL(" + precision + "," + scale + ")";
    }

    private static String bitType(SourceTable.SourceColumn column) {
        if (column.size <= 1) {
            return "BOOLEAN";
        }
        return "VARCHAR";
    }

    private static String fallback(int jdbcType) {
        return switch (jdbcType) {
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.TINYINT, Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.REAL, Types.FLOAT -> "FLOAT";
            case Types.DOUBLE -> "DOUBLE";
            case Types.NUMERIC, Types.DECIMAL -> "DECIMAL(38,9)";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP WITH TIME ZONE";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BLOB";
            default -> "VARCHAR";
        };
    }
}
