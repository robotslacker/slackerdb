package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.core.BaseConnection;
import org.postgresql.copy.CopyManager;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.CopyProtocolHandler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * {@code COPY ... FROM STDIN} 的<b>缓冲上限</b>契约测试。
 *
 * <p><b>被测行为</b>：本项目明确<b>不做</b>流式摄取 —— CopyData 的字节全部堆在
 * {@code DBSession.copyLastRemained} 里，到 CopyDone 才整体解析。没有上限时，超大 COPY 的结果是
 * JVM 去分配一个注定失败的数组（实测 {@code OutOfMemoryError: Requested array size exceeds VM limit}
 * 会一路逃逸到 Netty：客户端收不到任何错误、会话也一起废掉）。</p>
 *
 * <p>现在的语义是"有界 + 明确失败"：累计缓冲超过 {@link CopyProtocolHandler#maxPayloadBytes}
 * （2 GiB）即判定本次 COPY 失败 —— 丢弃已缓冲数据、回滚服务端自开的 COPY 事务、
 * 回 {@code ErrorResponse} + {@code ReadyForQuery}，会话保持可用。</p>
 *
 * <p>2 GiB 的载荷无法在测试里真的造出来，因此上限被设计成可调（非 final），
 * 测试临时调小它来验证同一条代码路径。</p>
 */
public class CopyPayloadLimitTest {

    private static DBInstance dbInstance;
    private static int dbPort;
    private long savedLimit;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("copylimit");
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

    @BeforeEach
    void saveLimit() {
        savedLimit = CopyProtocolHandler.maxPayloadBytes;
    }

    @AfterEach
    void restoreLimit() {
        CopyProtocolHandler.maxPayloadBytes = savedLimit;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/copylimit", "", "");
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static void createTable(Connection conn, String table) throws SQLException {
        exec(conn, "CREATE OR REPLACE TABLE " + table + "(id INT, name VARCHAR, note VARCHAR)");
    }

    private static long copyIn(Connection conn, String sql, String data) throws SQLException, java.io.IOException {
        return new CopyManager((BaseConnection) conn).copyIn(sql, new StringReader(data));
    }

    private static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return "<no row>";
            }
            String value = rs.getString(1);
            return rs.wasNull() ? "<null>" : value;
        }
    }

    // ================================================================
    // 一、上限本身
    // ================================================================

    /** 需求：上限就是 2 GiB。 */
    @Test
    void defaultLimitIsTwoGigabytes() {
        assert CopyProtocolHandler.maxPayloadBytes == 2L * 1024 * 1024 * 1024
                : "COPY 缓冲上限应为 2GiB，实际 " + CopyProtocolHandler.maxPayloadBytes;
    }

    /** 上限之内的 COPY 必须照常成功（防止"加了保护把正常导入弄坏"）。 */
    @Test
    void copyWithinLimitStillSucceeds() throws Exception {
        CopyProtocolHandler.maxPayloadBytes = 64 * 1024;
        try (Connection conn = connect()) {
            createTable(conn, "lim_ok");
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                data.append(i).append(",name").append(i).append(",n\n");
            }
            long rows = copyIn(conn, "COPY lim_ok FROM STDIN WITH (FORMAT csv)", data.toString());
            assert rows == 1000 : "上限内的 COPY 应正常导入 1000 行，实际 " + rows;
            assert count(conn, "lim_ok") == 1000;
        }
    }

    // ================================================================
    // 二、超过上限：按失败处理
    // ================================================================

    /** 超过上限的 COPY 必须失败：不写任何行、给出明确错误、会话仍然可用。 */
    @Test
    void copyExceedingLimitFailsAndWritesNothing() throws Exception {
        CopyProtocolHandler.maxPayloadBytes = 4096;
        try (Connection conn = connect()) {
            createTable(conn, "lim_big");

            StringBuilder data = new StringBuilder();
            while (data.length() < 200_000) {
                data.append("1,a,b\n");
            }

            String error = null;
            try {
                copyIn(conn, "COPY lim_big FROM STDIN WITH (FORMAT csv)", data.toString());
            } catch (SQLException e) {
                error = e.getMessage();
            }

            assert error != null : "超过缓冲上限的 COPY 必须失败，而不是被默默接受";
            assert error.contains("in-memory buffer limit")
                    : "错误信息应说明超出缓冲上限，实际: " + error;
            assert count(conn, "lim_big") == 0
                    : "失败的 COPY 不能留下任何数据，实际 " + count(conn, "lim_big");

            // 会话必须仍然可用，且下一次 COPY（在限额内）正常
            assert "42".equals(scalar(conn, "SELECT 42")) : "超限失败后会话不可用";
            long rows = copyIn(conn, "COPY lim_big FROM STDIN WITH (FORMAT csv)", "7,ok,fine\n");
            assert rows == 1 && count(conn, "lim_big") == 1
                    : "超限失败后的下一次 COPY 必须正常（不能被残留状态影响）";
        }
    }

    // ================================================================
    // 三、裸 socket：线协议契约
    // ================================================================

    /**
     * 超限时必须在<b>数据流中途</b>就给出 {@code ErrorResponse} + {@code ReadyForQuery}。
     *
     * <p>为什么必须是两个报文：驱动的 {@code processCopyResults} 在收到 'E' 之后会把
     * {@code block} 置真并继续等 'Z'，只有 'Z' 才会解锁 copy 操作并抛出保存下来的错误
     * （{@code QueryExecutorImpl:1309-1416}）。只回 'E' 会让驱动一直等下去。</p>
     */
    @Test
    void oversizedCopyAnswersErrorResponseAndReadyForQuery() throws Exception {
        CopyProtocolHandler.maxPayloadBytes = 1024;
        try (Connection seed = connect()) {
            createTable(seed, "lim_wire");
        }

        try (RawClient client = new RawClient()) {
            client.sendQuery("COPY lim_wire FROM STDIN WITH (FORMAT csv)");
            client.readUntilType('G');

            // 1024 + 512 > 1024：第二段就超限
            client.sendCopyData(repeat('x', 512));
            client.sendCopyData(repeat('x', 512));
            client.sendCopyData(repeat('x', 512));

            byte[] errorFrame = client.readUntilType('E');
            String message = RawClient.errorMessageOf(errorFrame);
            assert message.contains("in-memory buffer limit")
                    : "ErrorResponse 应说明超出缓冲上限，实际: " + message;

            String afterError = client.readUntilReadyForQuery();
            assert "Z".equals(afterError)
                    : "ErrorResponse 之后必须紧跟一个 ReadyForQuery，实际序列=" + afterError;

            // 失败之后的 CopyDone 只做清理：不能回 CommandComplete（否则客户端看到 "COPY 0" 的成功）
            client.sendCopyDone();

            // 同一个连接上再发一条普通查询：序列必须是干净的 TDCZ，
            // 说明既没有多余的 E/Z，也没有把失败 COPY 的数据当成这条查询的响应
            client.sendQuery("SELECT 1");
            String queryTypes = client.readUntilReadyForQuery();
            assert "TDCZ".equals(queryTypes)
                    : "超限失败之后普通查询的报文序列应为 TDCZ，实际=" + queryTypes;
        }
    }

    /** 超限之后新开一次 COPY 必须干净：既不继承"已失败"标记，也不继承被丢弃的数据。 */
    @Test
    void copyAfterOversizedAbortStartsClean() throws Exception {
        CopyProtocolHandler.maxPayloadBytes = 1024;
        try (Connection seed = connect()) {
            createTable(seed, "lim_next");
        }

        try (RawClient client = new RawClient()) {
            client.sendQuery("COPY lim_next FROM STDIN WITH (FORMAT csv)");
            client.readUntilType('G');
            client.sendCopyData(repeat('x', 512));
            client.sendCopyData(repeat('x', 512));
            client.sendCopyData(repeat('x', 512));
            client.readUntilType('E');
            client.readUntilReadyForQuery();
            client.sendCopyDone();

            // 新的一次 COPY：必须能进入子协议并只写入这一次的数据
            client.sendQuery("COPY lim_next FROM STDIN WITH (FORMAT csv)");
            client.readUntilType('G');
            client.sendCopyData("1,a,b\n2,c,d\n");
            client.sendCopyDone();
            String types = client.readUntilReadyForQuery();
            assert types.indexOf('C') >= 0
                    : "新 COPY 应收到 CommandComplete，实际序列=" + types;
        }

        try (Connection verify = connect()) {
            assert count(verify, "lim_next") == 2
                    : "新 COPY 应恰好写入 2 行（被丢弃的超限数据不能漏进来），实际 "
                    + count(verify, "lim_next");
        }
    }

    /** 超限失败之后客户端仍发 CopyFail：必须收到错误 + ReadyForQuery，且会话保持可用。 */
    @Test
    void copyFailAfterOversizedAbortStillAnswers() throws Exception {
        CopyProtocolHandler.maxPayloadBytes = 1024;
        try (Connection seed = connect()) {
            createTable(seed, "lim_fail");
        }

        try (RawClient client = new RawClient()) {
            client.sendQuery("COPY lim_fail FROM STDIN WITH (FORMAT csv)");
            client.readUntilType('G');
            client.sendCopyData(repeat('x', 512));
            client.sendCopyData(repeat('x', 512));
            client.sendCopyData(repeat('x', 512));
            client.readUntilType('E');
            client.readUntilReadyForQuery();

            client.sendCopyFail("client gave up");
            byte[] errorFrame = client.readUntilType('E');
            assert RawClient.errorMessageOf(errorFrame).contains("client gave up")
                    : "CopyFail 之后应回客户端的失败原因";
            assert "Z".equals(client.readUntilReadyForQuery())
                    : "CopyFail 之后应回 ReadyForQuery";

            client.sendQuery("SELECT 1");
            assert "TDCZ".equals(client.readUntilReadyForQuery())
                    : "CopyFail 之后会话应仍然可用且报文干净";
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count + 1);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        sb.append('\n');
        return sb.toString();
    }

    // ================================================================
    // 极简线协议客户端
    // ================================================================

    private static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;

        RawClient() throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", dbPort), 5_000);
            socket.setSoTimeout(8_000);
            in = socket.getInputStream();
            startup();
        }

        private void startup() throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(Utils.int32ToBytes(196608));
            writeCString(body, "user");
            writeCString(body, "test");
            writeCString(body, "database");
            writeCString(body, "copylimit");
            body.write(0);
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.writeBytes(Utils.int32ToBytes(bodyBytes.length + 4));
            packet.writeBytes(bodyBytes);
            write(packet.toByteArray());
            readUntilReadyForQuery();
        }

        /** 简单查询 'Q'。 */
        void sendQuery(String sql) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, sql);
            writeFrame('Q', body.toByteArray());
        }

        void sendCopyData(String data) throws Exception {
            writeFrame('d', data.getBytes(StandardCharsets.UTF_8));
        }

        void sendCopyDone() throws Exception {
            writeFrame('c', new byte[0]);
        }

        void sendCopyFail(String reason) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, reason);
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

        /** 读到指定类型的报文并把它返回（中途连接关闭则抛异常）。 */
        byte[] readUntilType(char expected) throws Exception {
            StringBuilder seen = new StringBuilder();
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException(
                            "连接在收到 '" + expected + "' 之前被关闭，已收到：" + seen);
                }
                char type = (char) (frame[0] & 0xFF);
                if (type == expected) {
                    return frame;
                }
                seen.append(type);
            }
        }

        /** 读到 ReadyForQuery，返回沿途（含 Z 本身）的报文类型序列。 */
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

        /** 取出 ErrorResponse 的 'M'（message）字段。 */
        static String errorMessageOf(byte[] frame) {
            int pos = 5;
            while (pos < frame.length && frame[pos] != 0) {
                char fieldType = (char) (frame[pos] & 0xFF);
                pos++;
                int start = pos;
                while (pos < frame.length && frame[pos] != 0) {
                    pos++;
                }
                String value = new String(frame, start, pos - start, StandardCharsets.UTF_8);
                if (fieldType == 'M') {
                    return value;
                }
                pos++;
            }
            return "";
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
