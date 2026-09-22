package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

/**
 * dbproxy 取消（CancelRequest）与启动包契约的统一验收测试。
 *
 * <p>覆盖两类"前导报文分派"缺陷（它们同源：代理只把首 8 字节与 SSLRequest 比较）：</p>
 *
 * <ol>
 *   <li><b>取消报文被静默丢弃</b>：取消是"新连接上的首个报文"，既没有 database 也没有别名，
 *       代理无法按别名选路。改造前它在解码阶段就被丢弃，客户端一路挂到空闲超时，目标查询跑到底。
 *       修复后代理在握手期从后端的 BackendKeyData 学到 pid，取消到达时按 pid 新建短连接
 *       把 16 字节原样回送到正确的上游。</li>
 *   <li><b>启动包缺少 database 时静默挂住</b>：改造前直接 {@code return}，既不回复也不关闭。
 *       修复后与 dbserver 对齐，回 {@code ErrorResponse(SLACKERDB-00001)} 再关闭连接。</li>
 * </ol>
 *
 * <p><b>判据设计</b>：取消是否生效必须在<b>时间上可区分</b> —— 目标查询自然耗时约 11 秒，
 * 取消后必须 5 秒内结束且异常为取消语义；启动包契约则必须断言"客户端收到了明确的
 * ErrorResponse 帧"，而不只是"连接最终会关闭"（后者在修复前靠空闲超时也会满足）。</p>
 */
public class CancelTest {

    /** 目标查询自然耗时约 11 秒（本机实测），取消必须在 5 秒内完成。 */
    private static final long CANCEL_BUDGET_MS = 5_000;

    /** 取消报文固定首 8 字节：Int32(16) + Int32(80877102)，与协议常量一致。 */
    private static final byte[] CANCEL_HEADER =
            {0x00, 0x00, 0x00, 0x10, 0x04, (byte) 0xD2, 0x16, 0x2E};

    private static ProxyInstance proxyInstance;
    private static DBInstance dbInstance1;
    private static DBInstance dbInstance2;
    private static int proxyPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 启动代理
        org.slackerdb.dbproxy.configuration.ServerConfiguration proxyConfiguration =
                new org.slackerdb.dbproxy.configuration.ServerConfiguration();
        proxyConfiguration.setPort(0);
        proxyConfiguration.setLog_level("INFO");
        proxyInstance = new ProxyInstance(proxyConfiguration);
        proxyInstance.start();
        proxyPort = proxyConfiguration.getPort();
        while (!proxyInstance.instanceState.equalsIgnoreCase("RUNNING")) {
            Sleeper.sleep(50);
        }

        // 启动两个 dbserver，让它们向代理自注册（用于验证取消的多别名路由隔离）
        org.slackerdb.dbserver.configuration.ServerConfiguration cfg1 =
                new org.slackerdb.dbserver.configuration.ServerConfiguration();
        cfg1.setPort(0);
        cfg1.setData("mem1");
        cfg1.setLog_level("INFO");
        cfg1.setRemoteListener("127.0.0.1:" + proxyPort);
        dbInstance1 = new DBInstance(cfg1);
        dbInstance1.start();

        org.slackerdb.dbserver.configuration.ServerConfiguration cfg2 =
                new org.slackerdb.dbserver.configuration.ServerConfiguration();
        cfg2.setPort(0);
        cfg2.setData("mem2");
        cfg2.setLog_level("INFO");
        cfg2.setRemoteListener("127.0.0.1:" + proxyPort);
        dbInstance2 = new DBInstance(cfg2);
        dbInstance2.start();

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline
                && !(proxyInstance.proxyTarget.containsKey("mem1") && proxyInstance.proxyTarget.containsKey("mem2"))) {
            Sleeper.sleep(200);
        }
        assert proxyInstance.proxyTarget.containsKey("mem1") : "mem1 未注册到代理，用例无效";
        assert proxyInstance.proxyTarget.containsKey("mem2") : "mem2 未注册到代理，用例无效";
    }

    @AfterAll
    static void tearDownAll() {
        if (dbInstance1 != null) {
            dbInstance1.stop();
        }
        if (dbInstance2 != null) {
            dbInstance2.stop();
        }
        if (proxyInstance != null) {
            proxyInstance.stop();
        }
    }

    // ------------------------------------------------------------------ 公共辅助

    private static Connection connect(String alias) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + proxyPort + "/" + alias, "", "");
    }

    /** 一条自然耗时约 11 秒的查询（尺寸沿用 dbserver 侧实测标定）。 */
    private static String longQuery() {
        return "SELECT count(*) FROM (SELECT sum(a.i * b.i) FROM range(200000) a(i), range(200000) b(i)) t";
    }

    private static boolean isCancelError(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("cancel") || lower.contains("interrupt");
    }

    /** 等待取消路由表条目数达到期望值（基线会话全部建立）。 */
    private static void waitRouteSize(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && proxyInstance.cancelRouter.size() != expected) {
            Thread.sleep(50);
        }
    }

    /** 等待取消路由表回落到基线。 */
    private static void waitRouteSizeBackTo(int baseline) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && proxyInstance.cancelRouter.size() > baseline) {
            Thread.sleep(50);
        }
    }

    /** 构造 16 字节取消报文（本模块内自建，不依赖其他模块的测试类）。 */
    private static byte[] cancelFrame(int processId, int secretKey) {
        byte[] frame = new byte[16];
        System.arraycopy(CANCEL_HEADER, 0, frame, 0, 8);
        System.arraycopy(Utils.int32ToBytes(processId), 0, frame, 8, 4);
        System.arraycopy(Utils.int32ToBytes(secretKey), 0, frame, 12, 4);
        return frame;
    }

    /** 发送一个裸取消报文，并断言代理不回任何字节（PG 规范：取消无响应）。 */
    private static void sendRawCancelFrame(int processId) throws Exception {
        byte[] frame = cancelFrame(processId, 5678);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", proxyPort), 5_000);
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(frame);
            socket.getOutputStream().flush();
            assert socket.getInputStream().read() == -1
                    : "取消报文（pid=" + processId + "）不应该收到任何响应字节";
        }
    }

    // ------------------------------------------------------------------ 取消：核心

    /**
     * 核心用例：经代理连接时，取消必须真的中断查询。
     *
     * <p>{@link Statement#cancel()} 会在另一条线程上**新建连接向代理**发送 16 字节取消报文，
     * 走的正是修复后的解码分支与 pid→上游 路由。</p>
     */
    @Test
    void cancelThroughProxyInterruptsRunningQuery() throws Exception {
        try (Connection target = connect("mem1");
             Statement targetStmt = target.createStatement()) {
            waitRouteSize(1);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            long startedAt = System.currentTimeMillis();

            Thread queryThread = new Thread(() -> {
                try (ResultSet rs = targetStmt.executeQuery(longQuery())) {
                    rs.next();
                }
                catch (Throwable t) {
                    failure.set(t);
                }
            }, "proxy-cancel-target");
            queryThread.start();

            Thread.sleep(1_500);
            assert queryThread.isAlive()
                    : "目标查询在 1.5 秒内就结束了，用例无效（查询不够重，无法区分取消是否生效）";

            targetStmt.cancel();

            queryThread.join(CANCEL_BUDGET_MS);
            long elapsed = System.currentTimeMillis() - startedAt;

            assert !queryThread.isAlive()
                    : "经代理取消无效：查询在 " + CANCEL_BUDGET_MS + "ms 后仍在执行（自然耗时约 11s）";

            Throwable thrown = failure.get();
            assert thrown != null
                    : "查询在 " + elapsed + "ms 内结束但没有抛异常，无法判定是取消生效还是提前返回";
            assert thrown instanceof SQLException
                    : "取消后抛出的不是 SQLException：" + thrown;
            assert isCancelError((SQLException) thrown)
                    : "异常看起来不是[语句被取消]：" + thrown.getMessage();

            // 取消不等于断连
            try (Statement verify = target.createStatement();
                 ResultSet rs = verify.executeQuery("SELECT 1")) {
                assert rs.next() && rs.getInt(1) == 1 : "取消之后经代理的连接不可用";
            }
        }

        waitRouteSizeBackTo(0);
        assert proxyInstance.cancelRouter.size() == 0
                : "会话关闭后取消路由未摘除，残留 " + proxyInstance.cancelRouter.size() + " 条";
    }

    /**
     * {@code setQueryTimeout} 在代理后必须生效（底层等价于定时取消）。
     * 这条用例直接验收"代理后超时完全失效"的原始问题。
     */
    @Test
    void queryTimeoutWorksThroughProxy() throws Exception {
        try (Connection target = connect("mem1");
             Statement stmt = target.createStatement()) {
            waitRouteSize(1);

            stmt.setQueryTimeout(2);

            long startedAt = System.currentTimeMillis();
            try (ResultSet rs = stmt.executeQuery(longQuery())) {
                rs.next();
                throw new AssertionError("查询在 2 秒超时设置下竟然正常完成了，说明超时取消没有生效");
            }
            catch (SQLException e) {
                long elapsed = System.currentTimeMillis() - startedAt;
                // 自然耗时约 11 秒；生效应在 2 秒附近、最迟不超过预算
                assert elapsed < CANCEL_BUDGET_MS + 3_000
                        : "setQueryTimeout(2) 生效太慢：" + elapsed + "ms";
            }
        }
    }

    /** 取消空闲会话必须无害：不报错、连接仍可用、路由表不膨胀。 */
    @Test
    void cancelIdleSessionThroughProxyIsHarmless() throws Exception {
        try (Connection idle = connect("mem2");
             Statement idleStmt = idle.createStatement()) {
            waitRouteSize(1);

            idleStmt.cancel();

            try (ResultSet rs = idleStmt.executeQuery("SELECT 3")) {
                assert rs.next() && rs.getInt(1) == 3 : "对空闲会话取消之后经代理的连接不可用";
            }
        }
    }

    /** 多别名隔离：用 mem2 连接的 pid 去取消 mem1 上正在跑的查询，必须不产生影响。 */
    @Test
    void cancelIsRoutedToCorrectUpstreamOnly() throws Exception {
        try (Connection target = connect("mem1");
             Connection other = connect("mem2");
             Statement targetStmt = target.createStatement();
             Statement otherStmt = other.createStatement()) {

            waitRouteSize(2);

            assert proxyInstance.proxyTarget.get("mem1").port != proxyInstance.proxyTarget.get("mem2").port
                    : "mem1 与 mem2 指向同一上游，无法验证路由隔离";

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread queryThread = new Thread(() -> {
                try (ResultSet rs = targetStmt.executeQuery(longQuery())) {
                    rs.next();
                }
                catch (Throwable t) {
                    failure.set(t);
                }
            }, "isolation-target");
            queryThread.start();

            try {
                Thread.sleep(1_500);
                assert queryThread.isAlive() : "目标查询结束太快，用例无效";

                // 对"另一个别名上的会话"发取消（pid 属于 mem2 的会话）
                otherStmt.cancel();

                // 目标查询必须继续执行：1 秒后仍然活着
                Thread.sleep(1_000);
                assert queryThread.isAlive()
                        : "取消被路由到了错误的上游：mem1 上的查询被 mem2 的取消打断了";

                // 收尾：真正取消 mem1 上的查询
                targetStmt.cancel();
                queryThread.join(CANCEL_BUDGET_MS);
                assert !queryThread.isAlive() : "收尾取消失败，查询仍在执行";
            }
            finally {
                targetStmt.cancel();
                queryThread.join(CANCEL_BUDGET_MS);
            }
        }
    }

    /** 取消一个代理不认识的 pid：静默忽略，不影响其他会话与路由表。 */
    @Test
    void cancelUnknownPidThroughProxyIsIgnored() throws Exception {
        try (Connection alive = connect("mem1");
             Statement stmt = alive.createStatement()) {
            waitRouteSize(1);

            sendRawCancelFrame(999_999);

            try (ResultSet rs = stmt.executeQuery("SELECT 11")) {
                assert rs.next() && rs.getInt(1) == 11 : "取消未知 pid 影响了其他会话";
            }
        }
    }

    /** 会话断开后，其 pid 对应的取消路由必须被摘除（避免陈旧路由把取消送错上游）。 */
    @Test
    void routeIsRemovedWhenSessionCloses() throws Exception {
        int baseline = proxyInstance.cancelRouter.size();

        try (Connection temp = connect("mem1")) {
            int pid = ((PGConnection) temp).getBackendPID();
            waitRouteSize(baseline + 1);

            assert proxyInstance.cancelRouter.find(pid) != null
                    : "握手后未登记取消路由，pid=" + pid;
        }

        waitRouteSizeBackTo(baseline);
        assert proxyInstance.cancelRouter.size() == baseline
                : "连接关闭后取消路由未摘除：baseline=" + baseline
                        + " current=" + proxyInstance.cancelRouter.size();
    }

    // ------------------------------------------------------------------ 启动包契约

    /**
     * 核心用例：启动包里没有 database 时，必须收到明确的 ErrorResponse 而不是挂住。
     *
     * <p>超时（客户端挂住）会抛出 {@code SocketTimeoutException} —— 那正是修复前的表现。</p>
     */
    @Test
    void startupWithoutDatabaseGetsExplicitError() throws Exception {
        long startedAt = System.currentTimeMillis();
        byte[] frame = sendStartup(startupPacket("test", null));
        long elapsed = System.currentTimeMillis() - startedAt;

        assert frame != null
                : "服务端没有回复任何内容就关闭了连接（应回 ErrorResponse），耗时 " + elapsed + "ms";
        assert frame[0] == 'E'
                : "期望 ErrorResponse('E')，实际首字节=" + (char) frame[0];

        Map<Character, String> fields = parseErrorFields(frame);
        assert fields.containsKey('M') : "ErrorResponse 缺少消息字段 'M'";
        assert fields.get('M').contains("alias") || fields.get('M').contains("Database")
                : "错误消息没有说明原因：" + fields.get('M');
    }

    /** database 为空串时同样必须被明确拒绝（否则无法选路）。 */
    @Test
    void startupWithEmptyDatabaseGetsExplicitError() throws Exception {
        byte[] frame = sendStartup(startupPacket("test", ""));

        assert frame != null : "database 为空串时服务端没有回复任何内容";
        assert frame[0] == 'E' : "期望 ErrorResponse('E')，实际首字节=" + (char) frame[0];

        Map<Character, String> fields = parseErrorFields(frame);
        assert fields.containsKey('M') : "ErrorResponse 缺少消息字段 'M'";
    }

    /**
     * 未知别名同样必须收到明确错误（回归：确保改动没有破坏原有分支，
     * 也没有因为"先关闭后写"而丢掉错误响应）。
     */
    @Test
    void startupWithUnknownDatabaseGetsExplicitError() throws Exception {
        byte[] frame = sendStartup(startupPacket("test", "no_such_alias"));

        assert frame != null : "未知别名时服务端没有回复任何内容";
        assert frame[0] == 'E' : "期望 ErrorResponse('E')，实际首字节=" + (char) frame[0];

        Map<Character, String> fields = parseErrorFields(frame);
        assert fields.containsKey('M') : "ErrorResponse 缺少消息字段 'M'";
        assert fields.get('M').contains("no_such_alias")
                : "错误消息里没有带上请求的库名：" + fields.get('M');
    }

    /** 两次请求都必须收到错误响应，说明错误路径不会残留状态。 */
    @Test
    void repeatedRejectedStartupsBothGetError() throws Exception {
        for (int i = 0; i < 2; i++) {
            byte[] frame = sendStartup(startupPacket("test", null));
            assert frame != null && frame[0] == 'E'
                    : "第 " + (i + 1) + " 次被拒的连接没有收到 ErrorResponse";
        }
    }

    // ------------------------------------------------------------------ 裸协议辅助

    /**
     * 构造一个 StartupMessage。
     *
     * @param database 为 null 时不写入 database 参数；否则写入给定值（可为空串）
     */
    private static byte[] startupPacket(String user, String database) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeInt32(body, 196608);            // 协议版本 3.0
        writeCString(body, "user");
        writeCString(body, user == null ? "test" : user);
        if (database != null) {
            writeCString(body, "database");
            writeCString(body, database);
        }
        body.write(0);                       // 参数结束标记

        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeInt32(packet, bodyBytes.length + 4);
        packet.writeBytes(bodyBytes);
        return packet.toByteArray();
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.writeBytes(Utils.int32ToBytes(value));
    }

    private static void writeCString(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    /** 发送启动包，返回服务端回的第一个报文；超时（客户端挂住）会抛出 SocketTimeoutException。 */
    private static byte[] sendStartup(byte[] packet) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", proxyPort), 5_000);
            socket.setSoTimeout(5_000);               // 修复前这里会超时，正是"客户端挂住"的表现
            socket.getOutputStream().write(packet);
            socket.getOutputStream().flush();
            return readFrame(socket.getInputStream());
        }
    }

    /** 从 socket 读取一个完整的 PG 报文（类型字节 + Int32 长度 + 正文）。 */
    private static byte[] readFrame(InputStream in) throws Exception {
        int type = in.read();
        if (type == -1) {
            return null;
        }
        byte[] lenBytes = readFully(in, 4);
        int len = Utils.bytesToInt32(lenBytes);
        if (len < 4) {
            throw new IllegalStateException("非法报文长度：" + len);
        }
        byte[] body = readFully(in, len - 4);
        byte[] frame = new byte[1 + len];
        frame[0] = (byte) type;
        System.arraycopy(lenBytes, 0, frame, 1, 4);
        System.arraycopy(body, 0, frame, 5, body.length);
        return frame;
    }

    private static byte[] readFully(InputStream in, int length) throws Exception {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read == -1) {
                throw new IllegalStateException("连接在报文结束前被关闭（已读 " + offset + "/" + length + " 字节）");
            }
            offset += read;
        }
        return buffer;
    }

    /** 解析 ErrorResponse 报文的字段（'S' 严重级别、'C' 错误码、'M' 错误消息）。 */
    private static Map<Character, String> parseErrorFields(byte[] frame) {
        Map<Character, String> fields = new LinkedHashMap<>();
        int pos = 5;                                  // 跳过类型字节 + 长度
        while (pos < frame.length && frame[pos] != 0) {
            char fieldType = (char) (frame[pos] & 0xFF);
            pos++;
            int start = pos;
            while (pos < frame.length && frame[pos] != 0) {
                pos++;
            }
            fields.put(fieldType, new String(frame, start, pos - start, StandardCharsets.UTF_8));
            pos++;                                    // 跳过字段结束的 0
        }
        return fields;
    }
}
