package org.slackerdb.dbcli.engine;

/**
 * 错误输出的**唯一**实现：打印 + 错误计数 + {@code _WHENEVER ERROR} 判定。
 *
 * <p>从 {@link ScriptRunner} 抽出来的原因：这三件事以前混在"语句推进"里，读代码时要同时想着
 * "退出码、错误计数、打印到哪个流"。现在推进逻辑只调用这里的两个方法，错误语义只在这里读：</p>
 * <ul>
 *   <li>{@link #report(String)}：一次普通错误——打印一行、计一次、必要时请求退出；</li>
 *   <li>{@link #fail(String)}：一条"既是状态行又是失败"的消息——**只打一次**（状态行已经在 stdout 上）、
 *       计一次错误（{@code Assert fail.}/{@code Compare failed.}/失败的 {@code _JOB WAIT}）。</li>
 * </ul>
 *
 * <p>退出码口径见 {@link ScriptContext#finalExitCode()}：默认错误**不**改变进程退出码，
 * 只有 {@code _WHENEVER ERROR EXIT n} 或 {@code _EXIT n} 才改。README §9.3 与这里必须一致。</p>
 */
final class ErrorReporter {

    private final ScriptContext context;

    ErrorReporter(ScriptContext context) {
        this.context = context;
    }

    /** 一条普通错误：打印、计数、{@code _WHENEVER} 判定。 */
    void report(String message) {
        count(message, false);
    }

    /** 一条"既是状态行又是失败"的消息：已经打印过，只计数与判定。 */
    void fail(String message) {
        context.output().printFailure(text(message));
        count(message, true);
    }

    /**
     * @param alreadyPrinted 消息是否已经打印过（{@code true} 时不再走 {@link ScriptOutput#printError}）
     */
    private void count(String message, boolean alreadyPrinted) {
        if (!alreadyPrinted) {
            context.output().printError(text(message));
        }
        context.countError();
        ScriptContext.Whenever whenever = context.whenever();
        if ("exit".equalsIgnoreCase(whenever.action())) {
            context.requestExit(whenever.exitCode());
        }
    }

    private static String text(String message) {
        return message == null || message.isBlank() ? "ERROR: unknown failure." : message;
    }
}
