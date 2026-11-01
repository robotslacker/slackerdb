package org.slackerdb.dbcli.api;

/**
 * 请求被作业停止信号或线程中断取消。
 *
 * <p>它不携带用户可见错误：调用方应先走 dbcli 的停止流程
 * （{@code CommandHost.checkStopRequested()}），再由停止流程决定输出。</p>
 */
public final class ApiCancelledException extends Exception {

    private static final long serialVersionUID = 1L;

    public ApiCancelledException() {
        super("API request cancelled.");
    }
}
