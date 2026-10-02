package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.test.support.PgWireClient;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结果集"声明格式 vs 实际字节"一致性契约测试。
 *
 * <p><b>被测缺陷</b>：{@code RowDescription} 里每一列的 format code 声明了这一列的字节该怎么解
 * （0 = 文本，1 = 二进制），服务端必须保证声明与实际发出去的内容一致。</p>
 *
 * <p>而 {@code RowEncoder} 里 SMALLINT / INTEGER / BIGINT / BOOLEAN / FLOAT / DOUBLE / DATE
 * 这 7 个定长类型是<b>无条件</b>按二进制写的（只有 BYTEA 看 formatCode），
 * 简单查询路径（{@code QueryRequest} 用 {@code binaryAllowed=false}）于是
 * <b>声明 text、实际发二进制</b>：</p>
 *
 * <pre>
 *   SELECT 7   (简单查询 'Q')
 *   RowDescription: typeOid=23(int4), typeSize=4, formatCode=0   ← 声明文本
 *   DataRow       : 长度 4, 内容 00 00 00 07                      ← 实际二进制
 * </pre>
 *
 * <p>后果（用真实 pgjdbc 实测）：{@code preferQueryMode=simple} 下
 * {@code getInt()} 抛 "Bad value for type int"，{@code getDouble()} 抛
 * "Bad value for type double"，而 <b>{@code SELECT TRUE} 不报错、直接返回 false</b>
 * —— 静默错值是最坏的一档。psql / libpq {@code PQexec} / psycopg2 的非参数化查询
 * 走的都是简单查询，所以这不是理论问题。</p>
 *
 * <p>本用例把"同一条 SQL 在两种协议路径下，声明的格式必须与实际字节一致、
 * 且解出来的逻辑值必须相同"写成契约：</p>
 * <ol>
 *   <li>简单查询路径：所有列必须声明 format=0，且值必须是<b>文本</b>；</li>
 *   <li>扩展协议路径：定长类型声明 format=1，值必须是<b>对应宽度的二进制</b>；</li>
 *   <li>两条路径解出来的逻辑值必须完全一致（这才是客户端真正关心的）。</li>
 * </ol>
 */
public class RowFormatConsistencyTest {

    /** 一列的期望：二进制宽度 < 0 表示该类型在扩展协议下也走文本。 */
    private record ColumnSpec(String name, int binaryWidth, String expectedText) {
    }

    private static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec("c_smallint", 2, "7"),
            new ColumnSpec("c_int", 4, "42"),
            new ColumnSpec("c_bigint", 8, "1234567890123"),
            new ColumnSpec("c_bool", 1, "t"),
            new ColumnSpec("c_real", 4, "1.5"),
            new ColumnSpec("c_double", 8, "3.25"),
            new ColumnSpec("c_date", 4, "2026-01-02"),
            // 对照组：本来就是文本编码的类型，改前改后都必须正确
            new ColumnSpec("c_varchar", -1, "ok"),
            new ColumnSpec("c_decimal", -1, "12.34"));

    private static final String SELECT_ALL =
            "SELECT c_smallint, c_int, c_bigint, c_bool, c_real, c_double, c_date,"
                    + " c_varchar, c_decimal FROM fmt_t";

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("rowfmt");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        try (Connection conn = connect("extended"); Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE fmt_t("
                    + " c_smallint SMALLINT, c_int INTEGER, c_bigint BIGINT, c_bool BOOLEAN,"
                    + " c_real FLOAT, c_double DOUBLE, c_date DATE,"
                    + " c_varchar VARCHAR, c_decimal DECIMAL(10,2))");
            st.execute("INSERT INTO fmt_t VALUES (7, 42, 1234567890123, TRUE, 1.5, 3.25,"
                    + " DATE '2026-01-02', 'ok', 12.34)");
        }
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static Connection connect(String queryMode) throws Exception {
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/rowfmt?preferQueryMode=" + queryMode, "", "");
    }

    private static PgWireClient newClient() throws Exception {
        return new PgWireClient("127.0.0.1", dbPort, "rowfmt", 10_000);
    }

    // ================================================================
    // 一、简单查询路径：必须声明文本，且必须真的发文本
    // ================================================================

    @Test
    void simpleQueryDeclaresTextAndActuallySendsText() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendQuery(SELECT_ALL);
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            List<PgWireClient.FieldDesc> fields =
                    PgWireClient.parseRowDescription(PgWireClient.ofType(frames, 'T').get(0));
            List<byte[]> values = PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0));
            assertEquals(COLUMNS.size(), fields.size(), "列数不符");

            for (int i = 0; i < COLUMNS.size(); i++) {
                ColumnSpec spec = COLUMNS.get(i);
                PgWireClient.FieldDesc field = fields.get(i);
                assertEquals(spec.name(), field.name, "第 " + i + " 列的名字不符");
                assertEquals(0, field.formatCode,
                        "简单查询路径必须声明文本格式（PG 规范），列 " + spec.name()
                                + " 实际声明 format=" + field.formatCode);

                // 声明了文本，就必须是文本；这里同时覆盖"报错"与"静默错值"两种坏结果
                assertEquals(spec.expectedText(), PgWireClient.textValue(values.get(i)),
                        "列 " + spec.name() + " 声明为文本，但值是二进制字节"
                                + "（原始字节=" + Arrays.toString(values.get(i)) + "）");
            }
        }
    }

    // ================================================================
    // 二、扩展协议路径：定长类型声明二进制，且宽度必须对得上
    // ================================================================

    @Test
    void extendedQueryDeclaresBinaryWithMatchingWidth() throws Exception {
        try (PgWireClient client = newClient()) {
            client.sendParse("fmt_s", SELECT_ALL);
            client.sendBind("fmt_p", "fmt_s");
            client.sendDescribe('P', "fmt_p");
            client.sendExecute("fmt_p", 0);
            client.sendSync();
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();

            List<PgWireClient.FieldDesc> fields =
                    PgWireClient.parseRowDescription(PgWireClient.ofType(frames, 'T').get(0));
            List<byte[]> values = PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0));

            for (int i = 0; i < COLUMNS.size(); i++) {
                ColumnSpec spec = COLUMNS.get(i);
                PgWireClient.FieldDesc field = fields.get(i);
                byte[] value = values.get(i);

                if (spec.binaryWidth() < 0) {
                    // 对照组：这些类型在两条路径下都是文本
                    assertEquals(0, field.formatCode, "列 " + spec.name() + " 应为文本格式");
                    assertEquals(spec.expectedText(), PgWireClient.textValue(value),
                            "列 " + spec.name() + " 的文本值不符");
                } else {
                    assertEquals(1, field.formatCode,
                            "扩展协议下定长类型应声明二进制格式，列 " + spec.name());
                    assertEquals(spec.binaryWidth(), value.length,
                            "列 " + spec.name() + " 声明的格式与实际字节数不符");
                }
            }
        }
    }

    // ================================================================
    // 三、两条路径解出来的逻辑值必须一致（客户端真正关心的不变量）
    // ================================================================

    @Test
    void bothProtocolPathsYieldTheSameLogicalValues() throws Exception {
        List<String> simple = readValues("simple");
        List<String> extended = readValues("extended");
        assertEquals(simple, extended,
                "同一条 SQL 在简单查询与扩展协议下解出的值不一致（声明格式与实际字节不匹配）");
        assertEquals(COLUMNS.stream().map(ColumnSpec::expectedText).toList(), simple,
                "解出的值与期望不符");
    }

    /**
     * 用某种协议路径读回全部列，并按 RowDescription 声明的格式解码成规范字符串。
     * 解码完全依据<b>服务端声明的 formatCode</b>，因此一旦声明与实际不符就会暴露。
     */
    private static List<String> readValues(String mode) throws Exception {
        try (PgWireClient client = newClient()) {
            if (mode.equals("simple")) {
                client.sendQuery(SELECT_ALL);
            } else {
                client.sendParse("", SELECT_ALL);
                client.sendBind("", "");
                client.sendDescribe('P', "");
                client.sendExecute("", 0);
                client.sendSync();
            }
            List<PgWireClient.Frame> frames = client.readUntilReadyForQuery();
            List<PgWireClient.FieldDesc> fields =
                    PgWireClient.parseRowDescription(PgWireClient.ofType(frames, 'T').get(0));
            List<byte[]> values = PgWireClient.dataRowValues(PgWireClient.ofType(frames, 'D').get(0));

            List<String> decoded = new ArrayList<>();
            for (int i = 0; i < fields.size(); i++) {
                decoded.add(decode(fields.get(i), values.get(i)));
            }
            return decoded;
        }
    }

    /** 严格按声明的 formatCode 解码（0 = 文本，1 = 二进制）。 */
    private static String decode(PgWireClient.FieldDesc field, byte[] value) {
        if (value == null) {
            return null;
        }
        if (field.formatCode == 0) {
            return new String(value, StandardCharsets.UTF_8);
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return switch (field.typeOid) {
            case 21 -> Short.toString(buffer.getShort());          // int2
            case 23 -> Integer.toString(buffer.getInt());          // int4
            case 20 -> Long.toString(buffer.getLong());            // int8
            case 16 -> buffer.get() != 0 ? "t" : "f";              // bool
            case 700 -> Float.toString(buffer.getFloat());         // float4
            case 701 -> Double.toString(buffer.getDouble());       // float8
            case 1082 -> LocalDate.ofEpochDay(                         // date：PG 纪元 2000-01-01
                    LocalDate.of(2000, 1, 1).toEpochDay() + buffer.getInt()).toString();
            default -> throw new IllegalStateException(
                    "测试不认识声明为二进制的类型 oid=" + field.typeOid + "（列 " + field.name + "）");
        };
    }

    // ================================================================
    // 四、用户可见症状：真实驱动在 simple 模式下必须能正确读出值
    // ================================================================

    @Test
    void jdbcSimpleQueryModeReadsTypedValuesCorrectly() throws Exception {
        try (Connection conn = connect("simple");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(SELECT_ALL)) {
            assertTrue(rs.next(), "没有返回数据行");

            assertEquals(7, rs.getInt("c_smallint"), "SMALLINT 读错");
            assertEquals(42, rs.getInt("c_int"), "INTEGER 读错");
            assertEquals(1234567890123L, rs.getLong("c_bigint"), "BIGINT 读错");
            assertTrue(rs.getBoolean("c_bool"), "BOOLEAN 读错（最简单的坏结果是静默返回 false）");
            assertEquals(1.5f, rs.getFloat("c_real"), 0.0f, "FLOAT 读错");
            assertEquals(3.25d, rs.getDouble("c_double"), 0.0d, "DOUBLE 读错");
            assertEquals(LocalDate.of(2026, 1, 2), rs.getDate("c_date").toLocalDate(), "DATE 读错");
            assertEquals("ok", rs.getString("c_varchar"), "VARCHAR 读错");
            assertEquals("12.34", rs.getBigDecimal("c_decimal").toPlainString(), "DECIMAL 读错");
        }
    }

    /** 反向确认：扩展协议（默认模式）本来就是对的，别在修复时改坏。 */
    @Test
    void jdbcExtendedQueryModeStillReadsTypedValuesCorrectly() throws Exception {
        try (Connection conn = connect("extended");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(SELECT_ALL)) {
            assertTrue(rs.next(), "没有返回数据行");
            assertEquals(7, rs.getInt("c_smallint"));
            assertEquals(42, rs.getInt("c_int"));
            assertEquals(1234567890123L, rs.getLong("c_bigint"));
            assertTrue(rs.getBoolean("c_bool"));
            assertEquals(1.5f, rs.getFloat("c_real"), 0.0f);
            assertEquals(3.25d, rs.getDouble("c_double"), 0.0d);
            assertEquals(LocalDate.of(2026, 1, 2), rs.getDate("c_date").toLocalDate());
            assertNotNull(rs.getString("c_varchar"));
        }
    }
}
