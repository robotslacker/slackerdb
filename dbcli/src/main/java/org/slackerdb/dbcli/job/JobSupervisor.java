package org.slackerdb.dbcli.job;

/**
 * 一个作业的监督者：**唯一**负责"跑执行体 + 写终态"的线程。
 *
 * <p>线程作业与进程作业走同一条路径，区别只在注入的 {@link JobExecutor}：
 * 前者跑另一份脚本，后者起一个 dbcli 子进程并等待它退出。</p>
 *
 * <h2>顺序上的硬保证</h2>
 * <p>先关闭（落盘）作业输出，再写终态；因此父脚本 {@code _JOB WAIT} 看到
 * {@code DONE/FAILED/...} 时 {@code <作业名>.out} 一定完整。</p>
 *
 * <h2>终态优先级</h2>
 * <p>由 {@link SqliteJobStore#finish(long, int, String)} 在 CAS 内计算：
 * {@code stop_terminal} &gt; {@code timeout_hit} &gt; 退出码。请求方（abort/shutdown/超时调度器）
 * 只写"意图"，不再与监督者抢着写终态。</p>
 */
final class JobSupervisor implements Runnable {

    private final JobManager manager;
    private final Job job;
    private final LocalJobRuntime runtime;
    private final JobOutput output;
    private final JobExecutor executor;
    private final long jobId;

    JobSupervisor(JobManager manager, Job job, LocalJobRuntime runtime, JobOutput output,
                  JobExecutor executor) {
        this.manager = manager;
        this.job = job;
        this.runtime = runtime;
        this.output = output;
        this.executor = executor;
        this.jobId = job.jobId();
    }

    @Override
    public void run() {
        int exitCode = Integer.MIN_VALUE;
        Throwable failure = null;
        try {
            JobRecord entry = manager.store().findById(jobId);
            boolean shouldRun = entry != null && entry.isRunning()
                    && entry.stopTerminal() == null && !entry.timeoutHit();
            if (shouldRun) {
                exitCode = executor.execute(new JobContext(manager, job, runtime, output));
            } else {
                // 启动与 ABORT/SHUTDOWN/超时撞在一起：不执行脚本，直接收尾（终态由意图决定）
                exitCode = 1;
            }
        } catch (Throwable caught) {
            // 作业的异常绝不冒到父脚本线程/别的作业：写进 .out 并把作业置为 FAILED
            failure = caught;
            writeFailure(output, caught);
        } finally {
            closeQuietly(output);
            runtime.clearCancellers();
            try {
                manager.finish(jobId, exitCode, failure == null ? null : messageOf(failure));
            } catch (RuntimeException alreadyStopping) {
                // 管理器已经关闭/库不可用：终态写不进去也无法补救，绝不能因此拖住收尾
                manager.logSupervisorFailure(job.name(), alreadyStopping);
            }
            manager.timeoutScheduler().cancel(jobId);
        }
    }

    /** 作业抛出异常时的可读错误：写进 {@code <作业名>.out}（作业的错不该冒到父脚本线程）。 */
    private static void writeFailure(JobOutput output, Throwable failure) {
        if (output == null) {
            return;
        }
        try {
            output.stderr().print("ERROR: " + messageOf(failure));
            output.stderr().print('\n');
            output.stderr().flush();
        } catch (RuntimeException ignored) {
            // 错误文件写不进去也无法补救：作业状态已经记在 failure 上
        }
    }

    private static String messageOf(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(JobOutput output) {
        if (output != null) {
            output.close();
        }
    }
}
