package org.slackerdb.dbcli;

import picocli.CommandLine;

import java.io.PrintWriter;

/**
 * dbcli 进程入口。
 *
 * <p>只做三件事：构造命令树、执行、把退出码交给操作系统。所有业务逻辑都在 {@link Cli} 及其子命令里，
 * 这样 Main 不需要随功能演进反复改动（测试也只需针对 Cli，不必启动进程）。</p>
 *
 * <p>退出码语义：正常 0、脚本 {@code _EXIT n} 返回 n、
 * {@code _WHENEVER ERROR EXIT n} 返回 n、有错误则 1、
 * <b>未捕获异常 255</b>（picocli 的解析错误由框架映射为非 0）、
 * <b>{@link Error}（OOM / StackOverflow 之类）70</b>。</p>
 *
 * <p><b>为什么要单独兜 {@link Error}</b>：picocli 的 {@code IExecutionExceptionHandler} 只接收
 * {@link Exception}，{@code OutOfMemoryError} 这类 {@link Error} 会绕过它，于是进程直接带着 Java 栈退出
 * ——使用者看到的是 {@code Exception in thread "main" java.lang.OutOfMemoryError}，既不是可读错误，
 * 也没有"这次运行不完整"的结论。因此这里再包一层 {@code Throwable}：
 * 打一行可读结论（stdout 不碰，仍然只留数据），返回一个与其它退出码都不冲突的 70，
 * 让 CI 能一眼区分"脚本失败（1）"与"工具自己崩了（70）"。</p>
 */
public final class Main {

    /** 未捕获异常的进程退出码（§3.7）。 */
    private static final int EXIT_UNCAUGHT_EXCEPTION = 255;

    /**
     * 未捕获 {@link Error} 的退出码。
     *
     * <p>用 70（{@code EX_SOFTWARE}）而不是 1：1 已经被"脚本里有错误"占用，
     * 把工具自身的崩溃混进去会让 CI 无法区分"测试失败"与"工具没跑完"。</p>
     */
    static final int EXIT_INTERNAL_ERROR = 70;

    private Main() {
    }

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new Cli());
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        // 输出策略（含 --silent 的总闸）只有一处：必须在**解析参数之前**装好，否则 picocli 自己的
        // "未知选项/缺参数"提示会在 call() 之前打出去，与"除了退出码什么都不打印"冲突。
        CliOutput.attach(commandLine, CliOutput.silentRequested(args));
        commandLine.setExecutionExceptionHandler((failure, cmd, parseResult) -> {
            cmd.getErr().println("ERROR: uncaught exception: " + failure);
            failure.printStackTrace(cmd.getErr());
            cmd.getErr().flush();
            return EXIT_UNCAUGHT_EXCEPTION;
        });
        int exitCode;
        try {
            exitCode = commandLine.execute(args);
        } catch (Throwable fatal) {
            // Error（OOM / StackOverflow / NoClassDefFoundError…）到不了上面的处理器：
            // 它们不是 Exception，picocli 不会接。这里给出"这次运行不完整"的结论并换一个专用退出码。
            exitCode = reportFatal(commandLine, fatal);
        }
        System.exit(exitCode);
    }

    /**
     * 把未捕获的 {@link Error} 收敛成一行可读错误 + {@link #EXIT_INTERNAL_ERROR}。
     *
     * <p><b>不打印栈</b>：这类失败的读者是脚本使用者，不是 dbcli 的开发者；一行"什么崩了 + 这次运行不完整"
     * 比几十行 Java 栈更能指导下一步（OOM 时还会额外给出两条可执行的建议）。
     * 需要栈的场景由 {@code --version} 之外的诊断手段承担，不属于本条路径。</p>
     *
     * <p>报告本身也包在 try 里：OOM 时连字符串拼接都可能再次失败，那时宁可静默退出也不能
     * 在错误处理里再抛一次。</p>
     *
     * <p>包可见：单测直接验证"OOM 得到的行与退出码"（{@code Main.main} 会 {@code System.exit}，
     * 不便在测试进程里调用）。</p>
     */
    static int reportFatal(CommandLine commandLine, Throwable fatal) {
        try {
            PrintWriter err = commandLine.getErr();
            err.println("ERROR: dbcli aborted by " + describe(fatal) + "; this run is incomplete.");
            if (fatal instanceof OutOfMemoryError) {
                err.println("Hint: the output or the result set did not fit in memory; "
                        + "raise the heap (-Xmx).");
            }
            err.flush();
        } catch (Throwable ignored) {
            // 报错路径本身失败（例如再次 OOM）：静默退出，退出码仍然表达"工具崩了"
        }
        return EXIT_INTERNAL_ERROR;
    }


    /** {@code java.lang.OutOfMemoryError: Java heap space} 这类"类名 + 消息"的一行描述。 */
    private static String describe(Throwable fatal) {
        String name = fatal.getClass().getName();
        String message = fatal.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }
}
