package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端被外部强杀（连接被硬断）后，服务端必须<b>立刻中断它正在执行的语句</b>，
 * 而不是让那条语句继续跑完。
 *
 * <p><b>为什么需要这个用例</b>：清理链路（{@code channelInactive → abortSession → 回滚 + 归还连接}）
 * 跑在业务线程上；客户端被强杀时那条业务线程往往正卡在一条长语句里，于是断连事件被排在语句后面。
 * 结果是会话、连接池槽位、未提交事务全部悬置（语句越长拖得越久），这类会话足够多时新客户端连启动握手
 * 都没人处理（实测 {@code Read timed out}）。修复见
 * {@code PostgresServer#cancelRunningStatementOfDeadSession}：连接一断就从 <b>I/O 线程</b>
 * 对该会话调用 {@code cancelRunningStatements()}（与 CancelRequest / KILL SESSION 同一条路径）。</p>
 *
 * <p><b>判据</b>：目标语句自然耗时约 11 秒，这里要求"硬断 → 会话与连接全部释放"在
 * {@link #RELEASE_BUDGET_MS}（5 秒）内完成 —— 两者可区分，且不受机器快慢的偶然影响。
 * 修复前：要等语句跑完（≥11 秒，必然超预算）；修复后：取消生效，实测约 0.3 秒。
 * 每个用例在动手前都先断言"目标语句确实正在执行"（{@link DBSession#executingSQL}），
 * 以免将来 DuckDB 把这条语句优化成瞬回后，用例退化成"永远通过"。</p>
 */
public class DisconnectCancelsRunningStatementTest {

    /** 目标语句自然耗时约 11 秒（与 CancelRequestTest 同一标定），释放预算取 5 秒。 */
    private static final long RELEASE_BUDGET_MS = 5_000;

    private static DBInstance dbInstance;
    private static int dbPort;
    private static final String DB = "disconnectcancel";

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData(DB);
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");

        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();
    }

    @AfterAll
    static void tearDownAll() {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/" + DB, "", "");
    }

    /** 重到足以观察取消、但内存占用很低的语句：流式自连接聚合。 */
    private static String heavyQuery() {
        return "SELECT count(*) FROM (SELECT sum(a.i * b.i) FROM range(200000) a(i), range(200000) b(i)) t";
    }

    /**
     * 一条同样够长、但会真的写表且占用很小的语句（用于"表还能不能写"的端到端用例）：
     * 5 千万行 × 每行一个 md5 字符，实测约 12 秒、内存几百 MB。
     */
    private static String heavyWriteQuery(String table) {
        return "INSERT INTO " + table
                + " SELECT range AS i, substr(md5(range::VARCHAR),1,1) FROM range(0, 50000000)";
    }

    private static int liveSessionCount() {
        return dbInstance.dbSessions.size();
    }

    private static int usedConnections() {
        return dbInstance.dbDataSourcePool.getUsedConnectionPoolSize();
    }

    /**
     * 等上一个用例/上一条连接彻底结算干净（会话 0、池占用 0）再取基线。
     *
     * <p>必须等：jdbc 客户端 {@code close()} 只是发出 Terminate 就返回，服务端摘除会话是异步的。
     * 不等就取基线会拿到"上一个会话还没消失"的计数，之后 "会话总数大于基线" 这类判据会永久不成立，
     * 用例变成假失败（本文件第一版就踩了这个坑）。</p>
     */
    private static void awaitQuiescent() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (liveSessionCount() == 0 && usedConnections() == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("服务端没有回到空闲状态：liveSessions=" + liveSessionCount()
                + ", usedConnections=" + usedConnections());
    }

    /**
     * 等目标语句真正进入执行期，并返回它的会话（断言失败即用例前提不成立）。
     *
     * <p>判据只看"这条会话自己"：会话存在 + {@code executingSQL} 已是目标语句 + 池里确有占用；
     * 刻意不用"会话总数大于基线"——那会受上一个连接的异步收尾影响。</p>
     */
    private static DBSession awaitStatementInFlight(PgWireClient victim, String expectedSqlFragment)
            throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        DBSession session = null;
        while (System.currentTimeMillis() < deadline) {
            session = dbInstance.getSession(victim.backendPid());
            if (session != null
                    && session.executingSQL != null
                    && session.executingSQL.contains(expectedSqlFragment)
                    && usedConnections() > 0) {
                return session;
            }
            Thread.sleep(50);
        }
        assertNotNull(session, "目标会话没有建立，用例无效");
        throw new AssertionError("目标语句在 10 秒内没有进入执行期，用例无效；当前 executingSQL=["
                + session.executingSQL + "]");
    }

    /** 轮询等待"会话数回到基线、且池占用不超过基线"，返回耗时毫秒；超时返回 -1。 */
    private static long waitUntilReleased(long timeoutMs, int baselineSessions, int baselineUsed) {
        long begin = System.currentTimeMillis();
        while (System.currentTimeMillis() - begin < timeoutMs) {
            if (liveSessionCount() <= baselineSessions && usedConnections() <= baselineUsed) {
                return System.currentTimeMillis() - begin;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 用例

    /**
     * 硬断正在跑长语句的连接：会话与池连接必须在预算内释放（= 语句被真的中断了）。
     */
    @Test
    void hardDisconnectReleasesSessionQuickly() throws Exception {
        awaitQuiescent();
        int baselineSessions = liveSessionCount();
        int baselineUsed = usedConnections();

        PgWireClient victim = new PgWireClient("127.0.0.1", dbPort, DB, 10_000);
        long disconnectAt;
        try {
            victim.sendQuery(heavyQuery());
            DBSession inFlight = awaitStatementInFlight(victim, "range(200000)");
            assertTrue(inFlight.executingFunction != null && !inFlight.executingFunction.isEmpty(),
                    "服务端应记录正在执行的请求类型");

            disconnectAt = System.currentTimeMillis();
            // 关键动作：不发 Terminate，直接 RST 断开（等价于客户端被 taskkill /F）
            victim.abortConnection();
        } finally {
            victim.abortConnection();
        }

        long releasedMs = waitUntilReleased(RELEASE_BUDGET_MS, baselineSessions, baselineUsed);
        assertTrue(releasedMs >= 0,
                "硬断后会话/连接在 " + RELEASE_BUDGET_MS + "ms 内没有释放"
                        + "（说明断连没有中断正在执行的语句，清理被排在语句后面："
                        + "目标语句自然耗时约 11s），当前 liveSessions=" + liveSessionCount()
                        + ", usedConnections=" + usedConnections());
        System.out.println("TEST:: hard disconnect released session+connection in "
                + (System.currentTimeMillis() - disconnectAt) + " ms");
    }

    /**
     * 用户场景端到端：未提交的写入被强杀后，
     * (1) 表仍然可以被新客户端 INSERT 与 COPY（append）；
     * (2) 被杀会话的未提交数据不落库。
     */
    @Test
    void tableRemainsWritableAndUncommittedDataIsDiscarded() throws Exception {
        try (Connection setup = connect();
             Statement st = setup.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE disc_kill(id BIGINT, payload VARCHAR)");
        }
        awaitQuiescent();
        int baselineSessions = liveSessionCount();
        int baselineUsed = usedConnections();

        // 步骤 1~2：客户端开事务 + 大量写入、不提交
        PgWireClient victim = new PgWireClient("127.0.0.1", dbPort, DB, 10_000);
        try {
            victim.sendQuery("BEGIN");
            victim.readUntilReadyForQuery();
            victim.sendQuery(heavyWriteQuery("disc_kill"));
            awaitStatementInFlight(victim, "INSERT INTO disc_kill");

            // 步骤 3：外部强杀（硬断，没有 COMMIT/ROLLBACK/Terminate）
            victim.abortConnection();
        } finally {
            victim.abortConnection();
        }

        assertTrue(waitUntilReleased(RELEASE_BUDGET_MS, baselineSessions, baselineUsed) >= 0,
                "硬断后被杀会话没有在预算内释放（未提交事务仍悬着）");

        // 步骤 4：新客户端必须还能写同一张表（INSERT + COPY）
        try (Connection fresh = connect()) {
            try (Statement st = fresh.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM disc_kill")) {
                rs.next();
                assertEquals(0L, rs.getLong(1), "被杀会话未提交的 INSERT 不应该落库");
            }

            try (PreparedStatement ps = fresh.prepareStatement(
                    "INSERT INTO disc_kill (id, payload) VALUES (?, ?)")) {
                ps.setLong(1, -1L);
                ps.setString(2, "fresh-insert");
                assertEquals(1, ps.executeUpdate(), "新客户端的 INSERT 应该成功");
            }

            CopyManager copyManager = new CopyManager((BaseConnection) fresh);
            long copied = copyManager.copyIn("COPY disc_kill FROM STDIN (FORMAT CSV)",
                    new StringReader("-2,fresh-copy-1\n-3,fresh-copy-2\n"));
            assertEquals(2L, copied, "新客户端的 COPY(append) 应该成功");

            try (Statement st = fresh.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM disc_kill")) {
                rs.next();
                assertEquals(3L, rs.getLong(1), "表里应该只有新客户端刚写入的 3 行");
            }
        }
    }

    /**
     * 反向保护：正常收尾（Terminate 后关闭）不能被新逻辑误伤 —— 清理照旧，无残留。
     */
    @Test
    void gracefulDisconnectStillCleansUp() throws Exception {
        awaitQuiescent();
        int baselineSessions = liveSessionCount();
        int baselineUsed = usedConnections();

        PgWireClient client = new PgWireClient("127.0.0.1", dbPort, DB, 10_000);
        client.sendQuery("SELECT 1");
        client.readUntilReadyForQuery();
        client.sendTerminate();
        client.close();

        assertTrue(waitUntilReleased(RELEASE_BUDGET_MS, baselineSessions, baselineUsed) >= 0,
                "礼貌断开的会话没有被清理干净：liveSessions=" + liveSessionCount()
                        + ", usedConnections=" + usedConnections());
    }

    /**
     * 断连取消不能影响别的会话：硬断一个长语句会话后，另一个常规会话照常可用。
     */
    @Test
    void otherSessionsAreUnaffected() throws Exception {
        awaitQuiescent();
        try (Connection steady = connect()) {
            // 基线在 steady 占住一条连接之后再取，否则下面的 used<=baseline 永远不成立
            int baselineSessions = liveSessionCount();
            int baselineUsed = usedConnections();

            PgWireClient victim = new PgWireClient("127.0.0.1", dbPort, DB, 10_000);
            victim.sendQuery(heavyQuery());
            awaitStatementInFlight(victim, "range(200000)");
            victim.abortConnection();

            assertTrue(waitUntilReleased(RELEASE_BUDGET_MS, baselineSessions, baselineUsed) >= 0,
                    "被杀会话没有在预算内释放");

            try (Statement st = steady.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 42")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1), "旁观会话应完全不受影响");
            }
        }
    }
}

