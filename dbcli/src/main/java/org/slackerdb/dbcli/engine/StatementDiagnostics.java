package org.slackerdb.dbcli.engine;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 每条语句收尾的诊断行：{@code _SET TIMING} 的耗时与 {@code _SET TIME} 的系统时间。
 *
 * <p>两条都是为了"排查慢语句"：它们**走 stdout**（因此也进 {@code --logfile}/{@code --ref} 与
 * {@code _SPOOL}），所以默认关闭——逐字节比对时它们每次都不同。</p>
 *
 * <p>耗时一行只打一次：SQL 在 {@link SqlStatementExecutor} 里按"真正执行那段"计时（更贴近服务端耗时），
 * 收尾的 {@link #finish(long)} 因此只补它没打的那种。这个"已经打过一次"的状态就放在本类里，
 * 不必让 call site 记一个布尔值。</p>
 */
final class StatementDiagnostics {

    /** {@code Current time:} 的固定格式（毫秒三位，本地时区）。 */
    private static final DateTimeFormatter CURRENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private final ScriptContext context;

    /** 本条语句是否已经输出过 {@code elapsed} 行（SQL 路径会先打一次）。 */
    private boolean elapsedReported;

    StatementDiagnostics(ScriptContext context) {
        this.context = context;
    }

    /** 一条语句开始：重置"已打过耗时"的标记。 */
    void begin() {
        elapsedReported = false;
    }

    /** 补一行耗时（{@code _SET TIMING ON} 时）。 */
    void reportElapsed(long elapsedNanos) {
        if (!context.timingOn()) {
            return;
        }
        elapsedReported = true;
        long totalMillis = elapsedNanos / 1_000_000L;
        context.output().printDiagnostic(String.format(Locale.ROOT,
                "%d minutes %d seconds %d ms elapsed.",
                totalMillis / 60_000L, (totalMillis / 1000L) % 60L, totalMillis % 1000L));
    }

    /**
     * 一条语句收尾：补它还没打过的耗时行，再按 {@code _SET TIME} 打系统时间。
     *
     * @param startedAtNanos {@code System.nanoTime()} 的起点
     */
    void finish(long startedAtNanos) {
        if (!elapsedReported) {
            reportElapsed(System.nanoTime() - startedAtNanos);
        }
        if (context.timeOn()) {
            context.output().printDiagnostic("Current time: "
                    + LocalDateTime.now().format(CURRENT_TIME_FORMAT));
        }
    }
}
