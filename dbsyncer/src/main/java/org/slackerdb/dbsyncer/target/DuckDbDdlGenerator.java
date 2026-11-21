package org.slackerdb.dbsyncer.target;

import org.slackerdb.dbsyncer.model.TargetTable;
import org.slackerdb.dbsyncer.util.SqlIdentifiers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 生成并执行 DuckDB 的建 schema / 建表 / 加列 DDL。 */
public final class DuckDbDdlGenerator {

    private DuckDbDdlGenerator() {
    }

    public static void ensureSchema(Connection connection, String catalog, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + SqlIdentifiers.qualifiedSchema(catalog, schema));
        }
    }

    public static boolean tableExists(Connection connection, String catalog, String schema, String table)
            throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_catalog=? AND table_schema=? AND table_name=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, catalog);
            statement.setString(2, schema);
            statement.setString(3, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 比较目标表实际结构与期望结构。返回空列表表示一致；表不存在返回 ["table missing"]。
     */
    public static List<String> schemaMismatch(Connection connection, TargetTable table) throws SQLException {
        if (!tableExists(connection, table.catalog, table.schema, table.table)) {
            return List.of("table missing");
        }
        Map<String, String> actual = new LinkedHashMap<>();
        String sql = "SELECT column_name, data_type FROM information_schema.columns"
                + " WHERE table_catalog=? AND table_schema=? AND table_name=? ORDER BY ordinal_position";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.catalog);
            statement.setString(2, table.schema);
            statement.setString(3, table.table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    actual.put(rs.getString(1).toLowerCase(Locale.ROOT), normalizeType(rs.getString(2)));
                }
            }
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (TargetTable.TargetColumn column : table.columns) {
            expected.put(column.name().toLowerCase(Locale.ROOT), normalizeType(column.duckType()));
        }
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actualType = actual.remove(entry.getKey());
            if (actualType == null) {
                differences.add("missing column " + entry.getKey());
            } else if (!actualType.equals(entry.getValue())) {
                differences.add("column " + entry.getKey() + " type " + entry.getValue()
                        + " expected, actual " + actualType);
            }
        }
        for (String extra : actual.keySet()) {
            differences.add("extra column " + extra);
        }
        return differences;
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        return type.toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replace(" ,", ",")
                .replace(", ", ",")
                .trim();
    }

    /**
     * 比较目标表的实际主键与期望主键（忽略顺序与大小写）。
     *
     * <p>主键不一致时 {@code INSERT OR REPLACE} 会按"表上的旧主键"判冲突、{@code DELETE ... WHERE}
     * 会按"配置里的新主键"定位，结果可能是重复行或误删多行，所以必须当作结构不一致处理。</p>
     */
    public static List<String> primaryKeyMismatch(Connection connection, TargetTable table)
            throws SQLException {
        List<String> actual = primaryKeyColumns(connection, table);
        Set<String> expected = new LinkedHashSet<>();
        for (String column : table.primaryKey) {
            expected.add(column.toLowerCase(Locale.ROOT));
        }
        Set<String> actualSet = new LinkedHashSet<>();
        for (String column : actual) {
            actualSet.add(column.toLowerCase(Locale.ROOT));
        }
        if (expected.equals(actualSet)) {
            return List.of();
        }
        return List.of("primary key " + expected + " expected, actual " + actualSet);
    }

    /** 目标表实际主键列（按约束顺序）。 */
    public static List<String> primaryKeyColumns(Connection connection, TargetTable table)
            throws SQLException {
        String sql = "SELECT k.column_name FROM information_schema.table_constraints c"
                + " JOIN information_schema.key_column_usage k"
                + " ON k.constraint_catalog = c.constraint_catalog"
                + " AND k.constraint_schema = c.constraint_schema"
                + " AND k.constraint_name = c.constraint_name"
                + " WHERE c.table_catalog = ? AND c.table_schema = ? AND c.table_name = ?"
                + " AND c.constraint_type = 'PRIMARY KEY' ORDER BY k.ordinal_position";
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.catalog);
            statement.setString(2, table.schema);
            statement.setString(3, table.table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        }
        return result;
    }

    public static boolean tableHasRows(Connection connection, TargetTable table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1 FROM " + table.qualifiedName() + " LIMIT 1")) {
            return rs.next();
        }
    }

    public static void createTable(Connection connection, TargetTable table) throws SQLException {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(table.qualifiedName()).append(" (");
        List<String> definitions = new ArrayList<>();
        for (TargetTable.TargetColumn column : table.columns) {
            definitions.add(SqlIdentifiers.quote(column.name()) + " " + column.duckType());
        }
        if (!table.primaryKey.isEmpty()) {
            List<String> pk = new ArrayList<>();
            for (String column : table.primaryKey) {
                pk.add(SqlIdentifiers.quote(column));
            }
            definitions.add("PRIMARY KEY (" + String.join(", ", pk) + ")");
        }
        sql.append(String.join(", ", definitions)).append(')');
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql.toString());
        }
    }

    public static void addColumnIfMissing(Connection connection, TargetTable table, String column, String type)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table.qualifiedName()
                    + " ADD COLUMN IF NOT EXISTS " + SqlIdentifiers.quote(column) + " " + type);
        }
    }

    public static void truncate(Connection connection, TargetTable table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE " + table.qualifiedName());
        }
    }
}
