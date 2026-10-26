package org.slackerdb.dbdesk.access.sql;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 SQL 错误文案里解析出**行列位置**（DuckDB/SlackerDB 的行列信息只在文案里）。
 *
 * <p>服务端返回的形态（实测）：</p>
 * <pre>
 * ERROR: Binder Error: Referenced column "no_such_column" not found in FROM clause!
 * Candidate bindings: "c1", "c2", "c3", "c4", "c5"
 *
 * LINE 1: select no_such_column from m0_probe
 *                ^
 * </pre>
 *
 * <p>pgwire 的 {@code Position} 字段恒为 0，所以只能解析文案：取 {@code LINE n:} 那一行 + 下面的
 * 插入符 {@code ^}，算出列号（1-based）。解析不出来时返回 {@code null} 位置 —— 调用方降级为
 * "只有消息、没有行列"，**不要瞎猜**。</p>
 */
public record SqlError(int line, int column, String summary) {

    private static final Pattern LINE_PATTERN = Pattern.compile("(?m)^LINE (\\d+):\\s?(.*)$");
    private static final Pattern CARET_PATTERN = Pattern.compile("(?m)^[\\s\\t]*\\^");

    /** 解析；永远返回非 null（解析不出位置时 line/column = 0）。 */
    public static SqlError parse(String message) {
        if (message == null || message.isBlank()) {
            return new SqlError(0, 0, "");
        }
        String text = message.replace("\r\n", "\n");

        String summary = text.strip().split("\n")[0]
                .replaceFirst("^ERROR:\\s*", "")
                .strip();

        Matcher lineMatcher = LINE_PATTERN.matcher(text);
        if (!lineMatcher.find()) {
            return new SqlError(0, 0, summary);
        }
        int lineNumber = Integer.parseInt(lineMatcher.group(1));
        // "LINE n: " 的宽度（插入符那一行用等宽空格对齐，所以按同一前缀宽度换算列号）
        int prefixWidth = lineMatcher.start(2) - lineMatcher.start();

        Matcher caretMatcher = CARET_PATTERN.matcher(text);
        if (!caretMatcher.find(lineMatcher.end())) {
            return new SqlError(lineNumber, 0, summary);
        }
        // 注意用 end()-1：匹配的是"整行前导空白 + ^"，start() 落在行首空白处
        int caretIndex = caretMatcher.end() - 1;
        int caretLineStart = text.lastIndexOf('\n', caretIndex - 1) + 1;
        int column = (caretIndex - caretLineStart) - prefixWidth + 1;
        return new SqlError(lineNumber, Math.max(1, column), summary);
    }

    public boolean hasPosition() {
        return line > 0 && column > 0;
    }
}
