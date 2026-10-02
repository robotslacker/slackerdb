package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * COPY 列类型支持契约：<b>除块 Appender 建不起来的 {@code BIT / INTERVAL / TIME_NS / BIGNUM / VARIANT}
 * 明确报错外，其余 DuckDB 类型都必须能 COPY 进来</b>。
 *
 * <p>写入通道<b>只有一条</b>：{@code DuckDBAppender}（块 Appender）。它按列物理类型分派、
 * 不做任何隐式转换，所以 CSV 字段的文本由 {@code CopyValueWriters} 按 DuckDB 列类型名
 * 解析成精确的 Java 值（每种列类型一个写入器，构造一次、逐行复用）。
 * <b>没有中转表、也没有收尾的二次 INSERT</b>：数据直接写进目标表，
 * 整条 COPY 由服务端事务包住，失败即整体回滚。</p>
 *
 * <p>每个用例都验证：COPY 行数、目标列的读回值（与 DuckDB 自己对该字面量的文本表示比对）、
 * 以及 COPY 前后库里的表集合不变（不留任何多余的表）。另外单独验证写入通道的原子性
 * （有一行坏数据时整体不生效）。</p>
 */
public class CopyTypeSupportTest {

    static int dbPort;
    static DBInstance dbInstance;

    record TypeCase(String label, String ddl, String prelude, String literal, String csvValue,
                    boolean rejected) {}

    static final List<TypeCase> CASES = new ArrayList<>();

    static void add(String label, String ddl, String literal, String csvValue) {
        CASES.add(new TypeCase(label, ddl, null, literal, csvValue, false));
    }

    static void addT(String label, String ddl, String prelude, String literal, String csvValue) {
        CASES.add(new TypeCase(label, ddl, prelude, literal, csvValue, false));
    }

    static void addRejected(String label, String ddl, String literal, String csvValue) {
        CASES.add(new TypeCase(label, ddl, null, literal, csvValue, true));
    }

    static {
        // ---- 标量 ----
        add("BOOLEAN", "BOOLEAN", "TRUE", "true");
        add("TINYINT", "TINYINT", "-42", "-42");
        add("SMALLINT", "SMALLINT", "-12345", "-12345");
        add("INTEGER", "INTEGER", "123456789", "123456789");
        add("BIGINT", "BIGINT", "1234567890123", "1234567890123");
        add("HUGEINT", "HUGEINT", "170141183460469231731687303715884105727",
                "170141183460469231731687303715884105727");
        add("UTINYINT", "UTINYINT", "250", "250");
        add("USMALLINT", "USMALLINT", "60000", "60000");
        add("UINTEGER", "UINTEGER", "4000000000", "4000000000");
        add("UBIGINT", "UBIGINT", "18446744073709551615", "18446744073709551615");
        add("UHUGEINT", "UHUGEINT", "340282366920938463463374607431768211455",
                "340282366920938463463374607431768211455");
        add("FLOAT", "FLOAT", "1.5", "1.5");
        add("DOUBLE", "DOUBLE", "3.141592653589793", "3.141592653589793");
        add("DECIMAL(18,4)", "DECIMAL(18,4)", "12345678.9012", "12345678.9012");
        add("VARCHAR", "VARCHAR", "'hello 世界'", "hello 世界");
        add("BLOB", "BLOB", "from_hex('DEADBEEF')", "\\xDE\\xAD\\xBE\\xEF");
        add("UUID", "UUID", "'123e4567-e89b-12d3-a456-426614174000'::UUID",
                "123e4567-e89b-12d3-a456-426614174000");
        add("JSON", "JSON", "'{\"a\": 1}'::JSON", "{\"a\": 1}");
        addT("ENUM", "mood", "CREATE OR REPLACE TYPE mood AS ENUM ('sad','ok','happy')",
                "'happy'::mood", "happy");

        // ---- 日期时间 ----
        add("DATE", "DATE", "DATE '2024-02-29'", "2024-02-29");
        add("TIME", "TIME", "TIME '12:34:56.789'", "12:34:56.789");
        add("TIMETZ", "TIMETZ", "TIMETZ '12:34:56.789+08'", "12:34:56.789+08");
        add("TIMESTAMP(带小数秒)", "TIMESTAMP", "TIMESTAMP '2024-02-29 12:34:56.789'",
                "2024-02-29 12:34:56.789");
        add("TIMESTAMP_S", "TIMESTAMP_S", "TIMESTAMP_S '2024-02-29 12:34:56'", "2024-02-29 12:34:56");
        add("TIMESTAMP_MS", "TIMESTAMP_MS", "TIMESTAMP_MS '2024-02-29 12:34:56.789'",
                "2024-02-29 12:34:56.789");
        add("TIMESTAMP_NS", "TIMESTAMP_NS", "TIMESTAMP_NS '2024-02-29 12:34:56.789123456'",
                "2024-02-29 12:34:56.789123456");
        add("TIMESTAMPTZ", "TIMESTAMPTZ", "TIMESTAMPTZ '2024-02-29 12:34:56.789+08'",
                "2024-02-29 12:34:56.789+08");
        // INTERVAL：连 createAppender() 都建不起来（unsupported C API type: 15），
        // 整表因此走不了 Appender 通道，放在最后的"明确拒绝"区块里。

        // ---- 复合类型 ----
        add("LIST(INTEGER)", "INTEGER[]", "[1, NULL, 3]", "[1, NULL, 3]");
        add("LIST(VARCHAR)", "VARCHAR[]", "['a', 'b']", "['a', 'b']");
        add("ARRAY(INTEGER,3)", "INTEGER[3]", "CAST([1, 2, 3] AS INTEGER[3])", "[1, 2, 3]");
        add("LIST(LIST)", "INTEGER[][]", "[[1, 2], [3]]", "[[1, 2], [3]]");
        add("STRUCT", "STRUCT(a INTEGER, b VARCHAR)", "{'a': 1, 'b': 'x'}", "{'a': 1, 'b': 'x'}");
        add("STRUCT(JSON 文本)", "STRUCT(a INTEGER, b VARCHAR)", "{'a': 1, 'b': 'x'}",
                "{\"a\": 1, \"b\": \"x\"}");
        add("STRUCT(nested)", "STRUCT(a INTEGER, s STRUCT(x INTEGER))", "{'a': 1, 's': {'x': 7}}",
                "{'a': 1, 's': {'x': 7}}");
        add("LIST(STRUCT)", "STRUCT(a INTEGER)[]", "[{'a': 1}, {'a': 2}]", "[{'a': 1}, {'a': 2}]");
        add("MAP", "MAP(VARCHAR, INTEGER)", "MAP {'a': 1, 'b': 2}", "{a=1, b=2}");
        add("MAP(STRUCT)", "MAP(VARCHAR, STRUCT(a INTEGER))", "MAP {'k': {'a': 1}}", "{k={'a': 1}}");
        // 再嵌套一层：MAP 的值是 LIST、LIST 的元素是"带 LIST 字段的 STRUCT"
        add("MAP(LIST)", "MAP(VARCHAR, INTEGER[])", "MAP {'a': [1, 2]}", "{a=[1, 2]}");
        add("LIST(STRUCT(LIST))", "STRUCT(a INTEGER, l INTEGER[])[]",
                "[{'a': 1, 'l': [1, 2]}]", "[{'a': 1, 'l': [1, 2]}]");
        addT("UNION(标量成员)", "udemo", "CREATE OR REPLACE TYPE udemo AS UNION(num INTEGER, str VARCHAR)",
                "union_value(num := 42)::udemo", "42");
        // UNION 出现在集合内部：Appender 只接受 SimpleEntry(tag, 值) 这种形态，
        // 这里验证服务端确实按这个形态交付（而不是解析到一半报 Unsupported nested value）
        addT("LIST(UNION)", "udemo[]", "CREATE OR REPLACE TYPE udemo AS UNION(num INTEGER, str VARCHAR)",
                "[union_value(num := 42)::udemo]", "[42]");
        addT("MAP(UNION)", "MAP(VARCHAR, udemo)",
                "CREATE OR REPLACE TYPE udemo AS UNION(num INTEGER, str VARCHAR)",
                "MAP {'k': union_value(str := 'abc')::udemo}", "{k=abc}");

        // ---- 明确拒绝：块 Appender 连 createAppender 都建不起来的类型 ----
        addRejected("BIT", "BIT", "'1010'::BIT", "1010");
        addRejected("INTERVAL", "INTERVAL", "INTERVAL '1 year 2 months 3 days 04:05:06.007'",
                "1 year 2 months 3 days 04:05:06.007");
        addRejected("TIME_NS", "TIME_NS", "TIME_NS '12:34:56.123456789'", "12:34:56.123456789");
        addRejected("BIGNUM", "BIGNUM", "'123456789012345678901234567890'::BIGNUM",
                "123456789012345678901234567890");
        addRejected("VARIANT", "VARIANT", "CAST(42 AS VARIANT)", "42");
    }

    @BeforeAll
    static void initAll() throws ServerException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
        assertTrue(dbInstance.instanceState.equalsIgnoreCase("RUNNING"));
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:slackerdb://127.0.0.1:" + dbPort + "/mem", "", "");
    }

    static long copyIn(Connection conn, String sql, String csv) throws SQLException {
        try {
            return new org.slackerdb.jdbc.copy.CopyManager(
                    (org.slackerdb.jdbc.core.BaseConnection) conn).copyIn(sql, new StringReader(csv));
        }
        catch (java.io.IOException e) {
            throw new SQLException(e);
        }
    }

    /** CSV 字段：含逗号或双引号时按 CSV 规则加引号并转义。 */
    static String csvLine(String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"\n";
        }
        return value + "\n";
    }

    static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /**
     * 当前库里所有表的表名（有序）。
     *
     * <p>用于"COPY 不会在库里留下多余的表"这类检查。<b>刻意不按表名前缀判断</b>：写入通道只有块
     * Appender，实现里没有任何中转表，按 {@code __copy%} 之类的名字去找是永远为真的空断言。
     * 这里改成与命名无关的"表集合前后不变"，将来真出现残留（无论叫什么名字）都会红。</p>
     */
    static List<String> tableNames(Connection conn) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT table_name FROM duckdb_tables() ORDER BY table_name")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    @Test
    void copySupportsEveryTypeExceptAppenderUnsupported() throws SQLException {
        int index = 0;
        for (TypeCase tc : CASES) {
            index++;
            String table = "copy_type_" + index;
            try (Connection conn = connect()) {
                if (tc.prelude() != null) {
                    exec(conn, tc.prelude());
                }
                exec(conn, "CREATE OR REPLACE TABLE " + table + "(c " + tc.ddl() + ")");

                if (tc.rejected()) {
                    try {
                        copyIn(conn, "COPY " + table + " (c) FROM STDIN (FORMAT CSV)",
                                csvLine(tc.csvValue()));
                        fail("[" + tc.label() + "] 块 Appender 建不起来的列类型必须明确报错，实际却成功了");
                    }
                    catch (SQLException e) {
                        String message = e.getMessage();
                        assertNotNull(message, "[" + tc.label() + "] 错误信息不能为空");
                        assertTrue(message.contains("does not support"),
                                "[" + tc.label() + "] 错误信息应说明类型不支持，实际: " + message);
                        assertTrue(message.contains(tc.ddl()),
                                "[" + tc.label() + "] 错误信息应带类型名 " + tc.ddl() + "，实际: " + message);
                        assertTrue(message.contains("c"),
                                "[" + tc.label() + "] 错误信息应带列名，实际: " + message);
                    }
                    continue;
                }

                long copied;
                List<String> tablesBefore = tableNames(conn);
                // 自校验：确认 duckdb_tables() 在本服务端确实能看到表。
                // 否则"表集合前后不变"会退化成 [] == [] 的空断言（和它替换掉的 __copy% 检查一样）。
                assertTrue(tablesBefore.contains(table),
                        "[" + tc.label() + "] duckdb_tables() 应能看到刚建的表 " + table
                                + "，实际看到: " + tablesBefore);
                try {
                    copied = copyIn(conn, "COPY " + table + " (c) FROM STDIN (FORMAT CSV)",
                            csvLine(tc.csvValue()));
                }
                catch (SQLException e) {
                    throw new SQLException("[" + tc.label() + "] " + e.getMessage(), e);
                }
                assertEquals(1, copied, "[" + tc.label() + "] COPY 应写入 1 行");

                String expected = scalar(conn, "SELECT CAST((" + tc.literal() + ") AS " + tc.ddl() + ")::VARCHAR");
                String actual = scalar(conn, "SELECT CAST(c AS VARCHAR) FROM " + table);
                assertEquals(expected, actual, "[" + tc.label() + "] COPY 进来的值应等于字面量插入的值");

                assertEquals(tablesBefore, tableNames(conn),
                        "[" + tc.label() + "] COPY 不应在库里新建任何表（实现里没有中转表，也不允许有残留）");
            }
        }
    }

    /**
     * 写入通道的原子性：任意一行失败（例如文本转不成目标列类型），整条 COPY 都不生效
     * —— 既不留行，也不留表（服务端在 Appender 外层包了事务）。
     */
    @Test
    void failedCopyIsAtomic() throws SQLException {
        try (Connection conn = connect()) {
            exec(conn, "CREATE OR REPLACE TABLE copy_atomic(d DATE, n INTEGER)");
            List<String> tablesBefore = tableNames(conn);
            // 自校验：避免"表集合前后不变"在 duckdb_tables() 看不到表时变成空断言
            assertTrue(tablesBefore.contains("copy_atomic"),
                    "duckdb_tables() 应能看到刚建的表，实际看到: " + tablesBefore);
            boolean failed = false;
            try {
                copyIn(conn, "COPY copy_atomic FROM STDIN (FORMAT CSV)",
                        "2024-02-29,1\nnot-a-date,2\n");
            }
            catch (SQLException e) {
                failed = true;
            }
            assertTrue(failed, "坏数据必须让 COPY 失败");
            assertEquals("0", scalar(conn, "SELECT count(*) FROM copy_atomic"),
                    "失败的 COPY 不能留下任何行（含第一条合法行）");
            assertEquals(tablesBefore, tableNames(conn),
                    "失败后不应在库里留下任何表（没有中转表，也不允许有残留）");

            // 同一条连接仍可继续使用
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 + 1")) {
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next() && rs.getInt(1) == 2, "COPY 失败后会话必须仍然可用");
            }
        }
    }

    /** 复合类型列上的 NULL：未加引号的空字段必须写进 NULL（不能变成空串或报类型错误）。 */
    @Test
    void nullValuesWorkOnCompositeColumns() throws SQLException {
        try (Connection conn = connect()) {
            exec(conn, "CREATE OR REPLACE TABLE copy_null_composite(d DATE,"
                    + " s STRUCT(a INTEGER, b VARCHAR), m MAP(VARCHAR, INTEGER))");
            long copied = copyIn(conn, "COPY copy_null_composite FROM STDIN (FORMAT CSV)",
                    "2024-02-29,\"{'a': 1, 'b': 'x'}\",\"{k=1}\"\n,,\n");
            assertEquals(2, copied);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT d, s, m FROM copy_null_composite ORDER BY d NULLS LAST")) {
                assertTrue(rs.next());
                assertNotNull(rs.getDate(1), "第一行 DATE 不应为空");
                assertNotNull(rs.getString(2), "第一行 STRUCT 不应为空");
                assertNotNull(rs.getString(3), "第一行 MAP 不应为空");
                assertTrue(rs.next());
                assertEquals(null, rs.getDate(1), "第二行 DATE 应为 NULL");
                assertEquals(null, rs.getString(2), "第二行 STRUCT 应为 NULL");
                assertEquals(null, rs.getString(3), "第二行 MAP 应为 NULL");
            }
        }
    }

    /**
     * 表里有块 Appender 建不起来的列（BIT/INTERVAL/…）时：整表都无法 COPY（DuckDB 的
     * {@code createAppender()} 是按整表列类型校验的），必须在建立阶段给出明确错误。
     */
    @Test
    void tableWithUnsupportedColumnIsRejectedExplicitly() throws SQLException {
        try (Connection conn = connect()) {
            exec(conn, "CREATE OR REPLACE TABLE copy_unsupported_table(id INTEGER, iv INTERVAL)");
            boolean failed = false;
            try {
                copyIn(conn, "COPY copy_unsupported_table (id) FROM STDIN (FORMAT CSV)", "7\n");
            }
            catch (SQLException e) {
                failed = true;
                assertNotNull(e.getMessage());
                assertTrue(e.getMessage().contains("does not support"),
                        "错误信息应说明类型不支持，实际: " + e.getMessage());
                assertTrue(e.getMessage().contains("INTERVAL"),
                        "错误信息应带类型名，实际: " + e.getMessage());
                assertTrue(e.getMessage().contains("iv"),
                        "错误信息应带列名，实际: " + e.getMessage());
            }
            assertTrue(failed, "含不支持列类型的表必须明确报错");
        }
    }

    /** 值域/格式校验：宁可报错也不能静默回绕或静默改写（无符号类型与 BLOB 十六进制）。 */
    @Test
    void invalidValuesAreRejectedInsteadOfSilentlyWrapped() throws SQLException {
        try (Connection conn = connect()) {
            exec(conn, "CREATE OR REPLACE TABLE copy_invalid(v UTINYINT, b BLOB)");

            // UTINYINT 上界 255：-1 / 300 都必须报错，而不是写成 255 / 44
            for (String bad : java.util.List.of("-1", "300")) {
                boolean failed = false;
                try {
                    copyIn(conn, "COPY copy_invalid (v) FROM STDIN (FORMAT CSV)", bad + "\n");
                }
                catch (SQLException e) {
                    failed = true;
                    assertTrue(e.getMessage() != null && e.getMessage().contains("out of range"),
                            "[" + bad + "] 应报值域错误，实际: " + e.getMessage());
                }
                assertTrue(failed, "[" + bad + "] 超出 UTINYINT 值域必须报错");
            }
            assertEquals("0", scalar(conn, "SELECT count(*) FROM copy_invalid"),
                    "被拒绝的值不能留下任何行");

            // BLOB：非法的十六进制（奇数位 / 非十六进制字符）必须报错
            for (String bad : java.util.List.of("\\xABC", "\\xZZ")) {
                boolean failed = false;
                try {
                    copyIn(conn, "COPY copy_invalid (b) FROM STDIN (FORMAT CSV)", bad + "\n");
                }
                catch (SQLException e) {
                    failed = true;
                }
                assertTrue(failed, "[" + bad + "] 非法 BLOB 十六进制必须报错");
            }
        }
    }

    /** 未映射列如果带默认值，块 Appender 通道要按默认值补齐（appendDefault）。 */
    @Test
    void unmappedColumnDefaultIsApplied() throws SQLException {
        try (Connection conn = connect()) {
            exec(conn, "CREATE OR REPLACE TABLE copy_default_text(id INTEGER,"
                    + " d DATE DEFAULT DATE '2020-01-01', s STRUCT(a INTEGER) DEFAULT {'a': 5})");
            long copied = copyIn(conn, "COPY copy_default_text (id) FROM STDIN (FORMAT CSV)", "1\n2\n");
            assertEquals(2, copied);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT count(*) FROM copy_default_text WHERE d = DATE '2020-01-01'"
                                 + " AND s = {'a': 5}")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "未映射列必须取到列默认值");
            }
        }
    }
}
