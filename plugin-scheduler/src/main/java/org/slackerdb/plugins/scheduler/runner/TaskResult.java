package org.slackerdb.plugins.scheduler.runner;

/**
 * 任务执行结果（归一化后的退出语义）。
 *
 * <p>退出码约定（与历史实现保持一致）：</p>
 * <ul>
 *   <li>{@code 0} 成功；</li>
 *   <li>{@code -1} 启动失败 / 脚本不存在 / IO 错误；</li>
 *   <li>{@code -2} 超时被强制终止；</li>
 *   <li>{@code -3} 被用户中止；</li>
 *   <li>其它：子进程真实退出码原样透传。</li>
 * </ul>
 */
public final class TaskResult {

    public static final int EXIT_OK = 0;
    public static final int EXIT_START_FAILED = -1;
    public static final int EXIT_TIMEOUT = -2;
    public static final int EXIT_CANCELED = -3;

    public int exitCode;
    public long durationMs;
    /** 实际写入的日志文件（绝对路径，可为 null）。 */
    public String logFile;
    /** 失败原因摘要（可为空）。 */
    public String errorMessage;
    public boolean timedOut;
    public boolean canceled;

    public static TaskResult of(int exitCode, long durationMs, String logFile) {
        TaskResult r = new TaskResult();
        r.exitCode = exitCode;
        r.durationMs = durationMs;
        r.logFile = logFile;
        return r;
    }

    public static TaskResult failed(String message, long durationMs, String logFile) {
        TaskResult r = of(EXIT_START_FAILED, durationMs, logFile);
        r.errorMessage = message;
        return r;
    }

    public boolean isSuccess() {
        return exitCode == EXIT_OK;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder("exitCode=").append(exitCode)
                .append(", 耗时=").append(durationMs).append("ms");
        if (timedOut) {
            sb.append(", 超时");
        }
        if (canceled) {
            sb.append(", 已中止");
        }
        if (errorMessage != null && !errorMessage.isBlank()) {
            sb.append(", 原因=").append(errorMessage);
        }
        return sb.toString();
    }
}
