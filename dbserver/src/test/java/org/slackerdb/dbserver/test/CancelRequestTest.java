package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.message.response.BackendKeyData;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CancelRequest 的统一验收测试（帧格式契约 + 端到端行为）。
 *
 * <p><b>被测缺陷</b>：改造前 dbserver 的解码器只把首 8 字节与 SSLRequest /
 * AdminClient 两种前导报文比较，取消码 80877102 没有任何分支。于是真实客户端在新连接上
 * 发出的 16 字节 CancelRequest 被回退成 StartupMessage 解析、因缺少 {@code database}
 * 参数而被拒绝并关闭连接 —— <b>取消功能整体失效，而被取消的查询会一直跑到底</b>。</p>
 *
 * <p>修复分两处：① 解码器新增"取消前导报文"分支（本文件的行为用例覆盖）；
 * ② 简单查询路径此前从不登记可取消句柄，需要一并登记（覆盖"简单查询可取消"用例）。</p>
 *
 * <p><b>判据设计</b>：不使用"服务端仍然健康"这种弱判据，而是：</p>
 * <ol>
 *   <li>用一条自然耗时约 11 秒的查询作为取消目标（尺寸经实测标定）；</li>
 *   <li>断言取消失败与成功在<b>时间上可区分</b>：取消后必须 5 秒内结束，自然跑完需约 11 秒；</li>
 *   <li>断言抛出的异常确实是"语句被取消"，而不是超时或连接断开。</li>
 * </ol>
 */
public class CancelRequestTest {

    /** 目标查询的自然耗时约 11 秒（本机实测），取消必须在 5 秒内完成 —— 两者可区分。 */
    private static final long CANCEL_BUDGET_MS = 5_000;

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("cancelproto");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");

        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    // ------------------------------------------------------------------ 公共辅助

    private static Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/cancelproto", "", "");
        conn.setAutoCommit(true);
        return conn;
    }

    /**
     * 一条"重到足以观察取消"的查询：range(n) 自连接的聚合，n 由实测标定。
     * 用 count(*) 包住以避免客户端侧传输成为瓶颈。
     */
    private static String longQuery() {
        return "SELECT count(*) FROM (SELECT sum(a.i * b.i) FROM range(200000) a(i), range(200000) b(i)) t";
    }

    private static int sessionCount() {
        return dbInstance.dbSessions.size();
    }

    private static boolean isCancelError(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("cancel") || lower.contains("interrupt");
    }

    /** 构造 16 字节取消前导报文：Int32(16) | Int32(80877102) | Int32(pid) | Int32(secret)。 */
    static byte[] cancelFrame(int processId, int secretKey) {
        byte[] frame = new byte[16];
        System.arraycopy(Utils.int32ToBytes(16), 0, frame, 0, 4);
        System.arraycopy(Utils.int32ToBytes(80877102), 0, frame, 4, 4);
        System.arraycopy(Utils.int32ToBytes(processId), 0, frame, 8, 4);
        System.arraycopy(Utils.int32ToBytes(secretKey), 0, frame, 12, 4);
        return frame;
    }

    /** 发送一个裸取消报文，并断言服务端不回任何字节。 */
    private static void sendRawCancelFrame(int processId) throws Exception {
        byte[] frame = cancelFrame(processId, BackendKeyData.FIXED_SECRET);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", dbPort), 5_000);
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(frame);
            socket.getOutputStream().flush();
            assert socket.getInputStream().read() == -1
                    : "取消报文（pid=" + processId + "）不应该收到任何响应字节";
        }
    }

    /** 等取消产生的临时会话全部结算完毕。 */
    private static void assertSessionsSettle(int baseline, String scene) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && sessionCount() > baseline) {
            Thread.sleep(50);
        }
        assert sessionCount() == baseline
                : scene + " 留下了残留会话：baseline=" + baseline + " current=" + sessionCount();
    }

    // ------------------------------------------------------------------ 帧格式契约

    /**
     * 取消前导报文的固定首 8 字节必须与协议一致（长度 16 + 取消码 80877102）。
     * 这个常量是解码器分支的判据，写错就会静默退回原来的 bug。
     */
    @Test
    void cancelRequestHeaderMatchesProtocol() {
        byte[] expectedHeader = new byte[8];
        System.arraycopy(Utils.int32ToBytes(16), 0, expectedHeader, 0, 4);
        System.arraycopy(Utils.int32ToBytes(80877102), 0, expectedHeader, 4, 4);

        org.slackerdb.dbserver.message.request.CancelRequest request =
                new org.slackerdb.dbserver.message.request.CancelRequest(null);

        assert Arrays.equals(expectedHeader,
                org.slackerdb.dbserver.message.request.CancelRequest.CancelRequestHeader)
                : "CancelRequestHeader 与协议字节不一致";
        assert Arrays.equals(expectedHeader, Arrays.copyOf(cancelFrame(1, 2), 8))
                : "CancelRequestHeader 与测试构造的真实报文首 8 字节不一致";

        // decodeTail 必须从尾 8 字节正确取出 pid 与 secret
        request.decodeTail(Arrays.copyOfRange(cancelFrame(100001, BackendKeyData.FIXED_SECRET), 8, 16));
        assert request.processId == 100001 : "processId 解析错误：" + request.processId;
        assert request.secretKey == BackendKeyData.FIXED_SECRET
                : "secretKey 解析错误：" + request.secretKey;
    }

    /** pid/secret 为负数边界值时也必须按 32 位有符号整数解析（不能抛异常）。 */
    @Test
    void decodeTailHandlesNegativeValues() {
        byte[] tail = new byte[8];
        System.arraycopy(Utils.int32ToBytes(-1), 0, tail, 0, 4);
        System.arraycopy(Utils.int32ToBytes(Integer.MIN_VALUE), 0, tail, 4, 4);

        org.slackerdb.dbserver.message.request.CancelRequest request =
                new org.slackerdb.dbserver.message.request.CancelRequest(null);
        request.decodeTail(tail);

        assert request.processId == -1;
        assert request.secretKey == Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ 端到端行为

    /**
     * 核心用例：目标查询必须真的被 CancelRequest 中断（简单查询路径 / 消息 'Q'）。
     *
     * <p>取消用 {@link Statement#cancel()} 驱动：pgjdbc 会在<b>另一条线程</b>上新建连接、
     * 发送 16 字节取消前导报文，因此走的正是修复后的解码分支。</p>
     *
     * <p>注意不要用另一个连接的 {@code PGConnection.cancelQuery()}：按接口语义它只取消
     * "本连接"的查询（驱动内部用的就是本连接的 pid/secret），跨连接取消在客户端侧不成立。</p>
     */
    @Test
    void cancelRequestInterruptsRunningQuery() throws Exception {
        int baseline = sessionCount();

        try (Connection target = connect();
             Statement targetStmt = target.createStatement()) {

            AtomicReference<Throwable> failure = new AtomicReference<>();
            long startedAt = System.currentTimeMillis();

            Thread queryThread = new Thread(() -> {
                try (ResultSet rs = targetStmt.executeQuery(longQuery())) {
                    rs.next();
                }
                catch (Throwable t) {
                    failure.set(t);
                }
            }, "cancel-target-query");
            queryThread.start();

            // 等查询真正跑起来再取消，否则取消可能落在"还没开始"的空窗期
            Thread.sleep(1_500);
            assert queryThread.isAlive()
                    : "目标查询在 1.5 秒内就结束了，用例无效（查询不够重，无法区分取消是否生效）";

            targetStmt.cancel();

            queryThread.join(CANCEL_BUDGET_MS);
            long elapsed = System.currentTimeMillis() - startedAt;

            assert !queryThread.isAlive()
                    : "取消无效：查询在 " + CANCEL_BUDGET_MS + "ms 后仍在执行（自然耗时约 11s，"
                    + "说明 CancelRequest 没有真正中断语句）";

            Throwable thrown = failure.get();
            assert thrown != null
                    : "查询在 " + elapsed + "ms 内结束但没有抛异常，无法判定是取消生效还是提前返回";
            assert thrown instanceof SQLException
                    : "取消后抛出的不是 SQLException：" + thrown;
            assert isCancelError((SQLException) thrown)
                    : "异常看起来不是[语句被取消]：" + thrown.getMessage();

            // 取消不等于断连：目标连接必须仍然可用
            try (Statement stmt = target.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                assert rs.next() && rs.getInt(1) == 1 : "取消之后目标连接不可用";
            }
        }

        assertSessionsSettle(baseline, "简单查询取消");
    }

    /**
     * 扩展协议路径（Parse/Bind/Execute/portal 分批）同样必须可取消 ——
     * 覆盖 ExecuteRequest 侧登记的执行句柄。
     */
    @Test
    void cancelRequestInterruptsExtendedProtocolQuery() throws Exception {
        int baseline = sessionCount();

        try (Connection target = connect();
             java.sql.PreparedStatement prepared = target.prepareStatement(longQuery())) {

            // 用 fetchSize 走 portal 分批路径
            prepared.setFetchSize(1000);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread queryThread = new Thread(() -> {
                try (ResultSet rs = prepared.executeQuery()) {
                    while (rs.next()) {
                        // 只要消费到内容即可
                    }
                }
                catch (Throwable t) {
                    failure.set(t);
                }
            }, "cancel-target-extended");
            queryThread.start();

            Thread.sleep(1_500);
            assert queryThread.isAlive() : "扩展协议目标查询结束得太快，用例无效";

            prepared.cancel();

            queryThread.join(CANCEL_BUDGET_MS);
            assert !queryThread.isAlive()
                    : "扩展协议路径取消无效：查询在 " + CANCEL_BUDGET_MS + "ms 后仍在执行";

            Throwable thrown = failure.get();
            assert thrown != null : "扩展协议查询提前结束但没有抛异常";
            assert thrown instanceof SQLException : "取消后抛出的不是 SQLException：" + thrown;
            assert isCancelError((SQLException) thrown)
                    : "异常看起来不是[语句被取消]：" + thrown.getMessage();
        }

        assertSessionsSettle(baseline, "扩展协议取消");
    }

    /**
     * 同一个会话上"取消 → 继续用"的循环：确认取消不会破坏会话，
     * 且执行句柄被正确注销（不会累积、不会误伤后续语句）。
     */
    @Test
    void sessionRemainsUsableAfterRepeatedCancels() throws Exception {
        int baseline = sessionCount();

        try (Connection target = connect()) {
            int pid = ((PGConnection) target).getBackendPID();
            DBSession session = dbInstance.getSession(pid);
            assert session != null : "目标会话未注册，用例无效";

            for (int round = 0; round < 2; round++) {
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Statement stmt = target.createStatement();
                Thread queryThread = new Thread(() -> {
                    try (ResultSet rs = stmt.executeQuery(longQuery())) {
                        rs.next();
                    }
                    catch (Throwable t) {
                        failure.set(t);
                    }
                }, "cancel-repeat-" + round);
                queryThread.start();

                Thread.sleep(1_000);
                assert queryThread.isAlive() : "第 " + round + " 轮查询结束太快，用例无效";

                stmt.cancel();
                queryThread.join(CANCEL_BUDGET_MS);
                assert !queryThread.isAlive()
                        : "第 " + round + " 轮取消无效，查询仍在执行";
                assert failure.get() instanceof SQLException
                        : "第 " + round + " 轮取消后未抛出 SQLException：" + failure.get();

                // 取消结束后句柄必须被摘掉
                long deadline = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < deadline && !session.runningStatements.isEmpty()) {
                    Thread.sleep(50);
                }
                assert session.runningStatements.isEmpty()
                        : "第 " + round + " 轮取消后仍有残留执行句柄："
                                + session.runningStatements.size();

                // 会话仍然可用
                try (Statement verify = target.createStatement();
                     ResultSet rs = verify.executeQuery("SELECT 1")) {
                    assert rs.next() && rs.getInt(1) == 1 : "第 " + round + " 轮取消后会话不可用";
                }
            }
        }

        assertSessionsSettle(baseline, "重复取消");
    }

    /**
     * 裸 socket 验证协议契约：服务端收到取消报文后
     * <b>不发送任何响应字节</b>，并直接关闭连接（PG 规范要求）。
     *
     * <p>同时验证"跨连接取消"这一服务器能力：客户端 A 执行查询，由客户端 B
     * 手工构造携带 A 的 pid/secret 的取消报文（pgjdbc 的 {@code PgConnection.cancelQuery()}
     * 无法表达这种跨连接语义，所以这里直接走线协议）。</p>
     */
    @Test
    void rawCancelFrameInterruptsTargetAndGetsNoResponse() throws Exception {
        int baseline = sessionCount();

        try (Connection target = connect();
             Statement targetStmt = target.createStatement()) {
            int targetPid = ((PGConnection) target).getBackendPID();
            assert dbInstance.getSession(targetPid) != null : "目标会话未注册，用例无效";

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread queryThread = new Thread(() -> {
                try (ResultSet rs = targetStmt.executeQuery(longQuery())) {
                    rs.next();
                }
                catch (Throwable t) {
                    failure.set(t);
                }
            }, "raw-cancel-target");
            queryThread.start();

            Thread.sleep(1_500);
            assert queryThread.isAlive() : "目标查询结束太快，用例无效";

            byte[] frame = cancelFrame(targetPid, BackendKeyData.FIXED_SECRET);
            assert frame.length == 16;

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", dbPort), 5_000);
                socket.setSoTimeout(10_000);
                OutputStream out = socket.getOutputStream();
                out.write(frame);
                out.flush();

                // 服务端不应回任何字节：读到 -1（EOF）即为正确行为。
                // 修复前这里会读到 0x45（'E'，ErrorResponse 的首字节）。
                int first = socket.getInputStream().read();
                assert first == -1
                        : "取消后服务端返回了数据（应为空响应 + 关闭连接），首字节=" + first;
            }

            queryThread.join(CANCEL_BUDGET_MS);
            assert !queryThread.isAlive()
                    : "跨连接取消无效：查询在 " + CANCEL_BUDGET_MS + "ms 后仍在执行";
            assert failure.get() instanceof SQLException
                    : "跨连接取消后未抛出 SQLException：" + failure.get();

            // 取消之后目标会话仍应可用（取消不是终止）
            try (ResultSet rs = targetStmt.executeQuery("SELECT 5")) {
                assert rs.next() && rs.getInt(1) == 5 : "取消之后目标连接不可用";
            }
        }

        assertSessionsSettle(baseline, "裸取消报文");
    }

    /**
     * 边界：对空闲会话（存在但没有正在执行的语句）发取消，必须无害 ——
     * 不报错、目标连接继续可用、服务端无残留。
     */
    @Test
    void cancelOnIdleSessionIsHarmless() throws Exception {
        int baseline = sessionCount();

        try (Connection idle = connect()) {
            int idlePid = ((PGConnection) idle).getBackendPID();

            // 连续多次取消同一个空闲会话
            for (int i = 0; i < 3; i++) {
                sendRawCancelFrame(idlePid);
            }

            try (Statement stmt = idle.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 3")) {
                assert rs.next() && rs.getInt(1) == 3 : "对空闲会话取消之后连接不可用";
            }
        }

        assertSessionsSettle(baseline, "空闲会话取消");
    }

    /**
     * 边界：取消一个不存在的 pid 必须被静默忽略，不影响服务端与其他会话。
     */
    @Test
    void cancelNonexistentProcessIdIsIgnored() throws Exception {
        int baseline = sessionCount();

        try (Connection alive = connect()) {
            sendRawCancelFrame(999_999);

            // 其他会话必须不受影响
            try (Statement stmt = alive.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 11")) {
                assert rs.next() && rs.getInt(1) == 11 : "取消不存在的 pid 影响了其他会话";
            }
        }

        assertSessionsSettle(baseline, "取消不存在的 pid");
    }
}
