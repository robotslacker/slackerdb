package org.slackerdb.plsql.detect;

import java.util.ArrayList;
import java.util.List;

/**
 * 顶层分号切分器：把一个查询字符串切成若干语句，切分时"看不见"的内容不会误触发分号。
 *
 * <p>被跳过的内容：</p>
 * <ul>
 *   <li>单引号字符串（{@code ''} 转义）与双引号标识符（{@code ""} 转义）；</li>
 *   <li>行注释 {@code -- ...} 与块注释 {@code /* ... *}{@code /}（不嵌套，与 PostgreSQL 一致）；</li>
 *   <li>美元引用 {@code $tag$ ... $tag$}，tag 为空即 {@code $$}；内部不做任何转义（PG 语义）；</li>
 *   <li>括号内部（{@code ( ... )} 深度 &gt; 0）。</li>
 * </ul>
 *
 * <p>注意：本类<b>不理解块结构</b>（{@code BEGIN ... END}）。未加 {@code $$} 包装的块，
 * 其内部分号会被当作语句边界 —— 块合并由 {@link PlSqlDetector} 负责。</p>
 */
public final class SqlStatementSplitter {

    private SqlStatementSplitter() {
    }

    /** 一条原始语句片段：{@code [start, end)} 不含结尾分号。 */
    public static final class Segment {
        public final int start;
        public final int end;
        public final String text;

        Segment(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }

        /** 片段首个（非注释）关键字，大写；无关键字时为空串。 */
        public String firstKeyword(String source) {
            int i = skipNoise(source, start);
            String word = wordAt(source, i);
            return word == null ? "" : word.toUpperCase();
        }

        @Override
        public String toString() {
            return "Segment[" + start + "," + end + ")=" + text.trim();
        }
    }

    /** 切分结果；{@link #error} 非空表示文本本身有未闭合的词法单元。 */
    public static final class Result {
        public final List<Segment> segments;
        public final String error;

        Result(List<Segment> segments, String error) {
            this.segments = segments;
            this.error = error;
        }

        public boolean hasError() {
            return error != null;
        }
    }

    /** 美元引用字符串的边界。 */
    public static final class DollarString {
        /** 起始 tag（含两侧 $），例如 {@code $$} / {@code $body$}。 */
        public final String tag;
        public final int contentStart;
        public final int contentEnd;
        /** 结束 tag 之后的下标。 */
        public final int end;

        DollarString(String tag, int contentStart, int contentEnd, int end) {
            this.tag = tag;
            this.contentStart = contentStart;
            this.contentEnd = contentEnd;
            this.end = end;
        }
    }

    public static Result split(String text) {
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return new Result(segments, null);
        }

        int length = text.length();
        int index = 0;
        int segmentStart = 0;
        int parenDepth = 0;

        while (index < length) {
            char ch = text.charAt(index);

            int skipped = skipNonCode(text, index);
            if (skipped > index) {
                // 未闭合的字符串/注释/美元引用由 skipNonCode 抛出或返回 length
                index = skipped;
                continue;
            }

            if (ch == '(') {
                parenDepth++;
                index++;
                continue;
            }
            if (ch == ')') {
                if (parenDepth > 0) {
                    parenDepth--;
                }
                index++;
                continue;
            }
            if (ch == ';' && parenDepth == 0) {
                addSegment(segments, text, segmentStart, index);
                index++;
                segmentStart = index;
                continue;
            }
            index++;
        }

        addSegment(segments, text, segmentStart, length);
        return new Result(segments, null);
    }

    private static void addSegment(List<Segment> segments, String text, int start, int end) {
        if (start >= end) {
            return;
        }
        int effectiveStart = skipNoise(text, start);
        if (effectiveStart >= end) {
            // 只有空白/注释
            return;
        }
        int effectiveEnd = end;
        while (effectiveEnd > effectiveStart && Character.isWhitespace(text.charAt(effectiveEnd - 1))) {
            effectiveEnd--;
        }
        if (effectiveEnd <= effectiveStart) {
            return;
        }
        segments.add(new Segment(effectiveStart, effectiveEnd, text.substring(effectiveStart, effectiveEnd)));
    }

    /**
     * 若 {@code index} 处是注释/字符串/美元引用，返回其结束后的下标；否则返回 {@code index}。
     *
     * <p>未闭合时返回 {@code text.length()}（由 {@link #validate} 报错，切分本身不中断）。</p>
     */
    public static int skipNonCode(String text, int index) {
        int length = text.length();
        if (index >= length) {
            return index;
        }
        char ch = text.charAt(index);

        if (ch == '-' && index + 1 < length && text.charAt(index + 1) == '-') {
            int i = index + 2;
            while (i < length && text.charAt(i) != '\n') {
                i++;
            }
            return i;
        }
        if (ch == '/' && index + 1 < length && text.charAt(index + 1) == '*') {
            int close = text.indexOf("*/", index + 2);
            return close < 0 ? length : close + 2;
        }
        if (ch == '\'' || ch == '"') {
            int i = index + 1;
            while (i < length) {
                char c = text.charAt(i);
                if (c == ch) {
                    if (i + 1 < length && text.charAt(i + 1) == ch) {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                i++;
            }
            return length;
        }
        if (ch == '$') {
            DollarString dollar = readDollarString(text, index);
            if (dollar != null) {
                return dollar.end;
            }
        }
        return index;
    }

    /**
     * 读取 {@code $tag$ ... $tag$}。{@code index} 处不是合法 tag 起点时返回 null；
     * 起点合法但未闭合时返回 {@code end == text.length()} 的对象（由调用方判定未闭合）。
     */
    public static DollarString readDollarString(String text, int index) {
        int length = text.length();
        if (index >= length || text.charAt(index) != '$') {
            return null;
        }
        int i = index + 1;
        while (i < length && isTagChar(text.charAt(i))) {
            i++;
        }
        if (i >= length || text.charAt(i) != '$') {
            return null;
        }
        String tag = text.substring(index, i + 1);
        int contentStart = i + 1;
        int close = text.indexOf(tag, contentStart);
        if (close < 0) {
            return new DollarString(tag, contentStart, length, length);
        }
        return new DollarString(tag, contentStart, close, close + tag.length());
    }

    private static boolean isTagChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 跳过空白与注释（返回首个"有内容"的下标）。 */
    public static int skipNoise(String text, int index) {
        int i = index;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                i = skipNonCode(text, i);
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i = skipNonCode(text, i);
                continue;
            }
            break;
        }
        return i;
    }

    /** 读取 {@code index} 处的标识符/关键字（大小写不敏感）；非标识符返回 null。 */
    public static String wordAt(String text, int index) {
        int length = text.length();
        if (index >= length) {
            return null;
        }
        char first = text.charAt(index);
        if (!Character.isLetter(first) && first != '_') {
            return null;
        }
        int i = index + 1;
        while (i < length) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                break;
            }
            i++;
        }
        return text.substring(index, i);
    }

    /** 1 起的行号（用于错误消息）。 */
    public static int lineOf(String text, int index) {
        int line = 1;
        int limit = Math.min(index, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 词法自检：报告未闭合的字符串/注释/美元引用（{@code null} 表示通过）。 */
    public static String validate(String text) {
        if (text == null) {
            return null;
        }
        int length = text.length();
        int i = 0;
        while (i < length) {
            char ch = text.charAt(i);
            if (ch == '-' && i + 1 < length && text.charAt(i + 1) == '-') {
                i = skipNonCode(text, i);
                continue;
            }
            if (ch == '/' && i + 1 < length && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                if (close < 0) {
                    return "unterminated block comment at line " + lineOf(text, i);
                }
                i = close + 2;
                continue;
            }
            if (ch == '\'' || ch == '"') {
                int next = skipNonCode(text, i);
                if (next >= length && !isClosedQuote(text, i, ch)) {
                    return "unterminated " + (ch == '\'' ? "string" : "quoted identifier")
                            + " at line " + lineOf(text, i);
                }
                i = next;
                continue;
            }
            if (ch == '$') {
                DollarString dollar = readDollarString(text, i);
                if (dollar != null) {
                    if (dollar.end >= length && !text.regionMatches(dollar.contentEnd, dollar.tag, 0, dollar.tag.length())) {
                        return "unterminated dollar-quoted string " + dollar.tag + " at line " + lineOf(text, i);
                    }
                    i = dollar.end;
                    continue;
                }
            }
            i++;
        }
        return null;
    }

    private static boolean isClosedQuote(String text, int index, char quote) {
        int i = index + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return true;
            }
            i++;
        }
        return false;
    }
}
