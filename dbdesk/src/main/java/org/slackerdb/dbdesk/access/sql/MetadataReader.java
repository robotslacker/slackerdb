package org.slackerdb.dbdesk.access.sql;

import org.slackerdb.dbdesk.access.JdbcDrivers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 元数据读取（库 → 表 → 列）。走标准 {@code information_schema}，不碰 DuckDB 私有函数 ——
 * 服务端将来换存储，这一层不用改。
 *
 * <p>实测 SlackerDB 0.2.2（DuckDB 底座）的 {@code information_schema} 可用：
 * 用户表在 {@code main} 或其它用户 schema 下，系统表在
 * {@code sysaux}/{@code duck_catalog}/{@code information_schema}/{@code pg_catalog}，
 * 这里默认把它们过滤掉 —— 元数据页只展示用户 schema，避免把 sysaux 暴露给使用者。</p>
 *
 * <p>刻意做成**一次性小查询**而不是连接上的 {@code DatabaseMetaData}：后者在不同驱动/服务端上行为差异大，
 * 而且没法保证只查自己需要的 schema。</p>
 */
public final class MetadataReader {

    private static final String SYSTEM_SCHEMAS =
            "'information_schema','pg_catalog','duck_catalog','system','sysaux'";

    /** 一张表/视图。 */
    public record TableRef(String schema, String name, String type) {
        public String qualifiedName() {
            return schema + "." + name;
        }
    }

    /** 一列。 */
    public record ColumnRef(String name, String type, boolean nullable) {
    }

    private MetadataReader() {
    }

    public static List<TableRef> tables(String jdbcUrl) throws SQLException {
        String sql = "select table_schema, table_name, table_type from information_schema.tables"
                + " where table_schema not in (" + SYSTEM_SCHEMAS + ")"
                + " order by table_schema, table_name";
        List<TableRef> tables = new ArrayList<>();
        try (Connection connection = open(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                tables.add(new TableRef(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return List.copyOf(tables);
    }

    public static List<ColumnRef> columns(String jdbcUrl, String schema, String table) throws SQLException {
        String sql = "select column_name, data_type, is_nullable from information_schema.columns"
                + " where table_schema = ? and table_name = ?"
                + " order by ordinal_position";
        List<ColumnRef> columns = new ArrayList<>();
        try (Connection connection = open(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(new ColumnRef(rs.getString(1), rs.getString(2),
                            "YES".equalsIgnoreCase(rs.getString(3))));
                }
            }
        }
        return List.copyOf(columns);
    }

    private static Connection open(String jdbcUrl) throws SQLException {
        JdbcDrivers.ensureLoaded();
        return DriverManager.getConnection(jdbcUrl, "", "");
    }
}
