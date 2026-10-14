package org.slackerdb.dbserver.sql;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PG 事务控制语句的识别与规格化。
 *
 * <p><b>为什么需要它</b>：</p>
 * <ul>
 *   <li>{@code postgres_fdw} 开远端事务时发的是 {@code START TRANSACTION ISOLATION LEVEL REPEATABLE READ}
 *       —— DuckDB 没有这条语法（{@code 42601}），SQLReplacer 历史上把它<b>改写成空串</b>：
 *       客户端收到 {@code BEGIN} 的成功应答，DuckDB 却<b>什么都没开</b>，
 *       于是远端 {@code ROLLBACK} 一个字节都回滚不了（静默的语义错误，比报错更危险）。</li>
 *   <li>{@code BEGIN ISOLATION LEVEL ...} / {@code SET TRANSACTION ISOLATION LEVEL ...} → {@code 42601}；</li>
 *   <li>{@code COMMIT TRANSACTION} / {@code ABORT TRANSACTION} / {@code END} DuckDB 本来就认（已实测），
 *       但会话状态需要由这里显式驱动，而不是靠"SQL 前缀字符串"猜。</li>
 * </ul>
 *
 * <p><b>隔离级别取舍</b>：DuckDB 没有可设置的隔离级别（MVCC 快照 + 单写者，写冲突报 {@code 40001}）。</p>
 * <ul>
 *   <li>{@code READ COMMITTED} / {@code REPEATABLE READ}：DuckDB 的快照至少不弱于它们 →
 *       <b>接受且不打扰</b>（fdw 每次开事务都会发 REPEATABLE READ，逐个发 NOTICE 只会刷屏）；</li>
 *   <li>{@code SERIALIZABLE} / {@code DEFERRABLE}：做不到 →
 *       接受但发 NOTICE 说明"不会生效"，绝不假装；</li>
 *   <li>{@code READ ONLY}：DuckDB 支持（{@code BEGIN TRANSACTION READ ONLY} 会真的拒绝写入），
 *       因此<b>原样透传</b> —— SlackDB 的 JDBC 驱动在只读连接上正是发 {@code BEGIN READ ONLY}。</li>
 *   <li>子事务（{@code SAVEPOINT} / {@code RELEASE SAVEPOINT} / {@code ROLLBACK TO SAVEPOINT}）：
 *       DuckDB 没有子事务，因此<b>明确报错</b>（事务块内 {@code 0A000}，块外 {@code 25P01}），
 *       而不是让 DuckDB 的语法错误或"退化成整事务回滚"这种静默错误语义流出去。</li>
 * </ul>
 */
public final class TransactionSql {

    /** {@code START TRANSACTION ...} / {@code BEGIN [WORK|TRANSACTION] ...}。 */
    private static final Pattern BEGIN = Pattern.compile(
            "^\\s*(?:START\\s+TRANSACTION|BEGIN(?:\\s+(?:WORK|TRANSACTION))?)\\b([\\s\\S]*)$",
            Pattern.CASE_INSENSITIVE);

    /** {@code COMMIT ...} / {@code END ...}。 */
    private static final Pattern COMMIT = Pattern.compile(
            "^\\s*(?:COMMIT|END)(?:\\s+(?:WORK|TRANSACTION))?\\b([\\s\\S]*)$", Pattern.CASE_INSENSITIVE);

    /** {@code ROLLBACK ...} / {@code ABORT ...}。 */
    private static final Pattern ROLLBACK = Pattern.compile(
            "^\\s*(?:ROLLBACK|ABORT)(?:\\s+(?:WORK|TRANSACTION))?\\b([\\s\\S]*)$", Pattern.CASE_INSENSITIVE);

    /** {@code SET TRANSACTION ...}（设置"当前事务"的特征）。 */
    private static final Pattern SET_TRANSACTION = Pattern.compile(
            "^\\s*SET\\s+TRANSACTION\\b([\\s\\S]*)$", Pattern.CASE_INSENSITIVE);

    /**
     * 子事务控制：{@code SAVEPOINT n} / {@code RELEASE [SAVEPOINT] n} / {@code ROLLBACK [WORK|TRANSACTION] TO [SAVEPOINT] n}。
     *
     * <p><b>必须在 {@link #ROLLBACK} 之前匹配</b>：否则 {@code ROLLBACK TO SAVEPOINT s1} 会被当成普通
     * {@code ROLLBACK}，把整个事务回滚掉 —— 那是静默的错误语义（比报错危险得多）。
     * DuckDB 没有子事务，这里统一给出可预期的报错（见 {@code TransactionHandler}）。</p>
     */
    private static final Pattern SAVEPOINT = Pattern.compile(
            "^\\s*(?:"
                    + "SAVEPOINT\\s+(?:\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)"
                    + "|RELEASE\\s+(?:SAVEPOINT\\s+)?(?:\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)"
                    + "|ROLLBACK(?:\\s+(?:WORK|TRANSACTION))?\\s+TO\\s+(?:SAVEPOINT\\s+)?(?:\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)"
                    + ")\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);

    private TransactionSql() {
    }

    public enum Kind { BEGIN, COMMIT, ROLLBACK, SET_TRANSACTION, SAVEPOINT }

    /**
     * 一条事务控制语句。
     *
     * @param kind     语句种类
     * @param executeSql 交给 DuckDB 执行的规格化语句（{@code BEGIN TRANSACTION} / {@code COMMIT} / {@code ROLLBACK}）；
     *                   {@code SET_TRANSACTION} 为 {@code null}（不执行，只提示）
     * @param notice   非空时要发一条 NOTICE（说明哪部分不会生效）
     */
    public record TransactionStatement(Kind kind, String executeSql, String notice) {
    }

    /** 解析事务控制语句；不属于本类的返回 {@code null}。 */
    public static TransactionStatement parse(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }

        Matcher begin = BEGIN.matcher(sql);
        if (begin.matches()) {
            String tail = normalize(begin.group(1));
            return new TransactionStatement(Kind.BEGIN,
                    "BEGIN TRANSACTION" + readOnlySuffix(tail), beginNotice(tail));
        }

        // 子事务控制必须排在 COMMIT/ROLLBACK 之前：`ROLLBACK TO SAVEPOINT s1` 不能当成整事务回滚
        if (SAVEPOINT.matcher(sql).matches()) {
            return new TransactionStatement(Kind.SAVEPOINT, null, null);
        }

        Matcher commit = COMMIT.matcher(sql);
        if (commit.matches()) {
            return new TransactionStatement(Kind.COMMIT, "COMMIT", tailNotice("COMMIT", commit.group(1)));
        }

        Matcher rollback = ROLLBACK.matcher(sql);
        if (rollback.matches()) {
            return new TransactionStatement(Kind.ROLLBACK, "ROLLBACK", tailNotice("ROLLBACK", rollback.group(1)));
        }

        Matcher setTransaction = SET_TRANSACTION.matcher(sql);
        if (setTransaction.matches()) {
            return new TransactionStatement(Kind.SET_TRANSACTION, null,
                    "SET TRANSACTION is accepted but has no effect in SlackerDB "
                            + "(DuckDB has no settable transaction characteristics).");
        }

        return null;
    }

    /**
     * {@code READ ONLY} 必须**真的透传**给 DuckDB（{@code BEGIN TRANSACTION READ ONLY} 是 DuckDB 支持的，
     * 且会真的拒绝写入）：SlackDB 的 JDBC 驱动在 {@code readOnly=true} + {@code autoCommit=false}
     * 时正是发 {@code BEGIN READ ONLY}，把只读语义吞掉就等于悄悄放宽了权限。
     * {@code READ WRITE} 是默认值，不必写出来。
     */
    private static String readOnlySuffix(String normalizedTail) {
        return normalizedTail.contains("READ ONLY") ? " READ ONLY" : "";
    }

    /** 只有"做不到"的子句才提示；REPEATABLE READ / READ COMMITTED 视为已满足。 */
    private static String beginNotice(String tail) {
        String normalized = normalize(tail);
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.contains("SERIALIZABLE")) {
            return "ISOLATION LEVEL SERIALIZABLE is not enforced by SlackerDB: DuckDB uses a single snapshot "
                    + "with a single writer, so conflicting writes may fail with SQLSTATE 40001.";
        }
        if (normalized.contains("DEFERRABLE")) {
            return "DEFERRABLE is not supported by SlackerDB and is ignored.";
        }
        // READ COMMITTED / REPEATABLE READ：DuckDB 的快照不弱于它们，接受且不打扰
        // READ ONLY / READ WRITE：DuckDB 支持，原样透传（见 readOnlySuffix）
        return null;
    }

    /** COMMIT/ROLLBACK 的尾部子句（目前只有 AND CHAIN 值得提示）。 */
    private static String tailNotice(String verb, String tail) {
        String normalized = normalize(tail);
        if (normalized.contains("AND CHAIN")) {
            return "AND CHAIN is not supported by SlackerDB: the transaction is ended without starting a new one.";
        }
        return null;
    }

    private static String normalize(String tail) {
        if (tail == null) {
            return "";
        }
        return tail.replace(";", " ").trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
