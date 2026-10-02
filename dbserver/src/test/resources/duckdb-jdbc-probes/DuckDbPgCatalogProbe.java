import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** 看 DuckDB 自己的 pg_catalog 能不能支撑 pgjdbc 的类型解析（typtype/typinput/typelem/typarray + 元数据查询）。 */
public class DuckDbPgCatalogProbe {
    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            System.out.println("== pg_catalog.pg_type 的列");
            run(c, "select column_name || ' : ' || data_type from information_schema.columns "
                    + "where table_schema='pg_catalog' and table_name='pg_type' order by ordinal_position");

            System.out.println("== 建 enum / 复合类型后再看 pg_type");
            exec(c, "CREATE TYPE mood AS ENUM ('sad','ok','happy')");
            exec(c, "CREATE TABLE t1(s STRUCT(a INTEGER, b VARCHAR), l INTEGER[], e mood, m MAP(VARCHAR,INTEGER))");
            run(c, "select 'oid=' || oid || ' name=' || typname || ' typtype=' || typtype"
                    + " || ' typlen=' || typlen || ' typelem=' || typelem || ' typarray=' || typarray"
                    + " || ' typinput=' || coalesce(typinput::VARCHAR,'<null>') "
                    + "from pg_catalog.pg_type order by oid, typname");

            System.out.println("== 用 pg_attribute / format_type 反查 t1 的列类型");
            run(c, "select a.attname || ' -> ' || format_type(a.atttypid, a.atttypmod) "
                    + "from pg_catalog.pg_attribute a where a.attname in ('s','l','e','m')");
            run(c, "select t.oid || '/' || t.typname from pg_catalog.pg_type t "
                    + "join pg_catalog.pg_namespace n on t.typnamespace = n.oid "
                    + "where t.typname in ('mood','int4')");

            System.out.println("== pgjdbc 类型/元数据查询依赖的函数与表达式");
            String[] queries = {
                    "select typinput='pg_catalog.array_in'::regproc as is_array, typtype, typname, oid from pg_catalog.pg_type limit 3",
                    "select current_schemas(false)",
                    "select array_upper(current_schemas(false), 1)",
                    "select array_length(current_schemas(false), 1)",
                    "select (current_schemas(false))[1]",
                    "select * from generate_series(1, 2)",
                    "select ns.oid, ns.nspname from pg_catalog.pg_namespace ns",
                "select t.typarray, arr.typname from pg_catalog.pg_type t "
                        + "join pg_catalog.pg_namespace n on t.typnamespace = n.oid "
                        + "join pg_catalog.pg_type arr on arr.oid = t.typarray where t.typname = 'int4'",
                    "select t.oid, t.typname from pg_catalog.pg_type t where t.typelem = "
                            + "(select oid from pg_catalog.pg_type where typname = 'int4')",
                    "select e.typdelim from pg_catalog.pg_type t, pg_catalog.pg_type e limit 1",
                    "select typtype, typname from pg_catalog.pg_type where oid = 23",
            };
            for (String sql : queries) {
                run(c, sql);
            }
        }
    }

    static void exec(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            System.out.println("   FAIL : " + sql + "  -> " + oneLine(e.getMessage()));
        }
    }

    static void run(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = 0;
            while (rs.next()) {
                System.out.println("   " + rs.getString(1));
                n++;
            }
            if (n == 0) {
                System.out.println("   OK(0 rows): " + sql);
            }
        } catch (Exception e) {
            System.out.println("   FAIL : " + sql + "  -> " + oneLine(e.getMessage()));
        }
    }

    static String oneLine(String s) {
        return s == null ? "?" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
