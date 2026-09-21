package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.entity.ParsedStatement;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "跨会话访问"缺陷的回归测试。
 *
 * <p>背景：取消请求（CancelRequest）与管理端的 KILL SESSION 都是<b>跨会话</b>操作——
 * 它们走的是新建连接，与目标会话不在同一个线程上。改造前它们直接去动目标会话的内部状态：</p>
 * <ul>
 *   <li>{@code CancelRequest} 遍历目标会话的 {@code parsedStatements}（原为普通 {@code HashMap}）；</li>
 *   <li>{@code AdminClientRequest} 直接读/改目标会话的 {@code executingPreparedStatement}，
 *       并直接调用其 {@code abortSession()}——既不摘除注册也不断开客户端，
 *       留下"僵尸会话"（物理连接已归还连接池，会话仍在册）。</li>
 * </ul>
 */
public class SessionConcurrencyTest {
    static int dbPort;
    static DBInstance dbInstance;

    @BeforeAll
    static void initAll() throws ServerException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("sess");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    private static Connection connect() throws Exception {
        Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/sess", "", "");
        conn.setAutoCommit(false);
        return conn;
    }

    /**
     * 目标会话的 {@code parsedStatements} 会被别的线程遍历（取消/终止），
     * 所以"遍历期间另一线程持续增删"必须安全 —— 这正是改造前用普通 HashMap 会抛
     * {@code ConcurrentModificationException} 的场景。
     */
    @Test
    void cancelRunningStatementsIsSafeUnderConcurrentMutation() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(-1);
        cfg.setData("sessunit");
        cfg.setData_dir(":memory:");
        cfg.setLog_level("INFO");
        DBInstance unitInstance = new DBInstance(cfg);
        DBSession session = new DBSession(unitInstance);

        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final AtomicInteger iterations = new AtomicInteger();

        Thread mutator = new Thread(() -> {
            try {
                int i = 0;
                while (!stop.get()) {
                    String portal = "Portal-p" + (i++ % 64);
                    ParsedStatement ps = new ParsedStatement();
                    ps.sql = "SELECT " + i;
                    session.saveParsedStatement(portal, ps);
                    session.clearParsedStatement(portal);
                }
            } catch (Throwable e) {
                failures.add(e);
            }
        });

        Thread canceller = new Thread(() -> {
            try {
                while (!stop.get()) {
                    session.cancelRunningStatements();
                    iterations.incrementAndGet();
                }
            } catch (Throwable e) {
                failures.add(e);
            }
        });

        mutator.start();
        canceller.start();
        Thread.sleep(1500);
        stop.set(true);
        mutator.join(5_000);
        canceller.join(5_000);

        assert iterations.get() > 1000 : "取消循环没有真正跑起来，用例无效";
        assert failures.isEmpty()
                : "并发遍历/修改 parsedStatements 时抛异常：" + failures.get(0);
    }

    /**
     * KILL SESSION 必须真正终止会话：从注册表摘除 + 断开客户端连接。
     * 改造前它只直接调用目标会话的 {@code abortSession()}，既不摘除也不断开，
     * 留下"僵尸会话"——物理连接已归还连接池，会话仍在册。
     */
    @Test
    void killSessionClosesTargetAndUnregisters() throws Exception {
        Connection conn = connect();
        int backendPid;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assert rs.next();
            backendPid = ((PGConnection) conn).getBackendPID();
        }
        int sessionId = backendPid;
        assert sessionId > 0;
        assert dbInstance.getSession(sessionId) != null : "会话未注册，用例无效";

        assert dbInstance.killSession(sessionId) : "killSession 应返回 true";

        // 关闭会在目标会话自己的线程上触发 channelInactive -> abortSession，因此这里轮询等待
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && dbInstance.getSession(sessionId) != null) {
            Thread.sleep(50);
        }
        assert dbInstance.getSession(sessionId) == null
                : "KILL 之后会话仍在 dbSessions 中（僵尸会话），sessionId=" + sessionId;

        // 客户端应当被真正断开
        boolean clientBroken = false;
        try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT 1").close();
        }
        catch (Exception e) {
            clientBroken = true;
        }
        finally {
            try { conn.close(); } catch (Exception ignored) { }
        }
        assert clientBroken : "KILL 之后客户端连接仍然可用，说明没有真正断开";

        // 服务端整体仍然健康
        try (Connection fresh = connect();
             Statement stmt = fresh.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 42")) {
            assert rs.next() && rs.getInt(1) == 42;
        }

        assert dbInstance.killSession(sessionId) == false : "对已不存在的会话应返回 false";
    }

    /**
     * 端到端走一遍 CancelRequest 路径（pgjdbc 的 cancelQuery 会在新连接上发送 CancelRequest），
     * 确认跨线程取消不会破坏服务端，且后续连接照常可用。
     */
    @Test
    void cancelRequestThroughProtocolKeepsServerHealthy() throws Exception {
        final int connections = 6;
        final List<Connection> conns = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < connections; i++) {
                Connection c = connect();
                try (Statement stmt = c.createStatement()) {
                    stmt.execute("SELECT " + i);
                }
                conns.add(c);
            }

            // 并发：一半连接持续发 CancelRequest，另一半持续做查询
            final CountDownLatch start = new CountDownLatch(1);
            final List<Throwable> failures = new CopyOnWriteArrayList<>();
            Thread[] workers = new Thread[connections];
            for (int i = 0; i < connections; i++) {
                final Connection c = conns.get(i);
                final boolean canceller = (i % 2 == 0);
                workers[i] = new Thread(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < 30; n++) {
                            if (canceller) {
                                ((PGConnection) c).cancelQuery();
                            } else {
                                try (Statement stmt = c.createStatement();
                                     ResultSet rs = stmt.executeQuery("SELECT count(*) FROM range(1000)")) {
                                    assert rs.next();
                                }
                            }
                        }
                    } catch (Throwable e) {
                        // 取消与查询竞争时客户端可能收到取消/连接错误，属预期；只记录致命错误
                        if (!(e instanceof java.sql.SQLException)) {
                            failures.add(e);
                        }
                    }
                });
            }
            for (Thread w : workers) { w.start(); }
            start.countDown();
            for (Thread w : workers) { w.join(60_000); }

            assert failures.isEmpty() : "并发取消过程中出现非 SQL 异常：" + failures.get(0);

            // 服务端仍然健康
            try (Connection fresh = connect();
                 Statement stmt = fresh.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 7")) {
                assert rs.next() && rs.getInt(1) == 7;
            }
        }
        finally {
            for (Connection c : conns) {
                try { c.close(); } catch (Exception ignored) { }
            }
        }
    }
}
