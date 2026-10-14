package org.slackerdb.dbserver.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 只读判定：<b>在解析层拒绝</b>，而不是靠事务。
 *
 * <p><b>为什么不能靠事务</b>：官方 Postgres MCP 参考服务器用
 * {@code BEGIN TRANSACTION READ ONLY} 实现只读，任何能脱离事务包裹的语句都能绕过去，
 * 这正是它被归档（"太薄"）的原因之一。判定必须发生在语句进入引擎之前，
 * 而且必须<b>默认拒绝</b>（fail-closed）：认出是读才放行，认不出就拒绝。</p>
 *
 * <p><b>判定口径</b>（刻意保守，宁可误拒不可误放）：</p>
 * <ol>
 *   <li>去掉前导注释与空白；</li>
 *   <li>首关键字必须在只读白名单里（{@code SELECT}/{@code WITH}/{@code DESCRIBE}/
 *       {@code SHOW}/{@code EXPLAIN}/{@code SUMMARIZE}/{@code FROM}/{@code VALUES}/{@code TABLE}/
 *       {@code PIVOT}/{@code UNPIVOT}/{@code PRAGMA}）；</li>
 *   <li>并且整条语句<b>任何位置</b>都不得出现写关键字。</li>
 * </ol>
 *
 * <p>第 3 条看着粗糙，但它解决了一个真实的绕过面：{@code WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x}
 * 这类"读开头、写在内"的语句。因为判定的是关键字出现而非位置，
 * 代价是形如 {@code SELECT 'DELETE'} 的字符串字面量会被误拒——
 * 对 LLM 工具面来说这个代价可以接受（换个写法即可），漏放则不可接受。</p>
 */
final class SqlStatementGuard {

    private SqlStatementGuard() {
    }

    /**
     * 允许作为首关键字的形式。
     *
     * <p>{@code PRAGMA} 明确<b>不在</b>此列：DuckDB 的 PRAGMA 既能读
     * （{@code show_tables}）也能改实例状态（{@code enable_checkpoint_on_shutdown}、
     * {@code threads}、{@code memory_limit}），无法只看关键字区分，因此整体拒绝。
     * 这是 fail-closed 的取舍：宁可让模型换个写法，也不放行可能改状态的语句。</p>
     */
    private static final Set<String> READ_ONLY_LEADERS = Set.of(
            "SELECT", "WITH", "DESCRIBE", "DESC", "SHOW", "EXPLAIN", "SUMMARIZE",
            "FROM", "VALUES", "TABLE", "PIVOT", "UNPIVOT"
    );

    /**
     * 写关键字。包含 DDL、DML、以及会改实例或文件系统的管理语句。
     *
     * <p>{@code ATTACH}/{@code DETACH}/{@code INSTALL}/{@code LOAD}/{@code COPY}/{@code EXPORT}/
     * {@code IMPORT} 都在列：它们不改表，但会改实例状态或读写文件系统，
     * 在只读工具面里没有放行的理由。</p>
     */
    private static final Set<String> MUTATING_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "UPSERT",
            "CREATE", "DROP", "ALTER", "RENAME",
            "GRANT", "REVOKE",
            "ATTACH", "DETACH", "INSTALL", "LOAD", "FORCE",
            "COPY", "EXPORT", "IMPORT",
            "SET", "RESET", "CALL", "VACUUM", "ANALYZE", "CHECKPOINT",
            "BEGIN", "COMMIT", "ROLLBACK", "ABORT", "START", "END",
            "REPLACE", "REFRESH", "COMMENT", "PREPARE", "EXECUTE", "DEALLOCATE", "DISCARD"
    );

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 判定结果。 */
    record Verdict(boolean readOnly, String reason) {
        static Verdict allow() {
            return new Verdict(true, null);
        }

        static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * 判定一条或多条语句是否整体只读。
     *
     * <p>多语句（以 {@code ;} 分隔）必须<b>每一条</b>都只读；任何一条不是就整体拒绝。</p>
     */
    static Verdict classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return Verdict.deny("Empty statement");
        }
        boolean sawStatement = false;
        for (String single : splitStatements(sql)) {
            String stripped = stripLeadingComments(single).trim();
            if (stripped.isEmpty()) {
                continue;
            }
            sawStatement = true;
            Verdict verdict = classifySingle(stripped);
            if (!verdict.readOnly()) {
                return verdict;
            }
        }
        if (!sawStatement) {
            // 只有注释/空白：拒绝而不是放行，避免"看起来无害的输入"成为盲区
            return Verdict.deny("No statement found (input was empty or comments only)");
        }
        return Verdict.allow();
    }

    private static Verdict classifySingle(String statement) {
        List<String> words = words(statement);
        if (words.isEmpty()) {
            return Verdict.deny("Empty statement");
        }
        String leader = words.get(0);
        if (!READ_ONLY_LEADERS.contains(leader)) {
            return Verdict.deny("Statement starts with '" + leader
                    + "', which is not on the read-only allowlist. This MCP server is read-only by default.");
        }
        for (String word : words) {
            if (MUTATING_KEYWORDS.contains(word)) {
                return Verdict.deny("Statement contains '" + word
                        + "', so it is not read-only. This MCP server is read-only by default.");
            }
        }
        return Verdict.allow();
    }

    /**
     * 只取标识符形状的词，跳过字符串/引用标识符里的内容，避免
     * {@code SELECT 'DELETE'} 被当成写语句。
     *
     * <p>这里刻意做得比"整串正则扫关键字"更细一点：漏放数据比误拒一条合法查询严重得多，
     * 但误拒太频繁会让工具面不可用。跳过字面量是两者之间最小代价的折中。</p>
     */
    private static List<String> words(String statement) {
        List<String> result = new java.util.ArrayList<>();
        int i = 0;
        int n = statement.length();
        while (i < n) {
            char c = statement.charAt(i);
            if (c == '\'') {
                i = skipQuoted(statement, i, '\'');
                continue;
            }
            if (c == '"') {
                // 引用标识符：内容是标识符，但不该影响只读判定（用户可命名 "delete" 之类）
                i = skipQuoted(statement, i, '"');
                continue;
            }
            if (c == '-' && i + 1 < n && statement.charAt(i + 1) == '-') {
                while (i < n && statement.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && statement.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(statement.charAt(i) == '*' && statement.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(statement.charAt(i)) || statement.charAt(i) == '_')) {
                    i++;
                }
                result.add(statement.substring(start, i).toUpperCase(Locale.ROOT));
                continue;
            }
            i++;
        }
        return result;
    }

    private static int skipQuoted(String statement, int start, char quote) {
        int i = start + 1;
        int n = statement.length();
        while (i < n) {
            if (statement.charAt(i) == quote) {
                // '' 是转义的单引号
                if (i + 1 < n && statement.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /** 按 {@code ;} 切分，尊重引号与注释（这里只服务于逐条判定，不做完整 SQL 解析）。 */
    private static List<String> splitStatements(String sql) {
        List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = skipQuoted(sql, i, c);
                current.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = i;
                while (end < n && sql.charAt(end) != '\n') {
                    end++;
                }
                current.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == ';') {
                parts.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static String stripLeadingComments(String sql) {
        String s = sql.stripLeading();
        boolean changed = true;
        while (changed) {
            changed = false;
            if (s.startsWith("--")) {
                int end = s.indexOf('\n');
                s = end < 0 ? "" : s.substring(end + 1).stripLeading();
                changed = true;
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                s = end < 0 ? "" : s.substring(end + 2).stripLeading();
                changed = true;
            }
        }
        return s;
    }

    /** 供测试与文档使用：暴露白名单，避免文档与实现漂移。 */
    static Set<String> readOnlyLeaders() {
        return READ_ONLY_LEADERS;
    }

    static Set<String> mutatingKeywords() {
        return MUTATING_KEYWORDS;
    }

    static boolean isWord(String text) {
        return WORD.matcher(text).matches();
    }
}
