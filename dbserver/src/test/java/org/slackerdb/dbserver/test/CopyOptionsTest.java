package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.core.BaseConnection;
import org.postgresql.copy.CopyManager;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.CopyCsvReader;
import org.slackerdb.dbserver.sql.CopyDialect;
import org.slackerdb.dbserver.sql.antlr.CopyVisitor;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code COPY ... FROM STDIN} 的<b>格式与选项</b>契约测试（规划文档 BUG-10）。
 *
 * <p><b>被测缺陷</b>（改造前三条同时存在）：</p>
 * <ol>
 *   <li>{@code FORMAT 'csv'}（PG 文档推荐写法，值带引号）被拒 —— 服务端拿
 *       {@code "'csv'"} 与 {@code CSV} 比较，永远不相等；</li>
 *   <li>不写 {@code FORMAT} 时（PG 的默认格式是 <b>text</b>）直接报
 *       "Feature not supported. Only support FORMAT CSV|BINARY"；</li>
 *   <li>{@code HEADER}/{@code DELIMITER}/{@code QUOTE}/{@code NULL} 被完全忽略：
 *       {@code HEADER} 甚至过不了语法（裸开关），表头被当成数据行、分隔符写错就整行错位。</li>
 * </ol>
 *
 * <p>三条的根因是同一个：COPY 的"选项 → 解析参数"这一步不存在。
 * 现在由 {@link CopyDialect} 统一承担（默认值、单字符校验、组合校验），
 * 解析器 {@link CopyCsvReader} 按方言解析 TEXT 与 CSV 两种格式。</p>
 */
public class CopyOptionsTest {

    private static DBInstance dbInstance;
    private static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("copyopt");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("INFO");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/copyopt", "", "");
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** 三列的表：id INT, name VARCHAR, note VARCHAR。 */
    private static void createTable(Connection conn, String table) throws SQLException {
        exec(conn, "CREATE OR REPLACE TABLE " + table + "(id INT, name VARCHAR, note VARCHAR)");
    }

    private static long copyIn(Connection conn, String sql, String data) throws SQLException, java.io.IOException {
        return new CopyManager((BaseConnection) conn).copyIn(sql, new StringReader(data));
    }

    private static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String stringAt(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return "<no row>";
            }
            String value = rs.getString(1);
            return rs.wasNull() ? "<null>" : value;
        }
    }

    private static String errorOf(SQLException e) {
        String message = e.getMessage();
        return message == null ? "" : message;
    }

    // ================================================================
    // 一、FORMAT：带引号的值 / 大小写 / 默认值
    // ================================================================

    /** {@code FORMAT 'csv'}、{@code FORMAT "csv"}、{@code FORMAT csv} 必须等价。 */
    @Test
    void quotedAndUnquotedFormatValuesAreEquivalent() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "fmt_csv1");
            assert copyIn(conn, "COPY fmt_csv1 FROM STDIN WITH (FORMAT 'csv')", "1,a,b\n") == 1
                    : "FORMAT 'csv'（单引号）必须被接受";
            assert count(conn, "fmt_csv1") == 1;

            createTable(conn, "fmt_csv2");
            assert copyIn(conn, "COPY fmt_csv2 FROM STDIN WITH (FORMAT \"csv\")", "2,c,d\n") == 1
                    : "FORMAT \"csv\"（双引号）必须被接受";
            assert "c".equals(stringAt(conn, "SELECT name FROM fmt_csv2"));

            createTable(conn, "fmt_csv3");
            assert copyIn(conn, "COPY fmt_csv3 FROM STDIN WITH (FORMAT = 'csv')", "3,e,f\n") == 1
                    : "FORMAT = 'csv'（带等号）必须被接受";
            assert count(conn, "fmt_csv3") == 1;
        }
    }

    /** 选项名大小写不敏感：{@code format} / {@code delimiter} 小写同样生效。 */
    @Test
    void optionNamesAreCaseInsensitive() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "fmt_lower");
            assert copyIn(conn, "COPY fmt_lower FROM STDIN WITH (format csv, delimiter ';')",
                    "1;a;b\n") == 1
                    : "小写选项名必须被接受";
            assert stringAt(conn, "SELECT note FROM fmt_lower").equals("b");
        }
    }

    /** 不写 FORMAT 时按 PG 的默认格式 text 处理（制表符分隔）。 */
    @Test
    void defaultFormatIsText() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "fmt_default");
            long rows = copyIn(conn, "COPY fmt_default FROM STDIN", "1\thello\tworld\n2\tx\ty\n");
            assert rows == 2 : "默认 text 格式应导入 2 行，实际 " + rows;
            assert stringAt(conn, "SELECT name FROM fmt_default WHERE id = 1").equals("hello")
                    : "默认 text 格式的分隔符是制表符";
        }
    }

    /** FORMAT text 显式指定时同样可用，且 DELIMITER 生效。 */
    @Test
    void textFormatHonoursDelimiterAndNull() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "fmt_text");
            long rows = copyIn(conn,
                    "COPY fmt_text FROM STDIN WITH (FORMAT text, DELIMITER ',', NULL 'NULL')",
                    "1,a,b\n2,NULL,NULL\n");
            assert rows == 2 : "应导入 2 行，实际 " + rows;
            assert "<null>".equals(stringAt(conn, "SELECT name FROM fmt_text WHERE id = 2"))
                    : "NULL 'NULL' 必须被识别为 NULL";
        }
    }

    /** text 格式的反斜杠转义：{@code \t \\ \n}、八进制、十六进制，以及 {@code \N} 表示 NULL。 */
    @Test
    void textFormatSupportsBackslashEscapes() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "fmt_escape");
            // 注意：text 格式里字段内的制表符必须写成 \t（真正的制表符是分隔符）。
            // 第 1 行：name = a<TAB>b\c<TAB>d（\t -> 制表符、\\ -> 反斜杠）
            // 第 2 行：name 用十六进制转义 \x41 -> A
            // 第 3 行：name = \N -> NULL
            long rows = copyIn(conn, "COPY fmt_escape FROM STDIN",
                    "1\ta\\tb\\\\c\\td\tz\n2\t\\x41\tz\n3\t\\N\tz\n");
            assert rows == 3 : "应导入 3 行，实际 " + rows;
            assert "a\tb\\c\td".equals(stringAt(conn, "SELECT name FROM fmt_escape WHERE id = 1"))
                    : "\\t 应还原为制表符、\\\\ 应还原为反斜杠，实际 "
                    + stringAt(conn, "SELECT name FROM fmt_escape WHERE id = 1");
            assert "A".equals(stringAt(conn, "SELECT name FROM fmt_escape WHERE id = 2"))
                    : "\\x41 应还原为 A";
            assert "<null>".equals(stringAt(conn, "SELECT name FROM fmt_escape WHERE id = 3"))
                    : "\\N 必须被识别为 NULL";
        }
    }

    /** text 格式里未加引号的空字段是空字符串（只有 NULL 串才是 NULL）。 */
    @Test
    void textFormatEmptyFieldIsEmptyString() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "fmt_empty");
            copyIn(conn, "COPY fmt_empty FROM STDIN", "1\t\tz\n");
            String value = stringAt(conn, "SELECT name FROM fmt_empty");
            assert "".equals(value) : "text 格式的空字段应是空字符串，实际 " + value;
        }
    }

    // ================================================================
    // 二、CSV 的 HEADER / DELIMITER / QUOTE / NULL / ESCAPE
    // ================================================================

    /** 裸 {@code HEADER}（等价于 HEADER true）必须能解析，并且真的跳过表头。 */
    @Test
    void csvHeaderOptionSkipsHeaderRow() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "opt_header");
            long rows = copyIn(conn, "COPY opt_header FROM STDIN WITH (FORMAT csv, HEADER)",
                    "id,name,note\n1,a,b\n2,c,d\n");
            assert rows == 2 : "HEADER 后应只导入 2 行数据（表头不计），实际 " + rows;
            assert count(conn, "opt_header") == 2;
            assert "1".equals(stringAt(conn, "SELECT id FROM opt_header ORDER BY id"))
                    : "表头行不能被当作数据写进去";
        }
    }

    /** {@code HEADER true} / {@code HEADER false} 都要能解析。 */
    @Test
    void csvHeaderBooleanValuesAreHonoured() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "opt_header_true");
            long withHeader = copyIn(conn, "COPY opt_header_true FROM STDIN WITH (FORMAT csv, HEADER true)",
                    "id,name,note\n1,a,b\n");
            assert withHeader == 1 : "HEADER true 应跳过 1 行，实际 " + withHeader;

            createTable(conn, "opt_header_false");
            long withoutHeader = copyIn(conn, "COPY opt_header_false FROM STDIN WITH (FORMAT csv, HEADER false)",
                    "1,a,b\n");
            assert withoutHeader == 1 && count(conn, "opt_header_false") == 1
                    : "HEADER false 不应跳过任何行";
        }
    }

    /** {@code DELIMITER} 必须真正改变字段切分（逗号在数据里不再有特殊含义）。 */
    @Test
    void csvDelimiterOptionIsHonoured() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "opt_delim");
            copyIn(conn, "COPY opt_delim FROM STDIN WITH (FORMAT csv, DELIMITER ';')", "1;a,b;c\n");
            assert "a,b".equals(stringAt(conn, "SELECT name FROM opt_delim"))
                    : "分号是分隔符时，逗号必须留在字段内容里";
            assert "c".equals(stringAt(conn, "SELECT note FROM opt_delim"));
        }
    }

    /** {@code QUOTE} 必须真正改变引号字符。 */
    @Test
    void csvQuoteOptionIsHonoured() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "opt_quote");
            copyIn(conn, "COPY opt_quote FROM STDIN WITH (FORMAT csv, QUOTE '|')", "1,|a,b|,c\n");
            assert "a,b".equals(stringAt(conn, "SELECT name FROM opt_quote"))
                    : "QUOTE '|' 时 |a,b| 必须是一个字段";
        }
    }

    /** {@code NULL} 必须真正改变 NULL 串：自定义后未加引号的空字段是空字符串，而不是 NULL。 */
    @Test
    void csvNullOptionIsHonoured() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "opt_null");
            copyIn(conn, "COPY opt_null FROM STDIN WITH (FORMAT csv, NULL 'NULL')",
                    "1,NULL,x\n2,,y\n");
            assert "<null>".equals(stringAt(conn, "SELECT name FROM opt_null WHERE id = 1"))
                    : "NULL 'NULL' 时字面量 NULL 必须是 NULL";
            assert "".equals(stringAt(conn, "SELECT name FROM opt_null WHERE id = 2"))
                    : "自定义 NULL 串后，未加引号的空字段是空字符串（不再默认是 NULL）";
        }
    }

    /** {@code ESCAPE} 必须真正改变引号内的转义字符。 */
    @Test
    void csvEscapeOptionIsHonoured() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "opt_escape");
            copyIn(conn, "COPY opt_escape FROM STDIN WITH (FORMAT csv, ESCAPE '\\')",
                    "1,\"he said \\\"hi\\\"\",z\n");
            assert "he said \"hi\"".equals(stringAt(conn, "SELECT name FROM opt_escape"))
                    : "ESCAPE '\\' 时 \\\" 必须还原成引号";
        }
    }

    // ================================================================
    // 三、非法选项：必须明确报错，而不是静默按别的格式导入
    // ================================================================

    @Test
    void unknownFormatIsRejected() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "opt_bad_format");
            boolean failed = false;
            try {
                copyIn(conn, "COPY opt_bad_format FROM STDIN WITH (FORMAT 'parquet')", "1,a,b\n");
            } catch (SQLException e) {
                failed = true;
                assert errorOf(e).contains("not recognized") : "错误信息应说明格式无法识别，实际: " + errorOf(e);
            }
            assert failed : "未知 FORMAT 必须报错";
        }
    }

    @Test
    void multiCharacterDelimiterIsRejected() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "opt_bad_delim");
            boolean failed = false;
            try {
                copyIn(conn, "COPY opt_bad_delim FROM STDIN WITH (FORMAT csv, DELIMITER 'ab')", "1,a,b\n");
            } catch (SQLException e) {
                failed = true;
                assert errorOf(e).contains("single one-byte character")
                        : "错误信息应说明分隔符必须是单字节，实际: " + errorOf(e);
            }
            assert failed : "多字符分隔符必须报错";
        }
    }

    /** QUOTE/ESCAPE 只在 CSV 模式下有意义（与 PG 一致）。 */
    @Test
    void csvOnlyOptionsAreRejectedInTextMode() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "opt_text_quote");
            boolean failed = false;
            try {
                copyIn(conn, "COPY opt_text_quote FROM STDIN WITH (FORMAT text, QUOTE '|')", "1\ta\tb\n");
            } catch (SQLException e) {
                failed = true;
                assert errorOf(e).contains("CSV mode") : "错误信息应说明 QUOTE 只在 CSV 模式可用，实际: " + errorOf(e);
            }
            assert failed : "text 模式下指定 QUOTE 必须报错";
        }
    }

    /** 小写 format + 裸 HEADER 的组合必须能通过扩展协议路径（Statement.execute 走 Parse/Bind/Execute）。 */
    @Test
    void statementExecuteOnCopyWithBareHeaderEntersCopySubProtocol() throws Exception {
        try (Connection conn = connect()) {
            createTable(conn, "opt_stmt");
            String error = null;
            try (Statement st = conn.createStatement()) {
                st.execute("COPY opt_stmt FROM STDIN WITH (FORMAT csv, HEADER)");
            } catch (Exception e) {
                error = e.getMessage();
            }
            assert error == null
                    : "带裸 HEADER 的 COPY 在扩展协议下被丢给了 DuckDB（说明语法没解析出来）：" + error;
        }
    }

    // ================================================================
    // 四、解析器与方言的单元契约（不经服务端）
    // ================================================================

    @Test
    void dialectDefaultsMatchPostgres() {
        CopyDialect csv = CopyDialect.defaultCsv();
        assert csv.csv && csv.delimiter == ',' && csv.quote == '"' && !csv.header;

        JSONObject textOptions = CopyVisitor.parseCopyStatement("COPY t FROM STDIN").getJSONObject("options");
        CopyDialect text = CopyDialect.fromOptions(textOptions);
        assert text.text && text.delimiter == '\t' && CopyDialect.DEFAULT_TEXT_NULL.equals(text.nullString)
                : "不写 FORMAT 时必须是 PG 默认的 text 格式（制表符 + \\N）";
    }

    @Test
    void dialectRejectsInvalidOptions() {
        assertDialectRejected("FORMAT csv, QUOTE 'a', DELIMITER 'a'", "must be different");
        assertDialectRejected("FORMAT text, DELIMITER '\\n'", "newline");
        assertDialectRejected("FORMAT text, QUOTE '|'", "CSV mode");
        assertDialectRejected("FORMAT binary, DELIMITER ','", "TEXT or CSV mode");
        assertDialectRejected("FORMAT csv, HEADER match", "not supported");
        assertDialectRejected("FORMAT csv, DELIMITER 'ab'", "single one-byte character");
    }

    /** 断言某组选项会被 {@link CopyDialect} 拒绝，且错误信息里带指定片段。 */
    private static void assertDialectRejected(String options, String expectedFragment) {
        JSONObject parsed = CopyVisitor.parseCopyStatement(
                "COPY t FROM STDIN WITH (" + options + ")").getJSONObject("options");
        try {
            CopyDialect dialect = CopyDialect.fromOptions(parsed);
            throw new AssertionError("选项 [" + options + "] 应当被拒绝，但得到了 " + dialect.describe());
        } catch (CopyDialect.InvalidOptionException e) {
            assert e.getMessage().contains(expectedFragment)
                    : "错误信息应包含 [" + expectedFragment + "]，实际: " + e.getMessage();
        }
    }

    /** 方言生效的直接证据：同一份载荷在 text 与 csv 下解析成不同的字段。 */
    @Test
    void textAndCsvDialectsParseDifferently() {
        byte[] payload = "1\t\"a,b\"\n".getBytes(StandardCharsets.UTF_8);

        // CSV：分隔符是逗号，制表符只是普通字符；引号不在字段开头，因此没有特殊含义
        List<String> csvFields = parseWith(CopyDialect.defaultCsv(), payload);
        assert Arrays.asList("1\t\"a", "b\"", "<EOR>").equals(csvFields)
                : "CSV 方言下制表符不是分隔符，实际 " + csvFields;

        // TEXT：分隔符是制表符，引号没有特殊含义
        JSONObject textOptions = CopyVisitor.parseCopyStatement("COPY t FROM STDIN").getJSONObject("options");
        List<String> textFields = parseWith(CopyDialect.fromOptions(textOptions), payload);
        assert Arrays.asList("1", "\"a,b\"", "<EOR>").equals(textFields)
                : "TEXT 方言下制表符是分隔符、引号没有特殊含义，实际 " + textFields;
    }

    private static List<String> parseWith(CopyDialect dialect, byte[] payload) {
        return parseWith(dialect, payload, Integer.MAX_VALUE);
    }

    /**
     * 分片喂入解析：同一份载荷按 1..N 字节切片必须解析出完全相同的结果。
     * 这是"一行 / 一个转义序列被 TCP 分片切断"的等价场景。
     */
    private static List<String> parseWith(CopyDialect dialect, byte[] payload, int chunk) {
        List<String> out = new ArrayList<>();
        // 初始容量给个上限：chunk 为 Integer.MAX_VALUE 时不能真去分配 2GB 数组
        // （append 会按需扩容，小容量对结果没有影响）。
        CopyCsvReader reader = new CopyCsvReader(Math.min(1 << 20, Math.max(64, chunk)), dialect);
        int off = 0;
        while (off < payload.length) {
            int len = Math.min(chunk, payload.length - off);
            reader.append(payload, off, len);
            drain(reader, out);
            off += len;
        }
        reader.markEof();
        drain(reader, out);
        return out;
    }

    private static void drain(CopyCsvReader reader, List<String> out) {
        while (reader.nextRow(new CopyCsvReader.FieldSink() {
            @Override
            public void field(int off, int len, boolean isNull) {
                out.add(isNull ? "<NULL>" : new String(reader.buffer(), off, len, StandardCharsets.UTF_8));
            }

            @Override
            public void endRow() {
                out.add("<EOR>");
            }
        })) {
            // 读到底
        }
    }

    /** text 方言的转义序列跨分片时也必须续解（反斜杠可能正好落在切片边界上）。 */
    @Test
    void textDialectIsIncrementalAcrossChunkBoundaries() {
        JSONObject textOptions = CopyVisitor.parseCopyStatement("COPY t FROM STDIN").getJSONObject("options");
        CopyDialect text = CopyDialect.fromOptions(textOptions);
        byte[] payload = "1\ta\\tb\\\\c\t\\N\tz\n2\t\\x41\tz\n".getBytes(StandardCharsets.UTF_8);

        List<String> expected = parseWith(text, payload);
        assert Arrays.asList("1", "a\tb\\c", "<NULL>", "z", "<EOR>", "2", "A", "z", "<EOR>")
                .equals(expected) : "text 方言整体解析结果不符，实际 " + expected;

        for (int chunk = 1; chunk <= 9; chunk++) {
            assert expected.equals(parseWith(text, payload, chunk))
                    : "切片大小 " + chunk + " 时结果不一致，实际 " + parseWith(text, payload, chunk);
        }
    }
}
