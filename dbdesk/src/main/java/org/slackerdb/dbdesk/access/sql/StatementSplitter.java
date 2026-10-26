package org.slackerdb.dbdesk.access.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * 累积缓冲的**顶层 {@code ;} 切分**：把"一行多条语句"拆成可逐条执行的片段。
 *
 * <p><b>来源</b>：复制自 {@code dbcli/engine/StatementSplitter}（决策 R3：只复制小规则，不依赖模块）。
 * 与 {@code dbcli} 的差别只有包名与 javadoc 里的引用；**规则必须保持同源**，
 * 行为差异由 {@code StatementSplitterTest} 的用例钉住（两边的用例表要一起改）。</p>
 *
 * <p>什么不算顶层 {@code ;}：单/双引号字符串内部（含反斜杠转义）、{@code --} 行注释、
 * 行首 {@code #} 注释、{@code {% … %}} 断言外观内部。</p>
 *
 * <p>本类只给"切分点"，不判断语法是否完整 —— 块语句（{@code begin … end;}）由服务端判断。</p>
 */
public final class StatementSplitter {

    private StatementSplitter() {
    }

    /**
     * 顶层 {@code ;} 的位置（**独占式**下标：{@code end} 指向分号之后一位，可直接做 {@code substring} 的右边界）。
     *
     * <p>按出现顺序返回；对第 i 个位置取前缀 {@code text.substring(0, end)} 即为"第 i 条候选语句"。</p>
     */
    public static List<Integer> topLevelSemicolons(String text) {
        return scan(text).semicolons();
    }

    /**
     * 这段文本是否"只有空白与注释"。
     *
     * <p>用途：消费掉一条语句后，缓冲里可能只剩 {@code " -- 注释"} 这类残渣；若不当成空，
     * 界面会一直显示"还有内容"，脚本末尾还会把它当成"没写完的语句"。</p>
     */
    public static boolean isBlankOrCommentOnly(String text) {
        return !scan(text).hasCode();
    }

    /**
     * 按顶层分号把文本切成语句（去掉纯注释/空白的片段）。
     *
     * <p>这是控制台要的形态：{@code select 1; select 2;} → {@code ["select 1;", "select 2;"]}。
     * 末尾没有分号的残段也会作为一条返回（与一次性执行整段脚本的直觉一致）。</p>
     */
    public static List<String> split(String text) {
        String body = text == null ? "" : text;
        List<String> statements = new ArrayList<>();
        int start = 0;
        for (int end : topLevelSemicolons(body)) {
            String candidate = body.substring(start, end).strip();
            if (!isBlankOrCommentOnly(candidate)) {
                statements.add(candidate);
            }
            start = end;
        }
        String tail = start < body.length() ? body.substring(start).strip() : "";
        if (!tail.isEmpty() && !isBlankOrCommentOnly(tail)) {
            statements.add(tail);
        }
        return List.copyOf(statements);
    }

    /** 一次扫描的结果：切分点 + "是否出现过有效代码"。 */
    private record Scan(List<Integer> semicolons, boolean hasCode) {
    }

    private static Scan scan(String text) {
        String body = text == null ? "" : text;
        List<Integer> semicolons = new ArrayList<>();
        boolean[] hasCode = {false};
        LexicalScanner.Events events = new LexicalScanner.Events() {
            @Override
            public void topLevelSemicolon(int position) {
                semicolons.add(position);
                // 一个裸 ';' 也算"出现过有效代码"
                hasCode[0] = true;
            }

            @Override
            public boolean codePoint(int position, char character) {
                hasCode[0] = true;
                return true;
            }
        };
        LexicalScanner scanner = new LexicalScanner();
        int lineStart = 0;
        for (int index = 0; index <= body.length(); index++) {
            if (index < body.length() && body.charAt(index) != '\n') {
                continue;
            }
            scanner.scanLine(body.substring(lineStart, index), lineStart, events);
            lineStart = index + 1;
        }
        return new Scan(semicolons, hasCode[0]);
    }
}
