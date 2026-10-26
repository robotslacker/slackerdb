package org.slackerdb.dbdesk.access.sql;

import org.slackerdb.dbdesk.access.JdbcDrivers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 执行一条 DDL（建表等）。
 *
 * <p>为什么单独一个类：UI 层不允许直接碰 JDBC（ArchUnit 有规则钉住，导入页第一版就踩了）。
 * 把"打开连接 + 执行 + 关连接"收在 access 层，UI 只表达意图。</p>
 */
public final class SqlDdl {

    private SqlDdl() {
    }

    public static void execute(String jdbcUrl, String ddl) throws SQLException {
        JdbcDrivers.ensureLoaded();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "", "");
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    /** 从 {@code CREATE TABLE ...} 里取表名（够用即可：去掉前缀、取到空白或括号）。 */
    public static String tableNameFromDdl(String ddl) {
        String withoutPrefix = ddl.replaceAll("(?i)^\\s*create\\s+(?:or\\s+replace\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?", "");
        return withoutPrefix.trim().split("[\\s(]", 2)[0];
    }
}
