package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 二进制参数的契约测试（规划文档 BUG-7）。
 *
 * <p><b>被测缺陷</b>：{@code BindRequest} 的二进制分支只覆盖
 * INTEGER/BIGINT/BOOLEAN/DECIMAL/FLOAT/DOUBLE/VARCHAR 七种，其余类型落到
 * {@code default -> logger.error(...)}：<b>不绑定参数、不回错误</b>。
 * 客户端于是要么拿到 NULL、要么拿到一个与"参数没绑定"毫无关系的报错。</p>
 *
 * <p><b>为什么这些类型必须支持</b>（详见修复说明）：PG 对这些类型都有二进制格式，
 * 而驱动默认就用二进制发：本仓库驱动的二进制发送集合见
 * {@code dbdriver/.../jdbc/PgConnection.java:412-438}（BYTEA/INT2/INT4/INT8/FLOAT4/FLOAT8/
 * NUMERIC/TIME/TIMETZ/TIMESTAMP/TIMESTAMPTZ/UUID/POINT/BOX/数组），
 * 其中 <b>DATE 被刻意剔除</b>（{@code PgConnection.java:313-317}：二进制只有日期精度，
 * 而 JDBC 的 setDate 期望毫秒精度），所以 DATE 用裸协议用例覆盖。</p>
 *
 * <p>本文件分三段：驱动路径（真实二进制发送）、线协议（服务端解码）、
 * 未实现类型必须"明确报错而不是静默"。</p>
 */
public class BinaryParameterTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("binparam");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE bp_i2(v SMALLINT)");
            st.execute("CREATE OR REPLACE TABLE bp_uuid(v UUID)");
            st.execute("CREATE OR REPLACE TABLE bp_blob(v BLOB)");
            st.execute("CREATE OR REPLACE TABLE bp_basic(i INTEGER, l BIGINT, d DECIMAL(18,4), f REAL, x DOUBLE, s VARCHAR)");
            st.execute("CREATE OR REPLACE TABLE bp_int(v INTEGER)");

            st.execute("CREATE OR REPLACE TABLE bp_date(v DATE)");
            st.execute("CREATE OR REPLACE TABLE bp_time(v TIME)");
            st.execute("CREATE OR REPLACE TABLE bp_ts(v TIMESTAMP)");
            st.execute("CREATE OR REPLACE TABLE bp_tstz(v TIMESTAMPTZ)");
            st.execute("CREATE OR REPLACE TABLE bp_interval(v INTERVAL)");
            st.execute("CREATE OR REPLACE TABLE bp_bit(v BIT)");
            st.execute("CREATE OR REPLACE TABLE bp_json(v JSON)");
            st.execute("CREATE OR REPLACE TABLE bp_timetz(v TIME)");
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/binparam", "", "");
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** 取一行的单列值；NULL 返回 {@code <null>}。 */
    private static String scalar(Connection conn, String table) throws Exception {
        return scalarSql(conn, "SELECT v FROM " + table);
    }

    /** 执行任意单列查询并取首行；NULL 返回 {@code <null>}。 */
    private static String scalarSql(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return "<no row>";
            }
            String value = rs.getString(1);
            return value == null && rs.wasNull() ? "<null>" : value;
        }
    }

    /** 取布尔表达式结果。 */
    private static boolean truth(Connection conn, String expression) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + expression)) {
            assert rs.next() : "查询没有返回行: " + expression;
            boolean value = rs.getBoolean(1);
            return !rs.wasNull() && value;
        }
    }

    private static long count(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ================================================================
    // 一、驱动路径：pgjdbc 默认就会用二进制发的类型
    // ================================================================

    /** SMALLINT（OID 21）：pgjdbc 的 setShort 走二进制（{@code PgPreparedStatement:317}）。 */
    @Test
    void smallintParameterRoundTrips() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_i2");
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bp_i2 VALUES (?)")) {
                ps.setShort(1, (short) -12345);
                ps.executeUpdate();
            }
            assert "-12345".equals(scalar(conn, "bp_i2"))
                    : "SMALLINT 二进制参数没有被绑定（实际 " + scalar(conn, "bp_i2") + "）";
        }
    }

    /** UUID（OID 2950）：pgjdbc 的 setObject(UUID) 走二进制（{@code PgPreparedStatement:1711}）。 */
    @Test
    void uuidParameterRoundTrips() throws Exception {
        UUID uuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_uuid");
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bp_uuid VALUES (?)")) {
                ps.setObject(1, uuid);
                ps.executeUpdate();
            }
            assert uuid.toString().equals(scalar(conn, "bp_uuid"))
                    : "UUID 二进制参数没有被绑定（实际 " + scalar(conn, "bp_uuid") + "）";
        }
    }

    /** BYTEA（OID 17）：pgjdbc 的 setBytes 恒用二进制（{@code SimpleParameterList:149-165}）。 */
    @Test
    void byteaParameterRoundTrips() throws Exception {
        byte[] blob = new byte[]{0x00, 0x01, 0x7F, (byte) 0x80, (byte) 0xFF, 'S', 'D', 'B'};
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_blob");
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bp_blob VALUES (?)")) {
                ps.setBytes(1, blob);
                ps.executeUpdate();
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT v FROM bp_blob")) {
                assert rs.next() : "没有写入任何行";
                byte[] read = rs.getBytes(1);
                assert read != null : "BYTEA 二进制参数没有被绑定（读回 NULL）";
                assert java.util.Arrays.equals(blob, read)
                        : "BYTEA 内容不一致，期望 " + java.util.Arrays.toString(blob)
                        + " 实际 " + java.util.Arrays.toString(read);
            }
        }
    }

    /** 反向约束：本来就能工作的二进制类型 + 文本类型不能被改坏。 */
    @Test
    void alreadySupportedTypesStillWork() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_basic");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO bp_basic VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setInt(1, 42);
                ps.setLong(2, 1234567890123L);
                ps.setBigDecimal(3, new BigDecimal("1234.5678"));
                ps.setFloat(4, 1.5f);
                ps.setDouble(5, 2.25d);
                ps.setString(6, "text-参数");
                ps.executeUpdate();
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT i, l, d, f, x, s FROM bp_basic")) {
                assert rs.next() : "没有写入任何行";
                assert rs.getInt(1) == 42 : "INTEGER 参数错误: " + rs.getInt(1);
                assert rs.getLong(2) == 1234567890123L : "BIGINT 参数错误: " + rs.getLong(2);
                assert new BigDecimal("1234.5678").compareTo(rs.getBigDecimal(3)) == 0
                        : "DECIMAL 参数错误: " + rs.getBigDecimal(3);
                assert Math.abs(rs.getFloat(4) - 1.5f) < 1e-6 : "REAL 参数错误: " + rs.getFloat(4);
                assert Math.abs(rs.getDouble(5) - 2.25d) < 1e-9 : "DOUBLE 参数错误: " + rs.getDouble(5);
                assert "text-参数".equals(rs.getString(6)) : "VARCHAR 参数错误: " + rs.getString(6);
            }
        }
    }

    // ================================================================
    // 二、线协议：服务端二进制解码（这些类型 pgjdbc 不发二进制，但别的驱动会）
    // ================================================================

    /** DATE（OID 1082）：int4，自 2000-01-01 起的天数。 */
    @Test
    void binaryDateParameterIsDecoded() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_date");
        }
        try (RawClient client = new RawClient()) {
            client.insertBinary("bp_date", 1082, pgDate("2024-05-06"));
        }
        try (Connection conn = connect()) {
            assert "2024-05-06".equals(scalar(conn, "bp_date"))
                    : "DATE 二进制参数没有被正确解码（实际 " + scalar(conn, "bp_date") + "）";
        }
    }

    /** TIME（OID 1083）：int8，当日微秒数。 */
    @Test
    void binaryTimeParameterIsDecoded() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_time");
        }
        // 01:02:03.456789
        long micros = (1 * 3600 + 2 * 60 + 3) * 1_000_000L + 456_789L;
        try (RawClient client = new RawClient()) {
            client.insertBinary("bp_time", 1083, Utils.int64ToBytes(micros));
        }
        try (Connection conn = connect()) {
            assert "01:02:03.456789".equals(scalarSql(conn, "SELECT CAST(v AS VARCHAR) FROM bp_time"))
                    : "TIME 二进制参数没有被正确解码（实际 " + scalarSql(conn, "SELECT CAST(v AS VARCHAR) FROM bp_time") + "）";
        }
    }

    /** TIMESTAMP（OID 1114）：int8，自 2000-01-01 起的微秒数。 */
    @Test
    void binaryTimestampParameterIsDecoded() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_ts");
        }
        LocalDateTime value = LocalDateTime.of(2024, 5, 6, 7, 8, 9, 123_456_000);
        try (RawClient client = new RawClient()) {
            client.insertBinary("bp_ts", 1114, pgTimestamp(value));
        }
        try (Connection conn = connect()) {
            assert "2024-05-06 07:08:09.123456".equals(scalarSql(conn, "SELECT CAST(v AS VARCHAR) FROM bp_ts"))
                    : "TIMESTAMP 二进制参数没有被正确解码（实际 " + scalarSql(conn, "SELECT CAST(v AS VARCHAR) FROM bp_ts") + "）";
        }
    }

    /** TIMESTAMPTZ（OID 1184）：int8，自 2000-01-01 UTC 起的微秒数（绝对时刻）。 */
    @Test
    void binaryTimestamptzParameterIsDecoded() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_tstz");
        }
        Instant instant = Instant.parse("2024-05-06T07:08:09.123456Z");
        try (RawClient client = new RawClient()) {
            client.insertBinary("bp_tstz", 1184, pgTimestamptz(instant));
        }
        try (Connection conn = connect()) {
            // 用"等于同一时刻"判断，避免受会话时区影响
            assert truth(conn, "(SELECT v FROM bp_tstz) = TIMESTAMPTZ '2024-05-06 07:08:09.123456+00'")
                    : "TIMESTAMPTZ 二进制参数没有被正确解码（实际 " + scalar(conn, "bp_tstz") + "）";
        }
    }

    /** INTERVAL（OID 1186）：int8 微秒 + int4 天 + int4 月。 */
    @Test
    void binaryIntervalParameterIsDecoded() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_interval");
        }
        try (RawClient client = new RawClient()) {
            client.insertBinary("bp_interval", 1186, pgInterval(1, 2, 3_500_000L));
        }
        try (Connection conn = connect()) {
            assert truth(conn,
                    "(SELECT v FROM bp_interval) = INTERVAL '1 month 2 days 00:00:03.5'")
                    : "INTERVAL 二进制参数没有被正确解码（实际 " + scalar(conn, "bp_interval") + "）";
        }
    }

    /** BIT/VARBIT（OID 1563）：int4 位长 + 字节。 */
    @Test
    void binaryBitParameterIsDecoded() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_bit");
        }
        try (RawClient client = new RawClient()) {
            client.insertBinary("bp_bit", 1563, pgBit("1010"));
        }
        try (Connection conn = connect()) {
            assert "1010".equals(scalar(conn, "bp_bit"))
                    : "BIT 二进制参数没有被正确解码（实际 " + scalar(conn, "bp_bit") + "）";
        }
    }

    /** JSONB（OID 3802）：1 字节版本号（=1）+ UTF-8 文本。 */
    @Test
    void binaryJsonbParameterIsDecoded() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_json");
        }
        try (RawClient client = new RawClient()) {
            client.insertBinary("bp_json", 3802, pgJsonb("{\"a\":1}"));
        }
        try (Connection conn = connect()) {
            assert "1".equals(scalarSql(conn, "SELECT json_extract_string(v, '$.a') FROM bp_json"))
                    : "JSONB 二进制参数没有被正确解码（实际 " + scalar(conn, "bp_json") + "）";
        }
    }

    // ================================================================
    // 三、不支持的二进制类型：必须明确报错，不能静默丢参数
    // ================================================================

    /** TIMETZ（OID 1266）：DuckDB 没有"带时区的 TIME"，必须回 ErrorResponse 而不是静默插入 NULL。 */
    @Test
    void unimplementedBinaryTypeIsRejected() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_timetz");
        }
        try (RawClient client = new RawClient()) {
            // 12 字节：int8 微秒 + int4 时区（PG 的 timetz 二进制格式）
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.writeBytes(Utils.int64ToBytes(0L));
            payload.writeBytes(Utils.int32ToBytes(-8 * 3600));
            String types = client.insertBinaryAndReadTypes("bp_timetz", 1266, payload.toByteArray());

            assert client.lastErrorFrame != null
                    : "不支持的二进制类型必须回 ErrorResponse，实际报文序列=" + types;
            assert "0A000".equals(client.errorSqlState())
                    : "应回 SQLSTATE 0A000（feature_not_supported），实际 " + client.errorSqlState();
            assert client.errorMessage().contains("TIMETZ")
                    : "错误信息里应说明是哪种类型，实际: " + client.errorMessage();
        }
        try (Connection conn = connect()) {
            assert count(conn, "bp_timetz") == 0
                    : "被拒绝的 COPY/Bind 不能留下任何数据（说明参数被静默当成 NULL 写进去了）";
        }
    }

    /** 完全不认识的 OID：同样必须明确报错。 */
    @Test
    void unknownBinaryTypeOidIsRejected() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_timetz");
        }
        try (RawClient client = new RawClient()) {
            String types = client.insertBinaryAndReadTypes("bp_timetz", 99999, Utils.int32ToBytes(0));
            assert client.lastErrorFrame != null
                    : "未知 OID 的二进制参数必须回 ErrorResponse，实际报文序列=" + types;
            assert "0A000".equals(client.errorSqlState())
                    : "应回 SQLSTATE 0A000，实际 " + client.errorSqlState();
            assert client.errorMessage().contains("99999")
                    : "错误信息里应带上 OID，实际: " + client.errorMessage();
        }
    }

    /** 载荷长度与声明类型不符：必须明确报错（否则会读到越界字节或抛 JVM 异常）。 */
    @Test
    void malformedBinaryPayloadIsRejected() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_int");
        }
        try (RawClient client = new RawClient()) {
            // INTEGER 声明成 4 字节，实际只给 3 字节
            String types = client.insertBinaryAndReadTypes("bp_int", 23, new byte[]{0x00, 0x00, 0x2A});
            assert client.lastErrorFrame != null
                    : "长度不符的二进制载荷必须回 ErrorResponse，实际报文序列=" + types;
            assert "22P03".equals(client.errorSqlState())
                    : "应回 SQLSTATE 22P03（invalid_binary_representation），实际 " + client.errorSqlState();
        }
        try (Connection conn = connect()) {
            assert count(conn, "bp_int") == 0 : "失败的参数绑定不能写入任何数据";
            // 会话必须仍然可用，且正常参数不受影响
            assert "7".equals(scalarSql(conn, "SELECT 7")) : "出错后会话不可用";
        }
    }

    /**
     * 超出可表示范围的时间载荷：必须回**明确错误**，不能让解码期间抛出的未检查异常
     * （{@code DateTimeException}）逃逸到 Netty —— 那会静默断开连接，客户端连错误都收不到。
     *
     * <p>具体是 JDK 算术先越界（22008）还是 DuckDB 转换先失败（22P02）取决于值本身，
     * 因此这里断言的是**契约**：有 ErrorResponse、SQLSTATE 是合法五字符码（不是 "0"）、
     * 不写入任何数据、会话仍然可用。</p>
     */
    @Test
    void outOfRangeTimestampPayloadIsRejected() throws Exception {
        try (Connection conn = connect()) {
            exec(conn, "DELETE FROM bp_ts");
        }
        try (RawClient client = new RawClient()) {
            // TIMESTAMP 的 int8 微秒取到 Long.MAX_VALUE：远超出可表示范围
            String types = client.insertBinaryAndReadTypes("bp_ts", 1114, Utils.int64ToBytes(Long.MAX_VALUE));
            assert client.lastErrorFrame != null
                    : "越界的时间载荷必须回 ErrorResponse（不能断开连接），实际报文序列=" + types;
            String state = client.errorSqlState();
            assert state != null && state.length() == 5
                    : "SQLSTATE 应是合法五字符码，实际 [" + state + "]";
            assert !"00000".equals(state) && !"0".equals(state)
                    : "不能把 DuckDB 的数字 errorCode 当成 SQLSTATE，实际 [" + state + "]";
        }
        try (Connection conn = connect()) {
            assert count(conn, "bp_ts") == 0 : "失败的参数绑定不能写入任何数据";
            assert "7".equals(scalarSql(conn, "SELECT 7")) : "越界报错后会话必须仍然可用";
        }
    }

    // ================================================================
    // PG 二进制格式编码（测试侧）
    // ================================================================

    private static final LocalDate PG_EPOCH_DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDateTime PG_EPOCH_TIMESTAMP = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final Instant PG_EPOCH_INSTANT = Instant.parse("2000-01-01T00:00:00Z");

    static byte[] pgDate(String isoDate) {
        int days = (int) ChronoUnit.DAYS.between(PG_EPOCH_DATE, LocalDate.parse(isoDate));
        return Utils.int32ToBytes(days);
    }

    static byte[] pgTimestamp(LocalDateTime value) {
        long micros = Duration.between(PG_EPOCH_TIMESTAMP, value).toNanos() / 1000;
        return Utils.int64ToBytes(micros);
    }

    static byte[] pgTimestamptz(Instant value) {
        long micros = Duration.between(PG_EPOCH_INSTANT, value).toNanos() / 1000;
        return Utils.int64ToBytes(micros);
    }

    static byte[] pgInterval(int months, int days, long micros) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Utils.int64ToBytes(micros));
        out.writeBytes(Utils.int32ToBytes(days));
        out.writeBytes(Utils.int32ToBytes(months));
        return out.toByteArray();
    }

    static byte[] pgBit(String bits) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Utils.int32ToBytes(bits.length()));
        int bytes = (bits.length() + 7) / 8;
        byte[] data = new byte[bytes];
        for (int i = 0; i < bits.length(); i++) {
            if (bits.charAt(i) == '1') {
                data[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        out.writeBytes(data);
        return out.toByteArray();
    }

    static byte[] pgJsonb(String json) {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[text.length + 1];
        payload[0] = 1;   // JSONB 版本号
        System.arraycopy(text, 0, payload, 1, text.length);
        return payload;
    }

    // ================================================================
    // 极简扩展协议客户端（Parse + Bind + Execute + Sync）
    // ================================================================

    private static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        /** 最近一次读到的 ErrorResponse 原始帧（没有则为 null）。 */
        byte[] lastErrorFrame;

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
            writeCString(body, "binparam");
            body.write(0);
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.writeBytes(Utils.int32ToBytes(bodyBytes.length + 4));
            packet.writeBytes(bodyBytes);
            write(packet.toByteArray());
            readUntilReadyForQuery();
        }

        /** 一次性完成"插入一个二进制参数"并断言有应答；返回报文类型序列。 */
        String insertBinary(String table, int paramOid, byte[] payload) throws Exception {
            String types = insertBinaryAndReadTypes(table, paramOid, payload);
            assert lastErrorFrame == null
                    : "参数绑定失败：" + errorSqlState() + " " + errorMessage() + "（报文序列=" + types + "）";
            return types;
        }

        String insertBinaryAndReadTypes(String table, int paramOid, byte[] payload) throws Exception {
            sendParse("", "INSERT INTO " + table + " VALUES ($1)", paramOid);
            sendBindBinary("", "", payload);
            sendExecute("");
            sendSync();
            return readUntilReadyForQuery();
        }

        /** Parse：语句名 + SQL + Int16(1) + Int32(参数 OID)。 */
        void sendParse(String statementName, String sql, int paramOid) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, statementName);
            writeCString(body, sql);
            body.writeBytes(Utils.int16ToBytes((short) 1));
            body.writeBytes(Utils.int32ToBytes(paramOid));
            writeFrame('P', body.toByteArray());
        }

        /** Bind：门户 + 语句 + Int16(1) + Int16(1=二进制) + Int16(1) + Int32(长度) + 数据 + Int16(0)。 */
        void sendBindBinary(String portal, String statement, byte[] payload) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, portal);
            writeCString(body, statement);
            body.writeBytes(Utils.int16ToBytes((short) 1));      // 参数格式码个数
            body.writeBytes(Utils.int16ToBytes((short) 1));      // 1 = binary
            body.writeBytes(Utils.int16ToBytes((short) 1));      // 参数个数
            body.writeBytes(Utils.int32ToBytes(payload.length));
            body.writeBytes(payload);
            body.writeBytes(Utils.int16ToBytes((short) 0));      // 结果格式码个数
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

        /** 读到 ReadyForQuery，返回沿途的报文类型序列；记录第一个 ErrorResponse。 */
        String readUntilReadyForQuery() throws Exception {
            StringBuilder types = new StringBuilder();
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException("连接在 ReadyForQuery 之前被关闭，已收到：" + types);
                }
                char type = (char) (frame[0] & 0xFF);
                types.append(type);
                if (type == 'E' && lastErrorFrame == null) {
                    lastErrorFrame = frame;
                }
                if (type == 'Z') {
                    return types.toString();
                }
            }
        }

        String errorSqlState() {
            return errorField('C');
        }

        String errorMessage() {
            return errorField('M');
        }

        private String errorField(char wanted) {
            if (lastErrorFrame == null) {
                return "";
            }
            int pos = 5;
            while (pos < lastErrorFrame.length && lastErrorFrame[pos] != 0) {
                char fieldType = (char) (lastErrorFrame[pos] & 0xFF);
                pos++;
                int start = pos;
                while (pos < lastErrorFrame.length && lastErrorFrame[pos] != 0) {
                    pos++;
                }
                String value = new String(lastErrorFrame, start, pos - start, StandardCharsets.UTF_8);
                if (fieldType == wanted) {
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
