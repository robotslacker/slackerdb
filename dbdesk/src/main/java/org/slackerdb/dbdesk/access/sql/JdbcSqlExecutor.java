package org.slackerdb.dbdesk.access.sql;

import org.slackerdb.dbdesk.access.ConsoleIdentity;
import org.slackerdb.dbdesk.access.SqlColumnTypes;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 执行层：一次连接 = 一个会话，按顶层分号拆语句，逐条执行。
 *
 * <p><b>它能做的四件事</b>（M1 工作台的地基）：</p>
 * <ol>
 *   <li><b>多语句</b>：{@link StatementSplitter#split} 切分，出错即停，前面的结果保留；</li>
 *   <li><b>取消</b>：{@link #cancel()} 调 {@link Statement#cancel()}，可以从另一个线程触发；</li>
 *   <li><b>超时</b>：{@link #setQueryTimeoutSeconds}，默认 30 s；</li>
 *   <li><b>来源打标</b>：连接串上带 {@code ApplicationName=slackerdb-console/<ver>}，
 *       会话面板因此能认出"这是控制台自己"，避免被误杀。</li>
 * </ol>
 *
 * <p><b>与 {@code JdbcPageSource} 的分工</b>：本类是"执行一次、拿结果"，用于工作台；
 * 网格翻页仍走 {@code JdbcPageSource}（LIMIT/OFFSET 窗口化）。两者共用
 * {@link SqlColumnTypes} 的类型口径与同一条 1 MB 截断规则。</p>
 *
 * <p><b>线程模型</b>：执行在调用线程上进行；{@link #cancel()} 只碰 {@code volatile} 的当前 Statement，
 * 因此可以由 UI 的取消按钮线程调用。一条连接同一时刻只跑一条语句。</p>
 */
public final class JdbcSqlExecutor implements AutoCloseable {

    /** 单元格字符上限，与 {@code JdbcPageSource} 保持一致（1 MB 截断）。 */
    public static final int MAX_CELL_CHARS = 1_048_576;

    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private final String url;
    private final int maxCellChars;
    private Connection connection;
    private volatile Statement runningStatement;
    private int queryTimeoutSeconds = DEFAULT_QUERY_TIMEOUT_SECONDS;

    public JdbcSqlExecutor(String url) {
        this(url, MAX_CELL_CHARS);
    }

    public JdbcSqlExecutor(String url, int maxCellChars) {
        this.url = url;
        this.maxCellChars = maxCellChars;
    }

    /**
     * 执行一段脚本（可含多条语句），按出现顺序返回每条的结果。
     *
     * <p>{@code maxRows <= 0} 表示不限制行数（导出场景）；界面一般传 1000–5000。</p>
     */
    public List<StatementResult> execute(String script, int maxRows) {
        List<String> statements = StatementSplitter.split(script);
        if (statements.isEmpty()) {
            return List.of();
        }
        List<StatementResult> results = new ArrayList<>(statements.size());
        for (String statement : statements) {
            StatementResult result = executeOne(statement, maxRows);
            results.add(result);
            if (result.failed()) {
                break; // 出错即停：后面的语句不再执行，但前面的结果保留
            }
        }
        return List.copyOf(results);
    }

    private StatementResult executeOne(String sql, int maxRows) {
        long start = System.nanoTime();
        try (Statement statement = connection().createStatement()) {
            runningStatement = statement;
            statement.setQueryTimeout(queryTimeoutSeconds);
            if (maxRows > 0) {
                statement.setMaxRows(maxRows);
            }

            boolean hasResultSet = statement.execute(sql);
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            if (!hasResultSet) {
                return new StatementResult(sql, false, List.of(), List.of(), List.of(),
                        false, false, Math.max(0, statement.getUpdateCount()), elapsed, null);
            }
            try (ResultSet rs = statement.getResultSet()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();

                List<String> columns = new ArrayList<>(columnCount);
                List<String> types = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                    types.add(SqlColumnTypes.nameOf(meta.getColumnType(i), meta.getColumnTypeName(i)));
                }

                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        if (value instanceof String text && text.length() > maxCellChars) {
                            value = text.substring(0, maxCellChars)
                                    + String.format("…[已截断，原长 %d 字符]", text.length());
                            truncated = true;
                        }
                        row.add(value);
                    }
                    rows.add(row);
                }
                boolean rowsTruncated = maxRows > 0 && rows.size() >= maxRows;
                long total = (System.nanoTime() - start) / 1_000_000;
                return new StatementResult(sql, true, columns, types, rows, truncated, rowsTruncated,
                        0, total, null);
            }
        } catch (SQLException e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            return StatementResult.failure(sql, elapsed, e.getMessage(), e.getSQLState());
        } finally {
            runningStatement = null;
        }
    }

    /**
     * 取消正在执行的语句（可以从别的线程调用）。
     *
     * @return 是否真的发出了一次取消（没有正在跑的语句时为 false）
     */
    public boolean cancel() {
        Statement current = runningStatement;
        if (current == null) {
            return false;
        }
        try {
            current.cancel();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public void setQueryTimeoutSeconds(int seconds) {
        this.queryTimeoutSeconds = Math.max(1, seconds);
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    /** 连接串（已带上 ApplicationName）。 */
    public String url() {
        return url;
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            org.slackerdb.dbdesk.access.JdbcDrivers.ensureLoaded();
            connection = DriverManager.getConnection(withApplicationName(url), "", "");
        }
        return connection;
    }

    /**
     * 把 {@code ApplicationName} 拼进连接串：服务端在握手后用它填 {@code /status.sessions[].applicationName}，
     * 会话面板据此标注"本机控制台（勿误杀）"。
     */
    static String withApplicationName(String url) {
        if (url.contains("ApplicationName=") || url.contains("applicationName=")) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "ApplicationName=" + ConsoleIdentity.applicationName();
    }

    @Override
    public void close() {
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
