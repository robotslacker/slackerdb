package org.slackerdb.dbcli.engine;

import java.util.Locale;

/**
 * 语句边界的**词法状态机**：只回答"缓冲现在是不是停在一个可能收尾的位置"，不做任何语法分析。
 *
 * <h2>为什么需要它</h2>
 * <p>一条语句只可能在三种位置收尾：</p>
 * <ol>
 *   <li><b>顶层 {@code ;}</b>（绝大多数 SQL 与 {@code _} 命令）；</li>
 *   <li><b>行首 {@code /}</b>（{@code declare … begin … end;}、{@code create procedure …} 这类块）；</li>
 *   <li><b>{@code echo off}</b>（{@code _echo} 文本块）。</li>
 * </ol>
 * <p>如果缓冲不停在这三种位置之一，<b>任何解析都不可能给出"写完了"</b>——于是"每读一行就全量解析一次"
 * 可以换成"先看边界，不是边界就直接返回"。这是把大脚本从 O(N²) 降到 O(N) 的关键：
 * 一个 4000 行的存储过程只在最后那个 {@code /} 上解析一次。</p>
 *
 * <h2>它不猜语法，只认字面</h2>
 * <p>扫描状态与 {@link StatementSplitter} 完全一致（单/双引号与反斜杠转义、{@code --} 行注释、
 * 行首 {@code #} 注释、{@code {% %}} 断言外观）；判错的代价只是"多解析一次"或"晚一点解析"，
 * <b>不会改变哪一段被执行</b>——真正决定"能不能执行"的仍然是 {@code SqlAnalyzer}。</p>
 *
 * <h2>增量</h2>
 * <p>按行喂入（{@link #feedLine}），状态跨行保留，因此总扫描量是 O(总字符数)，而不是每行重扫整个缓冲。</p>
 */
public final class StatementBoundary {

    /** 缓冲当前停在哪一种"可能收尾"的位置。 */
    public enum Kind {
        /** 不在收尾位置：不可能完整，不必解析。 */
        NONE,
        /** 顶层 {@code ;} 之后（只剩空白/注释）。 */
        SEMICOLON,
        /** 最后一个非空行是 {@code /}。 */
        SLASH,
        /** 最后一个非空行是 {@code echo off}。 */
        ECHO_OFF,
        /**
         * 待处理语句是**单行 {@code _} 命令**（{@code _help}、{@code _sleep 0}…）。
         *
         * <p>{@code _} 命令在词法上按行收尾（各命令模式吃到行尾/EOF），因此单行命令**不需要 {@code ;}**。
         * 这也是"语句只在 {@code ;}//{@code echo off} 收尾"之外必须补的第四种边界。</p>
         */
        COMMAND
    }

    /** 词法状态机（与 {@link StatementSplitter} 共用同一份规则，见 {@link LexicalScanner}）。 */
    private final LexicalScanner scanner = new LexicalScanner();

    /** 正在扫描的那一行（事件回调里用来判断 {@code _ECHO} 与非空行信息）。 */
    private String currentLine = "";

    /** 见过顶层 {@code ;}。 */
    private boolean sawSemicolon;

    /** 最后一个顶层 {@code ;} 之后是否出现过有效代码（注释/空白不算）。 */
    private boolean codeAfterSemicolon;

    /** 最后一个顶层 {@code ;} 之后已经喂入的"语句行"数（注释/空行不算）。 */
    private int pendingLineCount;

    /** 待处理语句的第一行是否以 {@code _} 开头。 */
    private boolean pendingStartsCommand;

    /**
     * 待处理语句是不是 {@code _ECHO} 文本块。
     *
     * <p>块体是**数据**：里面的撇号/引号不会开启字符串，里面的 {@code ;} 也不是终结符。
     * 词法层把 {@code _ECHO} 到 {@code echo off} 整段收成一个 token，因此这里必须同样认定
     * "只有 {@code echo off} 那一行才收尾"；否则 {@code _echo} 正文里一个 {@code it's}
     * 就会把状态机带进未闭合字符串，整个块再也收不了尾（表现为 {@code Unknown command [_echo]}）。</p>
     */
    private boolean pendingEchoBlock;

    /** 待处理语句最后一个非空行（去掉首尾空白），用于认 {@code /} 与 {@code echo off}。 */
    private String pendingLastNonBlankLine = "";

    /** 清空状态（缓冲被消费掉/丢弃时调用）。 */
    public void reset() {
        scanner.reset();
        sawSemicolon = false;
        codeAfterSemicolon = false;
        pendingLineCount = 0;
        pendingStartsCommand = false;
        pendingEchoBlock = false;
        pendingLastNonBlankLine = "";
    }

    /** 喂入一段多行文本（按 {@code CRLF}/{@code LF} 切行）。 */
    public void feedText(String text) {
        for (String line : Utf8Text.splitLines(text == null ? "" : text)) {
            feedLine(line);
        }
    }

    /**
     * 喂入一行（不含行尾）。
     *
     * @param line 这一行的原文；行内的引号/注释状态会带到下一行
     */
    public void feedLine(String line) {
        String text = line == null ? "" : line;
        String stripped = text.strip();
        boolean blankOrComment = stripped.isEmpty() || stripped.startsWith("--") || stripped.startsWith("#");
        if (!blankOrComment) {
            pendingLastNonBlankLine = stripped;
            if (pendingLineCount == 0) {
                // 待处理语句从这一行开始（若同一行后面还有 `;`，scan 会把状态重置到下一句）
                pendingLineCount = 1;
                pendingStartsCommand = stripped.startsWith("_");
                pendingEchoBlock = startsEchoCommand(stripped, 0);
            } else {
                pendingLineCount = pendingLineCount + 1;
            }
        }
        if (pendingEchoBlock) {
            // _ECHO 的块体是数据行：不做引号/注释扫描（收尾只认下一行 `echo off`）
            return;
        }
        currentLine = text;
        scanner.scanLine(text, 0, events);
    }

    /** 扫描事件：把行内字符翻译成"待处理语句"的状态。 */
    private final LexicalScanner.Events events = new LexicalScanner.Events() {

        @Override
        public void topLevelSemicolon(int position) {
            sawSemicolon = true;
            codeAfterSemicolon = false;
            pendingLineCount = 0;
            pendingStartsCommand = false;
            pendingEchoBlock = false;
            pendingLastNonBlankLine = "";
        }

        @Override
        public boolean codePoint(int position, char character) {
            codeAfterSemicolon = true;
            if (pendingLineCount == 0) {
                pendingLineCount = 1;
                pendingStartsCommand = character == '_';
                pendingEchoBlock = startsEchoCommand(currentLine, position);
                if (pendingEchoBlock) {
                    // 本行剩余部分是 _ECHO 的标题/块体：不再参与词法扫描（块体里的撇号是数据）
                    return false;
                }
            }
            return true;
        }
    };

    /**
     * 从 {@code from} 起（跳过空白）是不是一条 {@code _ECHO} 命令。
     *
     * <p>只认"整词"：{@code _echo} 后面必须是行尾或空白，因此 {@code _echofoo} 不算。</p>
     */
    private static boolean startsEchoCommand(String line, int from) {
        int index = from;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        int length = "_echo".length();
        if (index + length > line.length() || !line.regionMatches(true, index, "_echo", 0, length)) {
            return false;
        }
        int after = index + length;
        return after == line.length() || Character.isWhitespace(line.charAt(after));
    }

    /**
     * 当前边界。
     *
     * <p>顺序有意义：</p>
     * <ol>
     *   <li>未闭合的引号/断言外观说明"还在字符串里"——此时行首 {@code /}、{@code echo off} 都只是内容；</li>
     *   <li>最后一个顶层 {@code ;} 之后没有代码 → 停在分号上；</li>
     *   <li>随后按"待处理语句"的形态判 {@code /}、{@code echo off}、单行 {@code _} 命令。</li>
     * </ol>
     */
    public Kind kind() {
        if (pendingEchoBlock) {
            // _ECHO 块只看结尾那一行：块体里的引号/分号都是数据（与词法的 EchoBlock 一致）
            return pendingLastNonBlankLine.equalsIgnoreCase("echo off") ? Kind.ECHO_OFF : Kind.NONE;
        }
        if (scanner.insideQuoteOrBraces()) {
            return Kind.NONE;
        }
        if (sawSemicolon && !codeAfterSemicolon) {
            return Kind.SEMICOLON;
        }
        if ("/".equals(pendingLastNonBlankLine)) {
            return Kind.SLASH;
        }
        if (pendingLastNonBlankLine.equalsIgnoreCase("echo off")) {
            return Kind.ECHO_OFF;
        }
        // 单行 _ 命令按行收尾（多行就不是命令了：命令模式吃到行尾）
        if (pendingLineCount == 1 && pendingStartsCommand) {
            return Kind.COMMAND;
        }
        return Kind.NONE;
    }

    /**
     * 这段文本是否**看起来**是一条"只能靠行首 {@code /} 收尾"的块语句。
     *
     * <p>与词法的判据对齐（{@code DECLARE}/{@code BEGIN} 进入 PL/SQL 收集模式、
     * {@code CREATE|REPLACE|ALTER … PROCEDURE|FUNCTION|CLASS|TRIGGER|PACKAGE} 走
     * {@code SQL_CREATE_PROCEDURE}）：</p>
     * <ul>
     *   <li>{@code declare …} → 块；</li>
     *   <li>{@code begin} 后面不是 {@code ;} → 块（{@code begin;} 是事务形式，不是块）；</li>
     *   <li>{@code create|replace|alter [or replace] &lt;routine&gt;} → 块；</li>
     *   <li>文本里出现 {@code $$} → **不是**块：那是 PostgreSQL 的美元引号函数体，按普通 {@code ;} 收尾
     *       （判据与词法的 {@code pgDollarBodyAhead()} 对齐）；</li>
     *   <li>其它 → 不是块。</li>
     * </ul>
     *
     * <p>这个判断只用于"把'等 {@code /}'这个结论记住"，从而在块体内部完全跳过解析；
     * 即使判错，最坏结果也只是多解析几次（不会把语句切错）。</p>
     */
    public static boolean startsBlock(String text) {
        String body = text == null ? "" : text;
        if (body.contains("$$")) {
            return false;
        }
        String head = skipLeadingTrivia(body);
        if (head.isEmpty()) {
            return false;
        }
        String lower = head.toLowerCase(Locale.ROOT);
        if (lower.startsWith("declare")) {
            return true;
        }
        if (lower.startsWith("begin")) {
            int index = "begin".length();
            while (index < lower.length() && Character.isWhitespace(lower.charAt(index))) {
                index++;
            }
            return index >= lower.length() || lower.charAt(index) != ';';
        }
        int index = 0;
        while (index < lower.length()) {
            while (index < lower.length() && !Character.isLetter(lower.charAt(index))) {
                index++;
            }
            int start = index;
            while (index < lower.length() && Character.isLetter(lower.charAt(index))) {
                index++;
            }
            if (start == index) {
                break;
            }
            String word = lower.substring(start, index);
            switch (word) {
                case "create", "replace", "alter", "or", "editionable", "noneditionable" -> {
                    // 继续看下一个词
                }
                case "procedure", "function", "class", "trigger", "package" -> {
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }

    /** 跳过前导空白与 {@code --}/{#} 注释，返回"第一个可能是关键字的位置"。 */
    private static String skipLeadingTrivia(String text) {
        int index = 0;
        int length = text.length();
        while (index < length) {
            char character = text.charAt(index);
            if (Character.isWhitespace(character)) {
                index++;
                continue;
            }
            if (character == '-' && index + 1 < length && text.charAt(index + 1) == '-') {
                index = skipLine(text, index);
                continue;
            }
            if (character == '#') {
                index = skipLine(text, index);
                continue;
            }
            break;
        }
        return text.substring(index);
    }

    private static int skipLine(String text, int from) {
        int index = from;
        while (index < text.length() && text.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

}
