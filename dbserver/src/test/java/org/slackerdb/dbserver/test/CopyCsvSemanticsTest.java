package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.StringReader;
import java.util.TimeZone;

/**
 * COPY ... FROM STDIN WITH (FORMAT csv) 的端到端语义测试。
 *
 * <p>背景：CSV 解析由 commons-csv 换成自研的 {@code CopyCsvReader}（去掉整段 {@code toString()}、
 * 去掉每行的 {@code CSVRecord}/{@code String[]}、数值直接从字节区间解析）。换实现的同时修正了空值语义，
 * 因此这里用端到端的方式把这些语义钉死：</p>
 *
 * <ul>
 *   <li>未加引号的空字段 = NULL；{@code ""} = 空字符串（commons-csv 无法区分，旧实现把前者写成空串）；</li>
 *   <li>数值/时间/布尔列的空字段 = NULL（旧实现是 {@code NumberFormatException}）；</li>
 *   <li>引号方言：引号内的分隔符、{@code ""} 转义、引号内的换行；{@code \n} / {@code \r\n} 行尾；
 *       完全空行跳过；末行可以没有换行；</li>
 *   <li>畸形输入与列数不符必须显式报错，且报错后同一连接仍可继续使用。</li>
 * </ul>
 */
public class CopyCsvSemanticsTest {
    static int dbPort;
    static DBInstance dbInstance;
    static String protocol = "postgresql";

    @BeforeAll
    static void initAll() throws ServerException {
        // 与其他用例一致：固定 UTC，避免时间列上的时区差异
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem", "", "");
    }

    private static long copyIn(Connection conn, String sql, String csv) throws SQLException {
        try {
            return new CopyManager((BaseConnection) conn)
                    .copyIn(sql, new StringReader(csv));
        } catch (java.io.IOException e) {
            // 读取 StringReader 不会失败；这里只是为了让用例签名保持只有 SQLException
            throw new SQLException(e);
        }
    }

    /** 未加引号的空字段是 NULL，加了引号的空字段是空字符串 —— 两者必须能区分。 */
    @Test
    void testNullVersusEmptyString() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_null(id int, name varchar, note varchar)");
            // 第1行: name 与 note 都是"未加引号的空字段" -> NULL
            // 第2行: name 是 "" -> 空字符串, note 未加引号为空 -> NULL
            long n = copyIn(conn, "COPY csv_null (id, name, note) FROM STDIN WITH (FORMAT csv)",
                    "1,,\n2,\"\",\n");
            conn.commit();
            assert n == 2;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, name, note from csv_null order by id")) {
                ResultSet rs = ps.executeQuery();

                assert rs.next();
                assert rs.getInt("id") == 1;
                assert rs.getString("name") == null : "未加引号的空字段必须是 NULL";
                assert rs.wasNull();
                assert rs.getString("note") == null;

                assert rs.next();
                assert rs.getInt("id") == 2;
                assert "".equals(rs.getString("name")) : "带引号的空字段必须是空字符串";
                assert !rs.wasNull();
                assert rs.getString("note") == null;

                assert !rs.next();
            }
        }
    }

    /** 数值/布尔/时间列的空字段 = NULL（旧实现会抛 NumberFormatException）。 */
    @Test
    void testEmptyNumericFieldIsNull() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_nullnum(id int, score double, cnt bigint,"
                            + " flag boolean, ts timestamp)");
            long n = copyIn(conn, "COPY csv_nullnum FROM STDIN WITH (FORMAT csv)",
                    "1,,,,\n2,1.5,7,true,2024-01-02 03:04:05\n");
            conn.commit();
            assert n == 2;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, score, cnt, flag, ts from csv_nullnum order by id")) {
                ResultSet rs = ps.executeQuery();

                assert rs.next();
                assert rs.getInt("id") == 1;
                rs.getDouble("score");
                assert rs.wasNull() : "空的 DOUBLE 字段必须是 NULL";
                rs.getLong("cnt");
                assert rs.wasNull() : "空的 BIGINT 字段必须是 NULL";
                rs.getBoolean("flag");
                assert rs.wasNull() : "空的 BOOLEAN 字段必须是 NULL";
                assert rs.getTimestamp("ts") == null : "空的 TIMESTAMP 字段必须是 NULL";

                assert rs.next();
                assert rs.getInt("id") == 2;
                assert rs.getDouble("score") == 1.5;
                assert rs.getLong("cnt") == 7L;
                assert rs.getBoolean("flag");
                assert rs.getTimestamp("ts").toLocalDateTime()
                        .equals(java.time.LocalDateTime.of(2024, 1, 2, 3, 4, 5));

                assert !rs.next();
            }
        }
    }

    /** 引号方言：引号内的分隔符、"" 转义、引号内的换行。 */
    @Test
    void testQuotingDialect() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_quote(id int, name varchar, note varchar)");
            // 第1行: 引号内的逗号 + "" 转义出的双引号
            // 第2行: 引号内的换行
            // 第3行: 引号内的裸逗号与引号混排
            String csv = "1,\"Smith, John\",\"He said \"\"hi\"\"\"\n"
                    + "2,\"line1\nline2\",plain\n"
                    + "3,a,\"x,\"\"y\"\"\"\n";
            long n = copyIn(conn, "COPY csv_quote FROM STDIN WITH (FORMAT csv)", csv);
            conn.commit();
            assert n == 3;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, name, note from csv_quote order by id")) {
                ResultSet rs = ps.executeQuery();

                assert rs.next();
                assert rs.getString("name").equals("Smith, John");
                assert rs.getString("note").equals("He said \"hi\"");

                assert rs.next();
                assert rs.getString("name").equals("line1\nline2") : "引号内的换行必须保留";
                assert rs.getString("note").equals("plain");

                assert rs.next();
                assert rs.getString("name").equals("a");
                assert rs.getString("note").equals("x,\"y\"");

                assert !rs.next();
            }
        }
    }

    /** 行尾(\n / \r\n)、完全空行跳过、末行无换行、行末空白不被裁剪。 */
    @Test
    void testLineEndingsAndBlankLines() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_lines(id int, name varchar)");
            // \n 行尾 + 中间完全空行 + \r\n 行尾 + 末行没有换行 + 末字段带空格
            String csv = "1,a\n\n2,b\r\n3,c \n4,d";
            long n = copyIn(conn, "COPY csv_lines FROM STDIN WITH (FORMAT csv)", csv);
            conn.commit();
            assert n == 4 : "完全空行必须被跳过，末行没有换行也必须算一行";

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, name from csv_lines order by id")) {
                ResultSet rs = ps.executeQuery();
                assert rs.next() && rs.getInt(1) == 1 && rs.getString(2).equals("a");
                assert rs.next() && rs.getInt(1) == 2 && rs.getString(2).equals("b");
                assert rs.next() && rs.getInt(1) == 3 && rs.getString(2).equals("c ") : "未加引号字段的尾部空格必须保留";
                assert rs.next() && rs.getInt(1) == 4 && rs.getString(2).equals("d");
                assert !rs.next();
            }
        }
    }

    /** COPY 列顺序与表定义不一致（含部分列）时，NULL/引号语义仍然按 COPY 的列序解释。 */
    @Test
    void testReorderedColumnsWithNulls() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_order(id int, first_name varchar, score double default 7.5)");
            // COPY 里列的顺序是 (score, first_name, id)：第1行 score 与 first_name 都是 NULL
            long n = copyIn(conn,
                    "COPY csv_order (score, first_name, id) FROM STDIN WITH (FORMAT csv)",
                    ",,1\n9.5,\"\",2\n");
            conn.commit();
            assert n == 2;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, first_name, score from csv_order order by id")) {
                ResultSet rs = ps.executeQuery();

                assert rs.next();
                assert rs.getInt("id") == 1;
                assert rs.getString("first_name") == null : "该列在 COPY 的列序里位于第2位，空的应是 NULL";
                rs.getDouble("score");
                assert rs.wasNull();

                assert rs.next();
                assert rs.getInt("id") == 2;
                assert "".equals(rs.getString("first_name"));
                assert rs.getDouble("score") == 9.5;

                assert !rs.next();
            }
        }
    }

    /** 不在 COPY 列清单中的列走表默认值（包括该列类型本身不受支持时也不应报错）。 */
    @Test
    void testUnmappedColumnUsesDefault() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            // d 列的类型(DATE)不在支持列表里，但没有出现在 COPY 列清单中，因此不应触发"类型不支持"
            conn.createStatement().execute(
                    "create or replace table csv_default(id int, d date default DATE '2020-01-01', name varchar)");
            long n = copyIn(conn, "COPY csv_default (id, name) FROM STDIN WITH (FORMAT csv)",
                    "1,John\n");
            conn.commit();
            assert n == 1;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select id, d, name from csv_default")) {
                ResultSet rs = ps.executeQuery();
                assert rs.next();
                assert rs.getInt("id") == 1;
                assert rs.getDate("d").toLocalDate().equals(java.time.LocalDate.of(2020, 1, 1));
                assert rs.getString("name").equals("John");
                assert !rs.next();
            }
        }
    }

    /** 列数不符必须报错，错误信息里带上实际列数与期望列数，且报错后连接仍可用。 */
    @Test
    void testColumnCountMismatch() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_mismatch(id int, name varchar, note varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY csv_mismatch FROM STDIN WITH (FORMAT csv)", "1,John\n");
            } catch (SQLException e) {
                failed = true;
                String message = e.getMessage();
                assert message != null && message.contains("column size not match")
                        : "错误信息应说明列数不符，实际: " + message;
                assert message.contains("[2] vs [3]")
                        : "错误信息应带实际列数与期望列数，实际: " + message;
            }
            assert failed : "列数不符必须失败";

            // 报错后同一连接仍可继续使用
            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery("select 1 + 1");
                assert rs.next() && rs.getInt(1) == 2;
            }
        }
    }

    /** 畸形 CSV（引号未闭合）必须显式报错，且报错后连接仍可用（不能挂起/不能把会话打坏）。 */
    @Test
    void testMalformedCsvReportsErrorAndKeepsSessionUsable() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_bad(id int, name varchar)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY csv_bad FROM STDIN WITH (FORMAT csv)", "1,\"abc\n");
            } catch (SQLException e) {
                failed = true;
                assert e.getMessage() != null && e.getMessage().contains("CSV")
                        : "应报 CSV 格式错误，实际: " + e.getMessage();
            }
            assert failed : "引号未闭合必须失败";

            // 闭引号之后出现非法字符，同样必须报错
            boolean failed2 = false;
            try {
                copyIn(conn, "COPY csv_bad FROM STDIN WITH (FORMAT csv)", "1,\"ab\"c\n");
            } catch (SQLException e) {
                failed2 = true;
            }
            assert failed2 : "闭引号之后的非法字符必须失败";

            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery("select 2 + 2");
                assert rs.next() && rs.getInt(1) == 4;
            }
        }
    }

    /** 不支持的列类型快速失败：在任何数据写入之前就报错（消息里带列类型名）。 */
    @Test
    void testUnsupportedColumnTypeFailsEarly() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_unsupported(id int, d date)");

            boolean failed = false;
            try {
                copyIn(conn, "COPY csv_unsupported FROM STDIN WITH (FORMAT csv)",
                        "1,2020-01-01\n2,2020-01-02\n");
            } catch (SQLException e) {
                failed = true;
                assert e.getMessage() != null && e.getMessage().contains("not support")
                        : "应报类型不支持，实际: " + e.getMessage();
                assert e.getMessage().contains("DATE")
                        : "错误信息应带列类型名，实际: " + e.getMessage();
            }
            assert failed : "不支持的列类型必须失败";
        }
    }

    /** 大数据量：多次 CopyData 分片(驱动按块发送)时必须与单行等价，且行数准确。 */
    @Test
    void testLargeChunkedCopy() throws SQLException {
        int rows = 5000;
        StringBuilder sb = new StringBuilder(rows * 24);
        for (int i = 0; i < rows; i++) {
            // 每 3 行留一个 NULL 与一个空串，确保跨分片状态下语义不漂移
            if (i % 3 == 0) {
                sb.append(i).append(",,x\n");
            } else if (i % 3 == 1) {
                sb.append(i).append(",\"\",y\n");
            } else {
                sb.append(i).append(",\"a,b\",z\n");
            }
        }

        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "create or replace table csv_bulk(id int, name varchar, tag varchar)");
            long n = copyIn(conn, "COPY csv_bulk FROM STDIN WITH (FORMAT csv)", sb.toString());
            conn.commit();
            assert n == rows : "应导入 " + rows + " 行，实际 " + n;

            try (PreparedStatement ps = conn.prepareStatement(
                    "select count(*) from csv_bulk where id % 3 = 0 and name is null")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                assert rs.getInt(1) == (rows + 2) / 3 : "未加引号的空字段必须都是 NULL";
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "select count(*) from csv_bulk where id % 3 = 1 and name = ''")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                assert rs.getInt(1) == (rows + 1) / 3 : "带引号的空字段必须都是空字符串";
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "select count(*) from csv_bulk where id % 3 = 2 and name = 'a,b'")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                assert rs.getInt(1) == rows / 3;
            }
        }
    }
}
