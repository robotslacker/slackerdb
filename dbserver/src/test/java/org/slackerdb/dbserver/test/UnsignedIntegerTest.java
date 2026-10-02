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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DuckDB 的 128 位/无符号整数在结果集上的契约测试（规划文档 BUG-8）。
 *
 * <p><b>被测缺陷</b>：`PostgresTypeOids` 把 HUGEINT/UBIGINT/UHUGEINT 声明成
 * BIGINT(OID 20)、UINTEGER 声明成 INTEGER(OID 23)、USMALLINT/UTINYINT 声明成 SMALLINT(OID 21)，
 * 而 `RowEncoder` 实际按<b>十进制文本</b>发送（format=0）。于是值一旦超出声明类型的值域，
 * 客户端按数字取值就炸：`PSQLException: Bad value for type long : 170141183460469231731687303715884105727`。
 * 另外 `typeCodeOf` 里 UHUGEINT/USMALLINT/UTINYINT 没有分支，落到 `T_UNKNOWN`，
 * 每次查询都打一条 `Not implemented column type` 的 WARN。</p>
 *
 * <p><b>PG 侧的现实</b>：PG 没有 128 位整数类型，能装下这些值的只有 NUMERIC(OID 1700)。
 * 所以 HUGEINT/UBIGINT/UHUGEINT 应声明为 NUMERIC 并按文本发（客户端拿到 BigDecimal，任意精度）；
 * UINTEGER/USMALLINT 的值域分别超出 int4/int2，应声明成更宽的 BIGINT/INTEGER。</p>
 */
public class UnsignedIntegerTest {

    /** 各类型的最大值：修复前只要"客户端按数字取"就会溢出。 */
    private static final String HUGEINT_MAX = "170141183460469231731687303715884105727";
    private static final String UBIGINT_MAX = "18446744073709551615";
    private static final String UHUGEINT_MAX = "340282366920938463463374607431768211455";
    private static final String UINTEGER_MAX = "4294967295";
    private static final String USMALLINT_MAX = "65535";
    private static final String UTINYINT_MAX = "255";

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("uintparam");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE uint_max("
                    + " h HUGEINT, ub UBIGINT, uh UHUGEINT, ui UINTEGER, us USMALLINT, ut UTINYINT)");
            st.execute("INSERT INTO uint_max VALUES ("
                    + HUGEINT_MAX + ", " + UBIGINT_MAX + ", " + UHUGEINT_MAX + ", "
                    + UINTEGER_MAX + ", " + USMALLINT_MAX + ", " + UTINYINT_MAX + ")");

            st.execute("CREATE OR REPLACE TABLE uint_small("
                    + " h HUGEINT, ub UBIGINT, uh UHUGEINT, ui UINTEGER, us USMALLINT, ut UTINYINT)");
            st.execute("INSERT INTO uint_small VALUES (1, 2, 3, 4, 5, 6)");
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/uintparam", "", "");
    }

    private static ResultSet query(Connection conn, String sql) throws Exception {
        Statement st = conn.createStatement();
        return st.executeQuery(sql);
    }

    // ================================================================
    // 一、驱动路径：最大值必须能"按数字"取出来
    // ================================================================

    /** HUGEINT / UBIGINT / UHUGEINT 超出 int64，必须按 NUMERIC 交付（客户端得到 BigDecimal）。 */
    @Test
    void hugeUnsignedTypesRoundTripAsBigDecimal() throws Exception {
        try (Connection conn = connect();
             ResultSet rs = query(conn, "SELECT h, ub, uh FROM uint_max")) {
            assert rs.next() : "没有返回行";

            Object h = rs.getObject(1);
            assert h instanceof BigDecimal
                    : "HUGEINT 最大值应按 NUMERIC 交付（BigDecimal），实际 " + describe(h);
            assert HUGEINT_MAX.equals(h.toString()) : "HUGEINT 值不符，实际 " + h;

            Object ub = rs.getObject(2);
            assert ub instanceof BigDecimal
                    : "UBIGINT 最大值应按 NUMERIC 交付（BigDecimal），实际 " + describe(ub);
            assert UBIGINT_MAX.equals(ub.toString()) : "UBIGINT 值不符，实际 " + ub;

            Object uh = rs.getObject(3);
            assert uh instanceof BigDecimal
                    : "UHUGEINT 最大值应按 NUMERIC 交付（BigDecimal），实际 " + describe(uh);
            assert UHUGEINT_MAX.equals(uh.toString()) : "UHUGEINT 值不符，实际 " + uh;

            // getBigDecimal / getString 也必须正确
            assert new BigDecimal(HUGEINT_MAX).compareTo(rs.getBigDecimal(1)) == 0
                    : "getBigDecimal(HUGEINT) 不符，实际 " + rs.getBigDecimal(1);
            assert UBIGINT_MAX.equals(rs.getString(2)) : "getString(UBIGINT) 不符，实际 " + rs.getString(2);
        }
    }

    /** UINTEGER 最大值 4294967295 超出 int4，必须按 BIGINT 交付。 */
    @Test
    void uintegerMaxValueRoundTripsAsBigint() throws Exception {
        try (Connection conn = connect();
             ResultSet rs = query(conn, "SELECT ui FROM uint_max")) {
            assert rs.next() : "没有返回行";
            Object value = rs.getObject(1);
            assert value instanceof Long
                    : "UINTEGER 最大值应按 BIGINT 交付（Long），实际 " + describe(value);
            assert Long.parseLong(UINTEGER_MAX) == (Long) value : "UINTEGER 值不符，实际 " + value;
            assert Long.parseLong(UINTEGER_MAX) == rs.getLong(1) : "getLong(UINTEGER) 不符";
            assert UINTEGER_MAX.equals(rs.getString(1)) : "getString(UINTEGER) 不符";
        }
    }

    /** USMALLINT 最大值 65535 超出 int2，必须按 INTEGER 交付；UTINYINT 按 SMALLINT 即可。 */
    @Test
    void smallUnsignedTypesRoundTrip() throws Exception {
        try (Connection conn = connect();
             ResultSet rs = query(conn, "SELECT us, ut FROM uint_max")) {
            assert rs.next() : "没有返回行";

            Object us = rs.getObject(1);
            assert us instanceof Integer
                    : "USMALLINT 最大值应按 INTEGER 交付（Integer），实际 " + describe(us);
            assert Integer.parseInt(USMALLINT_MAX) == (Integer) us : "USMALLINT 值不符，实际 " + us;
            assert Integer.parseInt(USMALLINT_MAX) == rs.getInt(1) : "getInt(USMALLINT) 不符";

            Object ut = rs.getObject(2);
            assert ut instanceof Number : "UTINYINT 应能按数字取出，实际 " + describe(ut);
            assert Integer.parseInt(UTINYINT_MAX) == ((Number) ut).intValue()
                    : "UTINYINT 值不符，实际 " + ut;
            assert Short.parseShort(UTINYINT_MAX) == rs.getShort(2) : "getShort(UTINYINT) 不符";
        }
    }

    /** 反向约束：小值的行为不能被改坏（既有 RowEncoderTest 用的就是小值）。 */
    @Test
    void smallValuesStillWork() throws Exception {
        try (Connection conn = connect();
             ResultSet rs = query(conn, "SELECT h, ub, uh, ui, us, ut FROM uint_small")) {
            assert rs.next() : "没有返回行";
            assert rs.getLong("h") == 1 : "HUGEINT 小值应可 getLong";
            assert rs.getLong("ub") == 2 : "UBIGINT 小值应可 getLong";
            assert rs.getLong("uh") == 3 : "UHUGEINT 小值应可 getLong";
            assert rs.getLong("ui") == 4 : "UINTEGER 小值应可 getLong";
            assert rs.getInt("us") == 5 : "USMALLINT 小值应可 getInt";
            assert rs.getShort("ut") == 6 : "UTINYINT 小值应可 getShort";
        }
    }

    /** 元数据侧：客户端看到的 JDBC 类型必须与"能装下值域"的声明一致。 */
    @Test
    void metadataDeclaresWideEnoughTypes() throws Exception {
        try (Connection conn = connect();
             ResultSet rs = query(conn, "SELECT h, ub, uh, ui, us, ut FROM uint_max")) {
            ResultSetMetaData md = rs.getMetaData();
            Map<String, String> jdbcTypes = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                jdbcTypes.put(md.getColumnName(i), md.getColumnTypeName(i));
            }
            assert "numeric".equals(jdbcTypes.get("h"))
                    : "HUGEINT 应声明为 numeric，实际 " + jdbcTypes.get("h");
            assert "numeric".equals(jdbcTypes.get("ub"))
                    : "UBIGINT 应声明为 numeric，实际 " + jdbcTypes.get("ub");
            assert "numeric".equals(jdbcTypes.get("uh"))
                    : "UHUGEINT 应声明为 numeric，实际 " + jdbcTypes.get("uh");
            assert "int8".equals(jdbcTypes.get("ui"))
                    : "UINTEGER 应声明为 int8，实际 " + jdbcTypes.get("ui");
            assert "int4".equals(jdbcTypes.get("us"))
                    : "USMALLINT 应声明为 int4，实际 " + jdbcTypes.get("us");
            assert "int2".equals(jdbcTypes.get("ut"))
                    : "UTINYINT 应声明为 int2，实际 " + jdbcTypes.get("ut");
        }
    }

    // ================================================================
    // 二、线协议：RowDescription 的 OID / format 必须与之一致
    // ================================================================

    /**
     * 简单查询路径：OID 必须能装下值域。
     *
     * <p>简单查询（{@code 'Q'}）一律文本返回（format=0），所以这里只断言声明类型；
     * 二进制格式由下一条用例在扩展协议下断言。</p>
     */
    @Test
    void wireRowDescriptionDeclaresWideEnoughOids() throws Exception {
        RawClient client = new RawClient();
        try (client) {
            client.sendQuery("SELECT h, ub, uh, ui, us, ut FROM uint_max");
            client.readAll();
        }

        assertField(client.rowDescription, "h", 1700, "NUMERIC", 0);
        assertField(client.rowDescription, "ub", 1700, "NUMERIC", 0);
        assertField(client.rowDescription, "uh", 1700, "NUMERIC", 0);
        assertField(client.rowDescription, "ui", 20, "BIGINT", 0);
        assertField(client.rowDescription, "us", 23, "INTEGER", 0);
        assertField(client.rowDescription, "ut", 21, "SMALLINT", 0);
    }

    /**
     * 扩展协议路径：宽整数按二进制发，且<b>字节宽度必须与声明的 OID 一致</b>
     * （int8=8、int4=4、int2=2）—— 声明与编码不一致正是本次缺陷的形态。
     */
    @Test
    void extendedProtocolUsesBinaryWithMatchingWidths() throws Exception {
        RawClient client = new RawClient();
        try (client) {
            client.sendParse("", "SELECT ui, us, ut FROM uint_max");
            client.sendBind("", "");
            client.sendDescribePortal("");
            client.sendExecute("");
            client.sendSync();
            client.readAll();
        }

        assertField(client.rowDescription, "ui", 20, "BIGINT", 1);
        assertField(client.rowDescription, "us", 23, "INTEGER", 1);
        assertField(client.rowDescription, "ut", 21, "SMALLINT", 1);

        assert client.dataRowLengths != null : "没有收到 DataRow";
        assert client.dataRowLengths.length == 3 : "DataRow 列数应为 3，实际 " + client.dataRowLengths.length;
        assert client.dataRowLengths[0] == 8
                : "UINTEGER 声明 int8，二进制值必须是 8 字节，实际 " + client.dataRowLengths[0];
        assert client.dataRowLengths[1] == 4
                : "USMALLINT 声明 int4，二进制值必须是 4 字节，实际 " + client.dataRowLengths[1];
        assert client.dataRowLengths[2] == 2
                : "UTINYINT 声明 int2，二进制值必须是 2 字节，实际 " + client.dataRowLengths[2];
    }

    private static void assertField(Map<String, int[]> fields, String name, int expectedOid,
                                     String label, int expectedFormat) {
        int[] field = fields.get(name);
        assert field != null : "RowDescription 里没有列 " + name;
        assert field[0] == expectedOid
                : "列 " + name + " 应声明 OID " + expectedOid + "（" + label + "），实际 " + field[0];
        assert field[1] == expectedFormat
                : "列 " + name + " 的结果格式应为 " + expectedFormat + "（必须与编码方式一致），实际 " + field[1];
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getName() + ":" + value;
    }

    // ================================================================
    // 极简线协议客户端：只关心 RowDescription
    // ================================================================

    private static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        /** 最近一次 readAll() 解析出的行描述：列名 -> {typeOid, formatCode} */
        Map<String, int[]> rowDescription = new LinkedHashMap<>();
        /** 最近一次 readAll() 收到的 DataRow 各列字节长度（-1 表示 NULL） */
        int[] dataRowLengths;

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
            writeCString(body, "uintparam");
            body.write(0);
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.writeBytes(Utils.int32ToBytes(bodyBytes.length + 4));
            packet.writeBytes(bodyBytes);
            write(packet.toByteArray());
            readAll();
        }

        void sendQuery(String sql) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, sql);
            writeFrame('Q', body.toByteArray());
        }

        void sendParse(String statementName, String sql) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, statementName);
            writeCString(body, sql);
            body.writeBytes(Utils.int16ToBytes((short) 0));      // 参数类型个数 = 0
            writeFrame('P', body.toByteArray());
        }

        void sendBind(String portal, String statement) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, portal);
            writeCString(body, statement);
            body.writeBytes(Utils.int16ToBytes((short) 0));      // 参数格式码个数
            body.writeBytes(Utils.int16ToBytes((short) 0));      // 参数个数
            body.writeBytes(Utils.int16ToBytes((short) 0));      // 结果格式码个数
            writeFrame('B', body.toByteArray());
        }

        void sendDescribePortal(String portal) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write('P');
            writeCString(body, portal);
            writeFrame('D', body.toByteArray());
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

        /** 读到 ReadyForQuery，期间解析出 RowDescription 与 DataRow 的列长度。 */
        void readAll() throws Exception {
            rowDescription = new LinkedHashMap<>();
            dataRowLengths = null;
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException("连接在 ReadyForQuery 之前被关闭");
                }
                char type = (char) (frame[0] & 0xFF);
                if (type == 'T') {
                    rowDescription = parseRowDescription(frame);
                } else if (type == 'D') {
                    dataRowLengths = parseDataRowLengths(frame);
                } else if (type == 'E') {
                    throw new IllegalStateException("服务端返回了 ErrorResponse");
                } else if (type == 'Z') {
                    return;
                }
            }
        }

        /** 解析 RowDescription 每列的 {typeOid, formatCode}。 */
        private static Map<String, int[]> parseRowDescription(byte[] frame) {
            Map<String, int[]> fields = new LinkedHashMap<>();
            int pos = 5;
            int count = int16(frame, pos);
            pos += 2;
            for (int i = 0; i < count; i++) {
                int start = pos;
                while (frame[pos] != 0) {
                    pos++;
                }
                String name = new String(frame, start, pos - start, StandardCharsets.UTF_8);
                pos++;
                pos += 4;                       // tableOid
                pos += 2;                       // attnum
                int typeOid = int32(frame, pos);
                pos += 4;
                pos += 2;                       // typeSize
                pos += 4;                       // typeModifier
                int format = int16(frame, pos);
                pos += 2;
                fields.put(name, new int[]{typeOid, format});
            }
            return fields;
        }

        /** 解析 DataRow 各列的字节长度（不做内容解码）。 */
        private static int[] parseDataRowLengths(byte[] frame) {
            int pos = 5;
            int count = int16(frame, pos);
            pos += 2;
            int[] lengths = new int[count];
            for (int i = 0; i < count; i++) {
                int len = int32(frame, pos);
                pos += 4;
                lengths[i] = len;
                if (len > 0) {
                    pos += len;
                }
            }
            return lengths;
        }

        private static int int16(byte[] frame, int pos) {
            return ((frame[pos] & 0xFF) << 8) | (frame[pos + 1] & 0xFF);
        }

        private static int int32(byte[] frame, int pos) {
            return ((frame[pos] & 0xFF) << 24) | ((frame[pos + 1] & 0xFF) << 16)
                    | ((frame[pos + 2] & 0xFF) << 8) | (frame[pos + 3] & 0xFF);
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
