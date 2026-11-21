package org.slackerdb.dbcli.job;

import org.slackerdb.dbcli.common.DbcliPaths;
import org.slackerdb.dbcli.common.VariableScopeCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 进程模式的作业执行体：**再起一个 dbcli 进程**跑同一份脚本，并回收它的退出码。
 *
 * <p>编排（注册表/状态/停止意图/超时/终态）全部由 {@link JobManager} 与
 * {@link SqliteJobStore} 负责；本类只做执行层的事：拼命令行、设置工作目录与输出重定向、
 * 传递"加入本次运行"的环境变量、等待并（必要时）杀掉整棵进程树。</p>
 *
 * <h2>输出为什么是"父进程不持有写句柄"</h2>
 * <p>子进程的 stdout 与 stderr 由 {@link ProcessBuilder#redirectOutput} 与
 * {@link ProcessBuilder#redirectErrorStream} **一起**落到 {@code <作业名>.out}（只有这一个文件）。
 * 父进程不打开这个文件（{@link JobManager} 在进程模式下给的是丢弃型 {@link JobOutput}），
 * 于是 Windows 上不会出现"父进程持有写句柄、子进程重定向打不开"的共享冲突；
 * 长跑作业也不会因为没人读管道而阻塞。</p>
 *
 * <h2>停止与硬超时</h2>
 * <ul>
 *   <li>{@code _JOB ABORT} → {@link ProcessTree#kill}（立刻 {@code destroyForcibly}，含整棵进程树）；</li>
 *   <li>{@code _JOB SHUTDOWN}/管理器关闭 → {@link ProcessTree#destroy}（{@code destroy()} 宽限
 *       {@link ProcessTree#SHUTDOWN_GRACE_MILLIS} 毫秒后仍在跑才强杀）；</li>
 *   <li>作业带 {@code timeout=<秒>} → 由 {@link JobTimeoutScheduler} 到点写库并发强制信号，
 *       走的还是上面第一条路，所以"到点一定杀掉"是 OS 级的。</li>
 * </ul>
 */
public final class ProcessJobExecutor implements JobExecutor {

    /**
     * 作业名的环境变量名（父进程 → 作业进程）：只用于把"谁到了会合点"写清楚
     * （作业库里的 {@code job_arrive.participant} 因此显示 {@code C1}，而不是笼统的 {@code MAIN}）。
     */
    public static final String ENV_JOB_NAME = "_DBCLI_JOB_NAME";

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
        Path stdoutFile = job.output().stdoutFile();
        DbcliPaths paths = job.manager().paths();
        JobSpec resolved = resolveSpec(job, paths);
        Path workingDirectory = paths.work();
        List<String> command = launcher.commandFor(resolved);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);   // stderr 并进 stdout：只有一个 <作业名>.out
        builder.redirectOutput(stdoutFile.toFile());
        // 加入同一次运行：库路径 + 子 scope；作业名只用于到达记录的显示
        builder.environment().putAll(job.manager().childEnvironment(spec.name()));
        builder.environment().put(VariableScopeCodec.ENV_SESSION,
                VariableScopeCodec.encode(resolved.sessionVariables()));
        builder.environment().put(VariableScopeCodec.ENV_LOCAL,
                VariableScopeCodec.encode(resolved.variables()));

        Process process;
        try {
            process = builder.start();
        } catch (IOException startFailed) {
            reportError(stdoutFile, "Cannot start the job process: " + startFailed.getMessage());
            return 1;
        }
        // 关掉子进程的 stdin 写端：每个进程作业都会留一个打开的句柄（Windows 上反复起作业会累积），
        // 子进程读 stdin 时也会一直等不到 EOF。作业不需要从父脚本拿到任何输入。
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 关不掉不影响作业本身：句柄会随 Process 回收释放
        }
        job.markProcessStarted(process);

        try (AutoCloseable registration = job.control().onStop(
                () -> stop(process, job.control().forcedStop()))) {
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            // abort/shutdown/硬超时都会先请求停止再中断监督线程：进程该被上面的回调杀掉了。
            // 这里不再往外抛（抛出去会被记成"作业线程异常"，把一次正常的中止写成一条 ERROR）。
            Thread.currentThread().interrupt();
            stop(process, true);
            return exitCodeOf(process);
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 进程作业的脚本/profile/reference 在父进程按“定义脚本目录”解析成绝对路径，再交给子进程；
     * 子进程的 WORK/HOME 由环境变量带来，不再自己按相对名猜。
     */
    private static JobSpec resolveSpec(JobContext job, DbcliPaths paths) {
        JobSpec spec = job.spec();
        Path script = paths.findScript(spec.script(), spec.definitionDirectory());
        if (script == null) {
            throw new JobException("Script file [" + spec.script() + "] does not exist.");
        }
        Map<String, String> options = new LinkedHashMap<>(spec.options());
        String profile = options.get("profile");
        if (profile != null) {
            Path resolved = paths.findScript(profile, spec.definitionDirectory());
            if (resolved == null) {
                throw new JobException("Profile script [" + profile + "] does not exist.");
            }
            options.put("profile", resolved.toString());
        }
        String reference = options.get("reference");
        if (reference != null) {
            options.put("reference", paths.findInput(reference, spec.definitionDirectory()).toString());
        }
        return new JobSpec(spec.name(), script.toString(), spec.variables(), options,
                paths.work(), spec.definitionDirectory(), spec.sessionVariables());
    }

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
    private static void reportError(Path stdoutFile, String message) {
        try {
            java.nio.file.Files.writeString(stdoutFile, message + System.lineSeparator(),
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
