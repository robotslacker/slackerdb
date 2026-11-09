package org.slackerdb.dbserver.sql;

import org.slackerdb.plsql.PlSqlEngine;
import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.detect.PlSqlDetector;
import org.slackerdb.plsql.detect.PlSqlScript;
import org.slackerdb.plsql.detect.PlSqlStatement;
import org.slackerdb.plsql.spi.DefaultJdbcHost;
import org.slackerdb.plsql.spi.PlSqlHost;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一段 SQL 文本的统一执行器。
 *
 * <p>负责把“普通 SQL / PL/SQL 匿名块 / 多语句脚本”路由到正确的执行方式：</p>
 * <ul>
 *   <li>单条普通 SQL：沿用 {@link PreparedStatement}；</li>
 *   <li>匿名块：交给 {@link PlSqlEngine}，与 PG 协议路径、SQL REPL 使用同一个引擎；</li>
 *   <li>多语句脚本：逐条执行；块交给引擎，普通语句交给 {@link Statement}；</li>
 *   <li>只保留最后一条语句的结果集；最后一条不是查询时结果集为空。</li>
 * </ul>
 *
 * <p>本类刻意不处理缓存、事务和结果契约：调用方拿到 {@link Result} 后，自己决定是
 * 物化结果（HTTP 数据服务）还是交给分页逻辑（SQL REPL）。</p>
 */
public final class SqlTextExecutor {

    private SqlTextExecutor() {
    }

    /**
     * 脚本执行期间的活动对象观察者。
     *
     * <p>SQL REPL 用它维护 {@code session.activeStatement}，从而保留“取消正在执行的语句”
     * 能力；数据服务不需要，使用 {@link #NONE} 即可。</p>
     */
    public interface Observer {
        /** 即将执行一个匿名块；此时没有活动的 JDBC Statement。 */
        void onBlock();

        /** 即将执行一条普通 SQL。 */
        void onStatement(Statement statement);

        Observer NONE = new Observer() {
            @Override
            public void onBlock() {
            }

            @Override
            public void onStatement(Statement statement) {
            }
        };
    }

    /**
     * 执行结果。
     *
     * <p>{@code resultSet} 非空时，调用方拥有它和它的 {@code statement}，必须在使用完后
     * 通过 {@link #closeQuietly(ResultSet, Statement)} 关闭；{@code affectedRows} 是脚本中
     * 普通语句的影响行数之和（PL/SQL 块内部的 DML 不计入）。</p>
     */
    public record Result(ResultSet resultSet, Statement statement, int affectedRows,
                         Map<String, Object> data) {
        public Result(ResultSet resultSet, Statement statement, int affectedRows) {
            this(resultSet, statement, affectedRows, null);
        }

        public boolean hasResultSet() {
            return resultSet != null;
        }

        /** PL/SQL 动态 SQL 被捕获后的结果（与普通查询的 data 同构）；没有时为 null。 */
        public boolean hasData() {
            return data != null;
        }
    }

    /**
     * 执行一段 SQL 文本，自动识别普通 SQL、匿名块和多语句脚本。
     *
     * <p>数据服务路径使用 {@link CapturingPlSqlHost}，因此块内最后一条不带
     * {@code INTO} 的 {@code EXECUTE IMMEDIATE} 查询会把结果集带回来；SQL REPL
     * 走另一个重载并传入自己的 host，语义不变。</p>
     */
    public static Result execute(Connection connection, String sql) throws SQLException {
        PlSqlScript script = PlSqlDetector.analyze(sql);
        if (!script.isSingleBlock() && !script.isScript()) {
            // 普通 SQL / 空脚本不需要 PL/SQL host
            return execute(connection, script, null, Observer.NONE);
        }
        if (script.statements().stream().noneMatch(PlSqlStatement::isBlock)) {
            // 纯 SQL 多语句脚本也不需要 PL/SQL host
            return execute(connection, script, null, Observer.NONE);
        }
        CapturingPlSqlHost host = new CapturingPlSqlHost(connection);
        Result result = execute(connection, script, host, Observer.NONE);
        if (!result.hasResultSet() && !result.hasData() && host.hasResult()) {
            return new Result(null, null, result.affectedRows(), host.takeResult());
        }
        return result;
    }

    /**
     * 执行已经分析好的脚本。
     *
     * @param host 可复用的 PL/SQL host；为 {@code null} 时按需创建（数据服务路径）。
     *             SQL REPL 传入自己的 host，以便取消请求能命中块内语句。
     * @param observer 活动语句观察者；不需要时传 {@link Observer#NONE}
     */
    public static Result execute(Connection connection, PlSqlScript script,
                                 PlSqlHost host, Observer observer) throws SQLException {
        if (script.hasError()) {
            throw new SQLException(script.error(), "42601");
        }
        if (script.isEmpty()) {
            return new Result(null, null, 0);
        }
        if (!script.isSingleBlock() && !script.isScript()) {
            return executeSingle(connection, script.first().sql(), observer);
        }
        return executeScript(connection, script, host, observer);
    }

    /** 单条普通 SQL：保持历史行为，使用 {@link PreparedStatement}。 */
    private static Result executeSingle(Connection connection, String sql, Observer observer)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            observer.onStatement(statement);
            if (!statement.execute()) {
                int affectedRows = safeInt(statement.getUpdateCount());
                closeQuietly(statement);
                return new Result(null, null, affectedRows);
            }
            ResultSet resultSet = statement.getResultSet();
            if (resultSet == null) {
                // execute() 声称有结果集却没有：保持资源干净，按无结果集处理
                closeQuietly(statement);
                return new Result(null, null, 0);
            }
            return new Result(resultSet, statement, 0);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(statement);
            throw e;
        }
    }

    /** 匿名块 / 多语句脚本：逐条执行，只保留最后一条语句的结果集。 */
    private static Result executeScript(Connection connection, PlSqlScript script,
                                        PlSqlHost host, Observer observer) throws SQLException {
        ResultSet lastResultSet = null;
        Statement lastStatement = null;
        long affectedRows = 0;
        boolean handedOff = false;
        PlSqlHost effectiveHost = host;

        try {
            for (PlSqlStatement statement : script.statements()) {
                // 同一连接不能同时挂多个打开的结果集：先关掉上一条
                closeQuietly(lastResultSet, lastStatement);
                lastResultSet = null;
                lastStatement = null;

                if (statement.isBlock()) {
                    observer.onBlock();
                    if (effectiveHost == null) {
                        effectiveHost = new DefaultJdbcHost(connection);
                    }
                    try {
                        PlSqlEngine.execute(effectiveHost, statement.body());
                    } catch (PlSqlException e) {
                        throw new SQLException(
                                e.getMessage() + e.positionSuffix(), e.getSqlState(), e);
                    }
                    continue;
                }

                Statement stmt = connection.createStatement();
                try {
                    observer.onStatement(stmt);
                    if (stmt.execute(statement.sql())) {
                        ResultSet resultSet = stmt.getResultSet();
                        if (resultSet == null) {
                            // 没有真实结果集就不要把 Statement 交出去，避免调用方泄漏
                            closeQuietly(stmt);
                        } else {
                            lastResultSet = resultSet;
                            lastStatement = stmt;
                        }
                    } else {
                        long updates = stmt.getUpdateCount();
                        if (updates > 0) {
                            affectedRows += updates;
                        }
                        closeQuietly(stmt);
                    }
                } catch (SQLException | RuntimeException e) {
                    closeQuietly(stmt);
                    throw e;
                }
            }

            Result result = new Result(lastResultSet, lastStatement, safeInt(affectedRows));
            handedOff = true;
            return result;
        } finally {
            if (!handedOff) {
                closeQuietly(lastResultSet, lastStatement);
            }
        }
    }

    /**
     * 把结果集物化成数据服务使用的 JSON 安全结构。
     *
     * <p>与历史实现保持一致：DATE/TIME/TIMESTAMPTZ/BLOB/LIST/JSON 等类型统一走
     * {@link JsonValues#toJsonValue(Object)}。</p>
     */
    public static Map<String, Object> materialize(ResultSet resultSet) throws SQLException {
        ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
        int columnCount = resultSetMetaData.getColumnCount();
        List<String> columnNames = new ArrayList<>();
        List<String> columnTypes = new ArrayList<>();
        List<List<Object>> dataset = new ArrayList<>();
        for (int nPos = 0; nPos < columnCount; nPos++) {
            columnNames.add(resultSetMetaData.getColumnName(nPos + 1));
            columnTypes.add(resultSetMetaData.getColumnTypeName(nPos + 1));
        }
        while (resultSet.next()) {
            List<Object> row = new ArrayList<>();
            for (int nPos = 0; nPos < columnCount; nPos++) {
                row.add(JsonValues.toJsonValue(resultSet.getObject(nPos + 1)));
            }
            dataset.add(row);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("columnTypes", columnTypes);
        result.put("columnNames", columnNames);
        result.put("timestamp", System.currentTimeMillis());
        result.put("dataset", dataset);
        result.put("affectedRows", dataset.size());
        return result;
    }

    /** 关闭结果集和语句；任何一步失败都不影响另一步，也不向外抛。 */
    public static void closeQuietly(ResultSet resultSet, Statement statement) {
        closeQuietly(resultSet);
        closeQuietly(statement);
    }

    private static void closeQuietly(ResultSet resultSet) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeQuietly(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static int safeInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }
}
