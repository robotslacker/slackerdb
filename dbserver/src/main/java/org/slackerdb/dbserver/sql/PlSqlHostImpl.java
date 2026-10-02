package org.slackerdb.dbserver.sql;

import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.plsql.spi.DefaultJdbcHost;
import org.slackerdb.plsql.spi.PlSqlCanceledException;
import org.slackerdb.plsql.spi.PlSqlHost;
import org.slackerdb.plsql.spi.StatementHandle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * dbserver 侧的 {@link PlSqlHost} 实现：PL/SQL 引擎与数据库之间的唯一通道。
 *
 * <p>它把 PL/SQL 内部语句接入服务端既有的横切能力，从而修复历史缺陷
 * （旧实现直接拿 {@code Connection} 执行，绕过了这里的一切）：</p>
 * <ul>
 *   <li>语句登记到 {@link DBSession#registerRunningStatement} —— {@code CancelRequest} /
 *       {@code KILL SESSION} 才能中断 PL/SQL 内部的当前语句；</li>
 *   <li>会话级取消标志 {@link DBSession#cancelRequested} —— 让长循环在语句之间也能被打断；</li>
 *   <li>{@link DBSession#executingSQL} 可观测（管理端 /status、KILL 时能看到正在跑什么）；</li>
 *   <li>句柄统一关闭，异常路径也不泄漏 PreparedStatement/ResultSet。</li>
 * </ul>
 *
 * <p>SQL 改写（{@code SQLReplacer}）暂<b>不</b>应用于块内语句：迁移期要求行为与旧实现一致，
 * 待 P3 统一协议路由时一并评估。</p>
 */
public class PlSqlHostImpl implements PlSqlHost {

    private final DBInstance dbInstance;
    private final DBSession session;

    public PlSqlHostImpl(DBInstance dbInstance, DBSession session) {
        this.dbInstance = dbInstance;
        this.session = session;
    }

    @Override
    public StatementHandle execute(String sql, List<Object> binds) throws SQLException {
        Connection connection = session.dbConnection;
        if (connection == null) {
            throw new SQLException("No database connection is bound to this session.");
        }

        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        long handleId = 0;
        try {
            DefaultJdbcHost.bind(preparedStatement, binds);
            // 登记"正在执行"，取消请求才能命中这条内部语句
            handleId = session.registerRunningStatement(preparedStatement);
            session.executingSQL = sql;

            boolean hasResultSet = preparedStatement.execute();
            ResultSet resultSet = hasResultSet ? preparedStatement.getResultSet() : null;
            return new HostStatementHandle(sql, preparedStatement, resultSet, handleId);
        } catch (SQLException e) {
            // 失败路径必须自己收尾：句柄从未交给引擎，引擎无法关闭它
            session.unregisterRunningStatement(handleId);
            closeQuietly(preparedStatement, null);
            // 语句是被"取消"打断时（CancelRequest 已置位），上报取消而不是底层 SQL 错误，
            // 这样协议层能稳定回 57014（否则 DuckDB 的中断错误会被误映射成 42601）。
            if (session.cancelRequested.get()) {
                throw new PlSqlCanceledException("PL/SQL execution canceled by user.", e);
            }
            throw e;
        }
    }

    @Override
    public Object evaluate(String sqlExpression, List<Object> binds) throws SQLException {
        StatementHandle handle = execute("select " + sqlExpression, binds);
        try {
            if (!handle.next()) {
                return null;
            }
            Object value = handle.get(1);
            if (handle.next()) {
                // 与历史行为一致：表达式求值不允许返回多行
                throw new SQLException("Evaluate got too much rows.");
            }
            return value;
        } finally {
            handle.close();
        }
    }

    @Override
    public void checkInterrupted() throws PlSqlCanceledException {
        if (session.cancelRequested.get()) {
            throw new PlSqlCanceledException("PL/SQL execution canceled by user.");
        }
    }

    @Override
    public void notice(String message) {
        if (dbInstance != null && dbInstance.logger != null) {
            dbInstance.logger.info("[SERVER][PLSQL      ] NOTICE: {}", message);
        }
    }

    @Override
    public void debug(String format, Object... args) {
        if (dbInstance != null && dbInstance.logger != null) {
            dbInstance.logger.debug("[SERVER][PLSQL      ] " + format, args);
        }
    }

    private static void closeQuietly(PreparedStatement preparedStatement, ResultSet resultSet) {
        DefaultJdbcHost.closeQuietly(preparedStatement, resultSet);
    }

    /** 句柄：关闭时同时摘掉"正在执行"登记并释放 JDBC 资源（幂等）。 */
    private final class HostStatementHandle implements StatementHandle {
        private final String sql;
        private final long handleId;
        private PreparedStatement preparedStatement;
        private ResultSet resultSet;

        HostStatementHandle(String sql, PreparedStatement preparedStatement, ResultSet resultSet, long handleId) {
            this.sql = sql;
            this.preparedStatement = preparedStatement;
            this.resultSet = resultSet;
            this.handleId = handleId;
        }

        @Override
        public boolean next() throws SQLException {
            return resultSet != null && resultSet.next();
        }

        @Override
        public Object get(int oneBasedIndex) throws SQLException {
            return resultSet == null ? null : resultSet.getObject(oneBasedIndex);
        }

        @Override
        public int columnCount() throws SQLException {
            return resultSet == null ? 0 : resultSet.getMetaData().getColumnCount();
        }

        @Override
        public long updateCount() throws SQLException {
            if (resultSet != null || preparedStatement == null) {
                return -1;
            }
            return preparedStatement.getUpdateCount();
        }

        @Override
        public boolean hasResultSet() {
            return resultSet != null;
        }

        @Override
        public String sql() {
            return sql;
        }

        @Override
        public void close() {
            session.unregisterRunningStatement(handleId);
            closeQuietly(preparedStatement, resultSet);
            preparedStatement = null;
            resultSet = null;
        }
    }
}
