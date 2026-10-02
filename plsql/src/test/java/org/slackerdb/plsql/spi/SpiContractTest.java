package org.slackerdb.plsql.spi;

import org.junit.jupiter.api.Test;
import org.slackerdb.plsql.PlSqlEngine;
import org.slackerdb.plsql.PlSqlException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L6：SPI 契约测试。
 *
 * <p>用一个记录型宿主包住 {@link DefaultJdbcHost}，断言引擎与宿主之间的契约：
 * 绑定参数顺序、句柄零泄漏、取消检查点、表达式求值路径。</p>
 */
class SpiContractTest {

    /** 记录所有宿主交互的包装实现（不改变语义）。 */
    static final class RecordingHost implements PlSqlHost {
        private final DefaultJdbcHost delegate;
        final List<String> executedSql = new ArrayList<>();
        final List<List<Object>> binds = new ArrayList<>();
        final List<String> evaluated = new ArrayList<>();
        final List<StatementHandle> opened = new ArrayList<>();
        int closedCount = 0;
        int interruptChecks = 0;

        RecordingHost(Connection connection) {
            this.delegate = new DefaultJdbcHost(connection);
        }

        @Override
        public StatementHandle execute(String sql, List<Object> bindValues) throws SQLException {
            executedSql.add(sql);
            binds.add(bindValues == null ? List.of() : List.copyOf(bindValues));
            StatementHandle handle = delegate.execute(sql, bindValues);
            StatementHandle recording = new StatementHandle() {
                @Override
                public boolean next() throws SQLException {
                    return handle.next();
                }

                @Override
                public Object get(int oneBasedIndex) throws SQLException {
                    return handle.get(oneBasedIndex);
                }

                @Override
                public int columnCount() throws SQLException {
                    return handle.columnCount();
                }

                @Override
                public long updateCount() throws SQLException {
                    return handle.updateCount();
                }

                @Override
                public boolean hasResultSet() {
                    return handle.hasResultSet();
                }

                @Override
                public String sql() {
                    return handle.sql();
                }

                @Override
                public void close() {
                    handle.close();
                    closedCount++;
                }
            };
            opened.add(recording);
            return recording;
        }

        @Override
        public Object evaluate(String sqlExpression, List<Object> bindValues) throws SQLException {
            evaluated.add(sqlExpression);
            return delegate.evaluate(sqlExpression, bindValues);
        }

        @Override
        public void checkInterrupted() throws PlSqlCanceledException {
            interruptChecks++;
            delegate.checkInterrupted();
        }

        void requestCancel() {
            delegate.requestCancel();
        }
    }

    private static Connection newConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:duckdb::memory:", "", "");
        connection.setAutoCommit(false);
        return connection;
    }

    @Test
    void bindsArePassedInDeclarationOrder() throws Exception {
        try (Connection connection = newConnection()) {
            connection.createStatement().execute("create table t(a int, b text, c double)");
            RecordingHost host = new RecordingHost(connection);

            PlSqlEngine.execute(host, """
                    declare
                      a int;
                      b text;
                      c double;
                    begin
                      let a = 1;
                      let b = upper('x');
                      let c = 2.5;
                      insert into t values(:a, :b, :c);
                    end;""");

            boolean sawInsert = false;
            for (int i = 0; i < host.executedSql.size(); i++) {
                if (host.executedSql.get(i).startsWith("insert into t")) {
                    sawInsert = true;
                    List<Object> actual = host.binds.get(i);
                    assertEquals(3, actual.size());
                    assertEquals(1, actual.get(0));
                    assertEquals("X", actual.get(1));
                    // let c = 2.5 经 SELECT 2.5 求值得到 DECIMAL → BigDecimal，这里只比较数值
                    assertEquals(0, new java.math.BigDecimal("2.5")
                            .compareTo(new java.math.BigDecimal(String.valueOf(actual.get(2)))));
                }
            }
            assertTrue(sawInsert, "未观察到 insert 语句：" + host.executedSql);
            assertFalse(host.evaluated.isEmpty(), "表达式求值必须经 host.evaluate（不得直连 JDBC）");

            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("select a, b, c from t")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("X", rs.getString(2));
                assertEquals(2.5d, rs.getDouble(3));
            }
        }
    }

    @Test
    void everyHandleIsClosedEvenOnError() throws Exception {
        try (Connection connection = newConnection()) {
            connection.createStatement().execute("create table t(a int)");
            RecordingHost host = new RecordingHost(connection);

            // 成功路径
            PlSqlEngine.execute(host, "begin insert into t values(1); end;");
            // 失败路径（表不存在 → 语句失败）
            PlSqlException error = assertThrows(PlSqlException.class,
                    () -> PlSqlEngine.execute(host, "begin insert into no_such_table values(1); end;"));
            assertNotNull(error.getSqlState());
            // 语法错误路径（解析阶段就失败，宿主不应收到任何语句）
            assertThrows(PlSqlException.class, () -> PlSqlEngine.execute(host, "begin while 1 loop end loop; end;"));

            assertEquals(host.opened.size(), host.closedCount,
                    "句柄未全部关闭：opened=" + host.opened.size() + " closed=" + host.closedCount);
        }
    }

    @Test
    void interruptIsPolledPerStatementAndPerLoopRound() throws Exception {
        try (Connection connection = newConnection()) {
            connection.createStatement().execute("create table t(v int)");
            RecordingHost host = new RecordingHost(connection);

            PlSqlEngine.execute(host, """
                    declare
                      i int;
                    begin
                      for :i in [1,2,3] loop
                        insert into t values(1);
                      end loop;
                    end;""");

            // 3 轮循环 × (循环回边 1 次 + 语句 1 次) >= 6
            assertTrue(host.interruptChecks >= 6,
                    "取消检查点太少：" + host.interruptChecks);
        }
    }

    @Test
    void cancelIsMappedToSqlState57014() throws Exception {
        try (Connection connection = newConnection()) {
            RecordingHost host = new RecordingHost(connection);
            host.requestCancel();

            PlSqlException error = assertThrows(PlSqlException.class,
                    () -> PlSqlEngine.execute(host, "begin pass; end;"));
            assertEquals(PlSqlException.QUERY_CANCELED, error.getSqlState());
            assertTrue(host.executedSql.isEmpty(), "被取消后不应再执行任何 SQL");
        }
    }
}
