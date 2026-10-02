package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L7：PL/SQL 取消的协议级集成测试。
 *
 * <p>回归的历史缺陷：PL/SQL 块内部的语句既不登记到 {@code runningStatements}，
 * 引擎也没有取消检查点，因此长循环一旦跑起来，{@code CancelRequest} /
 * {@code KILL SESSION} 完全无法中断（只能等它自己结束）。</p>
 *
 * <p>P2 起：内部语句经 {@code PlSqlHostImpl} 登记，且宿主用会话级取消标志
 * （{@code DBSession.cancelRequested}）在每条语句前/每次循环回边轮询，
 * 取消后应快速返回 SQLSTATE {@code 57014}。</p>
 */
public class PlSqlCancelTest {

    static final int dbPort = 4312;
    static DBInstance dbInstance;

    /** 一个"跑很久"的块：游标 + 循环逐行插入（每轮都会经过取消检查点）。 */
    static final String LONG_RUNNING_BLOCK = """
            DO $$
            declare
              cursor c is select unnest(generate_series(1, 5000000));
              x bigint;
            begin
              create or replace table cancel_target(id bigint);
              open c;
              loop
                fetch c into :x;
                exit when c%notfound;
                insert into cancel_target values(:x);
              end loop;
              close c;
            end;
            $$;
            """;

    @BeforeAll
    static void initAll() throws ServerException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(dbPort);
        serverConfiguration.setData("mem");
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem", "", "");
        connection.setAutoCommit(false);
        return connection;
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void cancelInterruptsLongRunningBlock() throws Exception {
        try (Connection connection = connect()) {
            Statement statement = connection.createStatement();

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    statement.execute(LONG_RUNNING_BLOCK);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "plsql-cancel-worker");
            worker.start();

            // 等它真的跑起来（否则 cancel 可能落在 Parse/Bind 阶段）
            Thread.sleep(2000);
            assertTrue(worker.isAlive(), "长块应当仍在执行（否则用例失去意义）");

            long startNanos = System.nanoTime();
            statement.cancel();
            worker.join(30_000);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            assertFalse(worker.isAlive(), "取消后块仍在执行，取消未生效");
            assertTrue(elapsedMillis < 15_000, "取消响应过慢：" + elapsedMillis + "ms");

            Throwable error = failure.get();
            assertNotNull(error, "取消应当让客户端收到错误（而不是静默成功）");
            assertTrue(error instanceof SQLException, "期望 SQLException，实际：" + error);
            assertEquals("57014", ((SQLException) error).getSQLState(),
                    "取消的 SQLSTATE 应为 57014，实际：" + error.getMessage());

            statement.close();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void sessionIsStillUsableAfterCancel() throws Exception {
        try (Connection connection = connect()) {
            Statement statement = connection.createStatement();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    statement.execute(LONG_RUNNING_BLOCK);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "plsql-cancel-worker-2");
            worker.start();
            Thread.sleep(2000);
            statement.cancel();
            worker.join(30_000);
            assertFalse(worker.isAlive(), "取消后块仍在执行");
            assertNotNull(failure.get(), "取消应当产生错误");

            // 取消后会话必须还能用：回滚（失败语句所在的事务）后继续执行普通 SQL
            try (Statement recover = connection.createStatement()) {
                try {
                    recover.execute("rollback");
                } catch (SQLException ignored) {
                    // 没有活跃事务时 DuckDB 会报 "cannot rollback - no transaction is active"，
                    // 这不影响"会话仍可用"的结论
                }
                try (ResultSet rs = recover.executeQuery("select 42")) {
                    assertTrue(rs.next());
                    assertEquals(42, rs.getInt(1));
                }
            }
        }
    }
}
