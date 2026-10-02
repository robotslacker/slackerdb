import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/** 纯块 Appender 需要的精确 Java 类型矩阵 + appendHugeInt 参数顺序。 */
public class BulkAppenderJavaTypeProbe {

    static final String UHUGEINT_MAX = "340282366920938463463374607431768211455";

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            DuckDBConnection dc = c.unwrap(DuckDBConnection.class);

            check(c, dc, "DATE[]", "List<LocalDate>",
                    ap -> ap.append(Arrays.asList(LocalDate.of(2024, 2, 29))));
            check(c, dc, "HUGEINT[]", "List<BigInteger>",
                    ap -> ap.append(List.of(new BigInteger("170141183460469231731687303715884105727"))));
            check(c, dc, "DECIMAL(18,4)[]", "List<BigDecimal>",
                    ap -> ap.append(List.of(new BigDecimal("1.5000"))));
            check(c, dc, "TIMESTAMPTZ[]", "List<OffsetDateTime>",
                    ap -> ap.append(List.of(OffsetDateTime.of(2024, 2, 29, 12, 34, 56, 789_000_000,
                            ZoneOffset.ofHours(8)))));
            check(c, dc, "STRUCT(a INTEGER, d DATE)", "Map<String,Object>{Integer,LocalDate}",
                    ap -> ap.append(mapOf("a", 1, "d", LocalDate.of(2024, 2, 29))));
            check(c, dc, "MAP(VARCHAR, DATE)", "Map<String,LocalDate>",
                    ap -> ap.append(mapOf("k", LocalDate.of(2024, 2, 29))));
            check(c, dc, "STRUCT(a INTEGER)[]", "List<Map<String,Object>>",
                    ap -> ap.append(List.of(mapOf("a", 1), mapOf("a", 2))));
            check(c, dc, "INTEGER[][]", "List<List<Integer>>",
                    ap -> ap.append(List.of(Arrays.asList(1, 2), List.of(3))));
            check(c, dc, "UUID[]", "List<UUID>",
                    ap -> ap.append(List.of(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))));
            check(c, dc, "BLOB[]", "List<byte[]>",
                    ap -> ap.append(List.of(new byte[] {(byte) 0xDE, (byte) 0xAD})));

            System.out.println("== appendHugeInt 参数顺序（UHUGEINT = 1 与 2^127）");
            hugeInt(c, dc, BigInteger.ONE);
            hugeInt(c, dc, new BigInteger("170141183460469231731687303715884105728"));
            check(c, dc, "UHUGEINT", "append(BigInteger) 65535",
                    ap -> ap.append(new BigInteger("65535")));
        } catch (SQLException e) {
            System.out.println("FAIL: " + e.getMessage());
        }
    }

    static void hugeInt(Connection c, DuckDBConnection dc, BigInteger value) {
        BigInteger low = value.and(new BigInteger("FFFFFFFFFFFFFFFF", 16));
        BigInteger high = value.shiftRight(64);
        // 参数顺序候选：(low, high) 与 (high, low)
        for (String order : List.of("低,高", "高,低")) {
            exec(c, "CREATE OR REPLACE TABLE hu(c UHUGEINT)");
            try (DuckDBAppender ap = dc.createAppender(null, null, "hu")) {
                ap.beginRow();
                if ("低,高".equals(order)) {
                    ap.appendHugeInt(low.longValue(), high.longValue());
                } else {
                    ap.appendHugeInt(high.longValue(), low.longValue());
                }
                ap.endRow();
                ap.flush();
                System.out.println("   " + order + " -> " + scalar(c, "select cast(c as varchar) from hu")
                        + "   (期望 " + value + ")");
            } catch (Exception e) {
                System.out.println("   " + order + " FAIL: " + one(e.getMessage()));
            }
        }
    }

    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    interface Call {
        void call(DuckDBAppender ap) throws SQLException;
    }

    static void check(Connection c, DuckDBConnection dc, String ddl, String label, Call call) {
        String table = "jt_" + Math.abs((ddl + label).hashCode());
        exec(c, "CREATE OR REPLACE TABLE " + table + "(c " + ddl + ")");
        try (DuckDBAppender ap = dc.createAppender(null, null, table)) {
            ap.beginRow();
            call.call(ap);
            ap.endRow();
            ap.flush();
            System.out.println("   OK   : " + ddl + " <- " + label + "  => "
                    + scalar(c, "select cast(c as varchar) from " + table));
        } catch (Exception e) {
            System.out.println("   FAIL : " + ddl + " <- " + label + "  -> " + one(e.getMessage()));
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
        return t.length() > 110 ? t.substring(0, 107) + "..." : t;
    }
}
