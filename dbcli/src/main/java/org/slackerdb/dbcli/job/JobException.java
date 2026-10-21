package org.slackerdb.dbcli.job;

/**
 * 作业管理相关的可读错误（作业不存在、管理器未启动、参数不合法……）。
 *
 * <p>分派器捕获它并转成 {@code CommandOutcome.Error}，因此消息必须能直接给人看。</p>
 */
public class JobException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JobException(String message) {
        super(message);
    }

    public JobException(String message, Throwable cause) {
        super(message, cause);
    }
}
