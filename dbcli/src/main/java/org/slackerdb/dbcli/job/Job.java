package org.slackerdb.dbcli.job;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 一个作业的**只读视图**：所有 getter 都通过 {@link JobManager} 读库里的那一行。
 *
 * <p>为什么不是可变对象：状态机已经搬到 {@link SqliteJobStore}，如果这里再存一份
 * {@code volatile state} 就会重新出现"两个真相"。视图只缓存不可变的身份
 * （{@code jobId}/{@code name}/{@code stdoutFile}），状态、退出码、失败原因、运行标识
 * 每次都从库里读；管理器关闭后读它保存的最终快照。</p>
 *
 * <p>因此 {@code _JOB WAIT} 返回的视图、{@code _JOB SHOW} 产出的行、测试里拿到的
 * {@code Job} 都是同一个语义：**当前事实**，而不是"取快照那一刻的事实"。</p>
 */
public final class Job {

    private final JobManager manager;
    private final long jobId;
    private final String name;
    private final Path stdoutFile;

    Job(JobManager manager, long jobId, String name, Path stdoutFile) {
        this.manager = manager;
        this.jobId = jobId;
        this.name = name;
        this.stdoutFile = stdoutFile;
    }

    long jobId() {
        return jobId;
    }

    public String name() {
        return name;
    }

    /** 作业定义（只有监督进程内存里有；跨进程的视图没有定义）。 */
    public JobSpec spec() {
        return manager.definition(jobId);
    }

    public JobState state() {
        return record().state();
    }

    public Path stdoutFile() {
        return stdoutFile;
    }

    public Instant startedAt() {
        Long started = record().startedAtMillis();
        return started == null ? null : Instant.ofEpochMilli(started);
    }

    public Instant finishedAt() {
        Long finished = record().finishedAtMillis();
        return finished == null ? null : Instant.ofEpochMilli(finished);
    }

    /** 退出码；未结束时为 {@link Integer#MIN_VALUE}（既有哨兵）。 */
    public int exitCode() {
        return record().exitCodeOrMin();
    }

    /** 作业异常/启动失败/停止的原因（正常路径为 null）。 */
    public String failureMessage() {
        return record().failure();
    }

    /** 监督线程；未启动（或管理器已关闭）时为 null。 */
    public Thread thread() {
        return manager.localThread(jobId);
    }

    /** 监督线程名；未启动时为空串。 */
    public String threadName() {
        Thread current = thread();
        return current == null ? "" : current.getName();
    }

    /**
     * 运行标识（{@code _JOB SHOW} 的 {@code thread} 列）：线程模式是线程名
     * （{@code dbcli-job-<作业名>}），进程模式是 {@code pid <n>}，未启动时为空串。
     */
    public String runtimeIdentity() {
        JobRecord current = record();
        if (current.mode() == JobMode.PROCESS) {
            // 进程模式：登记子进程 pid 之前没有"运行身份"（线程只是发起者，对使用者没有意义）
            return current.runtimeId() == null ? "" : current.runtimeId();
        }
        Thread worker = thread();
        if (worker != null) {
            return worker.getName();
        }
        return current.runtimeId() == null ? "" : current.runtimeId();
    }

    /** 进程模式子进程的 pid；线程模式或尚未启动时为 -1。 */
    public long processId() {
        return manager.localProcessId(jobId);
    }

    /** 已运行毫秒数（未启动 0；已结束取结束时刻）。 */
    public long elapsedMillis() {
        return record().elapsedMillis();
    }

    /** 只读的行摘要（{@code _JOB SHOW} 用）：{@code name/state/thread/elapsed/exitCode}。 */
    public List<String> describe() {
        JobRecord current = record();
        return List.of(
                name,
                current.state().name(),
                runtimeIdentity(),
                current.elapsedMillis() + "ms",
                current.exitCodeOrMin() == Integer.MIN_VALUE
                        ? "" : String.valueOf(current.exitCodeOrMin()));
    }

    public boolean isTerminal() {
        return state().isTerminal();
    }

    public boolean isRunning() {
        return state() == JobState.RUNNING;
    }

    private JobRecord record() {
        return manager.recordForView(jobId, name);
    }
}
