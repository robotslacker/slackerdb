package org.slackerdb.dbcli.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * 结果集的**文本渲染**：按 {@link RenderOptions#format()} 选表格或 CSV。
 *
 * <p>唯一的入口是 {@link #render(RenderOptions, List, List, List)}——所有结果集（SQL 查询、
 * {@code _SET} 的选项表、{@code _JOB SHOW}、{@code _COMPARE} 的差异表…）都从这里出去，
 * 因此"换格式"只有一个实现，不会出现"某种表格不认 --format"。</p>
 *
 * <p>单元格文本一律经过 {@link CellRenderer}（日期/BigDecimal/byte[] 的稳定渲染），
 * 因此换格式不会换掉值本身，只换分隔与排版。</p>
 */
public final class ResultRenderers {

    private ResultRenderers() {
    }

    /**
     * 渲染一个结果集。
     *
     * @param options     格式选项
     * @param headers     列名
     * @param columnTypes 列类型（表格用它决定左右对齐；CSV 不需要，可为 null）
     * @param rows        行数据（元素可为 null）
     * @return 要逐行输出的文本（行尾由 {@code ScriptOutput} 统一加 LF）
     */
    public static List<String> render(RenderOptions options, List<String> headers,
                                      List<String> columnTypes, List<List<Object>> rows) {
        RenderOptions effective = options == null ? RenderOptions.defaults() : options;
        // CSV 是导出格式：不截断、不换行（截断或折断就是毁数据）。这两件事只用于"给人看"的两种格式。
        boolean truncate = effective.format() != RenderOptions.Format.CSV;
        List<List<String>> cells = toCells(effective, rows, truncate);
        List<String> lines = switch (effective.format()) {
            case TABLE -> SqlTableRenderer.renderTab(headers, columnTypes, rows, 0,
                    effective.header(), effective.colWidth());
            case VERTICAL -> vertical(headers, cells);
            case CSV -> csv(headers, cells, effective.header());
        };
        return effective.format() == RenderOptions.Format.CSV
                ? lines : wrapLines(lines, effective.lineWidth());
    }

    /**
     * 把一行数据渲染成单元格文本。
     *
     * <p>NULL 的写法是**固定**的：table / vertical 用 {@code <null>}，CSV 写成空字段
     * （与空串等效——导出格式里"空"本来就是空）。</p>
     */
    private static List<List<String>> toCells(RenderOptions options, List<List<Object>> rows,
                                              boolean truncate) {
        List<List<String>> cells = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows == null) {
            return cells;
        }
        boolean csv = options.format() == RenderOptions.Format.CSV;
        for (List<Object> row : rows) {
            List<String> line = new ArrayList<>(row.size());
            for (Object value : row) {
                String text;
                if (value == null) {
                    text = csv ? "" : NULL_TEXT;
                } else {
                    String plain = CellRenderer.render(value);
                    text = truncate ? CellRenderer.truncate(plain, options.colWidth()) : plain;
                }
                line.add(text == null ? "" : text);
            }
            cells.add(line);
        }
        return cells;
    }

    /** NULL 在 table / vertical 里的固定显示文本（CSV 用空字段）。 */
    public static final String NULL_TEXT = "<null>";

    /**
     * 按整行宽度上限**换行**（不是截断）：超过 {@code lineWidth} 显示列的行拆成多行。
     *
     * <p>只用于 table / vertical；CSV 永不折断。{@code lineWidth <= 0} 表示不换行。</p>
     */
    static List<String> wrapLines(List<String> lines, int lineWidth) {
        if (lineWidth <= 0) {
            return lines;
        }
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            wrapOne(line, lineWidth, wrapped);
        }
        return wrapped;
    }

    /** 把一行按显示宽度切成若干段（CJK 算两列；不会把一个字符切成两半）。 */
    private static void wrapOne(String line, int lineWidth, List<String> out) {
        if (line == null) {
            out.add("");
            return;
        }
        if (SqlTableRenderer.displayWidth(line) <= lineWidth) {
            out.add(line);
            return;
        }
        StringBuilder current = new StringBuilder();
        int used = 0;
        for (int index = 0; index < line.length(); ) {
            int codePoint = line.codePointAt(index);
            String piece = new String(Character.toChars(codePoint));
            int width = SqlTableRenderer.displayWidth(piece);
            if (used + width > lineWidth && current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
                used = 0;
            }
            current.append(piece);
            used += width;
            index += Character.charCount(codePoint);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
    }

    /**
     * 竖排（psql {@code \x} 风格）：每条记录一个块，块内一行一个字段。
     *
     * <p>格式（逐字节稳定）：</p>
     * <pre>
     * -[ RECORD 1 ]
     * id   | 1
     * name | alice
     *
     * -[ RECORD 2 ]
     * ...
     * </pre>
     *
     * <p>字段名按表头宽度左对齐，因此同一份结果的竖排是确定的；块与块之间有一个空行
     * （最后一条之后不加）。宽表用它比横向表格好读得多。</p>
     */
    private static List<String> vertical(List<String> headers, List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        int nameWidth = 0;
        for (String header : headers) {
            nameWidth = Math.max(nameWidth, header == null ? 0 : SqlTableRenderer.displayWidth(header));
        }
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            if (rowIndex > 0) {
                lines.add("");
            }
            lines.add("-[ RECORD " + (rowIndex + 1) + " ]");
            List<String> row = rows.get(rowIndex);
            for (int column = 0; column < headers.size(); column++) {
                String name = headers.get(column) == null ? "" : headers.get(column);
                String value = column < row.size() ? row.get(column) : "";
                // 字段名按显示宽度补齐（CJK 列名不会把竖线挤歪）；值在 toCells 里已按需截断
                int padding = nameWidth - SqlTableRenderer.displayWidth(name);
                lines.add(name + " ".repeat(Math.max(padding, 0)) + " | " + value);
            }
        }
        return lines;
    }

    /** 按显示宽度截断（委托 {@link CellRenderer#truncate}，保持一份实现）。 */
    static String truncate(String text, int maxWidth) {
        return CellRenderer.truncate(text, maxWidth);
    }

    /**
     * CSV（RFC4180 子集）：逗号分隔；字段里出现 {@code ,} / {@code "} / CR / LF 时加双引号，
     * 内部 {@code "} 写成 {@code ""}。行尾用 LF（与 dbcli 其它输出一致，便于逐字节比对与 git diff）。
     */
    private static List<String> csv(List<String> headers, List<List<String>> rows, boolean header) {
        List<String> lines = new ArrayList<>();
        if (header) {
            List<String> headerCells = new ArrayList<>(headers.size());
            for (String name : headers) {
                headerCells.add(name == null ? "" : name);
            }
            lines.add(csvLine(headerCells));
        }
        for (List<String> row : rows) {
            lines.add(csvLine(row));
        }
        return lines;
    }

    private static String csvLine(List<String> cells) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                line.append(',');
            }
            line.append(csvCell(cells.get(index)));
        }
        return line.toString();
    }

    /** 按需加引号（含逗号/引号/CR/LF 的字段必须加）。 */
    static String csvCell(String text) {
        if (text == null) {
            return "";
        }
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!quote) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
