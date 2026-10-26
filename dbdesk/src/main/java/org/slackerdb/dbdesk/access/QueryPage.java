package org.slackerdb.dbdesk.access;

import java.util.List;

/**
 * 一页查询结果：列元数据 + 行 + 耗时。**访问层对外的唯一结果形状**（视图只认它，不认 JDBC）。
 *
 * <p>与 {@code dbcli} 的 {@code ResultModel} 保持同一套口径（列名/列类型/NULL 表示），
 * 只是这里多了"分页"这一维：{@code dbcli} 一次跑完一个脚本，控制台要按窗口取。
 *
 * @param columns      列标签（与 {@code dbcli} 的 {@code headers} 同义）
 * @param columnTypes  列类型大写名（{@link SqlColumnTypes#nameOf}），下标与 columns 对齐
 * @param rows         行数据；单元格已按 {@link JdbcPageSource#MAX_CELL_CHARS} 截断
 * @param truncated    本页是否有单元格被截断（>1 MB 的单值）
 * @param elapsedMillis 取这一页（含 JDBC 往返）的耗时
 */
public record QueryPage(
        List<String> columns,
        List<String> columnTypes,
        List<List<Object>> rows,
        boolean truncated,
        long elapsedMillis) {

    public QueryPage {
        columns = List.copyOf(columns);
        columnTypes = List.copyOf(columnTypes);
        rows = List.copyOf(rows);
    }

    public static QueryPage empty() {
        return new QueryPage(List.of(), List.of(), List.of(), false, 0);
    }

    public int rowCount() {
        return rows.size();
    }
}
