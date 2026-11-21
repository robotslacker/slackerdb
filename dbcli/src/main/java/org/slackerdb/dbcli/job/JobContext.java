package org.slackerdb.dbcli.job;

/**
 * 一次作业执行的输入（执行体视角）：**共享的管理器 + 作业视图 + 停止控制 + 输出目标**。
 *
 * <p>{@link #manager()} 就是父脚本的那一个 {@link JobManager}——同 scope 的线程作业共享它，
 * 因此同进程的祖母/父/子作业仍然互相可见、共享定时点。进程作业的子进程通过环境变量
 * 加入同一个库，但使用自己的 scope。</p>
 *
 * <p>{@link #control()} 是本地执行句柄（停止镜像 + 取消回调）；它不是状态来源，
 * 状态在 {@link SqliteJobStore} 里。{@link #markProcessStarted(Process)} 把子进程 pid
 * 登记到库里的 {@code runtime_id}，供 {@code _JOB SHOW} 显示。</p>
 */
public final class JobContext {

    private final JobManager manager;
    private final Job job;
    private final LocalJobRuntime runtime;
    private final JobOutput output;

    JobContext(JobManager manager, Job job, LocalJobRuntime runtime, JobOutput output) {
        this.manager = manager;
        this.job = job;
        this.runtime = runtime;
        this.output = output;
    }

    /** 共享的作业管理器。 */
    public JobManager manager() {
        return manager;
    }

    /** 作业的只读视图（状态从库里读）。 */
    public Job job() {
        return job;
    }

    /** 作业定义（脚本/变量/选项；只留在监督进程内存）。 */
    public JobSpec spec() {
        JobSpec spec = manager.definition(job.jobId());
        if (spec == null) {
            throw new JobException("Job [" + job.name() + "] has no definition.");
        }
        return spec;
    }

    /** 协作式停止控制（本地执行句柄）。 */
    public JobControl control() {
        return runtime;
    }

    /** 作业自己的输出目标（{@code <作业名>.out}；进程模式是丢弃型，由子进程自己重定向写）。 */
    public JobOutput output() {
        return output;
    }

    /** 进程模式启动子进程后调用：登记 pid（{@code _JOB SHOW} 的 thread 列据此显示）。 */
    public void markProcessStarted(Process process) {
        manager.processStarted(job.jobId(), process);
    }
}
