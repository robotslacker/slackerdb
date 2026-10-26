package org.slackerdb.dbdesk.m0;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M0-V2 取数策略探针（一次性调查工具，不进入产品代码，也不进测试生命周期）。
 *
 * <p><b>要回答的问题</b>：控制台的 Result Grid 该用哪种方式取数？
 * 三种候选：</p>
 * <ol>
 *   <li><b>A 游标</b>：{@code autoCommit=false} + {@code setFetchSize(n)}（pgjdbc 会走 portal，
 *       服务端在每次 Execute 后回 PortalSuspended）—— 期望：首行快、内存恒定；</li>
 *   <li><b>B LIMIT/OFFSET</b>：每页一条新查询 —— 期望：实现简单，但深分页随 offset 线性变慢；</li>
 *   <li><b>C WS 分页</b>：{@code /sql/ws} 的 exec+fetch —— 本轮不测（M1 用不到异步面板）。</li>
 * </ol>
 *
 * <p><b>怎么跑</b>（JDK 21 单文件源码模式，无需编译）：</p>
 * <pre>
 *   java -cp &lt;postgresql.jar&gt; FetchProbe.java 127.0.0.1 4409 m0
 * </pre>
 *
 * <p>会建两张表（{@code m0_probe}：100 万行 × 50 列；{@code m0_big}：单值 2 MB），
 * 跑完打印一行行可粘贴进验证报告的数值。</p>
 */
public final class FetchProbe {

    private static final int ROWS = 1_000_000;
    private static final int COLUMNS = 50;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 4409;
        String database = args.length > 2 ? args[2] : "m0";

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        System.out.println("=== M0-V2 取数策略探针 ===");
        System.out.println("URL: " + url);
        System.out.println("JVM: " + System.getProperty("java.version")
                + " / maxHeap=" + (Runtime.getRuntime().maxMemory() >> 20) + "MB");

        try (Connection conn = DriverManager.getConnection(url, "", "")) {
            prepare(conn);
            long rowCount = scalarLong(conn, "select count(*) from m0_probe");
            System.out.println("m0_probe 行数: " + rowCount);

            probeCursor(conn, 500);
            probeCursor(conn, 5000);
            probeOffset(conn);
        }

        System.out.println("=== 完成 ===");
    }

    // ---------------------------------------------------------------- 建数

    private static void prepare(Connection conn) throws SQLException {
        System.out.println("--- 准备数据 ---");
        long t0 = System.nanoTime();
        try (Statement st = conn.createStatement()) {
            // 100 万行 × 50 列：id 为主键式整数，其余列混合 int/varchar/double/date
            StringBuilder sql = new StringBuilder("create or replace table m0_probe as select ");
            sql.append("i as id");
            for (int c = 1; c < COLUMNS; c++) {
                sql.append(", ");
                switch (c % 5) {
                    case 0 -> sql.append("(i % 1000)::int as c").append(c);
                    case 1 -> sql.append("('v' || i) as c").append(c);
                    case 2 -> sql.append("(i * 1.5) as c").append(c);
                    case 3 -> sql.append("(i % 7 = 0) as c").append(c);
                    default -> sql.append("(date '2020-01-01' + (i % 365)::int) as c").append(c);
                }
            }
            sql.append(" from range(").append(ROWS).append(") t(i)");
            st.execute(sql.toString());

            // 单值 2 MB：用于后续"单值超 1MB 截断"的验证（本轮只建，不读）
            st.execute("create or replace table m0_big as select repeat('x', 2000000) as payload, 1 as id");
        }
        System.out.printf("建表耗时: %.1f s%n", (System.nanoTime() - t0) / 1e9);
    }

    // ---------------------------------------------------------------- A 游标

    private static void probeCursor(Connection conn, int fetchSize) throws SQLException {
        System.out.println("--- A 游标（autoCommit=false, fetchSize=" + fetchSize + "） ---");
        String sql = "select * from m0_probe";
        conn.setAutoCommit(false);
        long start = System.nanoTime();
        long firstRowNanos = -1;
        long rows = 0;
        long cells = 0;
        long checkpointNanos;
        long heapBefore = usedHeap();

        try (Statement st = conn.createStatement()) {
            st.setFetchSize(fetchSize);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                while (rs.next()) {
                    if (firstRowNanos < 0) {
                        firstRowNanos = System.nanoTime() - start;
                    }
                    for (int c = 1; c <= cols; c++) {
                        rs.getObject(c);
                        cells++;
                    }
                    rows++;
                }
            }
        } finally {
            conn.setAutoCommit(true);
        }
        checkpointNanos = System.nanoTime() - start;
        long heapAfter = usedHeap();

        System.out.printf("首行: %.0f ms | 全量: %.1f s | 行=%d 单元格=%d | %s 单元格/秒%n",
                firstRowNanos / 1e6, checkpointNanos / 1e9, rows, cells,
                fmt(cells * 1e9 / Math.max(1, checkpointNanos)));
        System.out.printf("  堆增量: %d MB（%d → %d MB）%n",
                (heapAfter - heapBefore) >> 20, heapBefore >> 20, heapAfter >> 20);
    }

    // ------------------------------------------------------- B LIMIT/OFFSET

    private static void probeOffset(Connection conn) throws SQLException {
        int pageSize = 500;
        long[] offsets = {0, 100_000, 500_000, 999_000};
        Map<Long, Long> pageMillis = new LinkedHashMap<>();
        System.out.println("--- B LIMIT/OFFSET（pageSize=" + pageSize + "） ---");
        for (long offset : offsets) {
            String sql = "select * from m0_probe limit " + pageSize + " offset " + offset;
            long start = System.nanoTime();
            long rows = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    rows++;
                }
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            pageMillis.put(offset, millis);
            System.out.printf("offset=%-8d 行=%-5d 耗时=%d ms%n", offset, rows, millis);
        }
        long first = Math.max(1, pageMillis.getOrDefault(0L, 1L));
        long deepest = Math.max(1, pageMillis.getOrDefault(999_000L, 1L));
        System.out.printf("  深分页放大倍数（offset=999k / 0）: %.1fx%n", (double) deepest / first);
    }

    // ---------------------------------------------------------------- 工具

    private static long scalarLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static String fmt(double value) {
        return String.format("%,.0f", value);
    }
}
