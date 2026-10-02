import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/** BLOB 列到底该用哪个 append 重载：append(byte[]) 还是 appendByteArray(byte[])？ */
public class BlobAppendProbe {
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            DuckDBConnection dc = c.unwrap(DuckDBConnection.class);
            for (String kind : new String[] {"append(byte[])", "appendByteArray(byte[])"}) {
                exec(c, "CREATE OR REPLACE TABLE t(c BLOB)");
                try (DuckDBAppender ap = dc.createAppender(null, null, "t")) {
                    ap.beginRow();
                    if ("append(byte[])".equals(kind)) {
                        ap.append(new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
                    } else {
                        ap.appendByteArray(new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
                    }
                    ap.endRow();
                    ap.flush();
                    System.out.println("   OK   : " + kind + " => " + scalar(c, "select cast(c as varchar) from t"));
                } catch (Exception e) {
                    System.out.println("   FAIL : " + kind + " -> " + one(e.getMessage()));
                }
            }
        }
    }

    static void exec(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            System.out.println("   FAIL(SQL): " + e.getMessage());
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
