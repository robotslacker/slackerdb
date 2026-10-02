import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** 验证"把 DuckDB 复合类型当作 PG jsonb 承载"是否可行：出去 to_json，回来 from_json。 */
public class JsonbBridgeProbe {

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            exec(c, "CREATE TYPE udemo AS UNION(num INTEGER, str VARCHAR)");
            exec(c, "CREATE TABLE t(s STRUCT(a INTEGER, b VARCHAR), m MAP(VARCHAR, INTEGER), l INTEGER[],"
                    + " u udemo, v VARIANT, h HUGEINT, b BLOB)");
            exec(c, "INSERT INTO t VALUES ({'a': 1, 'b': 'x'}, MAP {'k': 1}, [1,2],"
                    + " union_value(num := 42), CAST({'z': 9} AS VARIANT),"
                    + " 170141183460469231731687303715884105727, from_hex('DEADBEEF'))");

            System.out.println("== 出去：to_json(复合列) 能不能拿到标准 JSON 文本");
            String[] cols = {"s", "m", "l", "u", "v", "h", "b"};
            for (String col : cols) {
                query(c, "select to_json(" + col + ") as j from t", "to_json(" + col + ")");
            }

            System.out.println("== 回来：把 JSON 文本写回同类型列");
            tryRun(c, "真 jsonb 文本 -> STRUCT 隐式转换",
                    "select '" + "{\"a\": 1, \"b\": \"x\"}" + "'::STRUCT(a INTEGER, b VARCHAR)");
            tryRun(c, "from_json 文本 -> STRUCT",
                    "select from_json('{\"a\": 1, \"b\": \"x\"}', 'STRUCT(a INTEGER, b VARCHAR)')");
            tryRun(c, "from_json 文本 -> MAP",
                    "select from_json('{\"k\": 1}', 'MAP(VARCHAR, INTEGER)')");
            tryRun(c, "from_json 文本 -> LIST",
                    "select from_json('[1,2]', 'INTEGER[]')");
            tryRun(c, "from_json 文本 -> VARIANT",
                    "select from_json('{\"z\": 9}', 'VARIANT')");
            tryRun(c, "from_json 文本 -> UNION",
                    "select from_json('42', 'udemo')");
            tryRun(c, "PG 风格数组文本 -> LIST",
                    "select '{1,2}'::INTEGER[]");

            System.out.println("== 反过来：DuckDB 自己怎么把 JSON 文本转进 VARIANT/UNION");
            tryRun(c, "CAST(json AS VARIANT)", "select CAST('{\"z\": 9}' AS VARIANT)");
            tryRun(c, "json -> VARIANT", "select json('{\"z\": 9}')::VARIANT");
        }
    }

    static void exec(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            System.out.println("   FAIL : " + sql + " -> " + line(e.getMessage()));
        }
    }

    static void query(Connection c, String sql, String label) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            System.out.println("   " + label + " => " + rs.getString(1));
        } catch (Exception e) {
            System.out.println("   " + label + " => FAIL: " + line(e.getMessage()));
        }
    }

    static void tryRun(Connection c, String label, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            System.out.println("   OK   : " + label + " => " + rs.getString(1)
                    + " (" + rs.getMetaData().getColumnTypeName(1) + ")");
        } catch (SQLException e) {
            System.out.println("   FAIL : " + label + " -> " + line(e.getMessage()));
        }
    }

    static String line(String s) {
        return s == null ? "?" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
