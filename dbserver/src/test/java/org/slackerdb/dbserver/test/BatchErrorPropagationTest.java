package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 回归：批处理里出现坏值时，客户端必须拿到**真实的 SQL 错误**，而不是"连接被断开"。
 *
 * <p><b>缺陷现象（2026-10-22 定位）</b>：长跑的服务端上，{@code PreparedStatement.executeBatch()}
 * 里只要有一条参数转换失败（例如把 {@code "bad"} 插进 BIGINT 列），客户端收到的是
 * {@code BatchUpdateException: ... An I/O error occurred while sending to the backend}
 * ——没有任何 SQLSTATE、没有行号；服务端日志里也什么都没有（连接在
 * {@code exceptionCaught} 里以 trace 级别静默关闭）。重启服务端后同一段代码立刻恢复正常。</p>
 *
 * <p><b>根因</b>：失败批处理会在连接池的物理连接上留下未关闭的语句；池归还时把这条连接
 * 退役（物理关闭），于是长跑实例不断"建连接 / 销毁连接"。累积到一定程度后，某条被复用
 * 的连接上还带着未消费的 pending result，{@code Describe}/{@code Prepare} 直接失败，
 * 异常从协议状态机里逃出去，连接被静默关闭。</p>
 *
 * <p><b>修复</b>：① 归还时若只是"语句没关"，先关语句再用 {@code SELECT 1} 探活，探活通过就
 * 复用（{@link org.slackerdb.dbserver.server.DBDataSourcePool#recoverConnectionAfterResidue}），
 * 探活失败才退役；② 连接级异常在关闭前先回一个 {@code XX000} 错误，客户端不会再只看到 EOF。</p>
 *
 * <p>本用例断言"错误内容正确"而不是"连接被退役"：前者才是用户可见的契约。</p>
 */
class BatchErrorPropagationTest {

    private static DBInstance dbInstance;
    private static int port;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("batcherr");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("WARN");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        port = cfg.getPort();
    }

    @AfterAll
    static void tearDownAll() {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    private static String url() {
        return "jdbc:postgresql://127.0.0.1:" + port + "/batcherr";
    }

    @Test
    @DisplayName("10 failing batches in a row: every one reports a Conversion Error and the connection is never closed silently")
    void failingBatchKeepsReportingRealError() throws Exception {
        try (Connection setup = DriverManager.getConnection(url(), "", "");
             Statement statement = setup.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS batch_err");
            statement.execute("CREATE TABLE batch_err (id BIGINT, note VARCHAR)");
        }

        for (int i = 1; i <= 10; i++) {
            try (Connection connection = DriverManager.getConnection(url(), "", "");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO batch_err (id, note) VALUES (?, ?)")) {
                insert.setObject(1, (long) i);
                insert.setObject(2, "ok");
                insert.addBatch();
                insert.setObject(1, "not-a-number");
                insert.setObject(2, "bad");
                insert.addBatch();

                try {
                    insert.executeBatch();
                    fail("round " + i + ": the bad-value batch unexpectedly succeeded");
                } catch (SQLException expected) {
                    String message = expected.getMessage() == null ? "" : expected.getMessage();
                    assertFalse(message.contains("I/O error"),
                            "round " + i + ": the client got an I/O error (connection closed silently), actual: " + message);
                    assertTrue(message.contains("Conversion Error") || message.contains("could not convert")
                                    || message.contains("Could not convert"),
                            "round " + i + ": expected a concrete conversion error, actual: " + message);
                }
            }
        }

        // 服务端仍然可用（这一步才是关键：改造前这里往往已经"连接被断开"）
        try (Connection connection = DriverManager.getConnection(url(), "", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM batch_err")) {
            assertTrue(rs.next());
            // 注意：autocommit 下 JDBC 批量**不是原子的**（每条各自成事务），
            // 所以这里不断言行数；要"要么全进要么不进"必须显式开事务（控制台的导入器就是这么做的）。
            assertEquals(10, rs.getInt(1), "the good row of each failing batch was committed on its own");
        }
    }
}
