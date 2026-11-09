package org.slackerdb.dbserver.sql;

import org.slackerdb.plsql.PlSqlException;
import org.slackerdb.plsql.spi.DefaultJdbcHost;
import org.slackerdb.plsql.spi.PlSqlHost;
import org.slackerdb.plsql.spi.StatementHandle;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据服务 API 专用的 PL/SQL host：捕获不带 {@code INTO} 的
 * {@code EXECUTE IMMEDIATE} 产生的结果集。
 *
 * <p>PL/SQL 引擎默认把这类结果集丢弃；数据服务 API 需要把动态 SQL 的查询结果
 * 作为 HTTP 响应返回，因此在这里把结果集物化成与普通查询相同的
 * {@code columnNames/columnTypes/dataset/timestamp/affectedRows} 结构。</p>
 *
 * <p>捕获语义是“最后一条产生结果集的动态 SQL”：后续的 DML/普通语句不会清掉已经捕获的
 * 结果，只有新的动态查询结果会覆盖它。</p>
 */
public final class CapturingPlSqlHost implements PlSqlHost {

    private final DefaultJdbcHost delegate;
    private Map<String, Object> captured;

    public CapturingPlSqlHost(Connection connection) {
        this.delegate = new DefaultJdbcHost(connection);
    }

    @Override
    public StatementHandle execute(String sql, List<Object> binds) throws SQLException {
        return delegate.execute(sql, binds);
    }

    @Override
    public Object evaluate(String sqlExpression, List<Object> binds) throws SQLException {
        return delegate.evaluate(sqlExpression, binds);
    }

    @Override
    public void checkInterrupted() {
        delegate.checkInterrupted();
    }

    @Override
    public void notice(String message) {
        delegate.notice(message);
    }

    @Override
    public void debug(String format, Object... args) {
        delegate.debug(format, args);
    }

    @Override
    public void result(StatementHandle handle) {
        try {
            captured = materialize(handle);
        } catch (SQLException e) {
            throw new PlSqlException(
                    "Failed to capture EXECUTE IMMEDIATE result: " + e.getMessage(),
                    PlSqlException.INTERNAL_ERROR, e);
        } finally {
            handle.close();
        }
    }

    public boolean hasResult() {
        return captured != null;
    }

    /** 取出并清空捕获结果；没有捕获时返回 null。 */
    public Map<String, Object> takeResult() {
        Map<String, Object> result = captured;
        captured = null;
        return result;
    }

    private static Map<String, Object> materialize(StatementHandle handle) throws SQLException {
        int columnCount = handle.columnCount();
        List<String> columnNames = new ArrayList<>(columnCount);
        List<String> columnTypes = new ArrayList<>(columnCount);
        for (int index = 1; index <= columnCount; index++) {
            columnNames.add(handle.columnName(index));
            columnTypes.add(handle.columnType(index));
        }

        List<List<Object>> dataset = new ArrayList<>();
        while (handle.next()) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                row.add(JsonValues.toJsonValue(handle.get(index)));
            }
            dataset.add(row);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("columnNames", columnNames);
        result.put("columnTypes", columnTypes);
        result.put("dataset", dataset);
        result.put("affectedRows", dataset.size());
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }
}
