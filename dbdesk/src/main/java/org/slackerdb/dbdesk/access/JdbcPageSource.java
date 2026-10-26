package org.slackerdb.dbdesk.access;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 按 {@code LIMIT/OFFSET} 分页取数的 JDBC 数据源（M0 用最小实现，M1 长成真正的执行层）。
 *
 * <p><b>为什么是 LIMIT/OFFSET 而不是游标</b>：M0 实测（1,000,000 行 × 50 列，本机）
 * 每页 500 行约 18 ms，深分页（offset=999k）只放大到 24 ms（1.3x，与 PostgreSQL 的经验相反），
 * 且客户端内存恒定；游标更适合"一次读完"的导出，不适合"随便跳页"的网格。
 * 结论见 {@code dbdesk-final-plan.md} §7.1。</p>
 *
 * <p><b>这一层不许 import Vaadin</b>（架构约束，M1 起用 ArchUnit 守）。视图只拿到
 * {@link QueryPage}，拿不到 {@link Connection}。</p>
 *
 * <p><b>分页语义</b>：用户 SQL 被包成 {@code select * from (<sql>) dbdesk_page limit ? offset ?}。
 * 包一层是为了让它能同时接受"已有 limit"或"order by"的 SQL；代价是依赖子查询保序
 * （DuckDB 实测保序，见 {@code JdbcPageSourceTest}）。M1 换成真正的执行层时，这里要能识别
 * 顶层 {@code LIMIT} 并改用游标窗口。</p>
 *
 * <p><b>线程模型</b>：一个实例持有一条连接，所有取数方法串行（{@code synchronized}）。
 * Vaadin 的 {@code DataProvider} 回调可能来自不同请求线程，串行化是 M0 的刻意简化；
 * 多连接池留到 M1。</p>
 */
public final class JdbcPageSource implements AutoCloseable {

    /** 单元格字符上限：超过就截断（决策：单结果 100 万行 / 50 列 / 单值 1 MB 截断）。 */
    public static final int MAX_CELL_CHARS = 1_048_576;

    private static final String TRUNCATED_SUFFIX_FORMAT = "…[已截断，原长 %d 字符]";

    private final String url;
    private final int maxCellChars;
    private final Object lock = new Object();
    private Connection connection;

    public JdbcPageSource(String url) {
        this(url, MAX_CELL_CHARS);
    }

    public JdbcPageSource(String url, int maxCellChars) {
        this.url = url;
        this.maxCellChars = maxCellChars;
    }

    /** 取第 {@code offset} 行开始的 {@code limit} 行。调用方保证 offset/limit 非负。 */
    public QueryPage fetch(String sql, long offset, int limit) throws SQLException {
        String paged = "select * from (" + stripTrailingSemicolon(sql) + ") dbdesk_page limit " + limit
                + " offset " + offset;
        long start = System.nanoTime();
        synchronized (lock) {
            try (Statement statement = connection().createStatement();
                 ResultSet rs = statement.executeQuery(paged)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();

                List<String> columns = new ArrayList<>(columnCount);
                List<String> types = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                    types.add(SqlColumnTypes.nameOf(meta.getColumnType(i), meta.getColumnTypeName(i)));
                }

                List<List<Object>> rows = new ArrayList<>(Math.min(limit, 1024));
                boolean truncated = false;
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        if (value instanceof String text && text.length() > maxCellChars) {
                            value = text.substring(0, maxCellChars)
                                    + String.format(TRUNCATED_SUFFIX_FORMAT, text.length());
                            truncated = true;
                        }
                        row.add(value);
                    }
                    rows.add(row);
                }
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                return new QueryPage(columns, types, rows, truncated, elapsed);
            }
        }
    }

    /**
     * 总行数（Grid 的滚动条与"第 N 行"要用）。
     *
     * <p>对 100 万行的表这是**一次全扫描**（实测数百毫秒），所以只在"加载"时调一次，
     * 不要放进每页的 fetch 回调。</p>
     */
    public long count(String sql) throws SQLException {
        String counting = "select count(*) from (" + stripTrailingSemicolon(sql) + ") dbdesk_page";
        synchronized (lock) {
            try (Statement statement = connection().createStatement();
                 ResultSet rs = statement.executeQuery(counting)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** 只做连通性探测（启动/切换连接时用），失败抛异常。 */
    public void verify() throws SQLException {
        synchronized (lock) {
            try (Statement statement = connection().createStatement()) {
                statement.execute("select 1");
            }
        }
    }

    public String url() {
        return url;
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            JdbcDrivers.ensureLoaded();
            connection = DriverManager.getConnection(url, "", "");
        }
        return connection;
    }

    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql.strip();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 关闭失败不影响调用方
                } finally {
                    connection = null;
                }
            }
        }
    }
}
