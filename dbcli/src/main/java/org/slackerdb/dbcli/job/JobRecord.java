package org.slackerdb.dbcli.job;

import java.time.Duration;
import java.time.Instant;

/**
 * 作业行的**不可变快照**：{@link SqliteJobStore} 读出来的一行。
 *
 * <p>它只是数据，没有任何状态推进方法；对外暴露的 {@link Job} 是"活的视图"，
 * 每次读都从库里取一份新的 {@code JobRecord}（或管理器关闭后的最终快照）。</p>
 *
 * <p>{@code exitCode} 用 {@link Integer} 表示"还没落地"；{@link #exitCodeOrMin()} 把
 * {@code null} 折回 {@link Integer#MIN_VALUE}，保持与既有 {@code Job.exitCode()}、
 * {@code JOB.<名>.EXITCODE} 取值一致的哨兵语义。</p>
 */
public record JobRecord(
        long jobId,
        String scope,
        String name,
        JobMode mode,
        Long timeoutMillis,
        JobState state,
        Integer exitCode,
        String failure,
        long createdAtMillis,
        Long startedAtMillis,
        Long finishedAtMillis,
        String runtimeId,
        Long supervisorPid,
        long definitionVersion,
        JobState stopTerminal,
        boolean stopForced,
        boolean timeoutHit,
        boolean acknowledged) {

    /** 未落地退出码的既有哨兵值。 */
    public static final int NO_EXIT_CODE = Integer.MIN_VALUE;

    public boolean isRunning() {
        return state == JobState.RUNNING;
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    /** 退出码；未落地时为 {@link #NO_EXIT_CODE}。 */
    public int exitCodeOrMin() {
        return exitCode == null ? NO_EXIT_CODE : exitCode;
    }

    /** 已运行毫秒数（未启动 0；已结束取结束时刻；时钟回拨时夹紧到 0）。 */
    public long elapsedMillis() {
        Long started = startedAtMillis;
        if (started == null) {
            return 0L;
        }
        long end = finishedAtMillis == null ? System.currentTimeMillis() : finishedAtMillis;
        return Math.max(Duration.between(Instant.ofEpochMilli(started), Instant.ofEpochMilli(end))
                .toMillis(), 0L);
    }
}
