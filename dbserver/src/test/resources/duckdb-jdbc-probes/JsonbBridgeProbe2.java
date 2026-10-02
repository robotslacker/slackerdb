import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** 补充验证：from_json 的正确写法、VARIANT 的 JSON 出口、PG 数组文本的双向转换。 */
public class JsonbBridgeProbe2 {
    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            tryRun(c, "from_json + JSON schema -> STRUCT",
                    "select from_json('{\"a\": 1, \"b\": \"x\"}', '{\"a\":\"INTEGER\",\"b\":\"VARCHAR\"}')");
            tryRun(c, "from_json + JSON schema -> MAP",
                    "select from_json('{\"k\": 1}', '{\"k\":\"INTEGER\"}')");
            tryRun(c, "from_json + JSON schema -> LIST",
                    "select from_json('[1,2]', '[\"INTEGER\"]')");

            exec(c, "CREATE TABLE t(v VARIANT, l INTEGER[])");
            exec(c, "INSERT INTO t VALUES (CAST({'z': 9} AS VARIANT), [1,2])");
            tryRun(c, "variant_to_json 是否存在", "select variant_to_json(v) from t");
            tryRun(c, "to_json(VARIANT 内层 struct)", "select v::VARCHAR from t");
            tryRun(c, "variant_extract", "select variant_extract(v, 'z') from t");
            tryRun(c, "CAST(VARIANT AS JSON)", "select CAST(v AS JSON) from t");
            tryRun(c, "CAST(JSON AS VARIANT)", "select CAST('{\"z\": 9}' AS JSON)::VARIANT");

            System.out.println("== PG 数组文本 {1,2} 与 DuckDB 文本 [1,2] 的双向翻译");
            tryRun(c, "DuckDB 数组 -> 文本", "select l::VARCHAR from t");
            tryRun(c, "PG 数组文本 -> LIST（直接 cast）", "select '{1,2}'::INTEGER[]");
            tryRun(c, "PG 数组文本 -> LIST（手工翻译）",
                    "select string_split(trim('{1,2}', '{}'), ',')::INTEGER[]");
            tryRun(c, "DuckDB 文本 [1,2] -> PG 数组文本",
                    "select '{\" + ? + \"}'");
        }
    }

    static void exec(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            System.out.println("   FAIL : " + sql + " -> " + line(e.getMessage()));
        }
    }

    static void tryRun(Connection c, String label, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            System.out.println("   OK   : " + label + " => " + rs.getString(1));
        } catch (SQLException e) {
            System.out.println("   FAIL : " + label + " -> " + line(e.getMessage()));
        }
    }

    static String line(String s) {
        return s == null ? "?" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
