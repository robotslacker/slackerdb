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
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 扩展协议元数据契约测试：Describe 的即时应答（BUG-8）与 ParameterDescription（BUG-9）。
 *
 * <p><b>BUG-8</b>：{@code DescribeRequest.process()} 只置 {@code hasDescribeRequest=true}，
 * 把 RowDescription/NoData 推迟到 Execute 才发。于是"只 Describe 不 Execute"的客户端
 * （协议上完全合法）永远等不到回应 —— 在真实客户端上表现为同步阻塞/最终超时。</p>
 *
 * <p><b>BUG-9</b>：全仓没有 {@code ParameterDescription} 报文类。协议要求
 * {@code Describe('S')} 先回 ParameterDescription（参数个数 + 每个参数的 OID），
 * 再回 RowDescription/NoData；缺了它，驱动侧 {@code getParameterMetaData()} 拿不到参数类型。</p>
 *
 * <p><b>报文格式必须与真实驱动一致</b>：{@code Describe} 的名称字段是 NUL 结尾的
 * {@code String}，pgjdbc 的 {@code sendDescribePortal()} 会显式写一个 0 字节
 * （{@code pgStream.sendChar(0)}）。裸协议客户端若漏掉这个 0，就会掩盖服务端
 * "没按协议吃掉结尾 0"的解析缺陷 —— 而该缺陷在真实驱动下会让门户名变成
 * {@code "\0"}、查不到门户、回 NoData，最终驱动抛
 * {@code IllegalStateException: Received resultset tuples, but no field structure for them}。</p>
 */
public class DescribeMetadataTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("dmeta");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t(id INT, name VARCHAR)");
            st.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/dmeta", "", "");
    }

    // ================================================================
    // 一、只 Describe、不 Execute：必须立刻收到应答
    // ================================================================

    /**
     * 核心复现：Parse + Describe(语句) + Sync，**不发 Execute**。
     *
     * <p>期望报文序列：{@code 1}（ParseComplete）→ {@code t}（ParameterDescription）
     * → {@code T}（RowDescription）→ {@code Z}（ReadyForQuery）。</p>
     *
     * <p>修复前：只有 {@code 1} 和 {@code Z} —— Describe 的应答被推迟到 Execute，
     * 客户端拿不到行描述；若客户端此时就等待 T/t，就会挂住。</p>
     */
    @Test
    void describeStatementAnswersImmediatelyWithParameterDescription() throws Exception {
        try (RawDescribeClient client = new RawDescribeClient()) {
            client.sendParse("", "SELECT id FROM t WHERE id > $1");
            client.sendDescribeStatement("");
            client.sendSync();

            String types = client.readUntilReadyForQuery();

            assert types.indexOf('t') >= 0
                    : "Describe('S') 必须回 ParameterDescription('t')（BUG-9），实际序列=" + types;
            assert types.indexOf('T') >= 0
                    : "Describe('S') 必须回 RowDescription('T')（BUG-8），实际序列=" + types;

            int tPos = types.indexOf('t');
            int tPosRow = types.indexOf('T');
            assert tPos < tPosRow
                    : "ParameterDescription 必须在 RowDescription 之前，实际序列=" + types;

            // 参数个数必须为 1（$1）
            assert client.lastParameterCount == 1
                    : "ParameterDescription 的参数个数应为 1，实际 " + client.lastParameterCount;
            // 参数 OID 必须是一个已知类型（非 0/UNKNOWN），驱动据此绑定参数
            assert client.lastParameterOids.length == 1 && client.lastParameterOids[0] != 0
                    : "参数 OID 不应为 0（UNKNOWN），实际 " + java.util.Arrays.toString(client.lastParameterOids);
        }
    }

    /** 无参数的语句：ParameterDescription 必须带 0 个参数（而不是不回）。 */
    @Test
    void describeStatementWithoutParametersSendsEmptyParameterDescription() throws Exception {
        try (RawDescribeClient client = new RawDescribeClient()) {
            client.sendParse("", "SELECT id, name FROM t");
            client.sendDescribeStatement("");
            client.sendSync();

            String types = client.readUntilReadyForQuery();
            assert types.indexOf('t') >= 0
                    : "无参数语句也必须回 ParameterDescription（参数个数 0），实际序列=" + types;
            assert client.lastParameterCount == 0
                    : "无参数语句的 ParameterDescription 参数个数应为 0，实际 " + client.lastParameterCount;
            assert types.indexOf('T') >= 0 : "应回 RowDescription，实际序列=" + types;
        }
    }

    /** 非查询语句：Describe('S') 之后应是 NoData('n')，且必须先有 ParameterDescription。 */
    @Test
    void describeNonQueryStatementAnswersNoData() throws Exception {
        try (RawDescribeClient client = new RawDescribeClient()) {
            client.sendParse("", "CREATE TABLE dmeta_ddl(id INT)");
            client.sendDescribeStatement("");
            client.sendSync();

            String types = client.readUntilReadyForQuery();
            assert types.indexOf('t') >= 0
                    : "Describe('S') 必须回 ParameterDescription，实际序列=" + types;
            assert types.indexOf('n') >= 0
                    : "非查询语句的 Describe 应回 NoData('n')，实际序列=" + types;
            assert types.indexOf('T') < 0
                    : "非查询语句不应回 RowDescription，实际序列=" + types;
        }
    }

    /** Describe('P')（门户）：协议上**不**回 ParameterDescription，只回 RowDescription/NoData。 */
    @Test
    void describePortalDoesNotSendParameterDescription() throws Exception {
        try (RawDescribeClient client = new RawDescribeClient()) {
            client.sendParse("", "SELECT id FROM t");
            client.sendBind("", "");
            client.sendDescribePortal("");
            client.sendSync();

            String types = client.readUntilReadyForQuery();
            assert types.indexOf('T') >= 0
                    : "Describe('P') 必须回 RowDescription('T')，实际序列=" + types;
            assert types.indexOf('t') < 0
                    : "Describe('P') 不应回 ParameterDescription，实际序列=" + types;

            // 关键点：Describe('P') 也必须立即应答，不能推迟到 Execute
            // （若被推迟，这里就只有 '2' 和 'Z'）
            assert types.indexOf('T') >= 0 && types.indexOf('T') < types.indexOf('Z')
                    : "Describe('P') 的应答被推迟了，实际序列=" + types;
        }
    }

    /**
     * 命名语句 + 命名门户，报文按驱动原样带上名称结尾的 0 字节。
     *
     * <p>这条用例复现的是真实驱动下的连锁失败：服务端若把结尾 0 当成名称的一部分，
     * 门户名会变成 {@code "p2\0"}，按 {@code "Portal-p2\0"} 查不到 Parse/Bind 建立的门户，
     * 于是回 NoData；驱动拿到 DataRow 却没有字段结构，直接抛
     * {@code IllegalStateException: Received resultset tuples, but no field structure for them}。</p>
     */
    @Test
    void describeNamedStatementAndPortalWithDriverFraming() throws Exception {
        try (RawDescribeClient client = new RawDescribeClient()) {
            client.sendParse("s_named", "SELECT id FROM t WHERE id > 1");
            client.sendDescribeStatement("s_named");
            client.sendSync();

            String statementTypes = client.readUntilReadyForQuery();
            assert statementTypes.indexOf('t') >= 0 && statementTypes.indexOf('T') >= 0
                    : "命名语句的 Describe('S') 应回 't' + 'T'，实际序列=" + statementTypes;

            client.sendBind("p_named", "s_named");
            client.sendDescribePortal("p_named");
            client.sendSync();

            String portalTypes = client.readUntilReadyForQuery();
            assert portalTypes.indexOf('T') >= 0
                    : "命名门户的 Describe('P') 应回 RowDescription，实际序列=" + portalTypes;
            assert portalTypes.indexOf('n') < 0
                    : "命名门户的 Describe('P') 不应回 NoData，实际序列=" + portalTypes;
        }
    }

    // ================================================================
    // 二、无结果集的语句必须回 NoData（而不是元数据里的伪列）
    // ================================================================

    /**
     * DuckDB 的 JDBC 对"不返回结果集"的语句也会给出 1 列假元数据
     * （DML → {@code Count:BIGINT}，工具类 → {@code Success:BOOLEAN}）。
     *
     * <p>如果 Describe 直接照搬 {@code getMetaData()}，{@code INSERT}/{@code SET} 就会被
     * 描述成"有一个结果集"，驱动随后会认为语句返回了结果集
     * （{@code Statement.execute()} 返回 true，{@code executeUpdate()} 抛
     * "A result was returned when none was expected"）。
     * 改造前这些语句在 Execute 阶段由 {@code ps.execute()} 的布尔值判定，回的是 NoData，
     * 所以这里必须保持一致。</p>
     */
    @Test
    void describeInsertWithoutReturningAnswersNoData() throws Exception {
        try (RawDescribeClient client = new RawDescribeClient()) {
            client.sendParse("", "INSERT INTO t VALUES (3, 'c')");
            client.sendDescribeStatement("");
            client.sendSync();

            String types = client.readUntilReadyForQuery();
            assert types.indexOf('t') >= 0 : "应回 ParameterDescription，实际序列=" + types;
            assert types.indexOf('n') >= 0
                    : "INSERT（无 RETURNING）的 Describe 应回 NoData，实际序列=" + types;
            assert types.indexOf('T') < 0
                    : "INSERT（无 RETURNING）不应回 RowDescription（伪列 Count:BIGINT），实际序列=" + types;
        }
    }

    /** 工具类语句（SET/BEGIN）同样没有结果集，必须回 NoData。 */
    @Test
    void describeUtilityStatementAnswersNoData() throws Exception {
        try (RawDescribeClient client = new RawDescribeClient()) {
            client.sendParse("", "SET extra_float_digits = 3");
            client.sendDescribeStatement("");
            client.sendSync();

            String types = client.readUntilReadyForQuery();
            assert types.indexOf('t') >= 0 : "应回 ParameterDescription，实际序列=" + types;
            assert types.indexOf('n') >= 0
                    : "SET 的 Describe 应回 NoData，实际序列=" + types;
            assert types.indexOf('T') < 0
                    : "SET 不应回 RowDescription（伪列 Success:BOOLEAN），实际序列=" + types;
        }
    }

    /**
     * 反向约束：{@code INSERT ... RETURNING} 是**要**返回结果集的，
     * 不能被"抑制伪列"的规则误伤成 NoData。
     */
    @Test
    void describeInsertReturningAnswersRowDescription() throws Exception {
        try (RawDescribeClient client = new RawDescribeClient()) {
            client.sendParse("", "INSERT INTO t VALUES (4, 'd') RETURNING id");
            client.sendDescribeStatement("");
            client.sendSync();

            String types = client.readUntilReadyForQuery();
            assert types.indexOf('t') >= 0 : "应回 ParameterDescription，实际序列=" + types;
            assert types.indexOf('T') >= 0
                    : "INSERT ... RETURNING 应回 RowDescription，实际序列=" + types;
            assert types.indexOf('n') < 0
                    : "INSERT ... RETURNING 不应回 NoData，实际序列=" + types;
        }
    }

    // ================================================================
    // 三、驱动侧：参数元数据与 DML 不回结果集
    // ================================================================

    /**
     * 驱动侧影响：{@code PreparedStatement.getParameterMetaData()} 依赖
     * {@code ParameterDescription}。修复前服务端从不发送该报文。
     */
    @Test
    void driverCanReadParameterMetaData() throws Exception {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM t WHERE id > ?")) {

            java.sql.ParameterMetaData meta = ps.getParameterMetaData();
            assert meta != null : "getParameterMetaData() 不应为 null";
            assert meta.getParameterCount() == 1
                    : "参数个数应为 1，实际 " + meta.getParameterCount();

            ps.setInt(1, 0);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assert rs.next() : "带参数的查询应能正常执行";
            }
        }
    }

    /**
     * 驱动侧影响：DML 不能被描述成"有结果集"。
     *
     * <p>{@code executeUpdate()} 在收到结果集时会抛
     * "A result was returned when none was expected."；
     * {@code Statement.execute()} 则会错误地返回 true。</p>
     */
    @Test
    void driverDmlExecutionDoesNotReportResultSet() throws Exception {
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?, ?)")) {
                ps.setInt(1, 100);
                ps.setString(2, "dml");
                assert ps.executeUpdate() == 1 : "INSERT 应返回影响行数 1";
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE t SET name = ? WHERE id = ?")) {
                ps.setString(1, "dml2");
                ps.setInt(2, 100);
                assert ps.executeUpdate() == 1 : "UPDATE 应返回影响行数 1";
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM t WHERE id = ?")) {
                ps.setInt(1, 100);
                assert ps.executeUpdate() == 1 : "DELETE 应返回影响行数 1";
            }

            // Statement.execute()：必须报告"没有结果集"，并且影响行数可读
            try (Statement st = conn.createStatement()) {
                boolean hasResultSet = st.execute("INSERT INTO t VALUES (200, 'stmt')");
                assert !hasResultSet
                        : "INSERT 被当成了返回结果集的语句（描述了伪列 Count:BIGINT）";
                assert st.getUpdateCount() == 1
                        : "INSERT 的影响行数应为 1，实际 " + st.getUpdateCount();
            }
            try (Statement st = conn.createStatement()) {
                assert st.execute("DELETE FROM t WHERE id = 200") == false
                        : "DELETE 被当成了返回结果集的语句";
                assert st.getUpdateCount() == 1 : "DELETE 的影响行数应为 1";
            }

            // 查询路径不受影响
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM t WHERE id > ?")) {
                ps.setInt(1, 0);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    int rows = 0;
                    while (rs.next()) {
                        rows++;
                    }
                    assert rows >= 2 : "查询应正常返回数据行，实际 " + rows;
                }
            }
        }
    }

    // ================================================================
    // 裸协议客户端
    // ================================================================

    private static final class RawDescribeClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;

        int lastParameterCount = -1;
        int[] lastParameterOids = new int[0];

        RawDescribeClient() throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", dbPort), 5_000);
            socket.setSoTimeout(8_000);
            in = socket.getInputStream();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(Utils.int32ToBytes(196608));
            writeCString(body, "user");
            writeCString(body, "test");
            writeCString(body, "database");
            writeCString(body, "dmeta");
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
            body.writeBytes(new byte[]{0, 0});          // 参数类型个数 = 0（让服务端推断）
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

        /**
         * 发送 Describe。
         *
         * <p>名称字段按协议是 NUL 结尾的 {@code String}，真实驱动
         * （{@code QueryExecutorImpl.sendDescribePortal()}）也会写出这个结尾 0，
         * 因此这里必须照原样带上。</p>
         */
        void sendDescribeStatement(String statementName) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write('S');
            writeCString(body, statementName);
            writeFrame('D', body.toByteArray());
        }

        void sendDescribePortal(String portal) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write('P');
            writeCString(body, portal);
            writeFrame('D', body.toByteArray());
        }

        void sendSync() throws Exception {
            writeFrame('S', new byte[0]);
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

        /** 读到 ReadyForQuery，返回沿途的报文类型序列；遇到 ParameterDescription 顺带解析出参数 OID。 */
        String readUntilReadyForQuery() throws Exception {
            StringBuilder types = new StringBuilder();
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException("连接在 ReadyForQuery 之前被关闭，已收到：" + types);
                }
                char type = (char) (frame[0] & 0xFF);
                types.append(type);
                if (type == 't') {
                    parseParameterDescription(frame);
                }
                if (type == 'Z') {
                    return types.toString();
                }
            }
        }

        private void parseParameterDescription(byte[] frame) {
            // 't' + Int32 长度 + Int16 参数个数 + Int32[] OID
            int count = ((frame[5] & 0xFF) << 8) | (frame[6] & 0xFF);
            lastParameterCount = count;
            lastParameterOids = new int[count];
            int pos = 7;
            for (int i = 0; i < count; i++) {
                lastParameterOids[i] = ((frame[pos] & 0xFF) << 24)
                        | ((frame[pos + 1] & 0xFF) << 16)
                        | ((frame[pos + 2] & 0xFF) << 8)
                        | (frame[pos + 3] & 0xFF);
                pos += 4;
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
