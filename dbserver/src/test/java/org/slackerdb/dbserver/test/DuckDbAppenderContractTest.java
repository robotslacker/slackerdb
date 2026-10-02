package org.slackerdb.dbserver.test;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.sql.CopyColumnTypes;
import org.slackerdb.dbserver.sql.CopyValueWriters;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 块 Appender（{@code DuckDBAppender}）的能力契约：直接对 duckdb_jdbc 实测，不经过服务端。
 *
 * <p>这些断言是把 {@code jdbc-type-probe/} 里的探针程序固化成的 CI 守卫（原始探针已归档到
 * {@code src/test/resources/duckdb-jdbc-probes/}）。它们保护的是 COPY 实现的两个前提：</p>
 *
 * <ul>
 *   <li>{@link CopyColumnTypes} 的黑名单必须与"{@code createAppender()} 建不起来}"的实测集合一致
 *       —— 多一个会误拒，少一个就会退化成看不懂的 {@code XX000 unsupported C API type: N}；</li>
 *   <li>{@link CopyValueWriters} 的存在理由：块 Appender 按列物理类型分派、<b>不做任何隐式转换</b>
 *       （{@code append(String)} 只收 VARCHAR/ENUM/JSON，集合元素类型必须精确），
 *       所以服务端必须自己把文本解析成精确的 Java 值。</li>
 * </ul>
 *
 * <p><b>基线版本：duckdb_jdbc 1.5.6.0</b>（1.5.5.1 实测结果完全相同）。
 * 若某天本类在<b>能力集合</b>上失败（例如 2.0 开始支持 BIT/INTERVAL），那不是"测试过时了"，
 * 而是明确的行动项：同步更新 {@link CopyColumnTypes} 的黑名单与注释、{@code PostgresTypeOids}
 * 的相关取舍说明，以及本类下面的基线表。</p>
 */
public class DuckDbAppenderContractTest {

    /** 一个列类型的实测基线：{@code supported} 是 {@code createAppender()} 能否建起来。 */
    private record AppenderCase(String ddl, String prelude, boolean supported) {}

    private static final List<AppenderCase> APPENDER_CASES = List.of(
            // ---- 实测可建（Appender 通道可用）----
            new AppenderCase("VARCHAR", null, true),
            new AppenderCase("JSON", null, true),
            new AppenderCase("INTEGER", null, true),
            new AppenderCase("BIGINT", null, true),
            new AppenderCase("SMALLINT", null, true),
            new AppenderCase("TINYINT", null, true),
            new AppenderCase("FLOAT", null, true),
            new AppenderCase("DOUBLE", null, true),
            new AppenderCase("DECIMAL(18,4)", null, true),
            new AppenderCase("BOOLEAN", null, true),
            new AppenderCase("DATE", null, true),
            new AppenderCase("TIME", null, true),
            new AppenderCase("TIMETZ", null, true),
            new AppenderCase("TIMESTAMP", null, true),
            new AppenderCase("TIMESTAMPTZ", null, true),
            new AppenderCase("TIMESTAMP_NS", null, true),
            new AppenderCase("UUID", null, true),
            new AppenderCase("BLOB", null, true),
            new AppenderCase("HUGEINT", null, true),
            new AppenderCase("UBIGINT", null, true),
            new AppenderCase("UHUGEINT", null, true),
            new AppenderCase("UTINYINT", null, true),
            new AppenderCase("USMALLINT", null, true),
            new AppenderCase("UINTEGER", null, true),
            new AppenderCase("INTEGER[]", null, true),
            new AppenderCase("VARCHAR[]", null, true),
            new AppenderCase("INTEGER[3]", null, true),
            new AppenderCase("INTEGER[][]", null, true),
            new AppenderCase("STRUCT(a INTEGER, b VARCHAR)", null, true),
            new AppenderCase("MAP(VARCHAR, INTEGER)", null, true),
            new AppenderCase("ENUM", "CREATE OR REPLACE TYPE mood AS ENUM ('sad','ok','happy')", true),
            // ---- 实测建不起来（= CopyColumnTypes.APPENDER_UNSUPPORTED）----
            new AppenderCase("BIT", null, false),
            new AppenderCase("INTERVAL", null, false),
            new AppenderCase("TIME_NS", null, false),
            new AppenderCase("BIGNUM", null, false),
            new AppenderCase("VARIANT", null, false));

    /** 版本漂移提示：能力集合变化时的行动项，别让后人以为是"测试该改了"。 */
    private static final String DRIFT_HINT =
            "如果这是 DuckDB/JDBC 升级导致的能力变化：请同步更新 CopyColumnTypes.APPENDER_UNSUPPORTED、"
                    + "它和 CopyValueWriters/PostgresTypeOids 里的版本注释，以及本类的基线表（当前基线 duckdb_jdbc 1.5.6.0）。";

    @Test
    void appenderSupportMatchesBlacklistAndBaseline() throws SQLException {
        try (Connection conn = open()) {
            DuckDBConnection duck = duck(conn);
            int index = 0;
            for (AppenderCase testCase : APPENDER_CASES) {
                index++;
                String table = "appender_case_" + index;
                if (testCase.prelude() != null) {
                    exec(conn, testCase.prelude());
                }
                exec(conn, "CREATE OR REPLACE TABLE " + table + "(c "
                        + ("ENUM".equals(testCase.ddl()) ? "mood" : testCase.ddl()) + ")");
                String metaName = columnTypeName(conn, table);
                boolean appenderWorks = canCreateAppender(duck, table);

                // 1) 与实测基线一致（真正的守卫：版本漂移会红）
                assertEquals(testCase.supported(), appenderWorks,
                        "[" + testCase.ddl() + "] createAppender 的实测结果与基线不符。" + DRIFT_HINT);

                // 2) 生产代码的黑名单判定必须与实测一致（多一个=误拒，少一个=退化）
                assertEquals(!CopyColumnTypes.isAppenderUnsupported(metaName), appenderWorks,
                        "[" + testCase.ddl() + "] 元数据类型名 [" + metaName
                                + "] 的 CopyColumnTypes.isAppenderUnsupported 判定与实测不符。" + DRIFT_HINT);

                // 3) Appender 能开的类型，COPY 的文本解析器必须能构造写入器
                //    （否则会在 COPY 建立期就报 "does not support column type"，且黑名单挡不住）
                if (appenderWorks) {
                    assertDoesNotThrow(() -> CopyValueWriters.writer(metaName),
                            "[" + testCase.ddl() + "] 元数据名 [" + metaName
                                    + "] 能被 Appender 打不开，却构造不出 CopyValueWriters 写入器："
                                    + "该类型会在 COPY 时被拒绝，请补 CopyValueWriters（或把它加进黑名单）");
                }
            }
        }
    }

    /**
     * 黑名单是<b>整表</b>语义：目标表里只要有一列是 Appender 建不起来的类型，
     * 整张表都开不了 Appender（DuckDB 不支持只插入部分列的 Appender），
     * 所以 COPY 必须在建立阶段明确拒绝，而不是等某一行、或退化成 XX000。
     */
    @Test
    void unsupportedColumnBlocksWholeTableWhileInsertStillWorks() throws SQLException {
        try (Connection conn = open()) {
            DuckDBConnection duck = duck(conn);
            exec(conn, "CREATE OR REPLACE TABLE whole_table(id INTEGER, iv INTERVAL)");

            assertFalse(canCreateAppender(duck, "whole_table"),
                    "含 INTERVAL 列的表必须开不了 Appender（哪怕本次 COPY 不写那一列）");

            // 限制只来自 Appender 通道：INSERT 通道对同一张表完全可用
            exec(conn, "INSERT INTO whole_table VALUES (1, INTERVAL '1 day')");
            assertEquals("1", scalar(conn, "SELECT count(*) FROM whole_table"),
                    "INSERT 通道不应受 Appender 的类型限制影响");
        }
    }

    /**
     * {@code append(String)} 只接受 VARCHAR/ENUM/JSON —— 这就是服务端必须做文本解析的原因，
     * 也是"不能用 append(String) 图省事"的守卫。
     */
    @Test
    void appendStringOnlyAcceptedByVarcharEnumJson() throws SQLException {
        try (Connection conn = open()) {
            DuckDBConnection duck = duck(conn);

            // 正例：字符串列
            exec(conn, "CREATE OR REPLACE TABLE as_varchar(c VARCHAR)");
            appendStringRow(duck, "as_varchar", "hello 世界");
            assertEquals("hello 世界", scalar(conn, "SELECT c FROM as_varchar"));

            exec(conn, "CREATE OR REPLACE TABLE as_json(c JSON)");
            appendStringRow(duck, "as_json", "{\"a\": 1}");
            assertEquals("{\"a\": 1}", scalar(conn, "SELECT c FROM as_json"));

            exec(conn, "CREATE OR REPLACE TYPE mood2 AS ENUM ('sad','ok','happy')");
            exec(conn, "CREATE OR REPLACE TABLE as_enum(c mood2)");
            appendStringRow(duck, "as_enum", "happy");
            assertEquals("happy", scalar(conn, "SELECT c FROM as_enum"));

            // 反例：其余标量/复合类型一律拒绝
            List<String> rejected = List.of("INTEGER", "BIGINT", "SMALLINT", "DOUBLE", "FLOAT",
                    "DECIMAL(18,4)", "BOOLEAN", "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ",
                    "UUID", "BLOB", "HUGEINT", "INTEGER[]", "STRUCT(a INTEGER)",
                    "MAP(VARCHAR, INTEGER)");
            int index = 0;
            for (String ddl : rejected) {
                index++;
                String table = "as_reject_" + index;
                exec(conn, "CREATE OR REPLACE TABLE " + table + "(c " + ddl + ")");
                DuckDBAppender appender = duck.createAppender(null, null, table);
                try {
                    appender.beginRow();
                    SQLException failure = assertThrows(SQLException.class, () -> appender.append("1"),
                            "[" + ddl + "] append(String) 不应被接受");
                    assertNotNull(failure.getMessage());
                    assertTrue(failure.getMessage().contains("invalid column type"),
                            "[" + ddl + "] 应报 invalid column type，实际: " + failure.getMessage());
                } finally {
                    closeQuietly(appender);
                }
            }
        }
    }

    /** BLOB 列要用 {@code append(byte[])}；{@code appendByteArray(byte[])} 是给 ARRAY/LIST 的。 */
    @Test
    void blobUsesAppendByteArrayOverloadNotAppendByteArray() throws SQLException {
        try (Connection conn = open()) {
            DuckDBConnection duck = duck(conn);

            exec(conn, "CREATE OR REPLACE TABLE blob_ok(c BLOB)");
            DuckDBAppender ok = duck.createAppender(null, null, "blob_ok");
            try {
                ok.beginRow();
                ok.append(new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
                ok.endRow();
                ok.flush();
            } finally {
                closeQuietly(ok);
            }
            assertEquals(scalar(conn, "SELECT CAST(from_hex('DEADBEEF') AS VARCHAR)"),
                    scalar(conn, "SELECT CAST(c AS VARCHAR) FROM blob_ok"),
                    "append(byte[]) 应为 BLOB 列的写法");

            exec(conn, "CREATE OR REPLACE TABLE blob_bad(c BLOB)");
            DuckDBAppender bad = duck.createAppender(null, null, "blob_bad");
            try {
                bad.beginRow();
                SQLException failure = assertThrows(SQLException.class,
                        () -> bad.appendByteArray(new byte[] {1, 2}),
                        "appendByteArray(byte[]) 不应用来写 BLOB 列");
                assertNotNull(failure.getMessage());
                assertTrue(failure.getMessage().contains("DUCKDB_TYPE_ARRAY")
                                || failure.getMessage().contains("DUCKDB_TYPE_LIST"),
                        "应提示这是给 ARRAY/LIST 的重载，实际: " + failure.getMessage());
            } finally {
                closeQuietly(bad);
            }
        }
    }

    /**
     * 复合类型内部<b>不做隐式转换</b>：元素必须是与元素类型精确匹配的 Java 值。
     * 这就是 {@link CopyValueWriters} 要为每种元素类型单独解析的直接原因。
     */
    @Test
    void collectionElementsAreNotImplicitlyConverted() throws SQLException {
        try (Connection conn = open()) {
            DuckDBConnection duck = duck(conn);

            exec(conn, "CREATE OR REPLACE TABLE list_ok(c INTEGER[])");
            DuckDBAppender ok = duck.createAppender(null, null, "list_ok");
            try {
                ok.beginRow();
                ok.append(Arrays.asList(1, 2, 3));
                ok.endRow();
                ok.flush();
            } finally {
                closeQuietly(ok);
            }
            assertEquals("[1, 2, 3]", scalar(conn, "SELECT CAST(c AS VARCHAR) FROM list_ok"),
                    "精确匹配的元素类型应能写入");

            exec(conn, "CREATE OR REPLACE TABLE list_bad(c INTEGER[])");
            DuckDBAppender bad = duck.createAppender(null, null, "list_bad");
            try {
                bad.beginRow();
                // 断言"抛错"而不是具体异常类型：不同版本可能是 ClassCastException 或 SQLException，
                // 契约只有一条 —— 字符串不会被隐式转成 Integer 静默写进去。
                assertThrows(Exception.class, () -> bad.append(Arrays.asList("1", "2")),
                        "INTEGER[] 的元素不接受 String（块 Appender 不做隐式转换）");
            } finally {
                closeQuietly(bad);
            }
        }
    }

    /**
     * {@code appendHugeInt} 的参数顺序是"低位, 高位"（{@link CopyValueWriters} 的 UHUGEINT 路径依赖它）。
     * 用 2^127 这种低位为 0 的值可以明确区分两种顺序。
     */
    @Test
    void hugeIntPartsOrderIsLowThenHigh() throws SQLException {
        try (Connection conn = open()) {
            DuckDBConnection duck = duck(conn);
            BigInteger twoPower127 = new BigInteger("170141183460469231731687303715884105728");
            BigInteger mask = new BigInteger("FFFFFFFFFFFFFFFF", 16);
            BigInteger low = twoPower127.and(mask);
            BigInteger high = twoPower127.shiftRight(64);

            exec(conn, "CREATE OR REPLACE TABLE hu_low_high(c UHUGEINT)");
            appendHugeInt(duck, "hu_low_high", low, high);
            assertEquals(twoPower127.toString(), scalar(conn, "SELECT CAST(c AS VARCHAR) FROM hu_low_high"),
                    "appendHugeInt(低位, 高位) 才是正确顺序");

            // 顺序反了不会报错，只会静静地把高位当低位写进去（2^127 会变成 2^63），
            // 所以这里必须钉住"两种顺序结果不同"，而不是只看是否写入成功。
            exec(conn, "CREATE OR REPLACE TABLE hu_high_low(c UHUGEINT)");
            appendHugeInt(duck, "hu_high_low", high, low);
            String reversed = scalar(conn, "SELECT CAST(c AS VARCHAR) FROM hu_high_low");
            assertNotEquals(twoPower127.toString(), reversed,
                    "appendHugeInt(高, 低) 不应与 (低, 高) 得到同一个值 —— 参数顺序不能写反");
            assertEquals(high.toString(), reversed,
                    "顺序反了只会得到高位那部分（" + high + "）—— 说明参数顺序不是无关紧要的");
        }
    }

    // ------------------------------------------------------------------ 工具

    private static Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:");
    }

    private static DuckDBConnection duck(Connection conn) throws SQLException {
        return conn.unwrap(DuckDBConnection.class);
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "<no row>";
        }
    }

    /** 复刻 CopyProtocolHandler 取列类型的方式：{@code SELECT * FROM t LIMIT 0} 的元数据。 */
    private static String columnTypeName(Connection conn, String table) throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            return rs.getMetaData().getColumnTypeName(1);
        }
    }

    private static boolean canCreateAppender(DuckDBConnection duck, String table) {
        DuckDBAppender appender = null;
        try {
            appender = duck.createAppender(null, null, table);
            return true;
        } catch (Exception notSupported) {
            return false;
        } finally {
            closeQuietly(appender);
        }
    }

    private static void appendStringRow(DuckDBConnection duck, String table, String value)
            throws SQLException {
        DuckDBAppender appender = duck.createAppender(null, null, table);
        try {
            appender.beginRow();
            appender.append(value);
            appender.endRow();
            appender.flush();
        } finally {
            closeQuietly(appender);
        }
    }

    private static void appendHugeInt(DuckDBConnection duck, String table, BigInteger low, BigInteger high)
            throws SQLException {
        DuckDBAppender appender = duck.createAppender(null, null, table);
        try {
            appender.beginRow();
            appender.appendHugeInt(low.longValue(), high.longValue());
            appender.endRow();
            appender.flush();
        } finally {
            closeQuietly(appender);
        }
    }

    /** append 失败之后的 close() 自己也会报错，不能让它盖住真正的断言结果。 */
    private static void closeQuietly(DuckDBAppender appender) {
        if (appender != null) {
            try {
                appender.close();
            } catch (Exception ignored) {
                // 故意忽略
            }
        }
    }
}
