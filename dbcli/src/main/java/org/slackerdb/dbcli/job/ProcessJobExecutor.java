package org.slackerdb.dbcli.job;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 进程模式的作业执行体：**再起一个 dbcli 进程**跑同一份脚本，并回收它的退出码。
 *
 * <h2>与线程模式（{@code ScriptJobExecutor}）的分工</h2>
 * <p>两者都实现 {@link JobExecutor}，都是"把 {@link JobSpec#script()} 跑完并给一个退出码"，
 * 区别只在哪儿跑：线程模式在**当前 JVM 的一个线程**里，本类在**一个真正的子进程**里。
 * 因此本类不碰脚本引擎、不建连接，只做四件事：拼命令行、设置工作目录与输出重定向、
 * 传递会合点环境变量、等待并（必要时）杀掉整棵进程树。</p>
 *
 * <h2>输出为什么是"父进程不持有写句柄"</h2>
 * <p>子进程的 stdout 与 stderr 由 {@link ProcessBuilder#redirectOutput} 与
 * {@link ProcessBuilder#redirectErrorStream} **一起**落到 {@code <作业名>.out}（只有这一个文件）。
 * 父进程不打开这个文件（{@code JobManager} 在进程模式下给的是一个丢弃型的 {@link JobOutput}），
 * 于是 Windows 上不会出现"父进程持有写句柄、子进程重定向打不开"的共享冲突；
 * 长跑作业也不会因为没人读管道而阻塞（不需要抽水线程）。</p>
 *
 * <h2>"先落盘再置终态"为什么仍然成立</h2>
 * <p>子进程退出时，操作系统会释放它的全部写句柄，它自己写出的字节已经在文件里；
 * 因此父脚本看到 {@code DONE}/{@code FAILED} 时，{@code <作业名>.out} 一定是完整的。</p>
 *
 * <h2>停止与硬超时</h2>
 * <ul>
 *   <li>{@code _JOB ABORT} → {@link ProcessTree#kill}（立刻 {@code destroyForcibly}，含整棵进程树）；</li>
 *   <li>{@code _JOB SHUTDOWN}/管理器关闭 → {@link ProcessTree#destroy}（{@code destroy()} 宽限
 *       {@link ProcessTree#SHUTDOWN_GRACE_MILLIS} 毫秒后仍在跑才强杀）；</li>
 *   <li>作业带 {@code timeout=<秒>} → 由 {@link JobManager} 的看门狗到点发出**强制**停止请求，
 *       走的还是上面第一条路，所以"到点一定杀掉"是 OS 级的，不依赖子进程配合。</li>
 * </ul>
 */
public final class ProcessJobExecutor implements JobExecutor {

    /**
     * 作业名的环境变量名（父进程 → 作业进程）：只用于把"谁到了会合点"写清楚
     * （同步库里的 {@code sync_arrive.participant} 因此显示 {@code C1}，而不是笼统的 {@code MAIN}）。
     */
    public static final String ENV_JOB_NAME = "DBCLI_JOB_NAME";

    private final JobProcessLauncher launcher;

    public ProcessJobExecutor() {
        this(new SelfJobProcessLauncher());
    }

    public ProcessJobExecutor(JobProcessLauncher launcher) {
        this.launcher = launcher == null ? new SelfJobProcessLauncher() : launcher;
    }

    @Override
    // registration 只为"作业被停止时杀掉子进程"而存在（close() 的副作用），正文里不读它
    @SuppressWarnings("try")
    public int execute(JobContext job) throws Exception {
        JobSpec spec = job.spec();
        Job instance = job.job();
        Path workingDirectory = spec.workingDirectory() == null
                ? Path.of("").toAbsolutePath() : spec.workingDirectory();
        List<String> command = launcher.commandFor(spec);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);   // stderr 并进 stdout：只有一个 <作业名>.out
        builder.redirectOutput(instance.stdoutFile().toFile());
        // 会合点：同步库路径 + 运行代号（父进程建库时生成）。线程模式下这里是空的
        builder.environment().putAll(job.manager().rendezvousEnvironment());
        // 作业自己的名字：只为了让同步库里的"到达记录"能指名道姓（不影响同步语义）
        builder.environment().put(ENV_JOB_NAME, spec.name());

        Process process;
        try {
            process = builder.start();
        } catch (IOException startFailed) {
            reportError(instance, "Cannot start the job process: " + startFailed.getMessage());
            return 1;
        }
        // 关掉子进程的 stdin 写端：每个进程作业都会留一个打开的句柄（Windows 上反复起作业会累积），
        // 子进程读 stdin 时也会一直等不到 EOF。作业不需要从父脚本拿到任何输入。
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 关不掉不影响作业本身：句柄会随 Process 回收释放
        }
        instance.markProcessStarted(process.pid());

        try (AutoCloseable registration = job.control().onStop(
                () -> stop(process, job.control().forcedStop()))) {
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            // abort/shutdown/硬超时都会先请求停止再中断作业线程：进程该被上面那个回调杀掉了。
            // 这里不再往外抛（抛出去会被记成"作业线程异常"，把一次正常的中止写成一条 ERROR）。
            Thread.currentThread().interrupt();
            stop(process, true);
            return exitCodeOf(process);
        }
    }

    // ------------------------------------------------------------------ 内部

    private static void stop(Process process, boolean forced) {
        if (forced) {
            ProcessTree.kill(process);
        } else {
            ProcessTree.destroy(process, ProcessTree.SHUTDOWN_GRACE_MILLIS);
        }
    }

    private static int exitCodeOf(Process process) {
        return process.isAlive() ? 1 : process.exitValue();
    }

    /**
     * 启动失败之类"作业进程没能跑起来"的可读错误：直接追加到 {@code <作业名>.out}。
     *
     * <p>进程模式下的 {@link JobOutput} 是丢弃型的（真正的输出文件由子进程重定向写入），
     * 所以这里自己开文件追加——只有父进程会写这一行，子进程还不存在，不存在并发写同一个文件。</p>
     */
    private static void reportError(Job instance, String message) {
        try {
            java.nio.file.Files.writeString(instance.stdoutFile(), message + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // 错误文件写不进去也无法补救：作业状态已经落到 FAILED
        }
    }

    /** 便于排错：把实际拼出的命令行渲染成一行（不参与输出契约，只给日志/测试看）。 */
    public static String describe(List<String> command) {
        return String.join(" ", command.stream().map(ProcessJobExecutor::quoteIfNeeded).toList());
    }

    private static String quoteIfNeeded(String argument) {
        if (argument == null) {
            return "";
        }
        String lower = argument.toLowerCase(Locale.ROOT);
        return argument.contains(" ") || lower.startsWith("--") ? "[" + argument + "]" : argument;
    }
}
