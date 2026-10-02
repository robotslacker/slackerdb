package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

/**
 * CommandComplete 命令标签（command tag）的契约测试。
 *
 * <p><b>被测缺陷（规划文档 BUG-10）</b>：服务端靠字符串前缀猜 tag，猜不中一律回
 * {@code "UPDATE " + n}。于是 {@code DELETE}、各类 DDL、{@code SET}/{@code SHOW}、
 * {@code WITH ... SELECT} 全部被报成 {@code UPDATE <n>}，而它们本该是
 * {@code DELETE n}、{@code CREATE TABLE}（**不带数字**）、{@code SET}、{@code SELECT n}。</p>
 *
 * <p>tag 不是日志装饰：psql 的状态行与 {@code :ROW_COUNT}、asyncpg 的 {@code status} 字符串、
 * Npgsql/psycopg 的影响行数、以及驱动侧 {@code getUpdateCount()} 都直接解析它
 * （见 {@code dbdriver/.../CommandCompleteParser.java}：从末尾倒着最多找两组数字，
 * 末尾不是数字即按 0 行处理）。</p>
 *
 * <p>用裸 socket 逐语句断言 tag —— 只有读原始报文才能验证"有没有数字后缀"这种细节。</p>
 */
public class CommandTagTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("cmdtag");
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
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/cmdtag", "", "");
    }

    // ================================================================
    // 一、裸 socket：逐语句断言 tag 原文
    // ================================================================

    /** 建表并插入两行，供 DML/DDL 断言使用。 */
    private static void prepareData() throws Exception {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS tag_t");
            st.execute("CREATE TABLE tag_t(id INT, name VARCHAR)");
        }
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO tag_t VALUES (1, 'a')");
            st.execute("INSERT INTO tag_t VALUES (2, 'b')");
            st.execute("INSERT INTO tag_t VALUES (3, 'c')");
        }
    }

    /** 带行数后缀的语句：tag 必须形如 "<前缀> <数字>"。 */
    @Test
    void dmlAndSelectTagsCarryRowCount() throws Exception {
        prepareData();

        try (RawTagClient client = new RawTagClient()) {
            // SELECT：真实 PG 回 "SELECT 3"
            assertTag(client.query("SELECT id FROM tag_t"), "SELECT 3");

            // SELECT 无行：回 "SELECT 0"
            assertTag(client.query("SELECT id FROM tag_t WHERE id > 100"), "SELECT 0");

            // UPDATE
            assertTag(client.query("UPDATE tag_t SET name = 'x' WHERE id <= 2"), "UPDATE 2");

            // DELETE —— 当前实现错误地回 "UPDATE 1"
            assertTag(client.query("DELETE FROM tag_t WHERE id = 3"), "DELETE 1");

            // INSERT
            assertTag(client.query("INSERT INTO tag_t VALUES (9, 'z')"), "INSERT 0 1");

            // WITH ... SELECT（CTE）—— 当前实现错误地回 "UPDATE 1"
            assertTag(client.query("WITH c AS (SELECT id FROM tag_t) SELECT count(*) FROM c"),
                    "SELECT 1");

            // VALUES 也是查询系列
            assertTag(client.query("VALUES (1), (2)"), "SELECT 2");
        }
    }

    /** 不带行数后缀的语句：tag 必须是命令名本身、末尾不能是数字。 */
    @Test
    void ddlAndUtilityTagsCarryNoRowCount() throws Exception {
        prepareData();

        try (RawTagClient client = new RawTagClient()) {
            // DDL —— 当前实现错误地回 "UPDATE -1"
            assertTag(client.query("CREATE TABLE tag_new(id INT)"), "CREATE TABLE");
            assertTag(client.query("DROP TABLE tag_new"), "DROP TABLE");

            // 会话/工具类语句 —— 当前实现错误地回 "UPDATE -1"
            // 注意：SET 会被 SQLReplacer 改写成空语句、SHOW 会被改写成等价的 SELECT，
            // 因此标签必须基于客户端原文判断（本用例正是这条规则的回归保护）。
            assertTag(client.query("SET extra_float_digits = 3"), "SET");
            assertTag(client.query("SHOW search_path"), "SHOW");

            // CTAS：DDL 与查询的混合形态。这里只锁定"前缀是 CREATE TABLE"，
            // 不再让它退化成 "UPDATE -1"（具体是否带行数由 CommandTag 的规则决定）。
            String ctasTag = client.query("CREATE TABLE tag_ctas AS SELECT 1 AS a");
            assert ctasTag != null && ctasTag.startsWith("CREATE TABLE")
                    : "CTAS 的标签应以 CREATE TABLE 开头，实际 [" + ctasTag + "]";
        }
    }

    /** 事务控制语句：BEGIN / COMMIT / ROLLBACK。 */
    @Test
    void transactionTagsAreCorrect() throws Exception {
        prepareData();

        try (RawTagClient client = new RawTagClient()) {
            assertTag(client.query("BEGIN"), "BEGIN");
            assertTag(client.query("COMMIT"), "COMMIT");
            assertTag(client.query("BEGIN"), "BEGIN");
            assertTag(client.query("ROLLBACK"), "ROLLBACK");
        }
    }

    // ================================================================
    // 二、驱动侧：getUpdateCount / execute 的返回值
    // ================================================================

    /**
     * 驱动侧影响：{@code getUpdateCount()} 依赖 tag 末尾的数字，
     * 命令类型判断依赖 tag 的前缀（{@code Parser} 里的 {@code SqlCommandType}）。
     */
    @Test
    void updateCountMatchesStatementType() throws Exception {
        prepareData();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // DML：影响行数必须正确
            st.execute("UPDATE tag_t SET name = 'y' WHERE id <= 2");
            assert st.getUpdateCount() == 2
                    : "UPDATE 的 getUpdateCount() 应为 2，实际 " + st.getUpdateCount();

            // 额外插一行再删掉，确保 DELETE 真的影响 1 行
            st.execute("INSERT INTO tag_t VALUES (99, 'tmp')");
            st.execute("DELETE FROM tag_t WHERE id = 99");
            assert st.getUpdateCount() == 1
                    : "DELETE 的 getUpdateCount() 应为 1，实际 " + st.getUpdateCount();

            // DDL：PG 语义下没有行数（tag 不带数字 → 驱动解析为 0）
            st.execute("CREATE TABLE tag_ddl(id INT)");
            int ddlCount = st.getUpdateCount();
            assert ddlCount == 0
                    : "DDL 的 getUpdateCount() 应为 0（tag 不带数字），实际 " + ddlCount;

            st.execute("DROP TABLE tag_ddl");
        }
    }

    /** CTE 查询必须能被当作查询处理（tag 前缀为 SELECT）。 */
    @Test
    void cteQueryIsReportedAsSelect() throws Exception {
        prepareData();

        long expected = 0;
        try (Connection conn = connect(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM tag_t")) {
            rs.next();
            expected = rs.getLong(1);
        }

        try (Connection conn = connect(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "WITH c AS (SELECT id FROM tag_t) SELECT count(*) FROM c")) {
            assert rs.next() : "CTE 查询应返回结果集";
            assert rs.getLong(1) == expected
                    : "CTE 查询行数不符：期望 " + expected + "，实际 " + rs.getLong(1);
        }
    }

    // ================================================================
    // 辅助
    // ================================================================

    private static void assertTag(String actualTag, String expectedTag) {
        assert actualTag != null : "未收到 CommandComplete，期望 tag=" + expectedTag;
        assert expectedTag.equals(actualTag)
                : "命令标签不符：期望 [" + expectedTag + "]，实际 [" + actualTag + "]";
    }

    /** 极简客户端：发简单查询，返回 CommandComplete('C') 的 tag。 */
    private static final class RawTagClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;

        RawTagClient() throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", dbPort), 5_000);
            socket.setSoTimeout(8_000);
            in = socket.getInputStream();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(Utils.int32ToBytes(196608));
            writeCString(body, "user");
            writeCString(body, "test");
            writeCString(body, "database");
            writeCString(body, "cmdtag");
            body.write(0);
            byte[] bb = body.toByteArray();
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.writeBytes(Utils.int32ToBytes(bb.length + 4));
            pkt.writeBytes(bb);
            write(pkt.toByteArray());
            readUntilReadyForQuery();
        }

        /** 发送一条简单查询，返回其 CommandComplete 的 tag；没有 C 报文时返回 null。 */
        String query(String sql) throws Exception {
            byte[] sb = sql.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write('Q');
            frame.writeBytes(Utils.int32ToBytes(4 + sb.length + 1));
            frame.writeBytes(sb);
            frame.write(0);
            write(frame.toByteArray());

            StringBuilder seen = new StringBuilder();
            while (true) {
                byte[] f = readFrame();
                if (f == null) {
                    throw new IllegalStateException(
                            "连接在 ReadyForQuery 之前关闭；语句=[" + sql + "] 已收到：" + seen);
                }
                char type = (char) (f[0] & 0xFF);
                if (type == 'C') {
                    // tag = 报文体（去掉结尾 0）
                    int len = f.length - 5;
                    if (len > 0 && f[5 + len - 1] == 0) {
                        len--;
                    }
                    String tag = new String(f, 5, len, StandardCharsets.UTF_8);
                    // 读完这一回合剩余的 Z
                    readUntilReadyForQuery();
                    return tag;
                }
                if (type == 'Z') {
                    return null;
                }
                seen.append(type);
            }
        }

        private void readUntilReadyForQuery() throws Exception {
            while (true) {
                byte[] f = readFrame();
                if (f == null) {
                    throw new IllegalStateException("连接在 ReadyForQuery 之前关闭");
                }
                if ((char) (f[0] & 0xFF) == 'Z') {
                    return;
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

        private void write(byte[] data) throws Exception {
            socket.getOutputStream().write(data);
            socket.getOutputStream().flush();
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
