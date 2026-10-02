package org.slackerdb.plsql.detect;

import java.util.ArrayList;
import java.util.List;

import static org.slackerdb.plsql.detect.SqlStatementSplitter.DollarString;
import static org.slackerdb.plsql.detect.SqlStatementSplitter.Segment;

/**
 * 客户端文本分类器：回答"这串东西是普通 SQL、单个匿名块，还是多语句脚本"，
 * 并给出每条语句应执行的文本。
 *
 * <p>取代历史上的正则 {@code (.*)(DO)?(\s+)?\$\$(.*)\$\$.*}——那个正则除了只认 {@code $$}
 * 之外，还会把 {@code $$} 之前的语句<b>静默丢弃</b>。</p>
 *
 * <p>支持的包装：</p>
 * <ul>
 *   <li>{@code DO $$ ... $$} / {@code DO $tag$ ... $tag$}（PostgreSQL 风格，允许结尾分号）；</li>
 *   <li>裸 {@code $$ ... $$} / {@code $tag$ ... $tag$}；</li>
 *   <li>无包装的 {@code DECLARE ... BEGIN ... END;} 与 {@code BEGIN ... END;}</li>
 * </ul>
 *
 * <p>{@code BEGIN;}、{@code BEGIN WORK}、{@code BEGIN TRANSACTION} 是<b>事务控制语句</b>，
 * 不是块起点。</p>
 */
public final class PlSqlDetector {

    private PlSqlDetector() {
    }

    public static PlSqlScript analyze(String text) {
        if (text == null) {
            return PlSqlScript.empty();
        }
        int start = SqlStatementSplitter.skipNoise(text, 0);
        if (start >= text.length()) {
            return PlSqlScript.empty();
        }

        String lexicalError = SqlStatementSplitter.validate(text);
        if (lexicalError != null) {
            return PlSqlScript.error(lexicalError);
        }

        // 单条包装块：DO $$...$$[;] / $$...$$[;]，且之后没有别的内容
        DollarString leading = leadingWrapper(text, start);
        if (leading != null) {
            int tail = consumeOptionalSemicolon(text, leading.end);
            if (tail >= text.length()) {
                String body = text.substring(leading.contentStart, leading.contentEnd);
                return new PlSqlScript(List.of(new PlSqlStatement(PlSqlStatement.Kind.BLOCK, body, body,
                        start, text.length(), SqlStatementSplitter.lineOf(text, start))), null);
            }
            // 后面还有语句 → 按脚本处理（包装段由 merge 识别）
        }

        // 其余情况统一走"顶层分号切分 + 块合并"
        SqlStatementSplitter.Result result = SqlStatementSplitter.split(text);
        return new PlSqlScript(mergeBlocks(text, result.segments), null);
    }

    /** 便捷判断：该文本是否为需要交给 PL/SQL 引擎的单条块。 */
    public static boolean isBlock(String text) {
        return analyze(text).isSingleBlock();
    }

    /**
     * {@code BEGIN} 是否属于事务控制语句而不是匿名块。
     *
     * <p>事务控制的形态：{@code BEGIN}、{@code BEGIN;}、{@code BEGIN WORK}、
     * {@code BEGIN TRANSACTION}、以及带修饰词的形态
     * {@code BEGIN READ ONLY} / {@code BEGIN READ WRITE} /
     * {@code BEGIN ISOLATION LEVEL ...} / {@code BEGIN NOT DEFERRABLE}。</p>
     *
     * <p>为什么必须识别修饰词：pgjdbc（含 SlackerDB 自带驱动）在
     * {@code readOnly=true} 时就会发送 {@code BEGIN READ ONLY}。若误判成匿名块，
     * 该语句会被送进 PL/SQL 引擎并失败，<b>只读连接就悄悄变成了可写连接</b>
     * （回归用例：{@code PlSqlDetectorTest#beginReadOnlyIsTransaction}、
     * {@code Sanity02Test#testReadOnlyConnection}）。</p>
     */
    static boolean isTransactionBegin(String text, int beginIndex) {
        int next = SqlStatementSplitter.skipNoise(text, beginIndex + "BEGIN".length());
        if (next >= text.length()) {
            return true; // 光秃秃的 BEGIN：PG 里就是开启事务
        }
        char ch = text.charAt(next);
        if (ch == ';') {
            return true;
        }
        String token = SqlStatementSplitter.wordAt(text, next);
        if (token == null) {
            // BEGIN 后面不是词（例如消息结尾的 NUL、或直接就是结尾）：按事务控制处理。
            // 匿名块一定由语句组成，BEGIN 后面必须是词（DECLARE/IF/LOOP/END/或某条语句）。
            return true;
        }
        return TRANSACTION_BEGIN_KEYWORDS.contains(token.toUpperCase());
    }

    /** BEGIN 之后可出现的"事务控制修饰词"；不在此列的词意味着匿名块。 */
    private static final java.util.Set<String> TRANSACTION_BEGIN_KEYWORDS = java.util.Set.of(
            "TRANSACTION", "WORK", "READ", "WRITE", "ISOLATION", "DEFERRABLE", "NOT", "LEVEL");

    /** {@code index} 处是否为 {@code DO $tag$} 或 {@code $tag$}；是则返回美元引用信息。 */
    private static DollarString leadingWrapper(String text, int index) {
        String word = SqlStatementSplitter.wordAt(text, index);
        int dollarIndex = index;
        if (word != null && word.equalsIgnoreCase("DO")) {
            dollarIndex = SqlStatementSplitter.skipNoise(text, index + 2);
        }
        if (dollarIndex >= text.length() || text.charAt(dollarIndex) != '$') {
            return null;
        }
        return SqlStatementSplitter.readDollarString(text, dollarIndex);
    }

    private static int consumeOptionalSemicolon(String text, int index) {
        int next = SqlStatementSplitter.skipNoise(text, index);
        if (next < text.length() && text.charAt(next) == ';') {
            return SqlStatementSplitter.skipNoise(text, next + 1);
        }
        return next;
    }

    /**
     * 把切分结果归一化为语句列表：
     * <ul>
     *   <li>{@code $tag$...$tag$} / {@code DO $tag$...$tag$} 片段 → 一条 BLOCK（body 为包装内部）；</li>
     *   <li>以 {@code DECLARE}/{@code BEGIN}（非事务）开头的片段 → 按 BEGIN/END 深度合并成一条 BLOCK；</li>
     *   <li>其余 → 一条 SQL。</li>
     * </ul>
     *
     * <p>深度只统计"代码位置上的 BEGIN 词"与"光秃秃的独立 END"：{@code END IF} / {@code END LOOP} /
     * {@code END CASE} 不计数；SQL 里的 {@code CASE ... END} 属于同一条 SQL 片段，不会被拆开。</p>
     */
    private static List<PlSqlStatement> mergeBlocks(String text, List<Segment> segments) {
        List<PlSqlStatement> statements = new ArrayList<>();
        int i = 0;
        while (i < segments.size()) {
            Segment segment = segments.get(i);

            DollarString wrapper = leadingWrapper(text, segment.start);
            if (wrapper != null && wrapper.end <= segment.end + 1) {
                String body = text.substring(wrapper.contentStart, Math.min(wrapper.contentEnd, segment.end));
                statements.add(new PlSqlStatement(PlSqlStatement.Kind.BLOCK, body, body,
                        segment.start, segment.end, SqlStatementSplitter.lineOf(text, segment.start)));
                i++;
                continue;
            }

            String keyword = segment.firstKeyword(text);
            boolean blockStart = keyword.equals("DECLARE")
                    || (keyword.equals("BEGIN") && !isTransactionBegin(text, segment.start));
            if (!blockStart) {
                statements.add(new PlSqlStatement(PlSqlStatement.Kind.SQL, segment.text, null,
                        segment.start, segment.end, SqlStatementSplitter.lineOf(text, segment.start)));
                i++;
                continue;
            }

            int depth = 0;
            boolean seenBegin = false;
            int j = i;
            boolean closed = false;
            while (j < segments.size()) {
                int openers = countBeginWords(text, segments.get(j));
                int closers = countStandaloneEnd(text, segments.get(j));
                depth += openers - closers;
                seenBegin = seenBegin || openers > 0;
                j++;
                if (seenBegin && depth <= 0) {
                    closed = true;
                    break;
                }
            }
            int blockEnd = closed ? segments.get(j - 1).end : text.length();
            // 片段不含结尾分号，这里补回块末尾的分号
            String blockSql = text.substring(segment.start, blockEnd) + (closed ? ";" : "");
            statements.add(new PlSqlStatement(PlSqlStatement.Kind.BLOCK, blockSql, blockSql,
                    segment.start, blockEnd, SqlStatementSplitter.lineOf(text, segment.start)));
            i = j;
        }
        return statements;
    }

    private static int countBeginWords(String text, Segment segment) {
        int count = 0;
        int i = segment.start;
        while (i < segment.end) {
            int skipped = SqlStatementSplitter.skipNonCode(text, i);
            if (skipped > i) {
                i = skipped;
                continue;
            }
            String word = SqlStatementSplitter.wordAt(text, i);
            if (word == null) {
                i++;
                continue;
            }
            if (word.equalsIgnoreCase("BEGIN")) {
                count++;
            }
            i += word.length();
        }
        return count;
    }

    private static int countStandaloneEnd(String text, Segment segment) {
        int i = SqlStatementSplitter.skipNoise(text, segment.start);
        String word = SqlStatementSplitter.wordAt(text, i);
        if (word == null || !word.equalsIgnoreCase("END")) {
            return 0;
        }
        int next = SqlStatementSplitter.skipNoise(text, i + word.length());
        return next >= segment.end ? 1 : 0;
    }
}
