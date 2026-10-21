package org.slackerdb.dbcli.sql;

/**
 * SQL 执行层的运行期异常：消息是**给脚本使用者看的一行文本**，引擎直接打到 stderr。
 *
 * <p>只用于"连接阶段"的失败（URL 拼不出来、驱动连不上）：这类失败没有可用的
 * {@link org.slackerdb.dbcli.assertion.ResultModel} 可给 {@code _ASSERT}，因此按异常处理；
 * 语句执行期（{@code execute}）的 SQL 异常则收敛成 {@code errorCode=1} 的模型，不抛异常
 * —— 那段语义见 {@link SqlExecutor}。</p>
 */
public class SqlExecutorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SqlExecutorException(String message) {
        super(message);
    }

    public SqlExecutorException(String message, Throwable cause) {
        super(message, cause);
    }
}
