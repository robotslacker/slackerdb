import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/**
 * 干净版 Appender 能力实验：createAppender / append / flush / close / 读回 五步分别记录，
 * 避免 try-with-resources 把 close() 的异常算到 append 头上。
 */
public class AppenderCapabilityProbe {

    record C(String label, String ddl, String prelude, String text, String appendKind) {}

    static final List<C> CASES = List.of(
            new C("VARCHAR", "VARCHAR", null, "hello", "string"),
            new C("ENUM", "mood", "CREATE OR REPLACE TYPE mood AS ENUM ('sad','ok','happy')", "happy", "string"),
            new C("JSON", "JSON", null, "{\"a\": 1}", "string"),
            new C("INTEGER", "INTEGER", null, "42", "string"),
            new C("BIGINT", "BIGINT", null, "42", "long"),
            new C("DOUBLE", "DOUBLE", null, "1.5", "double"),
            new C("DECIMAL", "DECIMAL(18,4)", null, "1.5", "bigdecimal"),
            new C("BOOLEAN", "BOOLEAN", null, "true", "boolean"),
            new C("DATE", "DATE", null, "2024-02-29", "localdate"),
            new C("TIME", "TIME", null, "12:34:56", "localtime"),
            new C("TIMESTAMP", "TIMESTAMP", null, "2024-02-29 12:34:56", "localdatetime"),
            new C("UUID", "UUID", null, "123e4567-e89b-12d3-a456-426614174000", "string"),
            new C("BLOB", "BLOB", null, "\\xDEADBEEF", "string"),
            new C("LIST", "INTEGER[]", null, "[1, 2]", "string"),
            new C("STRUCT", "STRUCT(a INTEGER)", null, "{'a': 1}", "string"),
            new C("MAP", "MAP(VARCHAR, INTEGER)", null, "{a=1}", "string"),
            new C("BIT", "BIT", null, "1010", "string"),
            new C("INTERVAL", "INTERVAL", null, "1 day", "string"),
            new C("TIME_NS", "TIME_NS", null, "12:34:56", "string"),
            new C("BIGNUM", "BIGNUM", null, "123456789012345678901234567890", "string"),
            new C("VARIANT", "VARIANT", null, "42", "string"));

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            DuckDBConnection dc = c.unwrap(DuckDBConnection.class);
            System.out.printf("%-12s | %-16s | %-34s | %-6s | %s%n",
                    "类型", "createAppender", "append(String)", "flush", "读回");
            int n = 0;
            for (C tc : CASES) {
                n++;
                String table = "cap_" + n;
                if (tc.prelude() != null) {
                    exec(c, tc.prelude());
                }
                exec(c, "CREATE OR REPLACE TABLE " + table + "(c " + tc.ddl() + ")");

                // 1) 创建（不关闭，单独记录）
                DuckDBAppender ap = null;
                String create;
                try {
                    ap = dc.createAppender(null, null, table);
                    create = "OK";
                } catch (Exception e) {
                    create = "FAIL " + shortMsg(e.getMessage());
                }

                StringBuilder appendResult = new StringBuilder();
                if (ap != null) {
                    // 2) append（只包住 append 这一步）
                    try {
                        ap.beginRow();
                        ap.append(tc.text());
                        ap.endRow();
                        appendResult.append("OK");
                    } catch (Exception e) {
                        appendResult.append("FAIL ").append(shortMsg(e.getMessage()));
                    }

                    // 3) flush
                    String flush;
                    try {
                        ap.flush();
                        flush = "OK";
                    } catch (Exception e) {
                        flush = "FAIL " + shortMsg(e.getMessage());
                    }

                    // 4) close（单独记录）
                    try {
                        ap.close();
                    } catch (Exception e) {
                        appendResult.append(" | close FAIL ").append(shortMsg(e.getMessage()));
                    }

                    String read = scalar(c, "SELECT CAST(c AS VARCHAR) FROM " + table);
                    System.out.printf("%-12s | %-16s | %-34s | %-6s | %s%n", tc.label(), create,
                            appendResult, flush, read);
                } else {
                    System.out.printf("%-12s | %-16s | %-34s | %-6s | %s%n", tc.label(), create,
                            "-", "-", scalar(c, "SELECT CAST(c AS VARCHAR) FROM " + table));
                }
            }
        }
    }

    static void exec(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            System.out.println("   FAIL(SQL): " + sql + " -> " + shortMsg(e.getMessage()));
        }
    }

    static String scalar(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1)).append(' ');
            }
            return sb.length() == 0 ? "<no row>" : sb.toString().trim();
        } catch (SQLException e) {
            return "FAIL: " + shortMsg(e.getMessage());
        }
    }

    static String shortMsg(String s) {
        if (s == null) {
            return "?";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() > 70 ? t.substring(0, 67) + "..." : t;
    }
}
