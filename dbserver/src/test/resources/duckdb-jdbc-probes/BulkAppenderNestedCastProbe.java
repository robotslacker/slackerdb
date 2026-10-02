import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/**
 * 块 Appender 在复合类型内部是否做隐式转换？这决定服务端要写多少解析代码。
 * 重点：LIST/STRUCT/MAP/UNION 的元素用"字符串"给，能不能被转换。
 */
public class BulkAppenderNestedCastProbe {

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            DuckDBConnection dc = c.unwrap(DuckDBConnection.class);

            // LIST(INTEGER)：元素用字符串
            check(c, dc, "INTEGER[]", "{'x': 0}",
                    "append(List<String>) -> INTEGER[]",
                    ap -> ap.append(Arrays.asList("1", "2", "3")));
            // LIST(DATE)
            check(c, dc, "DATE[]", null,
                    "append(List<String>) -> DATE[]",
                    ap -> ap.append(Arrays.asList("2024-02-29", "2024-03-01")));
            // STRUCT：字段用字符串
            check(c, dc, "STRUCT(a INTEGER, b VARCHAR)", null,
                    "beginStruct/append(String)/append(String)",
                    ap -> ap.beginStruct().append("1").append("x").endStruct());
            // STRUCT 里嵌 LIST
            check(c, dc, "STRUCT(a INTEGER, l INTEGER[])", null,
                    "beginStruct/append(String)/append(List<String>)",
                    ap -> ap.beginStruct().append("1").append(Arrays.asList("1", "2")).endStruct());
            // MAP：键值用字符串
            check(c, dc, "MAP(VARCHAR, INTEGER)", null,
                    "append(Map<String,String>)",
                    ap -> ap.append(mapOf("a", "1", "b", "2")));
            // LIST(STRUCT)：元素是 Map<String,String>
            check(c, dc, "STRUCT(a INTEGER)[]", null,
                    "append(List<Map<String,String>>)",
                    ap -> ap.append(List.of(mapOf("a", "1"), mapOf("a", "2"))));
            // UNION：beginUnion + append(String)
            exec(c, "CREATE OR REPLACE TYPE udemo AS UNION(num INTEGER, str VARCHAR)");
            check(c, dc, "udemo", null,
                    "beginUnion('num')/append(String)",
                    ap -> ap.beginUnion("num").append("42").endUnion());
            // 对照：LIST 元素类型不匹配（字符串进 DATE[] 之外的错误形态）
            check(c, dc, "INTEGER[]", null,
                    "append(List<String> 非法值)",
                    ap -> ap.append(Arrays.asList("abc")));
        } catch (SQLException e) {
            System.out.println("FAIL: " + e.getMessage());
        }
    }

    static Map<String, String> mapOf(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    interface Call {
        void call(DuckDBAppender ap) throws SQLException;
    }

    static void check(Connection c, DuckDBConnection dc, String ddl, String unused, String label, Call call) {
        String table = "nc_" + Math.abs(label.hashCode());
        exec(c, "CREATE OR REPLACE TABLE " + table + "(c " + ddl + ")");
        try (DuckDBAppender ap = dc.createAppender(null, null, table)) {
            ap.beginRow();
            call.call(ap);
            ap.endRow();
            ap.flush();
            System.out.println("   OK   : " + label + "  => " + scalar(c, "select cast(c as varchar) from " + table));
        } catch (Exception e) {
            System.out.println("   FAIL : " + label + "  -> " + one(e.getMessage()));
        }
    }

    static void exec(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            System.out.println("   FAIL(SQL): " + sql + " -> " + one(e.getMessage()));
        }
    }

    static String scalar(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "<no row>";
        } catch (SQLException e) {
            return "FAIL: " + one(e.getMessage());
        }
    }

    static String one(String s) {
        if (s == null) {
            return "?";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() > 120 ? t.substring(0, 117) + "..." : t;
    }
}
