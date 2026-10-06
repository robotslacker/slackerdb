package org.slackerdb.plsql.spi;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于单条 JDBC 连接的默认 {@link PlSqlHost} 实现。
 *
 * <p>用途：</p>
 * <ul>
 *   <li>离线单元测试 / 一致性语料（{@code jdbc:duckdb::memory:}）；</li>
 *   <li>把 plsql 作为库嵌入到自己的程序里：{@code PlSqlEngine.execute(new DefaultJdbcHost(conn), script)}。</li>
 * </ul>
 */
public class DefaultJdbcHost implements PlSqlHost {

    private final Connection connection;
    private final AtomicBoolean canceled = new AtomicBoolean(false);

    public DefaultJdbcHost(Connection connection) {
        this.connection = connection;
    }

    /** 供嵌入方直接访问底层连接（引擎自身不得使用）。 */
    public Connection connection() {
        return connection;
    }

    /** 请求取消：下一次 {@link #checkInterrupted()} 生效（用于测试与嵌入方自建取消）。 */
    public void requestCancel() {
        canceled.set(true);
    }

    @Override
    public StatementHandle execute(String sql, List<Object> binds) throws SQLException {
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        try {
            bind(preparedStatement, binds);
            boolean hasResultSet = preparedStatement.execute();
            ResultSet resultSet = hasResultSet ? preparedStatement.getResultSet() : null;
            return new JdbcStatementHandle(sql, preparedStatement, resultSet);
        } catch (SQLException e) {
            closeQuietly(preparedStatement, null);
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
        if (canceled.get()) {
            throw new PlSqlCanceledException("PL/SQL execution canceled.");
        }
    }

    /** 绑定参数：null 元素按 SQL NULL 处理。 */
    public static void bind(PreparedStatement preparedStatement, List<Object> binds) throws SQLException {
        if (binds == null) {
            return;
        }
        for (int i = 0; i < binds.size(); i++) {
            preparedStatement.setObject(i + 1, binds.get(i));
        }
    }

    /** 静默关闭 JDBC 资源：清理失败不得掩盖业务异常（宿主实现也会复用）。 */
    public static void closeQuietly(PreparedStatement preparedStatement, ResultSet resultSet) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (SQLException ignored) {
                // 清理失败不掩盖业务异常
            }
        }
        if (preparedStatement != null) {
            try {
                preparedStatement.close();
            } catch (SQLException ignored) {
                // 同上
            }
        }
    }

    /** JDBC 句柄包装：幂等关闭。 */
    static final class JdbcStatementHandle implements StatementHandle {
        private final String sql;
        private PreparedStatement preparedStatement;
        private ResultSet resultSet;

        JdbcStatementHandle(String sql, PreparedStatement preparedStatement, ResultSet resultSet) {
            this.sql = sql;
            this.preparedStatement = preparedStatement;
            this.resultSet = resultSet;
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
            closeQuietly(preparedStatement, resultSet);
            preparedStatement = null;
            resultSet = null;
        }
    }
}
