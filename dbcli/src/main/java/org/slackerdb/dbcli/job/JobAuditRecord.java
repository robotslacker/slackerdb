package org.slackerdb.dbcli.job;

/**
 * 一条作业执行审计：**每次作业运行结束写一行**。
 *
 * <p>进程作业的 {@code pid} 是真正的子进程 PID；线程作业是当前 dbcli 进程的 PID。
 * {@code startedAtMillis}/{@code finishedAtMillis} 是作业监督者视角的开始/结束时间；
 * {@code state} 是终态（{@code DONE/FAILED/ABORTED/SHUTDOWN/TIMEOUT}），{@code exitCode}
 * 是进程退出码（没有退出码时为 {@code null}）。</p>
 */
public record JobAuditRecord(
        long auditId,
        long jobId,
        String scope,
        String jobName,
        String scriptName,
        String mode,
        long pid,
        long startedAtMillis,
        long finishedAtMillis,
        Integer exitCode,
        JobState state,
        String failure) {
}
