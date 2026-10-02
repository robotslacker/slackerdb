package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.jdbc.util.PGInterval;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
 * RowDescription 元数据契约测试（规划文档 BUG-7）。
 *
 * <p><b>被测缺陷</b>：{@code RowEncoder} 给每个字段填的是
 * {@code dataTypeSize = (short) 2147483647}（截断后线上是 <b>65535</b>）、
 * {@code dataTypeModifier = -1}、{@code tableOid/attnum = 0}。其中：
 * <ul>
 *   <li>{@code typeSize} 应当是 {@code pg_type.typlen}——类型的<b>固定内部宽度</b>，
 *       <b>变长类型一律 -1</b>（text/varchar/numeric/bytea/数组…）。填 65535 既不是 -1
 *       也不是任何合法宽度，libpq 系客户端（psql/psycopg2 的 {@code PQfsize}）会看到一个荒谬的值。</li>
 *   <li>{@code typeModifier} 应当是 {@code atttypmod}：{@code numeric(p,s)} →
 *       {@code ((p << 16) | s) + 4}、{@code varchar(n)} → {@code n + 4}、没有修饰符 → -1。
 *       DuckDB 的 DECIMAL 精度/标度<b>是可以拿到的</b>（类型名带 {@code (p,s)}，
 *       {@code getPrecision/getScale} 也有），所以这一项应当填对——否则客户端拿到的
 *       {@code getPrecision()/getScale()} 是 {@code 0/0}，DBeaver/ORM 看不到 {@code numeric(18,4)}。</li>
 * </ul>
 *
 * <p>DuckDB 拿不到的两件事，本测试<b>刻意断言为 -1</b>并作为"已知边界"钉住：
 * VARCHAR 的声明长度（DuckDB 的 {@code VARCHAR(n)} 只是运行期校验，
 * {@code information_schema.columns.character_maximum_length} 也是 null）；
 * 以及 TIME/TIMESTAMP 的精度（DuckDB 固定微秒，等价于 PG 默认精度 6）。
 * {@code tableOid/attnum} 保持 0（DuckDB 不暴露列所属表，见修复说明）。</p>
 */
public class RowDescriptionMetadataTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("rowdesc");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE rd("
                    + " c_bool BOOLEAN,"
                    + " c_i2 SMALLINT, c_i4 INTEGER, c_i8 BIGINT,"
                    + " c_f4 FLOAT, c_f8 DOUBLE,"
                    + " c_dec DECIMAL(18,4), c_dec38 DECIMAL(38,0),"
                    + " c_date DATE, c_time TIME, c_ts TIMESTAMP, c_tstz TIMESTAMPTZ,"
                    + " c_iv INTERVAL, c_uuid UUID,"
                    + " c_varchar VARCHAR, c_text VARCHAR,"
                    + " c_blob BLOB, c_bit BIT, c_json JSON)");
            st.execute("CREATE OR REPLACE TABLE rd_readable(iv INTERVAL, b BIT)");
            st.execute("INSERT INTO rd_readable VALUES (INTERVAL '1 day 02:03:04', '1010'::BIT)");
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/rowdesc", "", "");
    }

    /** 常用的期望值：{@code typeSize} 变长一律 -1。 */
    private static final short VAR = -1;

    private static final Map<String, Object[]> EXPECTED = new LinkedHashMap<>();
    static {
        // 列名 -> {期望 typeOid, 期望 typeSize, 期望 typeModifier}
        EXPECTED.put("c_bool", new Object[]{16, (short) 1, -1});
        EXPECTED.put("c_i2", new Object[]{21, (short) 2, -1});
        EXPECTED.put("c_i4", new Object[]{23, (short) 4, -1});
        EXPECTED.put("c_i8", new Object[]{20, (short) 8, -1});
        EXPECTED.put("c_f4", new Object[]{700, (short) 4, -1});
        EXPECTED.put("c_f8", new Object[]{701, (short) 8, -1});
        // numeric 是变长类型；typmod = ((p << 16) | s) + 4
        EXPECTED.put("c_dec", new Object[]{1700, VAR, ((18 << 16) | 4) + 4});
        EXPECTED.put("c_dec38", new Object[]{1700, VAR, ((38 << 16)) + 4});
        EXPECTED.put("c_date", new Object[]{1082, (short) 4, -1});
        EXPECTED.put("c_time", new Object[]{1083, (short) 8, -1});
        EXPECTED.put("c_ts", new Object[]{1114, (short) 8, -1});
        EXPECTED.put("c_tstz", new Object[]{1184, (short) 8, -1});
        // INTERVAL 声明成 PG 的 interval(1186)，typlen = 16（定长）
        EXPECTED.put("c_iv", new Object[]{1186, (short) 16, -1});
        EXPECTED.put("c_uuid", new Object[]{2950, (short) 16, -1});
        EXPECTED.put("c_varchar", new Object[]{1043, VAR, -1});
        EXPECTED.put("c_text", new Object[]{1043, VAR, -1});
        EXPECTED.put("c_blob", new Object[]{17, VAR, -1});
        // BIT 声明成 PG 的 bit(1560)：此前写 1563（= _varbit，数组 OID）会被客户端当数组读
        EXPECTED.put("c_bit", new Object[]{1560, VAR, -1});
        EXPECTED.put("c_json", new Object[]{114, VAR, -1});
    }

    // ================================================================
    // 一、线协议：逐列的 typeSize / typeModifier（含 tableOid/attnum）
    // ================================================================

    @Test
    void wireRowDescriptionCarriesTypeSizeAndTypeModifier() throws Exception {
        Map<String, int[]> fields;   // name -> {tableOid, attnum, typeOid, typeSize, typeModifier, format}
        try (RawClient client = new RawClient()) {
            client.sendQuery("SELECT * FROM rd");
            fields = client.rowDescription;
        }

        for (Map.Entry<String, Object[]> entry : EXPECTED.entrySet()) {
            String column = entry.getKey();
            int[] field = fields.get(column);
            assert field != null : "RowDescription 里没有列 " + column;

            int expectedOid = (Integer) entry.getValue()[0];
            short expectedSize = (Short) entry.getValue()[1];
            int expectedModifier = (Integer) entry.getValue()[2];

            assert field[2] == expectedOid
                    : column + " 的 typeOid 应为 " + expectedOid + "，实际 " + field[2];
            assert field[3] == expectedSize
                    : column + " 的 typeSize 应为 " + expectedSize
                    + "（pg_type.typlen，变长类型为 -1），实际 " + field[3];
            assert field[4] == expectedModifier
                    : column + " 的 typeModifier 应为 " + expectedModifier
                    + "（atttypmod），实际 " + field[4];
        }

        // tableOid/attnum 的现状决策：保持 0（DuckDB 不暴露列所属表，见修复说明）
        int[] anyField = fields.get("c_i4");
        assert anyField[0] == 0 : "tableOid 目前应保持 0，实际 " + anyField[0];
        assert anyField[1] == 0 : "attnum 目前应保持 0，实际 " + anyField[1];
    }

    /** 变长类型必须发 -1（Int16 的 0xFFFF），而不是 65535 这种"看起来像宽度"的值。 */
    @Test
    void variableLengthTypesReportMinusOne() throws Exception {
        try (RawClient client = new RawClient()) {
            client.sendQuery("SELECT c_varchar, c_dec, c_i4 FROM rd");
            assert client.rowDescription.get("c_varchar")[3] == -1
                    : "VARCHAR 是变长类型，typeSize 必须是 -1，实际 "
                    + client.rowDescription.get("c_varchar")[3];
            assert client.rowDescription.get("c_dec")[3] == -1
                    : "NUMERIC 是变长类型，typeSize 必须是 -1，实际 "
                    + client.rowDescription.get("c_dec")[3];
            assert client.rowDescription.get("c_i4")[3] == 4
                    : "INTEGER 是定长 4 字节，实际 " + client.rowDescription.get("c_i4")[3];
        }
    }

    // ================================================================
    // 二、客户端可见的收益：精度/标度不再丢
    // ================================================================

    @Test
    void clientSeesDecimalPrecisionAndScale() throws Exception {
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT c_dec, c_dec38 FROM rd")) {
            ResultSetMetaData md = rs.getMetaData();
            assert md.getPrecision(1) == 18
                    : "DECIMAL(18,4) 的 precision 应为 18，实际 " + md.getPrecision(1);
            assert md.getScale(1) == 4
                    : "DECIMAL(18,4) 的 scale 应为 4，实际 " + md.getScale(1);
            assert md.getPrecision(2) == 38
                    : "DECIMAL(38,0) 的 precision 应为 38，实际 " + md.getPrecision(2);
            assert md.getScale(2) == 0
                    : "DECIMAL(38,0) 的 scale 应为 0，实际 " + md.getScale(2);
        }
    }

    /** 反向约束：整数/日期等本来没有修饰符的类型，typmod 必须保持 -1。 */
    @Test
    void typesWithoutModifierKeepMinusOne() throws Exception {
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT c_i4, c_date, c_varchar FROM rd")) {
            ResultSetMetaData md = rs.getMetaData();
            assert md.getScale(1) == 0 : "INTEGER 的 scale 应为 0";
            // varchar 无声明长度 → pgjdbc 用它的"未知长度"哨兵值
            assert md.getPrecision(3) > 1000
                    : "VARCHAR（无声明长度）的 precision 应是 pgjdbc 的未知长度哨兵，实际 "
                    + md.getPrecision(3);
        }
    }

    // ================================================================
    // 三、INTERVAL / BIT 必须能被客户端正常读出
    // ================================================================

    /**
     * 读路径契约（<b>标准 PG 驱动</b>）：客户端完全可能是官方 pgjdbc —— 它的内置类型表里同样
     * 没有 interval，所以它和 dbdriver 一样依赖服务端 fake catalog 的"按 OID 取类型名"兜底查询。
     * 这正是"只给某一个驱动补内置类型"救不了标准驱动、必须修服务端的原因。
     */
    @Test
    void intervalAndBitColumnsAreReadable() throws Exception {
        try (Connection conn = connect()) {
            assertIntervalAndBitReadable(conn, "标准 pgjdbc", org.postgresql.util.PGInterval.class);
        }
    }

    /** 同一契约对项目自带的 dbdriver 也必须成立（它走的是同一个 fake catalog）。 */
    @Test
    void intervalIsReadableThroughProjectDriverToo() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:slackerdb://127.0.0.1:" + dbPort + "/rowdesc", "", "")) {
            assertIntervalAndBitReadable(conn, "dbdriver", PGInterval.class);
        }
    }

    /**
     * INTERVAL 与 BIT 两列的读契约：元数据类型名、{@code java.sql.Types}、值（文本 + 解析后的对象）都要对。
     *
     * <p>历史缺陷：INTERVAL 声明 1186 时，两个驱动的内置类型表里都没有 interval，
     * 而兜底查询（{@code pg_type JOIN pg_namespace}）在服务端返回 0 行 →
     * {@code getPGType(1186)} 为 null → {@code initSqlType} 的 {@code castNonNull} 直接抛，
     * 整列连 {@code getColumnTypeName()} 都不可用；BIT 曾经声明成 1563（{@code _varbit} 数组 OID），
     * 客户端按数组解析。</p>
     *
     * @param expectedIntervalClass 该驱动自己的 {@code PGInterval} 实现（官方驱动与 dbdriver 各有一份）
     */
    private void assertIntervalAndBitReadable(Connection conn, String clientName,
                                              Class<?> expectedIntervalClass) throws Exception {
        String expectedInterval;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT CAST(INTERVAL '1 day 02:03:04' AS VARCHAR)")) {
            rs.next();
            expectedInterval = rs.getString(1);
        }

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT iv, b FROM rd_readable")) {
            ResultSetMetaData md = rs.getMetaData();
            assert "interval".equals(md.getColumnTypeName(1))
                    : "[" + clientName + "] INTERVAL 列应按 interval 交付，实际 " + md.getColumnTypeName(1);
            assert md.getColumnType(1) == java.sql.Types.OTHER
                    : "[" + clientName + "] INTERVAL 列的 java.sql.Types 应为 OTHER，实际 " + md.getColumnType(1);
            assert "bit".equals(md.getColumnTypeName(2))
                    : "[" + clientName + "] BIT 列应按 bit 交付，实际 " + md.getColumnTypeName(2);
            assert md.getColumnType(2) == java.sql.Types.BIT
                    : "[" + clientName + "] BIT 列的 java.sql.Types 应为 BIT，实际 " + md.getColumnType(2);

            assert rs.next() : "[" + clientName + "] 应当查到一行";
            assert expectedInterval.equals(rs.getString(1))
                    : "[" + clientName + "] INTERVAL 读回应为 " + expectedInterval
                    + "，实际 " + rs.getString(1);
            Object interval = rs.getObject(1);
            assert expectedIntervalClass.isInstance(interval)
                    : "[" + clientName + "] INTERVAL 的 getObject 应为 " + expectedIntervalClass.getName()
                    + "，实际 " + (interval == null ? "null" : interval.getClass().getName());
            // 不只是"拿到了对象"：值也必须真的解析对了（两个驱动各有自己的 PGInterval，用反射取字段）
            assert 1 == (int) interval.getClass().getMethod("getDays").invoke(interval)
                    && 2 == (int) interval.getClass().getMethod("getHours").invoke(interval)
                    && 3 == (int) interval.getClass().getMethod("getMinutes").invoke(interval)
                    && 4.0 == (double) interval.getClass().getMethod("getSeconds").invoke(interval)
                    : "[" + clientName + "] PGInterval 应从 '" + expectedInterval
                    + "' 解析出 1 day 02:03:04";
            assert "1010".equals(rs.getString(2))
                    : "[" + clientName + "] BIT 读回应为 1010，实际 " + rs.getString(2);
            // 关键回归点：BIT 不能再被当成数组交付
            assert !(rs.getObject(2) instanceof java.sql.Array)
                    : "[" + clientName + "] BIT 的 getObject 不应是 java.sql.Array（1563 是 _varbit 数组 OID）";
        }
    }

    // ================================================================
    // 极简线协议客户端：解析 RowDescription 的每个字段
    // ================================================================

    private static final class RawClient implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        /** 列名 -> {tableOid, attnum, typeOid, typeSize(signed), typeModifier, format} */
        Map<String, int[]> rowDescription = new LinkedHashMap<>();

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
            writeCString(body, "rowdesc");
            body.write(0);
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.writeBytes(Utils.int32ToBytes(bodyBytes.length + 4));
            packet.writeBytes(bodyBytes);
            write(packet.toByteArray());
            readUntilReadyForQuery();
        }

        void sendQuery(String sql) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeCString(body, sql);
            writeFrame('Q', body.toByteArray());
            readUntilReadyForQuery();
        }

        private void readUntilReadyForQuery() throws Exception {
            while (true) {
                byte[] frame = readFrame();
                if (frame == null) {
                    throw new IllegalStateException("连接在 ReadyForQuery 之前被关闭");
                }
                char type = (char) (frame[0] & 0xFF);
                if (type == 'T') {
                    rowDescription = parse(frame);
                } else if (type == 'E') {
                    throw new IllegalStateException("服务端返回了 ErrorResponse");
                } else if (type == 'Z') {
                    return;
                }
            }
        }

        private static Map<String, int[]> parse(byte[] frame) {
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
                int tableOid = int32(frame, pos);
                pos += 4;
                int attnum = int16(frame, pos);
                pos += 2;
                int typeOid = int32(frame, pos);
                pos += 4;
                int typeSize = (short) int16(frame, pos);   // Int16：变长类型是 -1（0xFFFF）
                pos += 2;
                int typeModifier = int32(frame, pos);
                pos += 4;
                int format = int16(frame, pos);
                pos += 2;
                fields.put(name, new int[]{tableOid, attnum, typeOid, typeSize, typeModifier, format});
            }
            return fields;
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
