package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.entity.PostgresTypeOids;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.RowEncoder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 回归测试：结果集行编码（{@code RowEncoder}）。
 *
 * <p>覆盖改造后最容易出问题的两点：</p>
 * <ol>
 *   <li>空值判定由"每个单元格 {@code getObject(i) == null}"改成"按类型的 getter + {@code wasNull()}"，
 *       必须保证每种类型的 NULL 仍然以 SQL NULL 返回，而不是 0 / false / 空串；</li>
 *   <li>{@code TIME} 类型的长度缺陷修复：{@code LocalTime.toString()} 在秒为 0 时只输出 "HH:mm"，
 *       原实现却固定声明 8 字节，会导致 DataRow 长度与实际内容不一致（客户端解析错位）。
 *       现在补齐为 "HH:mm:ss" 并按实际字节数声明长度。</li>
 * </ol>
 *
 * <p>同时验证 extended 协议（PreparedStatement）与 simple query（Statement）两条路径，
 * 因为两者使用不同的 formatCode 策略（二进制 / 全文本）。</p>
 */
public class RowEncoderTest {
    static int dbPort;
    static DBInstance dbInstance;
    static final String protocol = "postgresql";

    @BeforeAll
    static void initAll() throws ServerException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("enc");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    private static Connection connect() throws Exception {
        Connection conn = DriverManager.getConnection(
                "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/enc", "", "");
        conn.setAutoCommit(false);
        return conn;
    }

    /**
     * 覆盖 RowEncoder 的每一个类型分支：非空值正确、NULL 值仍为 NULL。
     */
    @Test
    void allSupportedTypesRoundTripWithNulls() throws Exception {
        try (Connection conn = connect()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE OR REPLACE TABLE encAll (" +
                        "id INTEGER, " +
                        "c_smallint SMALLINT, " +
                        "c_integer INTEGER, " +
                        "c_bigint BIGINT, " +
                        "c_varchar VARCHAR, " +
                        "c_interval INTERVAL, " +
                        "c_date DATE, " +
                        "c_boolean BOOLEAN, " +
                        "c_float FLOAT, " +
                        "c_double DOUBLE, " +
                        "c_timestamp TIMESTAMP, " +
                        "c_time TIME, " +
                        "c_timestamptz TIMESTAMPTZ, " +
                        "c_bit BIT, " +
                        "c_uinteger UINTEGER, " +
                        "c_hugeint HUGEINT, " +
                        "c_ubigint UBIGINT, " +
                        "c_decimal DECIMAL(10,2), " +
                        "c_numeric NUMERIC(12,3))");

                // id=1 全字段非空；id=2 除了 id 以外全部为 NULL；id=3 只填 time（秒非 0 的对照样本）
                stmt.execute("""
                        INSERT INTO encAll VALUES (
                            1, 1, 2, 3, 'abc', INTERVAL 1 DAY, DATE '2020-01-02', TRUE,
                            1.5, 2.5, TIMESTAMP '2020-01-02 03:04:05.678',
                            TIME '02:14:00', TIMESTAMPTZ '2020-01-02 03:04:05.678+00',
                            '1010'::BIT, 10::UINTEGER, 20::HUGEINT, 30::UBIGINT,
                            123.45, 67.890)
                        """);
                stmt.execute("INSERT INTO encAll (id) VALUES (2)");
                stmt.execute("INSERT INTO encAll (id, c_time) VALUES (3, TIME '02:14:34')");
            }
            conn.commit();

            verify(conn, true);
            verify(conn, false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE encAll");
            }
            conn.commit();
        }
    }

    private void verify(Connection conn, boolean extendedProtocol) throws Exception {
        String sql = "SELECT * FROM encAll ORDER BY id";
        // 两条路径：PreparedStatement 走 extended 协议（定长类型为二进制），Statement 走 simple query（全文本）
        try (Statement stmt = extendedProtocol ? conn.prepareStatement(sql) : conn.createStatement();
             ResultSet rs = extendedProtocol ? ((PreparedStatement) stmt).executeQuery() : stmt.executeQuery(sql)) {

            ResultSetMetaData md = rs.getMetaData();
            int columnCount = md.getColumnCount();

            // ---- id = 1：全部非空 ----
            assert rs.next();
            assert rs.getInt("id") == 1;
            assert rs.getShort("c_smallint") == 1;
            assert rs.getInt("c_integer") == 2;
            assert rs.getLong("c_bigint") == 3;
            assert "abc".equals(rs.getString("c_varchar"));
            assert rs.getString("c_interval") != null;
            assert "2020-01-02".equals(rs.getDate("c_date").toString());
            assert rs.getBoolean("c_boolean");
            assert Math.abs(rs.getFloat("c_float") - 1.5f) < 0.0001;
            assert rs.getDouble("c_double") == 2.5;
            assert rs.getTimestamp("c_timestamp").toString().startsWith("2020-01-02 03:04:05.678");
            // H2 修复点：秒为 0 的 TIME 也必须是合法的 "HH:mm:ss"
            assert "02:14:00".equals(rs.getTime("c_time").toString())
                    : "unexpected c_time = " + rs.getTime("c_time");
            assert rs.getTimestamp("c_timestamptz") != null;
            assert rs.getString("c_bit") != null && rs.getString("c_bit").matches("[01]+")
                    : "unexpected c_bit = " + rs.getString("c_bit");
            assert rs.getLong("c_uinteger") == 10;
            assert rs.getLong("c_hugeint") == 20;
            assert rs.getLong("c_ubigint") == 30;
            assert rs.getBigDecimal("c_decimal").compareTo(new java.math.BigDecimal("123.45")) == 0;
            assert rs.getBigDecimal("c_numeric").compareTo(new java.math.BigDecimal("67.890")) == 0;

            // ---- id = 2：除 id 外全部为 NULL ----
            assert rs.next();
            assert rs.getInt("id") == 2;
            for (int i = 2; i <= columnCount; i++) {
                assert rs.getObject(i) == null
                        : "column " + md.getColumnName(i) + " (" + md.getColumnTypeName(i)
                        + ") should be NULL but was " + rs.getObject(i);
                assert rs.wasNull() : "wasNull() should be true for column " + md.getColumnName(i);
                assert md.getColumnName(i) != null;
            }

            // ---- id = 3：秒非 0 的 TIME 对照 ----
            assert rs.next();
            assert rs.getInt("id") == 3;
            assert "02:14:34".equals(rs.getTime("c_time").toString())
                    : "unexpected c_time = " + rs.getTime("c_time");

            assert !rs.next();
        }
    }

    /**
     * 未识别类型仍按文本返回，且不得因为"每单元格一次 WARN"而拖慢——此处只验证前者，
     * 后者由 {@code RowEncoder} 在构造阶段一次性告警保证。
     *
     * <p>注意这里用的是 STRUCT，不是 list：标量元素的 list 从本轮起会按**真正的 PG 数组**
     * 发送（见 {@link #scalarListColumnsAreSentAsPostgresArrays}），而 STRUCT / MAP 在 PG 里
     * 没有对应类型，仍然是文本。</p>
     */
    @Test
    void unknownTypeFallsBackToText() throws Exception {
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT {'a': 1} AS s, 'x' AS t");
                 ResultSet rs = ps.executeQuery()) {
                assert rs.next();
                assert rs.getString("t").equals("x");
                assert rs.getString("s") != null : "unregistered type should still be returned as text";
            }
        }
    }

    /**
     * 回归：DECIMAL 列表（{@code DECIMAL(p,s)[]}）曾被当成**标量** DECIMAL。
     *
     * <p>DuckDB 的列表类型名是"&lt;元素类型&gt;[]"，而标量 DECIMAL 靠名称前缀识别，
     * 于是 {@code DECIMAL(2,1)[]} 命中 {@code startsWith("DECIMAL")} 被判成标量，
     * 行编码阶段对列表调用 {@code rs.getBigDecimal()} → DuckDB JDBC 抛
     * {@code NumberFormatException}，客户端拿不到任何应答而**永久挂起**。</p>
     *
     * <p>这里断言"分类"这一层，不依赖 JDBC 往返：标量元素的列表必须是 {@code T_PG_ARRAY}
     * （绝不能是 {@code T_DECIMAL}），且声明的是真正的 PG 数组 OID；而 PG 没有对应元素的
     * 列表必须仍是 {@code T_UNKNOWN} 的文本路径。</p>
     */
    @Test
    void decimalArrayIsNotTreatedAsScalarDecimal() {
        // 标量元素的列表 → PG 数组（绝不能是标量 DECIMAL）
        assertEquals(RowEncoder.T_PG_ARRAY, RowEncoder.typeCodeOf("DECIMAL(2,1)[]"),
                "DECIMAL 列表必须是 PG 数组，不能被当成标量 DECIMAL");
        assertEquals(RowEncoder.T_PG_ARRAY, RowEncoder.typeCodeOf("DECIMAL(18,3)[]"));
        assertEquals(RowEncoder.T_PG_ARRAY, RowEncoder.typeCodeOf("DECIMAL[]"));
        assertEquals(RowEncoder.T_PG_ARRAY, RowEncoder.typeCodeOf("INTEGER[]"));
        assertEquals(RowEncoder.T_PG_ARRAY, RowEncoder.typeCodeOf("VARCHAR[]"));
        // 标量 DECIMAL 不能因为这次修复被改坏
        assertEquals(RowEncoder.T_DECIMAL, RowEncoder.typeCodeOf("DECIMAL(18,4)"));
        assertEquals(RowEncoder.T_DECIMAL, RowEncoder.typeCodeOf("DECIMAL"));

        // RowDescription 里声明的必须是 PG 数组 OID：
        // DECIMAL 列表曾经被前缀匹配改写成 NUMERIC(1700) 却发文本，客户端按 numeric 解析必然失败。
        assertEquals(1231, PostgresTypeOids.getTypeOidFromTypeName("DECIMAL(2,1)[]"),
                "DECIMAL 列表必须声明 numeric[]（1231），不能声明成标量 NUMERIC");
        assertEquals(1007, PostgresTypeOids.getTypeOidFromTypeName("INTEGER[]"));
        assertEquals(1015, PostgresTypeOids.getTypeOidFromTypeName("VARCHAR[]"));
        assertEquals(1016, PostgresTypeOids.getTypeOidFromTypeName("BIGINT[]"));
        // 标量仍是原来的 OID
        assertEquals(1700, PostgresTypeOids.getTypeOidFromTypeName("DECIMAL(18,4)"));

        // PG 里没有对应元素的列表（STRUCT / MAP / 多层嵌套）→ 保持文本路径与 VARCHAR 声明
        assertEquals(RowEncoder.T_UNKNOWN, RowEncoder.typeCodeOf("STRUCT(a INTEGER)[]"));
        assertEquals(RowEncoder.T_UNKNOWN, RowEncoder.typeCodeOf("MAP(INTEGER, INTEGER)[]"));
        assertEquals(RowEncoder.T_UNKNOWN, RowEncoder.typeCodeOf("INTEGER[][]"),
                "多层嵌套暂不支持，必须回退文本而不是发错的数组");
        assertEquals(1043, PostgresTypeOids.getTypeOidFromTypeName("INTEGER[][]"));
        assertEquals(1043, PostgresTypeOids.getTypeOidFromTypeName("STRUCT(a INTEGER)[]"));
    }

    /**
     * 端到端：标量元素的列表按 PostgreSQL 数组发送。
     *
     * <p>改前它们是 VARCHAR + DuckDB 的 repr（{@code [1, 2, 3]}）—— 既拿不到 list，
     * 内容也不是合法的 PG 数组字面量，ORM / BI 只能当普通文本。改后声明真实数组 OID、
     * 值是 {@code {1,2,3}}，客户端驱动会直接解成数组。</p>
     */
    @Test
    void scalarListColumnsAreSentAsPostgresArrays() throws Exception {
        try (Connection conn = connect()) {
            checkArray(conn, "SELECT [1, 2, 3] AS arr", "{1,2,3}");
            checkArray(conn, "SELECT [1.5, 2.5]::numeric[] AS arr", "{1.500,2.500}");
            checkArray(conn, "SELECT ['a', 'b'] AS arr", "{a,b}");
            checkArray(conn, "SELECT [true, false] AS arr", "{t,f}");
            checkArray(conn, "SELECT []::int[] AS arr", "{}");
            checkArray(conn, "SELECT [NULL, 1]::int[] AS arr", "{NULL,1}");
            // 需要转义的元素：逗号 / 双引号 / 反斜杠 / 空串 / 字面量 NULL 都必须加引号
            checkArray(conn, "SELECT ['has,comma'] AS arr", "{\"has,comma\"}");
            checkArray(conn, "SELECT ['has\"quote'] AS arr", "{\"has\\\"quote\"}");
            checkArray(conn, "SELECT [''] AS arr", "{\"\"}");
            checkArray(conn, "SELECT ['NULL'] AS arr", "{\"NULL\"}");

            // 不支持的类型必须保持原来的文本行为（不能回归成数组）
            try (PreparedStatement ps = conn.prepareStatement("SELECT {'a': 1} AS s");
                 ResultSet rs = ps.executeQuery()) {
                assert rs.next();
                assert "{'a': 1}".equals(rs.getString("s")) : "STRUCT 应当保持文本";
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT [[1,2],[3,4]] AS m");
                 ResultSet rs = ps.executeQuery()) {
                assert rs.next();
                assert "[[1, 2], [3, 4]]".equals(rs.getString("m"))
                        : "多层嵌套暂不支持，应当保持文本，实际 " + rs.getString("m");
            }
        }
    }

    /** 断言查询结果的声明类型是数组、且值是期望的 PG 数组字面量（simple + extended 两条路径）。 */
    private void checkArray(Connection conn, String sql, String expected) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assert rs.next() : sql + " 应当返回一行";
            assertEquals(expected, rs.getString(1), sql + " 的数组字面量不对");
            assert rs.getMetaData().getColumnTypeName(1).endsWith("[]")
                    || rs.getMetaData().getColumnTypeName(1).startsWith("_")
                    : sql + " 应当声明成数组类型，实际 "
                      + rs.getMetaData().getColumnTypeName(1);
            assert !rs.next();
        }
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assert rs.next() : sql + " 应当返回一行（extended 协议）";
            assertEquals(expected, rs.getString(1), sql + "（extended）的数组字面量不对");
        }
    }


    /**
     * 回归（端到端）：DECIMAL 列表查询必须**正常返回**，而不是把连接挂死。
     *
     * <p>修好分类之后这条查询声明成 numeric[] 并返回 PG 数组字面量（{@code {1.500,2.500}}）。
     * simple query 与 extended 协议两条路径都要覆盖，因为两者使用不同的 formatCode 策略。</p>
     *
     * <p>关于"万一又回归了会不会把 CI 挂住"：{@code setQueryTimeout} 让驱动在超时后发取消请求；
     * 即使取消也拿不到应答，{@code PostgresServerHandler} 的兜底也会"回错误 + 关闭连接"，
     * 所以最坏情况是测试失败，而不是无限等待。</p>
     */
    @Test
    void decimalArrayQueryReturnsInsteadOfHanging() throws Exception {
        try (Connection conn = connect()) {
            for (String sql : new String[]{
                    "SELECT [1.5, 2.5] AS arr",
                    "SELECT array[1.5, 2.5]::numeric[] AS arr"}) {

                // ---- simple query 路径（全文本）----
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(20);
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        assertTrue(rs.next(), sql + " 应该返回一行");
                        String value = rs.getString("arr");
                        assertNotNull(value, sql + " 的列表值不能是 NULL");
                        assertTrue(value.contains("1.5"),
                                "unexpected value for " + sql + " -> " + value);
                        // 必须声明成**数组**类型（numeric[]），绝不能是标量 numeric：
                        // 声明成 numeric 的话客户端会按 numeric 去解析 "{1.500,2.500}" 而失败。
                        String typeName = rs.getMetaData().getColumnTypeName(1);
                        assertTrue(typeName.endsWith("[]") || typeName.startsWith("_"),
                                "列表列必须声明成数组类型，实际是: " + typeName);
                        assertTrue(!rs.next(), sql + " 只应返回一行");
                    }
                }

                // ---- extended 协议路径（PreparedStatement）----
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setQueryTimeout(20);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), sql + " 应该返回一行（extended 协议）");
                        String value = rs.getString("arr");
                        assertNotNull(value);
                        assertTrue(value.contains("1.5"),
                                "unexpected value for " + sql + " (extended) -> " + value);
                    }
                }
            }

            // 出错/编码异常之后连接必须仍然可用（协议不能错位、连接不能被静默作废）
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 42")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1));
            }
        }
    }
}
