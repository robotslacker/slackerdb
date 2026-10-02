package org.slackerdb.dbserver.sql;

import java.util.Locale;

/**
 * 生成 PG 线协议 {@code CommandComplete} 的命令标签（command tag）。
 *
 * <p><b>为什么需要它</b>：改造前两条执行路径各自用字符串前缀猜标签，猜不中一律回
 * {@code "UPDATE " + n}。于是 {@code DELETE}、所有 DDL、{@code SET}/{@code SHOW}、
 * {@code WITH ... SELECT}、{@code VALUES}、{@code PRAGMA} 全被报成 {@code UPDATE <n>}，
 * 而 {@code CREATE TABLE} 会变成 {@code UPDATE -1}。</p>
 *
 * <p>标签是唯一告诉客户端"这是什么命令、影响多少行"的字段，被真实客户端解析：
 * psql 的状态行与 {@code :ROW_COUNT}、asyncpg 的 {@code status} 字符串、Npgsql/psycopg
 * 的影响行数、以及驱动侧 {@code CommandCompleteParser}（从末尾倒着最多找两组数字，
 * 末尾不是数字即按 0 行处理）。</p>
 *
 * <p><b>PG 的规则</b>：只有 DML 与查询系列带行数（{@code SELECT n} / {@code INSERT 0 n} /
 * {@code UPDATE n} / {@code DELETE n} / {@code COPY n} / {@code MERGE n}）；
 * DDL 与工具类命令回命令名本身、<b>不带数字</b>（{@code CREATE TABLE}、{@code SET}、{@code BEGIN}）。</p>
 *
 * <p><b>驱动的限制</b>：DuckDB 的 JDBC 只暴露 {@code StatementReturnType}
 * （查询结果 / 影响行数 / 无），不提供命令标签，因此服务端必须自行分类 —— 就是本类。</p>
 */
public final class CommandTag {

    private CommandTag() {
    }

    /**
     * 计算命令标签。
     *
     * @param sql            客户端执行的 SQL 原文（可含注释）
     * @param affectedRows   影响行数 / 返回行数；&lt;0 表示"未知"，此时会回退为 0
     * @param hasResultSet   该语句是否产出了结果集（决定无法归类时是否按 SELECT 处理）
     */
    public static String of(String sql, long affectedRows, boolean hasResultSet) {
        long rows = affectedRows < 0 ? 0 : affectedRows;

        String normalized = SqlCommentStripper.stripComments(sql == null ? "" : sql).trim();
        if (normalized.isEmpty()) {
            // 空语句（纯注释、或 SQLReplacer 改写后的空串）：
            // 回 "SELECT 0" 而不是空标签 —— 空标签在协议上非法，客户端也无法解析。
            return "SELECT 0";
        }

        String upper = normalized.toUpperCase(Locale.ROOT);

        // ---- 带行数后缀：DML 与查询系列 ----
        if (startsWithKeyword(upper, "INSERT")) {
            return "INSERT 0 " + rows;
        }
        if (startsWithKeyword(upper, "UPDATE")) {
            return "UPDATE " + rows;
        }
        if (startsWithKeyword(upper, "DELETE")) {
            return "DELETE " + rows;
        }
        if (startsWithKeyword(upper, "MERGE")) {
            return "MERGE " + rows;
        }
        if (startsWithKeyword(upper, "SELECT")
                || startsWithKeyword(upper, "VALUES")
                || startsWithKeyword(upper, "WITH")
                || startsWithKeyword(upper, "TABLE")) {
            // WITH ... SELECT（CTE）、VALUES、TABLE t 都是查询系列
            return "SELECT " + rows;
        }

        // ---- 事务控制 ----
        if (startsWithKeyword(upper, "BEGIN") || upper.startsWith("START TRANSACTION")) {
            return "BEGIN";
        }
        if (startsWithKeyword(upper, "COMMIT") || startsWithKeyword(upper, "END")) {
            return "COMMIT";
        }
        if (startsWithKeyword(upper, "ROLLBACK") || startsWithKeyword(upper, "ABORT")) {
            return "ROLLBACK";
        }

        // ---- 工具类：回命令名本身，不带数字 ----
        if (startsWithKeyword(upper, "SET")) {
            return "SET";
        }
        if (startsWithKeyword(upper, "RESET")) {
            return "RESET";
        }
        if (startsWithKeyword(upper, "SHOW")) {
            return "SHOW";
        }
        if (startsWithKeyword(upper, "EXPLAIN")) {
            return "EXPLAIN";
        }
        if (startsWithKeyword(upper, "TRUNCATE")) {
            return "TRUNCATE TABLE";
        }
        if (startsWithKeyword(upper, "CREATE")) {
            return leadingKeywords(upper, 2);
        }
        if (startsWithKeyword(upper, "DROP")) {
            return leadingKeywords(upper, 2);
        }
        if (startsWithKeyword(upper, "ALTER")) {
            return leadingKeywords(upper, 2);
        }
        if (startsWithKeyword(upper, "COMMENT")) {
            return "COMMENT";
        }
        if (startsWithKeyword(upper, "GRANT")) {
            return "GRANT";
        }
        if (startsWithKeyword(upper, "REVOKE")) {
            return "REVOKE";
        }
        if (startsWithKeyword(upper, "VACUUM")) {
            return "VACUUM";
        }
        if (startsWithKeyword(upper, "ANALYZE")) {
            return "ANALYZE";
        }
        if (startsWithKeyword(upper, "PRAGMA")) {
            return "PRAGMA";
        }
        if (startsWithKeyword(upper, "CALL")) {
            return "CALL";
        }
        if (startsWithKeyword(upper, "DO")) {
            return "DO";
        }
        if (startsWithKeyword(upper, "COPY")) {
            return "COPY";
        }

        // ---- 兜底 ----
        // 产出结果集的按查询处理；否则回首个关键字（不带数字）。
        // 刻意**不**再用 "UPDATE n" 兜底：把未知命令伪装成 UPDATE 只会误导客户端。
        if (hasResultSet) {
            return "SELECT " + rows;
        }
        String first = leadingKeywords(upper, 1);
        return first.isEmpty() ? "SELECT 0" : first;
    }

    /**
     * 判断是否以某个关键字开头（按单词边界匹配，避免 {@code SETTING} 被当成 {@code SET}）。
     */
    private static boolean startsWithKeyword(String upperSql, String keyword) {
        if (!upperSql.startsWith(keyword)) {
            return false;
        }
        if (upperSql.length() == keyword.length()) {
            return true;
        }
        char next = upperSql.charAt(keyword.length());
        return !Character.isLetterOrDigit(next) && next != '_';
    }

    /**
     * 取前 {@code count} 个"关键字"（按空白切分，最多两个字符）。
     * 用于 {@code CREATE TABLE} / {@code DROP VIEW} / {@code ALTER TABLE} 这类标签。
     */
    private static String leadingKeywords(String upperSql, int count) {
        String[] parts = upperSql.split("\\s+");
        StringBuilder tag = new StringBuilder();
        for (int i = 0; i < parts.length && i < count; i++) {
            String part = parts[i];
            // 只接受纯字母的关键字片段（跳过 IF / 表名 / 反引号等）
            if (part.isEmpty() || !part.chars().allMatch(Character::isLetter)) {
                break;
            }
            if (tag.length() > 0) {
                tag.append(' ');
            }
            tag.append(part);
        }
        return tag.toString();
    }
}
