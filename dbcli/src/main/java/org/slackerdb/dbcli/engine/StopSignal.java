package org.slackerdb.dbcli.engine;

/**
 * 协作式停止信号：正在跑脚本的线程用它判断"我该停下来了"。
 *
 * <p>唯一的生产来源是作业线程的 {@code job.JobControl#stopRequested()}（{@code _JOB ABORT}/{@code SHUTDOWN}
 * 或管理器关闭时置位）。父脚本与控制台用的是 {@link #NEVER}，因此它们的执行路径**逐字节不变**。</p>
 *
 * <p>只读的判据，不做中断：需要"唤醒阻塞中的长操作"的地方（{@code _SLEEP}、{@code _HOST}、JDBC）各自
 * 用可中断等待或取消回调实现，见 {@link ScriptRunner}。</p>
 */
@FunctionalInterface
public interface StopSignal {

    /** 永不停止（父脚本与控制台用）。 */
    StopSignal NEVER = () -> false;

    boolean stopRequested();
}
