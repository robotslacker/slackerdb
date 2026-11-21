package org.slackerdb.dbsyncer.util;

/**
 * DuckDB/源库标识符工具。所有动态拼接的标识符都必须经过这里。
 */
public final class SqlIdentifiers {

    private SqlIdentifiers() {
    }

    public static String quote(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("identifier must not be null");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public static String qualified(String catalog, String schema, String table) {
        if (catalog == null || catalog.isBlank()) {
            return quote(schema) + "." + quote(table);
        }
        return quote(catalog) + "." + quote(schema) + "." + quote(table);
    }

    public static String qualifiedSchema(String catalog, String schema) {
        if (catalog == null || catalog.isBlank()) {
            return quote(schema);
        }
        return quote(catalog) + "." + quote(schema);
    }
}
