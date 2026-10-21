package org.slackerdb.dbcli.job;

/**
 * 按 {@link JobSpec#mode()} 选执行体的分发器：{@code mode=thread}（默认）走线程执行体，
 * {@code mode=process} 走子进程执行体。
 *
 * <p>放在 {@code job} 包而不是引擎里：它只认识 {@link JobExecutor} 这个抽象，
 * 不需要知道任何一侧是怎么实现的（线程那侧要脚本引擎，进程那侧要 java 可执行文件与主类）。</p>
 */
public final class ModeJobExecutor implements JobExecutor {

    private final JobExecutor threadExecutor;
    private final JobExecutor processExecutor;

    /**
     * @param threadExecutor  线程模式执行体（{@code engine.ScriptJobExecutor}）
     * @param processExecutor 进程模式执行体（{@link ProcessJobExecutor}）
     */
    public ModeJobExecutor(JobExecutor threadExecutor, JobExecutor processExecutor) {
        this.threadExecutor = threadExecutor;
        this.processExecutor = processExecutor;
    }

    @Override
    public int execute(JobContext job) throws Exception {
        JobMode mode = job.spec().mode();
        return mode == JobMode.PROCESS
                ? processExecutor.execute(job)
                : threadExecutor.execute(job);
    }
}
