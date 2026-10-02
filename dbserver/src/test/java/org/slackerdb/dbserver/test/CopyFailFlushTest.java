package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.core.BaseConnection;
import org.postgresql.copy.CopyManager;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CopyFail('f') 与 Flush('H') 的协议契约测试。
 *
 * <p><b>被测缺陷</b>：{@code PostgresServer.RawMessageDecoder} 的 switch 只覆盖已知消息类型，
 * 其余落到 {@code default -> logger.error("Unknown message type")} —— <b>只记日志、不回应答、
 * 也不关闭连接</b>。客户端因此永远等不到它期待的下一条报文，只能等空闲超时（默认 600s）。</p>
 *
 * <p><b>CopyFail 是真实路径</b>：{@code CopyManager.cancelCopy()} 放弃进行中的 COPY IN 时会发送 'f'
 * （见 {@code dbdriver/.../QueryExecutorImpl.java:1027-1057}），随后等待一个 ErrorResponse。
 * 服务端不作任何响应时，客户端就此挂住。</p>
 *
 * <p>另有一条<b>相关但独立</b>的缺陷由
 * {@code Statement.execute("COPY ... FROM STDIN")} 走扩展协议（Parse/Bind/Execute），
 * 而 COPY 路由只做在简单查询（'Q'）路径上，因此它在 DuckDB 层就以
 * {@code /dev/stdin} 打不开而失败。本文件只锁定其现状，不在本次修复范围内。</p>
 */
public class CopyFailFlushTest {

    /** 修复前这里是"挂到空闲超时"（600s），因此判据必须远小于它。 */
    private static final long RESPONSE_BUDGET_MS = 8_000;

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("copyfail");
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

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/copyfail", "", "");
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static long countRows(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ================================================================
    // 一、裸 socket：精确的线协议契约
    // ================================================================

    /** 极简 PG 线协议客户端。 */
    private static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;

        RawClient() throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", dbPort), 5_000);
            socket.setSoTimeout((int) RESPONSE_BUDGET_MS);
            in = socket.getInputStream();
            startup();
        }

        private void startup() throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(Utils.int32ToBytes(196608));
            writeCString(body, "user");
            writeCString(body, "test");
            writeCString(body, "database");
            writeCString(body, "copyfail");
            body.write(0);
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.writeBytes(Utils.int32ToBytes(bodyBytes.length + 4));
            packet.writeBytes(bodyBytes);
            write(packet.toByteArray());
            readUntilReadyForQuery();
        }

        void sendQuery(String sql) throws Exception {
            byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write('Q');
            frame.writeBytes(Utils.int32ToBytes(4 + sqlBytes.length + 1));
            frame.writeBytes(sqlBytes);
            frame.write(0);
            write(frame.toByteArray());
        }

        /** 发送 CopyFail：'f' + Int32(长度) + CString 原因。 */
        void sendCopyFail(String reason) throws Exception {
            byte[] reasonBytes = (reason == null ? "" : reason).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write('f');
            frame.writeBytes(Utils.int32ToBytes(4 + reasonBytes.length + 1));
            frame.writeBytes(reasonBytes);
            frame.write(0);
            write(frame.toByteArray());
        }

        void sendSync() throws Exception {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write('S');
            frame.writeBytes(Utils.int32ToBytes(4));
            write(frame.toByteArray());
        }

        /** 发送 Flush：'H' + Int32(4)，无正文。 */
        void sendFlush() throws Exception {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write('H');
            frame.writeBytes(Utils.int32ToBytes(4));
            write(frame.toByteArray());
        }

        private void write(byte[] data) throws Exception {
            socket.getOutputStream().write(data);
            socket.getOutputStream().flush();
        }

        /** 读一个报文；超时（客户端挂住）会抛 SocketTimeoutException。 */
        byte[] readFrame() throws Exception {
            int type = in.read();
            if (type == -1) {
                return null;
            }
            byte[] lenBytes = readFully(4);
            int len = Utils.bytesToInt32(lenBytes);
            byte[] body = readFully(len - 4);
            byte[] frame = new byte[1 + len];
            frame[0] = (byte) type;
            System.arraycopy(lenBytes, 0, frame, 1, 4);
            System.arraycopy(body, 0, frame, 5, body.length);
            return frame;
        }

        /** 一直读到 ReadyForQuery('Z')，返回沿途观察到的报文类型序列。 */
        String readUntilReadyForQuery() throws Exception {
            StringBuilder types = new StringBuilder();
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException("连接在 ReadyForQuery 之前被关闭，已收到：" + types);
                }
                char type = (char) (frame[0] & 0xFF);
                types.append(type);
                if (type == 'Z') {
                    return types.toString();
                }
            }
        }

        private static void writeCString(ByteArrayOutputStream out, String value) {
            out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
            out.write(0);
        }

        private byte[] readFully(int length) throws Exception {
            byte[] buffer = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = in.read(buffer, offset, length - offset);
                if (read == -1) {
                    throw new IllegalStateException("连接提前关闭");
                }
                offset += read;
            }
            return buffer;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    private static Map<Character, String> parseErrorFields(byte[] frame) {
        Map<Character, String> fields = new LinkedHashMap<>();
        int pos = 5;
        while (pos < frame.length && frame[pos] != 0) {
            char fieldType = (char) (frame[pos] & 0xFF);
            pos++;
            int start = pos;
            while (pos < frame.length && frame[pos] != 0) {
                pos++;
            }
            fields.put(fieldType, new String(frame, start, pos - start, StandardCharsets.UTF_8));
            pos++;
        }
        return fields;
    }

    /**
     * 核心复现（协议层）：COPY IN 期间收到 CopyFail，服务端必须回 ErrorResponse；
     * 随后对 Sync 回恰好一个 ReadyForQuery。
     *
     * <p>修复前：CopyFail 落进 default 分支被丢弃，这里会阻塞到 socket 超时。</p>
     */
    @Test
    void copyFailDuringCopyInGetsErrorResponse() throws Exception {
        try (Connection seed = connect()) {
            exec(seed, "CREATE TABLE t_raw1(id INT)");
        }

        try (RawClient client = new RawClient()) {
            client.sendQuery("COPY t_raw1 FROM STDIN (FORMAT csv)");

            byte[] first = client.readFrame();
            assert first != null : "连接在 CopyInResponse 之前被关闭";
            assert (char) (first[0] & 0xFF) == 'G'
                    : "期望 CopyInResponse('G')，实际='" + (char) (first[0] & 0xFF) + "'";

            // 客户端决定放弃这次 COPY
            client.sendCopyFail("client aborted the copy");

            byte[] response = client.readFrame();
            assert response != null : "CopyFail 之后服务端关闭了连接（应回 ErrorResponse）";
            char type = (char) (response[0] & 0xFF);
            assert type == 'E'
                    : "CopyFail 之后期望 ErrorResponse('E')，实际='" + type
                    + "'（若抛 SocketTimeoutException 则说明服务端未作任何响应）";

            assert parseErrorFields(response).containsKey('M') : "ErrorResponse 缺少消息字段 'M'";

            // 驱动随后会发 Sync，服务端必须回恰好一个 ReadyForQuery
            client.sendSync();
            String types = client.readUntilReadyForQuery();
            assert "Z".equals(types) : "Sync 之后期望恰好一个 ReadyForQuery，实际序列=" + types;

            // 会话必须仍然可用
            client.sendQuery("SELECT 1");
            assert client.readUntilReadyForQuery().endsWith("Z") : "CopyFail 之后会话不可用";
        }

        // 被放弃的 COPY 必须整体不生效
        try (Connection verify = connect()) {
            assert countRows(verify, "t_raw1") == 0
                    : "被 CopyFail 放弃的 COPY 留下了数据：" + countRows(verify, "t_raw1");
        }
    }

    /**
     * Flush('H')：不得产生额外报文、不得让连接出问题，只是把已缓冲输出推出去。
     *
     * <p>修复前 Flush 落进 default 分支被丢弃（只记一条 ERROR 日志）。</p>
     */
    @Test
    void flushProducesNoExtraResponse() throws Exception {
        try (RawClient client = new RawClient()) {
            client.sendQuery("SELECT 1");
            client.sendFlush();

            String types = client.readUntilReadyForQuery();
            assert types.indexOf('Z') == types.length() - 1
                    : "ReadyForQuery 应当是最后一个报文，实际序列=" + types;
            assert types.indexOf('Z') == types.lastIndexOf('Z')
                    : "只应有一个 ReadyForQuery，实际序列=" + types;

            client.sendQuery("SELECT 2");
            String after = client.readUntilReadyForQuery();
            assert after.endsWith("Z") : "Flush 之后会话不可用，实际序列=" + after;
        }
    }

    // ================================================================
    // 二、JDBC 层：驱动在 COPY 中断开时的行为
    // ================================================================

    /**
     * 用户可见的复现：COPY IN 进行中，数据源抛异常 → 驱动中止这次 COPY。
     *
     * <p>驱动在 COPY IN 期间中止时会发送 CopyFail（{@code QueryExecutorImpl.java:1027-1057}）。
     * 修复前服务端不响应，调用方线程可能在收尾阶段挂住；这里用
     * {@code join(预算)} 把"挂住"变成可判定的失败。</p>
     */
    @Test
    void abortedCopyInMustNotHang() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "CREATE TABLE t_cancel(id INT)");

            CopyManager copyManager = new CopyManager((BaseConnection) conn);

            // 读到一半就抛异常，模拟"客户端决定放弃这次 COPY"
            java.io.Reader failingReader = new java.io.Reader() {
                private int served = 0;

                @Override
                public int read(char[] cbuf, int off, int len) throws java.io.IOException {
                    if (served > 0) {
                        throw new java.io.IOException("client aborted the copy on purpose");
                    }
                    served++;
                    String payload = "1\n2\n3\n";
                    int n = Math.min(len, payload.length());
                    payload.getChars(0, n, cbuf, off);
                    return n;
                }

                @Override
                public void close() {
                }
            };

            AtomicReference<Throwable> copyFailure = new AtomicReference<>();
            Thread copyThread = new Thread(() -> {
                try {
                    copyManager.copyIn("COPY t_cancel FROM STDIN (FORMAT csv)", failingReader);
                }
                catch (Throwable t) {
                    copyFailure.set(t);
                }
            }, "copy-abort-target");
            copyThread.setDaemon(true);
            copyThread.start();

            copyThread.join(RESPONSE_BUDGET_MS);
            if (copyThread.isAlive()) {
                StringBuilder stack = new StringBuilder();
                for (StackTraceElement el : copyThread.getStackTrace()) {
                    stack.append("\n    at ").append(el);
                }
                throw new AssertionError(
                        "COPY 中止后调用方挂住了（线程栈如下）：" + stack);
            }

            assert copyFailure.get() != null : "被中止的 COPY 本应抛异常，实际正常返回";

            // 失败后连接仍应可用
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                assert rs.next() && rs.getInt(1) == 1 : "COPY 中止之后连接不可用";
            }
        }

        // 被中止的 COPY 不得留下任何数据
        try (Connection fresh = connect()) {
            assert countRows(fresh, "t_cancel") == 0
                    : "被中止的 COPY 留下了数据：" + countRows(fresh, "t_cancel");
        }
    }

    // ================================================================
    // 三、扩展协议路径（BUG-17）
    // ================================================================

    /**
     * 扩展协议下进入 COPY 子协议后，CopyFail 必须同样被正确处理。
     *
     * <p>修复前 COPY 只在简单查询路径建立子协议，扩展协议下根本到不了 COPY 状态，
     * 因此这个组合是"两处缺陷叠加"：既进不了子协议（BUG-17），也处理不了 'f'（BUG-10）。
     * 本用例锁定修复后的组合行为：<b>能进子协议、能中途放弃、放弃后不挂住且不残留数据</b>。</p>
     *
     * <p>用裸 socket 而不是 JDBC：驱动在 {@code Statement} 上执行 COPY 时会自己发 CopyFail，
     * 但走哪条分支取决于语句缓存与一次性调用的判定，行为不稳定；线协议层的契约是确定的。</p>
     */
    @Test
    void copyFailWorksInsideExtendedProtocolCopy() throws Exception {
        try (Connection seed = connect()) {
            exec(seed, "CREATE TABLE t_ext_fail(id INT)");
        }

        try (ExtendedRawClient client = new ExtendedRawClient()) {
            client.sendParse("", "COPY t_ext_fail FROM STDIN (FORMAT csv)");
            client.sendBind("", "");
            client.sendExecute("");

            // 必须进入 COPY 子协议
            client.readUntilType('G');

            // 中途放弃：发 CopyFail（'f' + 原因），随后发 Sync
            client.sendCopyFail("client aborted the extended copy");
            client.sendSync();

            String types = client.readUntilReadyForQuery();
            assert types.indexOf('E') >= 0
                    : "扩展协议下的 CopyFail 应收到 ErrorResponse('E')，实际序列=" + types;

            // 放弃之后连接仍然可用
            client.sendQuery("SELECT 1");
            assert client.readUntilReadyForQuery().endsWith("Z") : "CopyFail 之后会话不可用";
        }

        // 被放弃的 COPY 不得留下数据
        try (Connection verify = connect()) {
            assert countRows(verify, "t_ext_fail") == 0
                    : "被放弃的扩展协议 COPY 留下了数据：" + countRows(verify, "t_ext_fail");
        }
    }

    /** 极简的扩展协议客户端（只覆盖本文件需要的报文）。 */
    private static final class ExtendedRawClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;

        ExtendedRawClient() throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", dbPort), 5_000);
            socket.setSoTimeout((int) RESPONSE_BUDGET_MS);
            in = socket.getInputStream();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(Utils.int32ToBytes(196608));
            writeCString(body, "user");
            writeCString(body, "test");
            writeCString(body, "database");
            writeCString(body, "copyfail");
            body.write(0);
            byte[] bb = body.toByteArray();
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.writeBytes(Utils.int32ToBytes(bb.length + 4));
            pkt.writeBytes(bb);
            write(pkt.toByteArray());
            readUntilReadyForQuery();
        }

        void sendParse(String statementName, String sql) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, statementName);
            writeCString(body, sql);
            body.writeBytes(new byte[]{0, 0});
            writeFrame('P', body.toByteArray());
        }

        void sendBind(String portal, String statement) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, portal);
            writeCString(body, statement);
            body.writeBytes(new byte[]{0, 0});
            body.writeBytes(new byte[]{0, 0});
            body.writeBytes(new byte[]{0, 0});
            writeFrame('B', body.toByteArray());
        }

        void sendExecute(String portal) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, portal);
            body.writeBytes(Utils.int32ToBytes(0));
            writeFrame('E', body.toByteArray());
        }

        void sendSync() throws Exception {
            writeFrame('S', new byte[0]);
        }

        void sendQuery(String sql) throws Exception {
            byte[] sb = sql.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(sb);
            body.write(0);
            writeFrame('Q', body.toByteArray());
        }

        void sendCopyFail(String reason) throws Exception {
            byte[] rb = reason.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(rb);
            body.write(0);
            writeFrame('f', body.toByteArray());
        }

        private void writeFrame(char type, byte[] body) throws Exception {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(type);
            frame.writeBytes(Utils.int32ToBytes(4 + body.length));
            frame.writeBytes(body);
            write(frame.toByteArray());
        }

        private void write(byte[] data) throws Exception {
            socket.getOutputStream().write(data);
            socket.getOutputStream().flush();
        }

        byte[] readFrame() throws Exception {
            int type = in.read();
            if (type == -1) {
                return null;
            }
            byte[] lenBytes = readFully(4);
            int len = Utils.bytesToInt32(lenBytes);
            byte[] body = readFully(len - 4);
            byte[] frame = new byte[1 + len];
            frame[0] = (byte) type;
            System.arraycopy(lenBytes, 0, frame, 1, 4);
            System.arraycopy(body, 0, frame, 5, body.length);
            return frame;
        }

        char readUntilType(char expected) throws Exception {
            StringBuilder seen = new StringBuilder();
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException("连接在收到 '" + expected + "' 之前被关闭，已收到：" + seen);
                }
                char type = (char) (frame[0] & 0xFF);
                if (type == expected) {
                    return type;
                }
                seen.append(type);
            }
        }

        String readUntilReadyForQuery() throws Exception {
            StringBuilder types = new StringBuilder();
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException("连接在 ReadyForQuery 之前被关闭，已收到：" + types);
                }
                char type = (char) (frame[0] & 0xFF);
                types.append(type);
                if (type == 'Z') {
                    return types.toString();
                }
            }
        }

        private static void writeCString(ByteArrayOutputStream out, String value) {
            out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
            out.write(0);
        }

        private byte[] readFully(int length) throws Exception {
            byte[] buffer = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = in.read(buffer, offset, length - offset);
                if (read == -1) {
                    throw new IllegalStateException("连接提前关闭");
                }
                offset += read;
            }
            return buffer;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
