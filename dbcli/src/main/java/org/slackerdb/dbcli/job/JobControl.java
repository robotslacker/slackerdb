package org.slackerdb.dbcli.job;

/**
 * 作业线程眼里的"停止控制"：协作式中止的唯一判据。
 *
 * <p>由 {@link Job} 实现（{@link JobManager} 只是创建与推进它），交给 {@link JobExecutor}：执行体在**长操作**
 * 前后查 {@link #stopRequested()}，并把"取消当前长操作"的回调登记到 {@link #onStop(Runnable)}
 * （JDBC 的 {@code Statement.cancel()}、SSH channel 断开……）。</p>
 *
 * <p>两级停止语义（见 {@link JobManager}）：{@code abort} = 请求 + 中断线程（尽力），
 * {@code shutdown}/管理器关闭 = 先只请求（优雅，等宽限期），超时后才中断。</p>
 */
public interface JobControl {

    /** 是否已经请求停止（{@code abort}/{@code shutdown}/管理器 {@code close()} 都会置位）。 */
    boolean stopRequested();

    /**
     * 这次停止请求是否要求**立刻强制**。
     *
     * <p>{@code _JOB ABORT} 与"作业到点硬超时"要求强制（进程模式直接杀整棵进程树）；
     * {@code _JOB SHUTDOWN} 与管理器关闭是**优雅**的（先 {@code destroy()} 给一点宽限，仍在跑才强杀）。
     * 线程模式不用这个判据（它只会中断线程）。</p>
     */
    default boolean forcedStop() {
        return true;
    }

    /**
     * 登记一个"停止时取消当前长操作"的回调。
     *
     * <p>若停止已经请求过，回调**立即**执行一次（不留"注册晚了一步"的窗口）。</p>
     *
     * @param canceller 取消动作（必须自己能容忍失败）
     * @return 注销句柄（幂等；作业正常结束时也会被清掉）
     */
    AutoCloseable onStop(Runnable canceller);
}
