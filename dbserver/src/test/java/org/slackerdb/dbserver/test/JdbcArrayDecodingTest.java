package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：DuckDB 的 list 列按**真正的 PostgreSQL 数组**发送。
 *
 * <p>改造前这些列声明成 VARCHAR(1043)、值是 DuckDB 的 repr（{@code [1, 2, 3]}）：
 * 既不是合法的 PG 数组字面量，客户端也只能拿到字符串。改造后声明 PG 标准数组 OID
 * （int4[]=1007、numeric[]=1231、varchar[]=1015…）、值是 {@code {1,2,3}}，
 * 驱动会解成 {@link Array}。</p>
 *
 * <p>两个 JDBC 驱动都必须覆盖，因为它们是两条独立的解码路径，而且原生驱动是本项目
 * 随包发布的：{@code jdbc:postgresql://}（标准 pgjdbc）与 {@code jdbc:slackerdb://}（原生）。</p>
 *
 * <p>这里刻意只断言 PG 标准数组 OID 带来的行为（类型名 {@code _int4}、{@code sqlType=2033}
 * 这类以驱动内置类型表为准）：这些 OID 是客户端"认识"的，正是选它们而不是自造 OID 的原因 ——
 * 自造 OID 会让客户端"按 OID 反查类型名"的兜底查询落空（INTERVAL 当年就栽在这里）。</p>
 */
public class JdbcArrayDecodingTest {
    static int dbPort;
    static DBInstance dbInstance;
    static final String DB = "jdbcarray";

    /** 两种驱动都要覆盖。 */
    static final String[] PROTOCOLS = {"postgresql", "slackerdb"};

    @BeforeAll
    static void initAll() throws ServerException {
        // 固定 UTC，避免日期/时间戳断言受时区影响（与 RowEncoderTest 同样的处理）
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData(DB);
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

    private static Connection connect(String protocol) throws Exception {
        return DriverManager.getConnection(
                "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/" + DB, "", "");
    }

    // ==================================================================
    // 1. 标量元素的 list → java.sql.Array
    // ==================================================================

    @Test
    void scalarListColumnsDecodeToJavaArrays() throws Exception {
        for (String protocol : PROTOCOLS) {
            try (Connection conn = connect(protocol)) {
                assertArrayCase(conn, "SELECT [1, 2, 3] AS arr",
                        "int4", "{1,2,3}", new Integer[]{1, 2, 3});
                assertArrayCase(conn, "SELECT [1, 2]::smallint[] AS arr",
                        "int2", "{1,2}", new Short[]{1, 2});
                assertArrayCase(conn, "SELECT [1, 2]::bigint[] AS arr",
                        "int8", "{1,2}", new Long[]{1L, 2L});
                assertArrayCase(conn, "SELECT [1.5, 2.5]::numeric[] AS arr",
                        "numeric", "{1.500,2.500}",
                        new BigDecimal[]{new BigDecimal("1.500"), new BigDecimal("2.500")});
                assertArrayCase(conn, "SELECT [1.5, 2.5]::float8[] AS arr",
                        "float8", "{1.5,2.5}", new Double[]{1.5, 2.5});
                assertArrayCase(conn, "SELECT [true, false] AS arr",
                        "bool", "{t,f}", new Boolean[]{Boolean.TRUE, Boolean.FALSE});
                assertArrayCase(conn, "SELECT ['a', 'b'] AS arr",
                        "varchar", "{a,b}", new String[]{"a", "b"});
                assertArrayCase(conn, "SELECT ['2020-01-02'::date, '2021-03-04'::date] AS arr",
                        "date", "{2020-01-02,2021-03-04}",
                        new Date[]{Date.valueOf("2020-01-02"), Date.valueOf("2021-03-04")});
            }
        }
    }

    /** 数组元素的转义：含逗号 / 双引号 / 反斜杠 / 空串 / 字面量 NULL 都必须能原样往返。 */
    @Test
    void arrayElementTextIsEscapedLikePostgres() throws Exception {
        for (String protocol : PROTOCOLS) {
            try (Connection conn = connect(protocol)) {
                assertArrayCase(conn, "SELECT ['has,comma', 'x'] AS arr",
                        "varchar", "{\"has,comma\",x}",
                        new String[]{"has,comma", "x"});
                assertArrayCase(conn, "SELECT ['has\"quote'] AS arr",
                        "varchar", "{\"has\\\"quote\"}",
                        new String[]{"has\"quote"});
                assertArrayCase(conn, "SELECT [''] AS arr",
                        "varchar", "{\"\"}", new String[]{""});
                // 字面量 "NULL" 必须加引号，否则会被读成 SQL NULL 元素
                assertArrayCase(conn, "SELECT ['NULL'] AS arr",
                        "varchar", "{\"NULL\"}", new String[]{"NULL"});
            }
        }
    }

    /** NULL 元素、空数组、以及 SQL NULL 数组。 */
    @Test
    void nullAndEmptyArrays() throws Exception {
        for (String protocol : PROTOCOLS) {
            try (Connection conn = connect(protocol)) {
                assertArrayCase(conn, "SELECT [NULL, 1]::int[] AS arr",
                        "int4", "{NULL,1}", new Integer[]{null, 1});
                assertArrayCase(conn, "SELECT []::int[] AS arr",
                        "int4", "{}", new Integer[0]);

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT NULL::int[] AS arr")) {
                    assertTrue(rs.next());
                    assertNull(rs.getString(1), "SQL NULL 数组的文本应当是 null");
                    assertNull(rs.getObject(1), "SQL NULL 数组的 getObject 应当是 null");
                    assertNull(rs.getArray(1), "SQL NULL 数组的 getArray 应当是 null");
                    // 即使值为 NULL，列本身仍然声明成数组类型
                    assertEquals(Types.ARRAY, rs.getMetaData().getColumnType(1));
                }
            }
        }
    }

    /** 表列（不只是一次性表达式）也必须按数组发送。 */
    @Test
    void listColumnsInTablesAreArraysToo() throws Exception {
        for (String protocol : PROTOCOLS) {
            try (Connection conn = connect(protocol)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE OR REPLACE TABLE arr_tbl(id INTEGER, "
                            + "tags VARCHAR[], nums INTEGER[])");
                    stmt.execute("INSERT INTO arr_tbl VALUES (1, ['a','b'], [1,2])");
                }
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT tags, nums FROM arr_tbl ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals("varchar", ((Array) rs.getObject("tags")).getBaseTypeName());
                    assertArrayEquals(new String[]{"a", "b"}, (Object[]) rs.getArray("tags").getArray());
                    assertArrayEquals(new Integer[]{1, 2}, (Integer[]) rs.getArray("nums").getArray());
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE arr_tbl");
                }
            }
        }
    }

    // ==================================================================
    // 2. 不支持的列表必须保持原样（PG 里没有对应类型）
    // ==================================================================

    /**
     * STRUCT / MAP / 多层嵌套在 PG 里没有对应类型，必须继续按文本返回：
     * 这是本版有意划下的边界，钉住它是为了将来"顺手"改成数组时不至于静默改变语义。
     */
    @Test
    void unsupportedListTypesStayText() throws Exception {
        for (String protocol : PROTOCOLS) {
            try (Connection conn = connect(protocol)) {
                assertTextCase(conn, "SELECT {'a': 1} AS v", "{'a': 1}");
                assertTextCase(conn, "SELECT map([1],[2]) AS v", "{1=2}");
                assertTextCase(conn, "SELECT [[1,2],[3,4]] AS v", "[[1, 2], [3, 4]]");
                assertTextCase(conn, "SELECT [{'a': 1},{'a': 2}] AS v", "[{'a': 1}, {'a': 2}]");
            }
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * 断言：列声明成数组类型、{@code getObject()} 是 {@link Array}、
     * 元素解码成期望的 Java 数组，且文本形式是期望的 PG 数组字面量。
     */
    private static void assertArrayCase(Connection conn, String sql, String expectedBaseType,
                                        String expectedText, Object[] expected) throws Exception {
        String label = "[" + sql + "]";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            assertEquals(Types.ARRAY, metaData.getColumnType(1),
                    label + " 必须声明成数组类型（java.sql.Types.ARRAY）");
            String typeName = metaData.getColumnTypeName(1);
            assertTrue(typeName.startsWith("_") || typeName.endsWith("[]"),
                    label + " 的类型名应当是 PG 数组类型，实际 " + typeName);
            assertEquals("java.sql.Array", metaData.getColumnClassName(1),
                    label + " 的列类名应当是 java.sql.Array");

            assertTrue(rs.next(), label + " 应当返回一行");
            Object raw = rs.getObject(1);
            assertTrue(raw instanceof Array,
                    label + " getObject() 应当是 java.sql.Array，实际 "
                            + (raw == null ? "null" : raw.getClass().getName()));
            Array array = (Array) raw;
            assertEquals(expectedBaseType, array.getBaseTypeName(),
                    label + " 的数组元素类型名不对");
            assertArrayEquals(expected, (Object[]) array.getArray(),
                    label + " 解码出的 Java 数组不对");
            assertEquals(expectedText, rs.getString(1), label + " 的数组字面量文本不对");
            assertFalse(rs.next(), label + " 只应返回一行");
        }
    }

    /** 断言：列**不是**数组类型，值按文本返回（未支持类型的既有行为）。 */
    private static void assertTextCase(Connection conn, String sql, String expectedText)
            throws Exception {
        String label = "[" + sql + "]";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next(), label + " 应当返回一行");
            assertTrue(rs.getMetaData().getColumnType(1) != Types.ARRAY,
                    label + " 不应当是数组类型（PG 里没有对应类型）");
            Object raw = rs.getObject(1);
            assertTrue(raw instanceof String,
                    label + " getObject() 应当仍是字符串，实际 "
                            + (raw == null ? "null" : raw.getClass().getName()));
            assertEquals(expectedText, rs.getString(1), label + " 的文本不对");
        }
    }

    /** TIME[] 也要能解成 java.sql.Time 数组。 */
    @Test
    void timeArrayDecodes() throws Exception {
        for (String protocol : PROTOCOLS) {
            try (Connection conn = connect(protocol)) {
                assertArrayCase(conn, "SELECT ['13:45:06'::time] AS arr",
                        "time", "{13:45:06}", new Time[]{Time.valueOf("13:45:06")});
            }
        }
    }

    /** 时间戳数组：只断言结构化结果（文本里的微秒尾数由引擎决定，不适合钉死）。 */
    @Test
    void timestampArrayDecodes() throws Exception {
        for (String protocol : PROTOCOLS) {
            try (Connection conn = connect(protocol)) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT ['2020-01-02 03:04:05'::timestamp] AS arr")) {
                    assertTrue(rs.next());
                    Object raw = rs.getObject(1);
                    assertNotNull(raw, "时间戳数组不应当是 null");
                    assertTrue(raw instanceof Array, "时间戳数组应当是 java.sql.Array");
                    Object[] elements = (Object[]) ((Array) raw).getArray();
                    assertEquals(1, elements.length);
                    assertTrue(elements[0] instanceof Timestamp,
                            "元素应当是 java.sql.Timestamp，实际 " + elements[0].getClass().getName());
                    assertEquals(Timestamp.valueOf("2020-01-02 03:04:05"), elements[0]);
                }
            }
        }
    }
}
