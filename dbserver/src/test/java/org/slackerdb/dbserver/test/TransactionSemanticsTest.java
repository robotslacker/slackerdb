package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

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
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 事务语义批次验收测试。
 *
 * <p>覆盖两个同源缺陷：</p>
 * <ul>
 *   <li><b>BUG-7</b>：客户端在事务块中优雅断开（Terminate）时，未提交的改动被 <b>commit</b>
 *       而不是回滚 —— 用户没提交的数据被静默落库；</li>
 *   <li><b>BUG-15</b>：{@code ReadyForQuery} 永远只回 'I'/'T'，事务块内出错后仍报 'T'，
 *       既不告知客户端事务已失败，也不拒绝后续语句。</li>
 * </ul>
 *
 * <p>BUG-15 只有协议层可见（事务状态字节），因此这里用<b>裸 socket</b> 直接读 'Z' 报文，
 * 而不是通过 JDBC 间接推断。BUG-7 用普通 JDBC 即可观测（重连后数行数）。</p>
 */
public class TransactionSemanticsTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("txsem");
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

    // ---------------------------------------------------------------- JDBC 辅助

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/txsem", "", "");
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static long countRows(String table) throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void ensureTable(String table) throws SQLException {
        try (Connection conn = connect()) {
            exec(conn, "CREATE TABLE IF NOT EXISTS " + table + "(id INT)");
        }
    }

    // ---------------------------------------------------------------- BUG-7

    /**
     * 核心：客户端在事务块中优雅断开（Terminate）时，未提交的改动必须被回滚。
     *
     * <p>修复前 {@code closeSession()} 会 commit，本用例会读到 2 而不是 1。</p>
     */
    @Test
    void gracefulDisconnectInsideTransactionRollsBack() throws Exception {
        ensureTable("tx_close_1");

        Connection conn = connect();
        Statement st = conn.createStatement();
        st.execute("INSERT INTO tx_close_1 VALUES (1)");   // 自动提交
        st.execute("BEGIN");
        st.execute("INSERT INTO tx_close_1 VALUES (2)");   // 事务内，未提交
        st.close();
        conn.close();                                     // pgjdbc 发送 Terminate('X')

        // 等待服务端完成会话清理
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            if (countRows("tx_close_1") == 1) {
                break;
            }
        }
        assert countRows("tx_close_1") == 1
                : "事务中未提交的改动被提交了（BUG-7）：期望 1 行，实际 " + countRows("tx_close_1");
    }

    /** 同一场景的 autoCommit=false 变体（pgjdbc 会自动发 BEGIN）。 */
    @Test
    void autoCommitFalseDisconnectRollsBack() throws Exception {
        ensureTable("tx_close_2");

        Connection conn = connect();
        conn.setAutoCommit(false);
        exec(conn, "INSERT INTO tx_close_2 VALUES (1)");   // 未提交
        conn.close();

        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && countRows("tx_close_2") != 0) {
            Thread.sleep(100);
        }
        assert countRows("tx_close_2") == 0
                : "autoCommit=false 下未提交的改动被提交了：期望 0 行，实际 " + countRows("tx_close_2");
    }

    /** 防护性用例：已提交的数据必须保留，避免"一刀切全回滚"的过度修复。 */
    @Test
    void committedDataSurvivesDisconnect() throws Exception {
        ensureTable("tx_close_3");

        Connection conn = connect();
        Statement st = conn.createStatement();
        st.execute("BEGIN");
        st.execute("INSERT INTO tx_close_3 VALUES (1)");
        st.execute("COMMIT");
        st.close();
        conn.close();

        Thread.sleep(300);
        assert countRows("tx_close_3") == 1
                : "已 COMMIT 的数据在断开后丢失了：期望 1 行，实际 " + countRows("tx_close_3");
    }

    /** 防护性用例：显式 ROLLBACK 后断开，数据必须不在。 */
    @Test
    void rollbackThenDisconnectKeepsNothing() throws Exception {
        ensureTable("tx_close_4");

        Connection conn = connect();
        Statement st = conn.createStatement();
        st.execute("BEGIN");
        st.execute("INSERT INTO tx_close_4 VALUES (1)");
        st.execute("ROLLBACK");
        st.close();
        conn.close();

        Thread.sleep(300);
        assert countRows("tx_close_4") == 0
                : "ROLLBACK 后数据仍然存在：实际 " + countRows("tx_close_4");
    }

    /** 防护性用例：COPY 正常完成（有 CopyDone）后断开，数据必须完整保留。 */
    @Test
    void normalCopySurvivesDisconnect() throws Exception {
        ensureTable("tx_copy_ok");

        try (Connection conn = connect()) {
            // pgjdbc 的 CopyManager 需要 core.BaseConnection
            org.postgresql.core.BaseConnection base = conn.unwrap(org.postgresql.core.BaseConnection.class);
            org.postgresql.copy.CopyManager cm = new org.postgresql.copy.CopyManager(base);
            cm.copyIn("COPY tx_copy_ok FROM STDIN (FORMAT CSV)",
                    new java.io.StringReader("1\n2\n"));
        }

        Thread.sleep(300);
        assert countRows("tx_copy_ok") == 2
                : "正常完成的 COPY 数据丢失：期望 2 行，实际 " + countRows("tx_copy_ok");
    }

    // ---------------------------------------------------------------- BUG-15（裸 socket）

    /** 一个极简的 PG 线协议客户端：只做 Startup + 简单查询，并解析回包。 */
    private static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;

        RawClient() throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", dbPort), 5_000);
            socket.setSoTimeout(10_000);
            in = socket.getInputStream();
        }

        /** 发送 StartupMessage 并读到第一个 ReadyForQuery，返回其事务状态字节。 */
        byte startup() throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(Utils.int32ToBytes(196608));
            body.writeBytes("user".getBytes(StandardCharsets.UTF_8));
            body.write(0);
            body.writeBytes("test".getBytes(StandardCharsets.UTF_8));
            body.write(0);
            body.writeBytes("database".getBytes(StandardCharsets.UTF_8));
            body.write(0);
            body.writeBytes("txsem".getBytes(StandardCharsets.UTF_8));
            body.write(0);
            body.write(0);

            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            byte[] bodyBytes = body.toByteArray();
            packet.writeBytes(Utils.int32ToBytes(bodyBytes.length + 4));
            packet.writeBytes(bodyBytes);
            socket.getOutputStream().write(packet.toByteArray());
            socket.getOutputStream().flush();

            return readUntilReadyForQuery().readyForQuery;
        }

        /** 发送一条简单查询（'Q'），返回这次应答的 ErrorResponse 个数与最终事务状态字节。 */
        QueryResult query(String sql) throws Exception {
            byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write('Q');
            frame.writeBytes(Utils.int32ToBytes(4 + sqlBytes.length + 1));
            frame.writeBytes(sqlBytes);
            frame.write(0);
            socket.getOutputStream().write(frame.toByteArray());
            socket.getOutputStream().flush();
            return readUntilReadyForQuery();
        }

        private static final class QueryResult {
            int errorCount;
            byte readyForQuery;
            String errorCode = "";
            String errorMessage = "";
        }

        private QueryResult readUntilReadyForQuery() throws Exception {
            QueryResult result = new QueryResult();
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException("连接在 ReadyForQuery 之前被关闭");
                }
                char type = (char) (frame[0] & 0xFF);
                if (type == 'Z') {
                    result.readyForQuery = frame[5];
                    return result;
                }
                if (type == 'E') {
                    result.errorCount++;
                    for (var entry : parseErrorFields(frame).entrySet()) {
                        if (entry.getKey() == 'C') {
                            result.errorCode = entry.getValue();
                        }
                        if (entry.getKey() == 'M') {
                            result.errorMessage = entry.getValue();
                        }
                    }
                }
            }
        }

        private byte[] readFrame() throws Exception {
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

        private static java.util.Map<Character, String> parseErrorFields(byte[] frame) {
            java.util.Map<Character, String> fields = new java.util.LinkedHashMap<>();
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

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    /** 事务状态字节必须覆盖 'I' / 'T' / 'E' 三种取值。 */
    @Test
    void readyForQueryReportsAllThreeTransactionStates() throws Exception {
        try (RawClient client = new RawClient()) {
            byte idle = client.startup();
            assert idle == 'I' : "空闲状态应为 'I'，实际 '" + (char) idle + "'";

            byte inTx = client.query("BEGIN").readyForQuery;
            assert inTx == 'T' : "BEGIN 之后应为 'T'，实际 '" + (char) inTx + "'";

            // 事务块内制造一次失败（表不存在）
            RawClient.QueryResult failed = client.query("SELECT * FROM no_such_table_for_failed_tx");
            assert failed.errorCount >= 1 : "失败的语句应当返回 ErrorResponse";
            assert failed.readyForQuery == 'E'
                    : "事务块内语句失败后 ReadyForQuery 应为 'E'（BUG-15），实际 '"
                            + (char) failed.readyForQuery + "'";

            byte afterRollback = client.query("ROLLBACK").readyForQuery;
            assert afterRollback == 'I' : "ROLLBACK 之后应回到 'I'，实际 '" + (char) afterRollback + "'";
        }
    }

    /** 失败事务块内，后续语句必须被拒绝并保持 'E'，直到事务块结束。 */
    @Test
    void statementsAreRejectedInsideFailedTransaction() throws Exception {
        try (RawClient client = new RawClient()) {
            client.startup();
            assert client.query("BEGIN").readyForQuery == 'T';

            RawClient.QueryResult failed = client.query("SELECT * FROM no_such_table_2");
            assert failed.readyForQuery == 'E' : "进入失败事务块失败";

            // 失败事务块内的普通语句：必须被拒绝（25P02），且状态保持 'E'
            RawClient.QueryResult rejected = client.query("SELECT 1");
            assert rejected.errorCount >= 1
                    : "失败事务块内的语句没有被拒绝（BUG-15 只改状态不改行为是不完整的）";
            assert "25P02".equals(rejected.errorCode)
                    : "拒绝失败事务块内语句应使用 SQLSTATE 25P02，实际 [" + rejected.errorCode + "]";
            assert rejected.readyForQuery == 'E'
                    : "拒绝之后仍应保持 'E'，实际 '" + (char) rejected.readyForQuery + "'";

            // 但 ROLLBACK 必须被放行，用来把会话解救出来
            RawClient.QueryResult rescued = client.query("ROLLBACK");
            assert rescued.readyForQuery == 'I'
                    : "失败事务块内 ROLLBACK 应被放行并回到 'I'，实际 '"
                            + (char) rescued.readyForQuery + "'";

            // 恢复后普通语句可用
            RawClient.QueryResult normal = client.query("SELECT 1");
            assert normal.errorCount == 0 : "ROLLBACK 之后语句应恢复正常";
            assert normal.readyForQuery == 'I';
        }
    }

    /** 自动提交模式下的语句失败不得让会话进入失败事务状态。 */
    @Test
    void failureOutsideTransactionDoesNotMarkSessionFailed() throws Exception {
        try (RawClient client = new RawClient()) {
            client.startup();

            RawClient.QueryResult failed = client.query("SELECT * FROM no_such_table_3");
            assert failed.errorCount >= 1;
            assert failed.readyForQuery == 'I'
                    : "自动提交模式下语句失败不应进入失败事务块，实际 '"
                            + (char) failed.readyForQuery + "'";

            RawClient.QueryResult normal = client.query("SELECT 1");
            assert normal.errorCount == 0 : "自动提交模式下失败后应立即恢复正常";
        }
    }

    /** 失败事务块内提交：PG 语义视为 ROLLBACK，会话回到 'I'。 */
    @Test
    void commitInsideFailedTransactionEndsTransactionBlock() throws Exception {
        try (RawClient client = new RawClient()) {
            client.startup();
            assert client.query("BEGIN").readyForQuery == 'T';
            assert client.query("SELECT * FROM no_such_table_4").readyForQuery == 'E';

            RawClient.QueryResult committed = client.query("COMMIT");
            assert committed.readyForQuery == 'I'
                    : "失败事务块内 COMMIT 应结束事务块（PG 视为 ROLLBACK），实际 '"
                            + (char) committed.readyForQuery + "'";

            assert client.query("SELECT 1").errorCount == 0 : "COMMIT 之后语句应恢复正常";
        }
    }

    // ---------------------------------------------------------------- 协议级错误也必须中止事务块

    /**
     * 扩展协议：<b>Parse 阶段</b>就失败的语句也必须让事务块进入 aborted 状态。
     *
     * <p>"表不存在"这类错误 DuckDB 在 {@code prepareStatement} 时就抛出，走的是
     * {@code ParseRequest} 的错误分支。改造前只有简单查询与 Execute 两条路径会调用
     * {@code markTransactionFailed()}，于是这里的事务块不会被中止：
     * ReadyForQuery 仍报 'T'，后续语句也不会被 25P02 拒绝，而是照常执行。</p>
     */
    @Test
    void parseFailureInsideTransactionAbortsBlock() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "txsem", 10_000)) {
            client.sendQuery("BEGIN");
            assertEquals('T', PgWireClient.readyStatus(client.readUntilReadyForQuery()),
                    "BEGIN 之后应为 'T'");

            // Parse 阶段失败（表不存在）
            client.sendParse("s_missing", "SELECT * FROM no_such_table_in_tx");
            client.sendSync();
            List<PgWireClient.Frame> failed = client.readUntilReadyForQuery();
            assertNotNull(PgWireClient.errorField(failed, 'C'), "Parse 失败应当回 ErrorResponse");
            assertEquals('E', PgWireClient.readyStatus(failed),
                    "Parse 阶段失败后事务块必须中止（ReadyForQuery = 'E'）");

            // 后续普通语句必须被 25P02 拒绝，而不是照常执行
            client.sendQuery("SELECT 1");
            List<PgWireClient.Frame> rejected = client.readUntilReadyForQuery();
            assertEquals("25P02", PgWireClient.errorField(rejected, 'C'),
                    "失败事务块内的语句必须被 25P02 拒绝");

            // ROLLBACK 仍能把会话救回来
            client.sendQuery("ROLLBACK");
            assertEquals('I', PgWireClient.readyStatus(client.readUntilReadyForQuery()),
                    "ROLLBACK 之后应回到 'I'");
        }
    }

    /**
     * 扩展协议：<b>Bind 阶段</b>失败的语句同样必须中止事务块。
     *
     * <p>构造方式：Parse 声明参数是 int4，Bind 却只发 3 字节二进制 → Bind 报 22P03。</p>
     */
    @Test
    void bindFailureInsideTransactionAbortsBlock() throws Exception {
        try (PgWireClient client = new PgWireClient("127.0.0.1", dbPort, "txsem", 10_000)) {
            client.sendQuery("BEGIN");
            assertEquals('T', PgWireClient.readyStatus(client.readUntilReadyForQuery()));

            client.sendParse("s_bin", "SELECT $1::INTEGER", 23);   // 23 = int4
            client.sendBindWithBinaryParam("p_bin", "s_bin", new byte[]{0x01, 0x02, 0x03});
            client.sendSync();
            List<PgWireClient.Frame> failed = client.readUntilReadyForQuery();
            assertNotNull(PgWireClient.errorField(failed, 'C'), "Bind 失败应当回 ErrorResponse");
            assertEquals('E', PgWireClient.readyStatus(failed),
                    "Bind 阶段失败后事务块必须中止（ReadyForQuery = 'E'）");

            client.sendQuery("SELECT 1");
            assertEquals("25P02", PgWireClient.errorField(client.readUntilReadyForQuery(), 'C'),
                    "失败事务块内的语句必须被 25P02 拒绝");

            client.sendQuery("ROLLBACK");
            assertEquals('I', PgWireClient.readyStatus(client.readUntilReadyForQuery()));
        }
    }
}
