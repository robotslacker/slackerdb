package org.slackerdb.dbcli.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * 结果集表格渲染器：把结果集渲染成"表格块 + 状态行"。
 *
 * <h2>为什么值得单独一个类、且格式必须逐字节稳定</h2>
 * dbcli 的判定手段是"脚本输出与 golden 夹具比对"（断言之外没有别的判定），
 * 表格与状态行是**唯一**的比对对象，差一个空格就不可信。
 *
 * <h2>格式要点</h2>
 * <ul>
 *   <li>列宽按**显示宽度**算：CJK 的宽 / 全角字符算 2 列，
 *       因此字符串列左对齐时要减掉宽字符多出的部分（{@code padRight(len - wide)}）；</li>
 *   <li>数值列右对齐时**不减**宽字符（{@code padLeft(len)}）；</li>
 *   <li>哪些列算"字符串列（左对齐）"取决于 {@code columnTypes} 是否属于
 *       {@code NVARCHAR/NCHAR/VARCHAR/LONGVARCHAR/CHAR/CLOB/NCLOB/STRUCT/ARRAY/DATE}；
 *       类型名来自 {@link SqlColumnTypes}（大写）；</li>
 *   <li>表头居中的补空格公式见 {@link #centerIn} —— 多出的空格落在哪一边**不能凭直觉**写；</li>
 *   <li>{@code null} 打印 {@code <null>}；单元格里的换行会撑高该行（续行的行号列打印 8 个空格）；
 *       每个换行段结尾的 {@code \r} 会被去掉。</li>
 * </ul>
 */
public final class SqlTableRenderer {

    /** 判"字符串列（左对齐）"的类型名集合（大写），顺序无关。 */
    private static final List<String> LEFT_ALIGNED_TYPES = List.of(
            "NVARCHAR", "NCHAR", "VARCHAR", "LONGVARCHAR", "CHAR", "CLOB", "NCLOB", "STRUCT", "ARRAY", "DATE");

    private SqlTableRenderer() {
    }

    /**
     * 渲染表格（含上下边框与表头分割线）；返回的各行即输出顺序。
     *
     * @param headers     列标签
     * @param columnTypes 列类型名（可为 null：此时全部按字符串左对齐处理）
     * @param rows        行数据（元素可为 null）
     * @param startIter   起始行号；第一条数据打印的行号是 {@code startIter + 1}
     */
    public static List<String> renderTab(List<String> headers, List<String> columnTypes,
                                         List<List<Object>> rows, int startIter) {
        return renderTab(headers, columnTypes, rows, startIter, true, 40);
    }

    /**
     * 渲染表格（带格式选项）。
     *
     * @param header   {@code false} 时不打印表头行与表头分割线（只有上下边框与数据）
     * @param colWidth 单元格最大显示宽度（{@code <= 0} = 不截断；超出部分用 {@code ...} 截断，
     *                 列宽按截断后计算）
     */
    public static List<String> renderTab(List<String> headers, List<String> columnTypes,
                                         List<List<Object>> rows, int startIter,
                                         boolean header, int colWidth) {
        List<String> lines = new ArrayList<>();
        String nullCell = ResultRenderers.NULL_TEXT;

        // 0) 每个单元格先渲染成文本（类型归一 + NULL 文本 + 截断），后面只处理文本。
        //    NULL 文本不截断：它是"这里是空值"的标记，截断后反而认不出来。
        List<List<String>> cells = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<String> rendered = new ArrayList<>(row.size());
            for (int pos = 0; pos < row.size(); pos++) {
                Object value = row.get(pos);
                String text;
                if (value == null) {
                    text = nullCell;
                } else {
                    String plain = CellRenderer.render(value);
                    // 单元格里的回车先去掉（单行单元格也要去）：留着裸 CR 会写进 stdout / log / spool，
                    // 终端会把光标移回行首，逐字节比对也会与"屏幕上看到的"不一致。
                    text = truncateLines(stripCarriageReturns(plain == null ? "" : plain), colWidth);
                }
                rendered.add(text);
            }
            cells.add(rendered);
        }

        // 1) 列宽：先用表头初始化，再逐行取最大显示宽度
        List<Integer> columnLength = new ArrayList<>();
        for (String title : headers) {
            columnLength.add(displayWidth(title));
        }
        for (List<String> row : cells) {
            for (int pos = 0; pos < row.size(); pos++) {
                for (String part : row.get(pos).split("\n", -1)) {
                    int width = displayWidth(part);
                    if (width > columnLength.get(pos)) {
                        columnLength.set(pos, width);
                    }
                }
            }
        }

        // 2) 上边框：'|   ##   |' 是 10 列 → 第一段固定 8 个 '-'
        StringBuilder box = new StringBuilder("+--------+");
        for (int length : columnLength) {
            box.append("-".repeat(length + 2)).append('+');
        }
        lines.add(box.toString());

        // 3) 表头（居中）+ 表头分割线（--no-header 时两行都省）
        if (header) {
            StringBuilder headerLine = new StringBuilder("|   ##   |");
            for (int pos = 0; pos < headers.size(); pos++) {
                String title = headers.get(pos);
                int width = columnLength.get(pos) - wideChars(title);
                headerLine.append(' ').append(centerIn(title, width)).append(" |");
            }
            lines.add(headerLine.toString());
            lines.add(box.toString());
        }

        // 4) 数据行（含多行单元格展开）
        int rowNo = startIter;
        for (List<String> row : cells) {
            rowNo = rowNo + 1;
            int rowHeight = 1;
            for (String text : row) {
                rowHeight = Math.max(rowHeight, text.split("\n", -1).length);
            }
            List<List<String>> physical = new ArrayList<>();
            if (rowHeight == 1) {
                physical.add(row);
            } else {
                for (int i = 0; i < rowHeight; i++) {
                    physical.add(new ArrayList<>());
                }
                for (String text : row) {
                    List<String> parts = new ArrayList<>();
                    for (String part : text.split("\n", -1)) {
                        parts.add(part.endsWith("\r") ? part.substring(0, part.length() - 1) : part);
                    }
                    for (int i = 0; i < rowHeight; i++) {
                        physical.get(i).add(parts.size() > i ? parts.get(i) : "");
                    }
                }
            }
            boolean rowNoPrinted = false;
            for (List<String> physicalRow : physical) {
                StringBuilder line = new StringBuilder("|");
                if (!rowNoPrinted) {
                    line.append(padLeft(String.valueOf(rowNo), 7)).append(" |");
                    rowNoPrinted = true;
                } else {
                    line.append("        |");
                }
                for (int pos = 0; pos < physicalRow.size(); pos++) {
                    String printValue = physicalRow.get(pos);
                    int length = columnLength.get(pos);
                    // 类型名可能是 null（SqlColumnTypes 对 Types.NULL 明确返回 null）：
                    // List.of(...).contains(null) 会抛 NPE，所以这里先取出来再判。
                    String columnType = columnTypes == null ? null : columnTypes.get(pos);
                    if (columnTypes == null || (columnType != null && LEFT_ALIGNED_TYPES.contains(columnType))) {
                        // 字符串列：左对齐，且列宽要去掉宽字符多占的显示宽度
                        line.append(' ')
                                .append(padRight(printValue, length - wideChars(printValue)))
                                .append(" |");
                    } else {
                        // 数值列：右对齐，不扣宽字符（保持与既有 golden 夹具一致）
                        line.append(' ').append(padLeft(printValue, length)).append(" |");
                    }
                }
                lines.add(line.toString());
            }
        }

        // 5) 下边框
        lines.add(box.toString());
        return lines;
    }

    /** 状态行（测试逐字节断言）：{@code "{0} row{1} selected."} / {@code "... affected."}。 */
    public static String statusLine(boolean query, int rowCount, boolean hasWarnings,
                                    boolean feedbackOn, boolean fetchStatus) {
        if (!feedbackOn || fetchStatus) {
            return null;
        }
        // {1} 在行数为 0 或 1 时是空串（**0 也用单数**），有警告时追加 " with warnings."
        String plural = (rowCount == 0 || rowCount == 1) ? "" : "s";
        String text = rowCount + " row" + plural + (query ? " selected" : " affected");
        return text + (hasWarnings ? " with warnings." : ".");
    }

    /** 去掉每个换行段结尾的 {@code \r}（CRLF 折成 LF，末尾的裸 CR 也去掉）。 */
    private static String stripCarriageReturns(String text) {
        if (text == null || text.indexOf('\r') < 0) {
            return text;
        }
        String normalized = text.replace("\r\n", "\n");
        return normalized.endsWith("\r")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    /**
     * 显示宽度：**码点**数 + 宽字符数（CJK 的 W/F 各多占 1 列）。
     *
     * <p>用码点数而不是 {@code length()}：补充平面的宽字符（例如 CJK 扩展 B 区）在 UTF-16 里占两个
     * {@code char}，按 {@code length()} 会多算一列，整行比边框少一列。</p>
     */
    public static int displayWidth(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length()) + wideChars(text);
    }

    /** 逐行截断（保留换行结构）；{@code colWidth <= 0} 时不截断。 */
    private static String truncateLines(String text, int colWidth) {
        if (colWidth <= 0 || text.indexOf('\n') < 0) {
            return CellRenderer.truncate(text, colWidth);
        }
        StringBuilder builder = new StringBuilder(text.length());
        String[] parts = text.split("\n", -1);
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                builder.append('\n');
            }
            builder.append(CellRenderer.truncate(parts[index], colWidth));
        }
        return builder.toString();
    }

    /**
     * 宽字符计数：Unicode East Asian Width 属于 {@code W}（宽）或 {@code F}（全角）的字符。
     *
     * <p>Java 没有直接暴露 East Asian Width 的 API，这里用 {@link Character.UnicodeBlock} 近似
     * "W/F 集合"里最常用的部分（CJK 汉字、全角标点、假名、谚文等）。**已知差异**：UnicodeBlock 的粒度
     * 比 EAW 粗，个别字符（例如某些符号区）可能判得不同；若将来发现具体偏差，在此处补精确区间即可，
     * 不要改调用方逻辑。</p>
     */
    public static int wideChars(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isWideCodePoint(codePoint)) {
                count++;
            }
            i += Character.charCount(codePoint);
        }
        return count;
    }

    private static boolean isWideCodePoint(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == null) {
            return false;
        }
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B.equals(block)
                || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION.equals(block)
                || Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS.equals(block)
                || Character.UnicodeBlock.HIRAGANA.equals(block)
                || Character.UnicodeBlock.KATAKANA.equals(block)
                || Character.UnicodeBlock.HANGUL_SYLLABLES.equals(block)
                || Character.UnicodeBlock.HANGUL_JAMO.equals(block);
    }

    /** 右补空格到指定宽度（宽度不足时原样返回）。 */
    static String padRight(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }

    /** 左补空格到指定宽度（宽度不足时原样返回）。 */
    static String padLeft(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return " ".repeat(width - text.length()) + text;
    }

    /**
     * 居中补空格：公式是 {@code left = marg // 2 + (marg & width & 1)}。
     *
     * <p>**不是**常见的"左边少、右边多"：多出的那 1 个空格落在哪边由 {@code marg} 与 {@code width}
     * 的最低位共同决定，例如 {@code centerIn("ab", 5)} → {@code "  ab "}（左 2 右 1）、
     * {@code centerIn("ab", 4)} → {@code " ab "}（左 1 右 1）。表格表头依赖它，不能用直觉实现。</p>
     */
    static String centerIn(String text, int width) {
        int marg = width - text.length();
        if (marg <= 0) {
            return text;
        }
        int left = marg / 2 + (marg & width & 1);
        int right = marg - left;
        return " ".repeat(left) + text + " ".repeat(right);
    }
}
