package org.slackerdb.dbcli.engine;

/**
 * 脚本执行被"停止请求"打断（协作式中止的内部信号）：{@link StopSignal#stopRequested()} 为真时，
 * 引擎在**下一条语句**之前抛出它，让作业线程尽快收尾。
 *
 * <p>它只在作业线程里可能出现（父脚本的 {@link StopSignal} 是 {@link StopSignal#NEVER}），
 * 由 {@code ScriptJobExecutor} 捕获并转成"作业被停掉"；因此它不会冒到父脚本、也不会变成未捕获异常
 * （255）。</p>
 *
 * <p>关掉栈与抑制信息：这不是缺陷，是正常的中止路径，堆栈对使用者没有信息量。</p>
 *
 * <p>公开的原因：命令实现（{@code engine.command}）通过 {@code CommandHost#checkStopRequested()}
 * 触发它，需要能看见这个类型。</p>
 */
public final class ScriptStoppedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ScriptStoppedException() {
        super("Script execution stopped by a job stop request.", null, false, false);
    }
}
