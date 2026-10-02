package org.slackerdb.plsql.parse;

import org.slackerdb.plsql.detect.SqlStatementSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 骨架遮罩：把内嵌 SQL 段替换成等长占位标识符，让结构文法永远看不到 SQL 内部。
 *
 * <p>为什么需要它：单一词法器没有语法上下文，无法同时处理 SQL 里合法的
 * {@code CASE ... END}、字符串里的分号、{@code $$} 引用、以及任意 DuckDB 关键字。
 * 遮罩后结构文法只需描述 IF/LOOP/游标/异常等结构。</p>
 *
 * <p>位置保真：占位符按"每行"等长补齐（首行补齐到首行长度、保留换行数、
 * 末行补齐到末行长度），因此 <b>Skepeton 与原文的行号、列号完全一致</b>；
 * 字符偏移通过 {@link PreparedSource#originalOffset(int)} 映射回原文。</p>
 *
 * <p>{@code SELECT ... INTO} 会被拆成结构片段
 * （{@code __SQL__ INTO v1, v2 __SQL__}），这样 AST 能拿到 INTO 目标列表。</p>
 */
public final class PlSqlSourcePreparer {

    /** 一条被遮罩的 SQL。 */
    public static final class MaskedSql {
        public final int id;
        public final String text;
        public final int originalStart;
        public final int originalEnd;
        public final int line;

        MaskedSql(int id, String text, int originalStart, int originalEnd, int line) {
            this.id = id;
            this.text = text;
            this.originalStart = originalStart;
            this.originalEnd = originalEnd;
            this.line = line;
        }

        @Override
        public String toString() {
            return "MaskedSql#" + id + "(line " + line + "): " + text;
        }
    }

    /** 遮罩结果。 */
    public static final class PreparedSource {
        public final String skeleton;
        public final List<MaskedSql> maskedSql;
        public final String error;
        /** 每 4 个一组：{@code [skeletonStart, skeletonEnd, originalStart, originalEnd]}，按 skeletonStart 升序。 */
        private final int[] regions;

        PreparedSource(String skeleton, List<MaskedSql> maskedSql, String error, int[] regions) {
            this.skeleton = skeleton;
            this.maskedSql = List.copyOf(maskedSql);
            this.error = error;
            this.regions = regions;
        }

        public boolean hasError() {
            return error != null;
        }

        public MaskedSql maskedById(int id) {
            for (MaskedSql masked : maskedSql) {
                if (masked.id == id) {
                    return masked;
                }
            }
            return null;
        }

        /** 把 Skepeton 中的字符偏移映射回原文偏移。 */
        public int originalOffset(int skeletonOffset) {
            int delta = 0;
            for (int i = 0; i + 3 < regions.length; i += 4) {
                int skeletonStart = regions[i];
                int skeletonEnd = regions[i + 1];
                int originalStart = regions[i + 2];
                int originalEnd = regions[i + 3];
                if (skeletonOffset <= skeletonStart) {
                    break;
                }
                if (skeletonOffset >= skeletonEnd) {
                    // 整个遮罩区间都在前面：累积长度差
                    delta += (originalEnd - originalStart) - (skeletonEnd - skeletonStart);
                    continue;
                }
                // 落在遮罩区间内部：返回该区间的起点（结构 token 不会出现在这里）
                return originalStart;
            }
            return skeletonOffset + delta;
        }
    }

    private enum State {
        /** 顶层：DECLARE 或 BEGIN。 */
        DECL_OR_STMT,
        /** DECLARE 段内部（逐字保留，遇到 IS 进入查询遮罩、遇到 BEGIN 进入语句）。 */
        DECLARATION,
        /** 遮罩游标查询直到分号。 */
        MASK_QUERY,
        /** 语句边界。 */
        STATEMENT,
        /** 语句内部（逐字直到分号）。 */
        STMT_BODY,
        /** IF/ELSIF 条件（逐字直到 THEN）。 */
        IF_COND,
        /** WHILE 条件（逐字直到 LOOP）。 */
        WHILE_COND,
        /** EXCEPTION/WHEN 头部（逐字直到 THEN）。 */
        HANDLER_HEADER,
        /** FOR 头部（逐字直到 LOOP）。 */
        FOR_HEADER,
        /** 循环标签头 {@code <<name>>}（逐字保留，不能当 SQL 遮罩）。 */
        LABEL_DECL
    }

    /** 语句起始处被视为"结构"的关键字（其余按内嵌 SQL 遮罩）。 */
    private static final java.util.Set<String> STATEMENT_KEYWORDS = java.util.Set.of(
            "DECLARE", "BEGIN", "IF", "ELSIF", "ELSEIF", "ELSE", "THEN", "END", "ENDIF",
            "LOOP", "WHILE", "FOR",
            "EXIT", "BREAK", "CONTINUE", "FETCH", "OPEN", "CLOSE", "RAISE", "RETURN",
            "COMMIT", "ROLLBACK", "NULL", "PASS", "LET", "EXCEPTION", "WHEN");

    /** 逐字保留直到分号的语句关键字（其内部由文法负责）。 */
    private static final java.util.Set<String> VERBATIM_STATEMENTS = java.util.Set.of(
            "EXIT", "BREAK", "CONTINUE", "FETCH", "OPEN", "CLOSE", "RAISE", "RETURN",
            "COMMIT", "ROLLBACK", "NULL", "PASS", "LET", "END", "THEN", "ELSE");

    private PlSqlSourcePreparer() {
    }

    public static PreparedSource prepare(String script) {
        if (script == null || script.isEmpty()) {
            return new PreparedSource("", List.of(), null, new int[0]);
        }
        String lexicalError = SqlStatementSplitter.validate(script);
        if (lexicalError != null) {
            return new PreparedSource(script, List.of(), lexicalError, new int[0]);
        }

        StringBuilder out = new StringBuilder(script.length());
        List<MaskedSql> masked = new ArrayList<>();
        List<int[]> regions = new ArrayList<>();
        int[] nextId = {0};
        int pos = 0;
        int length = script.length();
        State state = State.DECL_OR_STMT;

        while (pos < length) {
            char ch = script.charAt(pos);

            // 空白与注释一律逐字保留（保位置；词法器会把它们放到 HIDDEN 通道）
            int skipped = SqlStatementSplitter.skipNonCode(script, pos);
            if (skipped > pos) {
                out.append(script, pos, skipped);
                pos = skipped;
                continue;
            }

            switch (state) {
                case DECL_OR_STMT, STATEMENT -> {
                    if (Character.isWhitespace(ch)) {
                        out.append(ch);
                        pos++;
                        break;
                    }
                    if (ch == '<' && pos + 1 < length && script.charAt(pos + 1) == '<') {
                        // 循环标签 <<name>>：逐字保留（否则标签名会被当 SQL 遮罩掉）
                        out.append("<<");
                        pos += 2;
                        state = State.LABEL_DECL;
                        break;
                    }
                    String word = SqlStatementSplitter.wordAt(script, pos);
                    if (word == null) {
                        // 不是词（例如分号、左括号）：逐字保留
                        out.append(ch);
                        pos++;
                        break;
                    }
                    String upper = word.toUpperCase();
                    if (state == State.DECL_OR_STMT && upper.equals("DECLARE")) {
                        out.append(word);
                        pos += word.length();
                        state = State.DECLARATION;
                        break;
                    }
                    if (upper.equals("EXECUTE") && isExecuteImmediate(script, pos + word.length())) {
                        // 动态 SQL：整条语句逐字保留（其 SQL 文本是字符串表达式，不参与内嵌 SQL 遮罩）。
                        // 只有 EXECUTE 后紧跟 IMMEDIATE 才算；否则仍按内嵌 SQL 交给后端。
                        out.append(word);
                        pos += word.length();
                        state = State.STMT_BODY;
                        break;
                    }
                    if (!STATEMENT_KEYWORDS.contains(upper)) {
                        // 可能是赋值（name := ...）或内嵌 SQL
                        if (isAssignmentStart(script, pos + word.length())) {
                            out.append(word);
                            pos += word.length();
                            state = State.STMT_BODY;
                            break;
                        }
                        pos = maskStatement(script, pos, out, masked, regions, nextId);
                        state = State.STATEMENT;
                        break;
                    }
                    out.append(word);
                    pos += word.length();
                    state = switch (upper) {
                        case "DECLARE" -> State.DECLARATION;
                        case "BEGIN" -> State.STATEMENT;
                        case "LOOP", "ELSE", "THEN" -> State.STATEMENT;
                        case "IF", "ELSIF", "ELSEIF" -> State.IF_COND;
                        case "WHILE" -> State.WHILE_COND;
                        case "FOR" -> State.FOR_HEADER;
                        case "EXCEPTION", "WHEN" -> State.HANDLER_HEADER;
                        default -> State.STMT_BODY;
                    };
                }
                case DECLARATION -> {
                    String word = SqlStatementSplitter.wordAt(script, pos);
                    if (word == null) {
                        out.append(ch);
                        pos++;
                        break;
                    }
                    String upper = word.toUpperCase();
                    out.append(word);
                    pos += word.length();
                    if (upper.equals("BEGIN")) {
                        state = State.STATEMENT;
                    } else if (upper.equals("IS")) {
                        state = State.MASK_QUERY;
                    }
                }
                case MASK_QUERY -> {
                    pos = maskStatement(script, pos, out, masked, regions, nextId);
                    state = State.DECLARATION;
                }
                case STMT_BODY -> {
                    if (ch == ';') {
                        out.append(ch);
                        pos++;
                        state = State.STATEMENT;
                        break;
                    }
                    out.append(ch);
                    pos++;
                }
                case IF_COND -> {
                    String word = SqlStatementSplitter.wordAt(script, pos);
                    if (word != null && word.equalsIgnoreCase("THEN")) {
                        out.append(word);
                        pos += word.length();
                        state = State.STATEMENT;
                        break;
                    }
                    out.append(ch);
                    pos++;
                }
                case WHILE_COND -> {
                    String word = SqlStatementSplitter.wordAt(script, pos);
                    if (word != null && word.equalsIgnoreCase("LOOP")) {
                        out.append(word);
                        pos += word.length();
                        state = State.STATEMENT;
                        break;
                    }
                    out.append(ch);
                    pos++;
                }
                case HANDLER_HEADER -> {
                    // 标准写法 `WHEN 名 THEN` 用 THEN 结束头部；
                    // 历史写法 `EXCEPTION:` 用冒号结束（此后就是普通语句，必须能继续遮罩 SQL）
                    if (ch == ':') {
                        out.append(ch);
                        pos++;
                        state = State.STATEMENT;
                        break;
                    }
                    String word = SqlStatementSplitter.wordAt(script, pos);
                    if (word != null && word.equalsIgnoreCase("THEN")) {
                        out.append(word);
                        pos += word.length();
                        state = State.STATEMENT;
                        break;
                    }
                    out.append(ch);
                    pos++;
                }
                case LABEL_DECL -> {
                    // 逐字复制到 ">>" 为止
                    if (ch == '>' && pos + 1 < length && script.charAt(pos + 1) == '>') {
                        out.append(">>");
                        pos += 2;
                        state = State.STATEMENT;
                        break;
                    }
                    out.append(ch);
                    pos++;
                }
                case FOR_HEADER -> {
                    String word = SqlStatementSplitter.wordAt(script, pos);
                    if (word != null && word.equalsIgnoreCase("LOOP")) {
                        out.append(word);
                        pos += word.length();
                        state = State.STATEMENT;
                        break;
                    }
                    out.append(ch);
                    pos++;
                }
            }
        }

        int[] flat = new int[regions.size() * 4];
        for (int i = 0; i < regions.size(); i++) {
            int[] region = regions.get(i);
            flat[i * 4] = region[0];
            flat[i * 4 + 1] = region[1];
            flat[i * 4 + 2] = region[2];
            flat[i * 4 + 3] = region[3];
        }
        return new PreparedSource(out.toString(), masked, null, flat);
    }

    /** {@code name :=} 形态（赋值）而不是内嵌 SQL。 */
    private static boolean isAssignmentStart(String script, int index) {
        int next = SqlStatementSplitter.skipNoise(script, index);
        return script.startsWith(":=", next);
    }

    /** {@code EXECUTE} 之后紧跟 {@code IMMEDIATE} 才是动态 SQL 语句（见 grammar 的 execute_immediate_stmt）。 */
    private static boolean isExecuteImmediate(String script, int index) {
        int next = SqlStatementSplitter.skipNoise(script, index);
        String word = SqlStatementSplitter.wordAt(script, next);
        return word != null && word.equalsIgnoreCase("IMMEDIATE");
    }

    /**
     * 遮罩一条 SQL 语句：从 {@code start} 到顶层分号（不含），逐字保留分号本身。
     *
     * @return 分号（或文本末尾）的下标
     */
    private static int maskStatement(String script, int start, StringBuilder out, List<MaskedSql> masked,
                                     List<int[]> regions, int[] nextId) {
        // 前导空白逐字保留：否则占位符会与前一个词粘成一个 token
        // （典型：`cursor c is select ...` 里的 `is` + 占位符）
        int maskStart = start;
        while (maskStart < script.length() && Character.isWhitespace(script.charAt(maskStart))) {
            maskStart++;
        }
        if (maskStart > start) {
            out.append(script, start, maskStart);
        }

        int end = findTopLevelSemicolon(script, maskStart);
        String text = script.substring(maskStart, end);
        int id = nextId[0];

        // SELECT ... INTO ... ：拆成 "SQL 片段 + INTO 目标 + SQL 片段"，让 AST 拿到目标列表
        int[] split = splitSelectInto(text);
        if (split != null) {
            String before = text.substring(0, split[0]);
            String targets = text.substring(split[0], split[1]);
            String after = text.substring(split[1]);
            addMasked(masked, regions, out, before, script, maskStart, id);
            out.append(targets);
            addMasked(masked, regions, out, after, script, maskStart + split[1], id + 1);
            nextId[0] = id + 2;
        } else {
            addMasked(masked, regions, out, text, script, maskStart, id);
            nextId[0] = id + 1;
        }

        if (end < script.length() && script.charAt(end) == ';') {
            out.append(';');
            end++;
        }
        return end;
    }

    private static void addMasked(List<MaskedSql> masked, List<int[]> regions, StringBuilder out,
                                  String regionText, String script, int originalStart, int id) {
        if (regionText.isEmpty()) {
            return;
        }
        masked.add(new MaskedSql(id, regionText, originalStart, originalStart + regionText.length(),
                SqlStatementSplitter.lineOf(script, originalStart)));
        int skeletonStart = out.length();
        emitMaskedRegion(out, regionText, id);
        regions.add(new int[]{skeletonStart, out.length(), originalStart, originalStart + regionText.length()});
    }

    /** 把一段被遮罩的文本写成占位符：首行/末行等长补齐，换行数保留。 */
    private static void emitMaskedRegion(StringBuilder out, String region, int id) {
        int firstNewline = region.indexOf('\n');
        if (firstNewline < 0) {
            emitPlaceholder(out, id, region.length());
            return;
        }
        emitPlaceholder(out, id, firstNewline);
        int newlineCount = 0;
        for (int i = 0; i < region.length(); i++) {
            if (region.charAt(i) == '\n') {
                newlineCount++;
            }
        }
        out.append("\n".repeat(newlineCount));
        int lastNewline = region.lastIndexOf('\n');
        int lastLineLength = region.length() - lastNewline - 1;
        if (lastLineLength > 0) {
            emitPlaceholder(out, id, lastLineLength);
        }
    }

    /**
     * 生成占位符 token，并把它补齐到 {@code length} 个字符。
     *
     * <p>补齐用<b>空格</b>而不是下划线：空格是 HIDDEN 通道上的字符，既能保住列号
     * （位置精确），又能防止占位符与后面的词粘成一个 token
     * （下划线会粘：{@code _SQL_0____into} 会被当成一个标识符）。</p>
     */
    private static void emitPlaceholder(StringBuilder out, int id, int length) {
        String token = "_SQL_" + id;
        if (length <= token.length()) {
            // 行长不足以放下占位符：退化输出，并留一个空格防止粘连
            // （该行后续 token 的列号会偏移；行号始终精确）
            out.append(token).append(' ');
            return;
        }
        out.append(token);
        out.append(" ".repeat(length - token.length()));
    }

    private static int findTopLevelSemicolon(String script, int start) {
        int length = script.length();
        int index = start;
        int depth = 0;
        while (index < length) {
            int skipped = SqlStatementSplitter.skipNonCode(script, index);
            if (skipped > index) {
                index = skipped;
                continue;
            }
            char ch = script.charAt(index);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (ch == ';' && depth == 0) {
                return index;
            }
            index++;
        }
        return length;
    }

    /**
     * 识别 {@code SELECT/WITH ... INTO t1, t2 [FROM ...]} 形态。
     *
     * @return {@code [intoStart, targetsEnd]}（相对 text），不是该形态时返回 null
     */
    static int[] splitSelectInto(String text) {
        String firstWord = SqlStatementSplitter.wordAt(text, SqlStatementSplitter.skipNoise(text, 0));
        if (firstWord == null
                || !(firstWord.equalsIgnoreCase("SELECT") || firstWord.equalsIgnoreCase("WITH"))) {
            return null;
        }
        int into = findTopLevelWord(text, "INTO", 0, text.length());
        if (into < 0) {
            return null;
        }
        int from = findTopLevelWord(text, "FROM", 0, text.length());
        if (from >= 0 && from < into) {
            return null; // INTO 出现在 FROM 之后（例如 SELECT ... FROM x INTO ...），不按 PL/SQL INTO 处理
        }
        int targetsEnd = from > into ? from : text.length();
        String targets = text.substring(into, targetsEnd);
        if (!isTargetList(targets)) {
            return null;
        }
        return new int[]{into, targetsEnd};
    }

    /** {@code INTO v1, :v2, v3} 形态校验（目标必须是简单标识符）。 */
    static boolean isTargetList(String text) {
        String body = text.replaceFirst("(?i)^\\s*INTO\\s+", "");
        if (body.isBlank()) {
            return false;
        }
        for (String item : body.split(",")) {
            String target = item.trim();
            if (target.startsWith(":")) {
                target = target.substring(1);
            }
            if (target.isEmpty() || !Character.isLetter(target.charAt(0)) && target.charAt(0) != '_') {
                return false;
            }
            for (int i = 1; i < target.length(); i++) {
                char c = target.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '_' && c != '$' && c != '.') {
                    return false;
                }
            }
        }
        return true;
    }

    /** 在顶层（括号深度 0、非字符串/注释/美元引用）查找关键字词。 */
    static int findTopLevelWord(String text, String word, int from, int to) {
        int index = from;
        int depth = 0;
        while (index < to) {
            int skipped = SqlStatementSplitter.skipNonCode(text, index);
            if (skipped > index) {
                index = skipped;
                continue;
            }
            char ch = text.charAt(index);
            if (ch == '(') {
                depth++;
                index++;
                continue;
            }
            if (ch == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
                continue;
            }
            String token = SqlStatementSplitter.wordAt(text, index);
            if (token == null) {
                index++;
                continue;
            }
            if (depth == 0 && token.equalsIgnoreCase(word)) {
                return index;
            }
            index += token.length();
        }
        return -1;
    }
}
