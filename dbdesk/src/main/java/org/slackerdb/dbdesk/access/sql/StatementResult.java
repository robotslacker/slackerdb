package org.slackerdb.dbdesk.access.sql;

import java.util.List;

/**
 * 一条语句的执行结果（查询或更新）。
 *
 * <p>多语句脚本会得到 {@code List<StatementResult>}：**执行到出错那条就停**，出错那条带
 * {@link #error()}，前面的结果仍然保留（用户要看"跑到哪一步炸的"）。</p>
 *
 * @param sql           实际执行的语句（已去掉首尾空白与分号）
 * @param query         true=有结果集；false=更新/DDL
 * @param columns       列标签（仅查询）
 * @param columnTypes   列类型大写名（仅查询，口径同 {@code SqlColumnTypes}）
 * @param rows          行（仅查询，已按 maxRows 与单值上限截断）
 * @param truncated     是否有单元格因超长被截断
 * @param rowsTruncated 是否因 maxRows 只取了前 N 行
 * @param updateCount   影响行数（仅更新；DDL 为 0）
 * @param elapsedMillis 本语句耗时
 * @param error         错误文案；{@code null} 表示成功
 * @param errorState    SQLState（用于区分"连接没了"和"语句写错了"；可能为 null）
 */
public record StatementResult(
        String sql,
        boolean query,
        List<String> columns,
        List<String> columnTypes,
        List<List<Object>> rows,
        boolean truncated,
        boolean rowsTruncated,
        int updateCount,
        long elapsedMillis,
        String error,
        String errorState) {

    public StatementResult {
        columns = List.copyOf(columns);
        columnTypes = List.copyOf(columnTypes);
        rows = List.copyOf(rows);
    }

    /** 兼容旧调用（不带 SQLState）。 */
    public StatementResult(String sql, boolean query, List<String> columns, List<String> columnTypes,
                           List<List<Object>> rows, boolean truncated, boolean rowsTruncated,
                           int updateCount, long elapsedMillis, String error) {
        this(sql, query, columns, columnTypes, rows, truncated, rowsTruncated, updateCount, elapsedMillis,
                error, null);
    }

    public static StatementResult failure(String sql, long elapsedMillis, String error) {
        return new StatementResult(sql, false, List.of(), List.of(), List.of(),
                false, false, 0, elapsedMillis, error, null);
    }

    public static StatementResult failure(String sql, long elapsedMillis, String error, String errorState) {
        return new StatementResult(sql, false, List.of(), List.of(), List.of(),
                false, false, 0, elapsedMillis, error, errorState);
    }

    /** SQLState 08xxx = 连接类异常（断了/超时/认证失败），界面要提示"重连"而不是"改 SQL"。 */
    public boolean connectionFailure() {
        return errorState != null && errorState.startsWith("08");
    }

    public boolean failed() {
        return error != null;
    }

    public int rowCount() {
        return rows.size();
    }
}
