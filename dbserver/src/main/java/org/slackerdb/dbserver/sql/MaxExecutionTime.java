package org.slackerdb.dbserver.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DuckDB 会话级 {@code max_execution_time} 的读写与恢复。
 *
 * <p>SQL REPL 与 MCP {@code run_sql} 都允许"按单次执行"覆盖超时：执行前把会话值改成本次的值，
 * 执行后必须恢复成执行前的值，不能把一次调用的限制泄漏给同一会话里的后续语句。</p>
 *
 * <p>恢复规则与连接池归还时一致：执行前本来就是"跟随 GLOBAL"的，用 {@code RESET} 回到跟随状态；
 * 执行前是客户端显式设过的会话值，则写回该值。</p>
 */
public final class MaxExecutionTime {

    private MaxExecutionTime() {
    }

    /** 读取连接上当前生效的值（毫秒）。 */
    public static long read(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT current_setting('max_execution_time')")) {
            if (!resultSet.next()) {
                throw new SQLException("current_setting('max_execution_time') returned no row");
            }
            return Long.parseLong(resultSet.getString(1).trim());
        }
    }

    /** 设置本连接的会话级覆盖（毫秒；0 表示不限制）。 */
    public static void apply(Connection connection, long milliseconds) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION max_execution_time=" + milliseconds);
        }
    }

    /**
     * 恢复执行前的会话值。
     *
     * @param baseline  执行前读到的值
     * @param globalLimit 服务器 GLOBAL 配置；{@code baseline == globalLimit} 时说明执行前没有会话覆盖
     */
    public static void restore(Connection connection, long baseline, long globalLimit) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (baseline == globalLimit) {
                statement.execute("RESET max_execution_time");
            } else {
                statement.execute("SET SESSION max_execution_time=" + baseline);
            }
        }
    }
}
