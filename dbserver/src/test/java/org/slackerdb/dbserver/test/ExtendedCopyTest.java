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
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * COPY FROM STDIN 在<b>扩展协议</b>（Parse/Bind/Execute）下的行为测试。
 *
 * <p><b>被测缺陷（规划文档 BUG-17）</b>：COPY 的识别只做在简单查询路径
 * （{@code QueryRequest} 调用 {@code CopyVisitor.parseCopyStatement}）上，扩展协议路径
 * （{@code ParseRequest}/{@code ExecuteRequest}）完全没有这段逻辑。于是用
 * {@code Statement.execute("COPY ... FROM STDIN")} —— 驱动在默认 {@code preferQueryMode=extended}
 * 下走 Parse/Bind/Execute —— 时，语句会被直接丢给 DuckDB，报
 * {@code IO Error: No files found that match the pattern "/dev/stdin"}。</p>
 *
 * <p>而 {@code CopyManager.copyIn()} 之所以正常，是因为它走
 * {@code QueryExecutorImpl.startCopy()}，那里用的是简单查询（发送 {@code 'Q'}）。
 * 同一句 SQL 因驱动走哪条协议而结果不同，这就是缺陷本身。</p>
 */
public class ExtendedCopyTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("extcopy");
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
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/extcopy", "", "");
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
    // 一、驱动层复现：Statement.execute 走扩展协议
    // ================================================================

    /**
     * 核心复现：{@code Statement.execute("COPY ... FROM STDIN")} 应当像
     * {@code CopyManager} 一样进入 COPY 子协议（拿到 CopyInResponse），
     * 当前却被直接丢给 DuckDB 报 {@code /dev/stdin}。
     */
    @Test
    void statementExecuteOnCopyFromStdinShouldEnterCopySubProtocol() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "CREATE TABLE t_stmt(id INT)");

            String errorMessage = null;
            try (Statement st = conn.createStatement()) {
                st.execute("COPY t_stmt FROM STDIN (FORMAT csv)");
            }
            catch (Exception e) {
                errorMessage = e.getMessage();
            }

            assert errorMessage == null
                    : "Statement.execute(\"COPY ... FROM STDIN\") 被丢给了 DuckDB（扩展协议未识别 COPY）："
                    + errorMessage;
        }
    }

    /**
     * 对照：同一条 SQL 通过 {@code CopyManager}（简单查询路径）必须正常工作。
     * 这条用例在修复前后都应通过 —— 它用于证明"差别只来自协议路径"。
     */
    @Test
    void copyManagerOnSameSqlWorks() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "CREATE TABLE t_cm(id INT)");

            CopyManager copyManager = new CopyManager((BaseConnection) conn);
            long rows = copyManager.copyIn("COPY t_cm FROM STDIN (FORMAT csv)",
                    new StringReader("1\n2\n3\n"));

            assert rows == 3 : "CopyManager 应写入 3 行，实际 " + rows;
            assert countRows(conn, "t_cm") == 3 : "CopyManager 写入的行数不符";
        }
    }

    // ================================================================
    // 二、裸 socket：扩展协议下的精确协议契约
    // ================================================================

    /**
     * 直接按扩展协议发送 COPY：Parse + Bind + Execute + Sync。
     *
     * <p>期望：收到 {@code CopyInResponse('G')}（服务端进入 COPY 子协议），
     * 而不是 {@code ErrorResponse}。</p>
     */
    @Test
    void extendedProtocolCopyGetsCopyInResponse() throws Exception {
        try (Connection seed = connect()) {
            exec(seed, "CREATE TABLE t_ext(id INT)");
        }

        try (RawExtendedClient client = new RawExtendedClient()) {
            client.sendParse("", "COPY t_ext FROM STDIN (FORMAT csv)");
            client.sendBind("", "");
            client.sendDescribePortal("");
            client.sendExecute("");
            client.sendSync();

            String types = client.readUntilReadyForQuery();
            assert types.indexOf('G') >= 0
                    : "扩展协议下的 COPY 应收到 CopyInResponse('G')，实际报文序列=" + types
                    + "（若为 'E' 说明语句被丢给了 DuckDB）";
        }
    }

    /** 扩展协议 COPY 的全流程：CopyInResponse → CopyData → CopyDone → CommandComplete。 */
    @Test
    void extendedProtocolCopyFullFlowWritesRows() throws Exception {
        try (Connection seed = connect()) {
            exec(seed, "CREATE TABLE t_flow(id INT)");
        }

        try (RawExtendedClient client = new RawExtendedClient()) {
            // 注意：Sync 放在 COPY 整个子协议结束之后。
            // 若在 Execute 之后立刻 Sync，服务端会把 Sync 当作一个独立的回合先回一个
            // ReadyForQuery，客户端就会在 CopyInResponse 之前读到多余的 'Z'。
            client.sendParse("", "COPY t_flow FROM STDIN (FORMAT csv)");
            client.sendBind("", "");
            client.sendExecute("");

            // Parse/Bind 各自会回一个报文，因此不能假设第一帧就是 CopyInResponse，
            // 必须读到 CopyInResponse 为止。
            client.readUntilType('G');

            // 服务端进入 COPY 子协议后，客户端发数据 + CopyDone
            client.sendCopyData("1\n2\n3\n");
            client.sendCopyDone();

            String types = client.readUntilReadyForQuery();
            assert types.indexOf('C') >= 0
                    : "CopyDone 之后应收到 CommandComplete('C')，实际序列=" + types;
        }

        try (Connection verify = connect()) {
            assert countRows(verify, "t_flow") == 3
                    : "扩展协议 COPY 应写入 3 行，实际 " + countRows(verify, "t_flow");
        }
    }

    /** 极简的扩展协议客户端。 */
    private static final class RawExtendedClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;

        RawExtendedClient() throws Exception {
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
            writeCString(body, "extcopy");
            body.write(0);
            byte[] bb = body.toByteArray();
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.writeBytes(Utils.int32ToBytes(bb.length + 4));
            pkt.writeBytes(bb);
            write(pkt.toByteArray());
            readUntilReadyForQuery();
        }

        /** Parse: 'P' + len + statementName\0 + query\0 + Int16(参数类型个数=0) */
        void sendParse(String statementName, String sql) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, statementName);
            writeCString(body, sql);
            body.writeBytes(new byte[]{0, 0});          // 参数类型个数 = 0
            writeFrame('P', body.toByteArray());
        }

        /** Bind: 'B' + len + portal\0 + statement\0 + Int16(格式码数=0) + Int16(参数数=0) + Int16(结果格式码数=0) */
        void sendBind(String portal, String statement) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, portal);
            writeCString(body, statement);
            body.writeBytes(new byte[]{0, 0});          // 参数格式码个数 = 0
            body.writeBytes(new byte[]{0, 0});          // 参数个数 = 0
            body.writeBytes(new byte[]{0, 0});          // 结果格式码个数 = 0
            writeFrame('B', body.toByteArray());
        }

        /** Describe portal: 'D' + len + 'P' + portal\0 */
        void sendDescribePortal(String portal) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write('P');
            writeCString(body, portal);
            writeFrame('D', body.toByteArray());
        }

        /** Execute: 'E' + len + portal\0 + Int32(最大行数=0) */
        void sendExecute(String portal) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, portal);
            body.writeBytes(Utils.int32ToBytes(0));
            writeFrame('E', body.toByteArray());
        }

        void sendSync() throws Exception {
            writeFrame('S', new byte[0]);
        }

        /** CopyData: 'd' + len + 原始数据（像真实 COPY 数据那样以 \n 结尾） */
        void sendCopyData(String data) throws Exception {
            writeFrame('d', data.getBytes(StandardCharsets.UTF_8));
        }

        /** CopyDone: 'c' + len(4) */
        void sendCopyDone() throws Exception {
            writeFrame('c', new byte[0]);
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

        /** 读到指定类型的报文为止；返回该类型（中途连接关闭则抛异常）。 */
        char readUntilType(char expected) throws Exception {
            StringBuilder seen = new StringBuilder();
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException(
                            "连接在收到 '" + expected + "' 之前被关闭，已收到：" + seen);
                }
                char type = (char) (frame[0] & 0xFF);
                if (type == expected) {
                    return type;
                }
                seen.append(type);
            }
        }

        /** 读到 ReadyForQuery('Z')，返回沿途的报文类型序列。 */
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

    /** 便于失败时定位：把 ErrorResponse 的字段取出来（当前未使用，保留给后续诊断）。 */
    @SuppressWarnings("unused")
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
}
