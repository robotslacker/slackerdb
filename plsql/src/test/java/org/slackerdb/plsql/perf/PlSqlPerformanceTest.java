package org.slackerdb.plsql.perf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slackerdb.plsql.PlSqlEngine;
import org.slackerdb.plsql.spi.DefaultJdbcHost;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L8：性能基线。
 *
 * <p>目的不是"跑得快"，而是**把当前量级记录下来**，任何数量级退化都能被 CI 拦住。
 * 阈值给得宽松（CI 机器差异大），实际耗时打印到日志里便于对照。</p>
 */
class PlSqlPerformanceTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:duckdb::memory:", "", "");
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table perf(i int, s text)");
        }
        connection.commit();
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    private void run(String script) {
        PlSqlEngine.execute(new DefaultJdbcHost(connection), script);
    }

    private long count(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    /** 赋值 + 条件求值循环：每轮一次"编译好的表达式求值"（含一次数据库往返）。 */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void assignmentLoopThroughput() throws SQLException {
        int iterations = 20_000;
        long start = System.nanoTime();
        run("""
                declare
                  x int;
                begin
                  let x = 0;
                  while :x < %d loop
                    let x = :x + 1;
                  end loop;
                  insert into perf values(:x, 'done');
                end;""".formatted(iterations));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(iterations, count("select i from perf"));
        long perIterationMicros = elapsedMillis * 1000 / iterations;
        System.out.printf("[PERF] assignment loop: %d 轮, %d ms, 约 %d µs/轮%n",
                iterations, elapsedMillis, perIterationMicros);
        // 宽松阈值：只要不是数量级退化
        assertTrue(elapsedMillis < 60_000, "赋值循环过慢：" + elapsedMillis + "ms");
    }

    /** 游标逐行处理：每行 FETCH + INSERT。 */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void cursorRowThroughput() throws SQLException {
        int rows = 5_000;
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table src(i int)");
            statement.execute("insert into src select * from generate_series(1, " + rows + ")");
        }
        connection.commit();

        long start = System.nanoTime();
        run("""
                declare
                  cursor c is select i from src order by i;
                  x int;
                begin
                  open c;
                  loop
                    fetch c into :x;
                    exit when c%notfound;
                    insert into perf values(:x, 'row');
                  end loop;
                  close c;
                end;""");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(rows, count("select count(*) from perf"));
        System.out.printf("[PERF] cursor rows: %d 行, %d ms, 约 %d µs/行%n",
                rows, elapsedMillis, elapsedMillis * 1000 / rows);
        assertTrue(elapsedMillis < 90_000, "游标处理过慢：" + elapsedMillis + "ms");
    }

    /** 深层嵌套 IF 与表达式：验证编译缓存/递归深度不会退化。 */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void nestedConditionAndExpressionDepth() throws SQLException {
        StringBuilder script = new StringBuilder("declare\n  x int;\nbegin\n  let x = 1;\n");
        for (int i = 0; i < 50; i++) {
            script.append("  if :x + ").append(i).append(" > 0 then\n");
        }
        script.append("    insert into perf values(1, 'deep');\n");
        for (int i = 0; i < 50; i++) {
            script.append("  end if;\n");
        }
        script.append("end;");

        long start = System.nanoTime();
        run(script.toString());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[PERF] 50 层嵌套 IF: %d ms%n", elapsedMillis);
        assertEquals(1, count("select count(*) from perf"));
    }

    /** 预算护栏本身的开销：纯赋值死循环应在预算内被快速拦下。 */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void statementBudgetOverhead() throws SQLException {
        long start = System.nanoTime();
        try {
            run("""
                    declare
                      x int;
                    begin
                      loop
                        let x = 1;
                      end loop;
                    end;""");
            throw new AssertionError("死循环必须被预算拦下");
        } catch (org.slackerdb.plsql.PlSqlException expected) {
            assertEquals("54000", expected.getSqlState());
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[PERF] 20 万语句预算触顶: %d ms%n", elapsedMillis);
        assertTrue(elapsedMillis < 60_000, "预算检查过慢：" + elapsedMillis + "ms");
    }
}
