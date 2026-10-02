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
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLSTATE 契约测试（规划文档 BUG-7）。
 *
 * <p><b>被测缺陷</b>：服务端把 DuckDB 的 {@code SQLException.getErrorCode()} 数字直接填进
 * {@code ErrorResponse} 的 {@code 'C'}（SQLSTATE）字段。实测 DuckDB 的 {@code getSQLState()} 恒为
 * {@code null}、{@code getErrorCode()} 恒为 {@code 0}，于是<b>所有错误在客户端看来都是同一个码
 * {@code "0"}</b>：唯一约束冲突、NOT NULL、表不存在、语法错误、类型转换失败——全都分不出来。</p>
 *
 * <p>后果是客户端所有"按错误码分支"的逻辑失效：重试（40001/40P01）、幂等/upsert 兜底（23505）、
 * ORM 异常翻译（Spring/Hibernate 按五字符码映射，匹配不上就落到 UncategorizedSQLException）、
 * 连接池健康检查（08xxx）等。</p>
 *
 * <p>本文件按错误种类断言 PG 标准 SQLSTATE，并且**简单查询（'Q'）与扩展协议两条路径都要覆盖**
 * ——它们各自有独立的错误出口（QueryRequest / ParseRequest / ExecuteRequest）。</p>
 */
public class SqlStateTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("sqlstate");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE ss_parent(id INTEGER PRIMARY KEY)");
            st.execute("INSERT INTO ss_parent VALUES (1)");

            st.execute("CREATE OR REPLACE TABLE ss_child("
                    + " id INTEGER PRIMARY KEY,"
                    + " pid INTEGER NOT NULL REFERENCES ss_parent(id),"
                    + " amount INTEGER CHECK (amount > 0))");
            st.execute("INSERT INTO ss_child VALUES (100, 1, 5)");
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/sqlstate", "", "");
    }

    /** 执行一条语句，返回它的 SQLSTATE；没报错返回 {@code <no error>}。 */
    private static String sqlStateOf(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
            return "<no error>";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static void assertState(String expected, String actual, String scenario) {
        assert expected.equals(actual)
                : scenario + " 的 SQLSTATE 应为 " + expected + "（PG 标准码），实际 " + actual;
    }

    // ================================================================
    // 一、约束类错误（扩展协议路径：ExecutonRequest）
    // ================================================================

    @Test
    void constraintViolationsReportStandardSqlStates() throws Exception {
        try (Connection conn = connect()) {
            // 主键/唯一冲突 → 23505 unique_violation
            assertState("23505", sqlStateOf(conn, "INSERT INTO ss_child VALUES (100, 1, 5)"),
                    "主键冲突");
            // NOT NULL → 23502 not_null_violation
            assertState("23502", sqlStateOf(conn, "INSERT INTO ss_child VALUES (101, NULL, 5)"),
                    "NOT NULL 违反");
            // CHECK → 23514 check_violation
            assertState("23514", sqlStateOf(conn, "INSERT INTO ss_child VALUES (102, 1, -1)"),
                    "CHECK 违反");
            // 外键 → 23503 foreign_key_violation
            assertState("23503", sqlStateOf(conn, "INSERT INTO ss_child VALUES (103, 999, 5)"),
                    "外键违反");
        }
    }

    // ================================================================
    // 二、目录 / 绑定 / 语法 / 转换类错误
    // ================================================================

    @Test
    void catalogBindSyntaxAndConversionErrorsReportStandardSqlStates() throws Exception {
        try (Connection conn = connect()) {
            assertState("42P01", sqlStateOf(conn, "SELECT * FROM no_such_table"),
                    "表不存在");
            assertState("42703", sqlStateOf(conn, "SELECT no_such_column FROM ss_parent"),
                    "列不存在");
            assertState("42601", sqlStateOf(conn, "SELEC 1"),
                    "语法错误");
            assertState("22P02", sqlStateOf(conn, "SELECT CAST('abc' AS INTEGER)"),
                    "类型转换失败");
        }
    }

    // ================================================================
    // 三、简单查询路径（'Q'）：线协议上的 'C' 字段
    // ================================================================

    @Test
    void simpleQueryPathReportsStandardSqlStates() throws Exception {
        try (RawClient client = new RawClient()) {
            assertState("23505", client.sqlStateOf("INSERT INTO ss_child VALUES (100, 1, 5)"),
                    "简单查询-主键冲突");
            assertState("42P01", client.sqlStateOf("SELECT * FROM no_such_table"),
                    "简单查询-表不存在");
            assertState("42601", client.sqlStateOf("SELEC 1"),
                    "简单查询-语法错误");
        }
    }

    // ================================================================
    // 四、Bind 路径（参数绑定阶段的 DuckDB 错误）
    // ================================================================

    /**
     * 参数绑定阶段的错误也要有标准码 —— 这条路径单独有错误出口，历史上一度漏掉：
     * Bind 时若给多了参数值，DuckDB 会抛 "Parameter index out of bounds"，
     * 属于协议层面的参数不匹配 → {@code 08P01}（protocol_violation）。
     */
    @Test
    void bindPathErrorsReportStandardSqlStates() throws Exception {
        try (RawClient client = new RawClient()) {
            // 语句只要 1 个参数，Bind 却给 2 个值
            String state = client.extendedSqlStateOf("INSERT INTO ss_parent VALUES (?)", "999", "888");
            assertState("08P01", state, "Bind 参数个数不匹配");
        }
        // 失败的绑定不能写入数据，且会话仍可用
        try (Connection conn = connect()) {
            assert "<no error>".equals(sqlStateOf(conn, "SELECT 1")) : "出错后会话不可用";
        }
    }

    // ================================================================
    // 五、COPY 路径：选项错误 / 目标表不存在
    // ================================================================
    @Test
    void copyErrorsUseStandardSqlStates() throws Exception {
        try (Connection conn = connect()) {
            CopyManager copyManager = new CopyManager((BaseConnection) conn);

            // COPY 选项值非法 → 22023 invalid_parameter_value
            String badOption = null;
            try {
                copyManager.copyIn("COPY ss_child FROM STDIN WITH (FORMAT 'parquet')", new StringReader(""));
            } catch (SQLException e) {
                badOption = e.getSQLState();
            }
            assertState("22023", badOption, "COPY 选项非法");

            // 目标表不存在 → 42P01 undefined_table
            String missingTable = null;
            try {
                copyManager.copyIn("COPY no_such_table FROM STDIN WITH (FORMAT csv)", new StringReader(""));
            } catch (SQLException e) {
                missingTable = e.getSQLState();
            }
            assertState("42P01", missingTable, "COPY 目标表不存在");
        }
    }

    // ================================================================
    // 五、反向约束：本来就是标准码的路径不能被改坏
    // ================================================================

    /** 失败事务块（手写 25P02）必须保持标准码 —— 走简单查询路径，与既有事务语义用例一致。 */
    @Test
    void alreadyStandardStatesAreKept() throws Exception {
        try (RawClient client = new RawClient()) {
            client.sqlStateOf("BEGIN");
            // 事务内语句失败（这条本身也要在修复后带上标准码 42P01）
            assertState("42P01", client.sqlStateOf("SELECT * FROM no_such_table"), "事务内表不存在");
            // 失败事务块内的普通语句必须被拒绝（手写 25P02，不能被本次改动改坏）
            assertState("25P02", client.sqlStateOf("SELECT 1"), "失败事务块内语句被拒绝");
            client.sqlStateOf("ROLLBACK");
        }
    }

    // ================================================================
    // 极简线协议客户端（简单查询，只关心 ErrorResponse 的 'C' 字段）
    // ================================================================

    private static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;

        RawClient() throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", dbPort), 5_000);
            socket.setSoTimeout(8_000);
            in = socket.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(Utils.int32ToBytes(196608));
            writeCString(body, "user");
            writeCString(body, "test");
            writeCString(body, "database");
            writeCString(body, "sqlstate");
            body.write(0);
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.writeBytes(Utils.int32ToBytes(bodyBytes.length + 4));
            packet.writeBytes(bodyBytes);
            write(packet.toByteArray());
            readUntilReadyForQuery();
        }

        /** 执行一条简单查询，返回 ErrorResponse 的 'C'（SQLSTATE）；没报错返回 {@code <no error>}。 */
        String sqlStateOf(String sql) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, sql);
            writeFrame('Q', body.toByteArray());
            return readUntilReadyForQueryState();
        }

        /**
         * 走扩展协议（Parse + Bind + Execute + Sync）执行一条带参数的语句，
         * 参数一律按文本格式发送；返回 ErrorResponse 的 SQLSTATE。
         */
        String extendedSqlStateOf(String sql, String... values) throws Exception {
            // Parse：不声明参数类型（0 表示由服务端处理）
            ByteArrayOutputStream parseBody = new ByteArrayOutputStream();
            writeCString(parseBody, "");
            writeCString(parseBody, sql);
            parseBody.writeBytes(Utils.int16ToBytes((short) 0));
            writeFrame('P', parseBody.toByteArray());

            // Bind：全文本格式 + values.length 个值
            ByteArrayOutputStream bindBody = new ByteArrayOutputStream();
            writeCString(bindBody, "");
            writeCString(bindBody, "");
            bindBody.writeBytes(Utils.int16ToBytes((short) 0));   // 参数格式码个数 = 0（全文本）
            bindBody.writeBytes(Utils.int16ToBytes((short) values.length));
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                bindBody.writeBytes(Utils.int32ToBytes(bytes.length));
                bindBody.writeBytes(bytes);
            }
            bindBody.writeBytes(Utils.int16ToBytes((short) 0));   // 结果格式码个数 = 0
            writeFrame('B', bindBody.toByteArray());

            // Execute
            ByteArrayOutputStream executeBody = new ByteArrayOutputStream();
            writeCString(executeBody, "");
            executeBody.writeBytes(Utils.int32ToBytes(0));
            writeFrame('E', executeBody.toByteArray());

            sendSync();
            return readUntilReadyForQueryState();
        }

        private String readUntilReadyForQueryState() throws Exception {
            String state = null;
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException("连接在 ReadyForQuery 之前被关闭");
                }
                char type = (char) (frame[0] & 0xFF);
                if (type == 'E' && state == null) {
                    state = field(frame, 'C');
                }
                if (type == 'Z') {
                    return state == null ? "<no error>" : state;
                }
            }
        }

        void sendSync() throws Exception {
            writeFrame('S', new byte[0]);
        }

        private static String field(byte[] frame, char wanted) {
            int pos = 5;
            while (pos < frame.length && frame[pos] != 0) {
                char fieldType = (char) (frame[pos] & 0xFF);
                pos++;
                int start = pos;
                while (pos < frame.length && frame[pos] != 0) {
                    pos++;
                }
                if (fieldType == wanted) {
                    return new String(frame, start, pos - start, StandardCharsets.UTF_8);
                }
                pos++;
            }
            return "";
        }

        private void readUntilReadyForQuery() throws Exception {
            while (true) {
                byte[] frame = readFrame();
                if (frame == null || (char) (frame[0] & 0xFF) == 'Z') {
                    return;
                }
            }
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
