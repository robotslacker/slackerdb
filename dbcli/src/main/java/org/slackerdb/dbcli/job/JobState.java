package org.slackerdb.dbcli.job;

/**
 * 作业状态。
 *
 * <p>状态只反映**作业的真相**，不反映调用方的等待结果：{@code _JOB WAIT} 超时**不**改变作业状态
 * （作业线程可能还在跑），超时由 {@code JobManager.WaitResult.timedOut()} 表达。</p>
 *
 * <p>状态迁移是单向的，除 {@link #CREATED} 与 {@link #RUNNING} 之外都是终态（终态不会变，只能重建同名作业）：</p>
 * <pre>
 *   CREATED ──start──▶ RUNNING ──┬─▶ DONE      （作业正常结束，退出码 0）
 *                                ├─▶ FAILED    （退出码非 0，或作业抛异常，或启动失败）
 *                                ├─▶ ABORTED   （被 abort 请求停止 + 中断/强杀）
 *                                ├─▶ SHUTDOWN  （被 shutdown 优雅停止，或管理器 close() 收尾）
 *                                └─▶ TIMEOUT   （带 timeout= 的作业到点被停掉：进程模式杀进程树）
 * </pre>
 *
 * <p>例外只有一处、且是刻意的：{@code abort}/{@code shutdown} 的终态由**请求方**落地
 * （它们本来就表达"是我把它停掉的"），作业线程自己的结束不再覆盖它。</p>
 */
public enum JobState {
    /** 已定义参数，尚未启动作业线程。 */
    CREATED,
    /** 作业线程已启动。 */
    RUNNING,
    /** 作业线程正常结束且退出码为 0。 */
    DONE,
    /** 作业线程结束但退出码非 0（或作业线程抛异常、启动失败）。 */
    FAILED,
    /** 被 {@code abort} 请求停止 + 中断线程。 */
    ABORTED,
    /** 被 {@code shutdown} 优雅停止（先请求停止，宽限期后仍在跑才中断线程）。 */
    SHUTDOWN,
    /** 带 {@code timeout=<秒>} 的作业到点被停掉（进程模式是杀整棵进程树；线程模式是请求 + 中断）。 */
    TIMEOUT;

    /** 是否已经结束（不会再变化）。 */
    public boolean isTerminal() {
        return this != CREATED && this != RUNNING;
    }
}
