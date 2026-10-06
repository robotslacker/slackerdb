package org.slackerdb.plugins.scheduler.runner;

import org.slf4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 外部进程执行器：SHELL / COMMAND 直接跑命令行；HOP 跑 {@code hop-run} 命令行。
 *
 * <p>行为对齐历史实现（老 scheduler 的 {@code SingleProcessRunner}），并修正其缺陷：</p>
 * <ul>
 *   <li>stdout+stderr 合并后 <b>追加</b>写入本次执行专属日志文件（不依赖调用方读取管道，无阻塞风险）；</li>
 *   <li>超时到点先 {@code destroy()} 再 {@code destroyForcibly()}，最后按平台兜底
 *       （Windows {@code taskkill /F /T}，Unix {@code kill -9}），<b>连同子孙进程一起清理</b>；</li>
 *   <li>{@link #cancel()} 支持用户主动中止（老实现的 {@code terminate()}）；</li>
 *   <li>退出码归一化，超时与中止可区分（见 {@link TaskResult}）。</li>
 * </ul>
 *
 * <p>命令既支持参数数组（HOP，避免手工拼串再切词），也支持整条命令行字符串（SHELL/COMMAND，
 * 复用带引号感知的切词）。</p>
 */
public final class ProcessTaskExecutor {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final Logger logger;

    /** 当前子进程；{@code cancel()} 可能来自其它线程。 */
    private volatile Process process;
    private final AtomicBoolean canceled = new AtomicBoolean(false);

    public ProcessTaskExecutor(Logger logger) {
        this.logger = logger;
    }

    /**
     * 执行一次任务。
     *
     * @return 归一化结果；不会抛出业务异常（启动失败以 {@link TaskResult#EXIT_START_FAILED} 返回）
     */
    public TaskResult execute(TaskRequest request) {
        long start = System.currentTimeMillis();
        Path logFile = request.logFile;
        String logPath = logFile == null ? null : logFile.toAbsolutePath().toString();

        List<String> command;
        Path workDir;
        try {
            workDir = resolveWorkDir(request);
            command = buildCommand(request);
            prepareLogFile(logFile);
        } catch (Exception e) {
            logger.error("[RUNNER] 任务[{}/{}]准备执行环境失败: {}", request.runId, request.taskName, e.getMessage(), e);
            return TaskResult.failed(e.getMessage(), System.currentTimeMillis() - start, logPath);
        }

        if (command.isEmpty()) {
            return TaskResult.failed("empty command", System.currentTimeMillis() - start, logPath);
        }

        logger.info("[RUNNER] 任务[{}/{}] 启动: type={}, timeout={}s, workDir={}, log={}",
                request.runId, request.taskName, request.scriptType,
                request.timeoutSeconds > 0 ? request.timeoutSeconds : "无限制", workDir, logPath);
        logger.info("[RUNNER] 命令行: {}", String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        env.putAll(request.processEnv());
        if (!WINDOWS) {
            // Linux 下中文/编码问题：与历史实现保持一致
            env.putIfAbsent("LANG", "en_US.utf8");
        }
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        builder.redirectErrorStream(true);
        if (logFile != null) {
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        } else {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }

        try {
            process = builder.start();
        } catch (IOException e) {
            logger.error("[RUNNER] 任务[{}/{}] 进程启动失败: {}", request.runId, request.taskName, e.getMessage(), e);
            return TaskResult.failed("进程启动失败: " + e.getMessage(),
                    System.currentTimeMillis() - start, logPath);
        }

        long pid = process.pid();
        logger.info("[RUNNER] 任务[{}/{}] 进程已启动, PID={}", request.runId, request.taskName, pid);

        Integer exitCode = null;
        boolean timedOut = false;
        try {
            if (request.timeoutSeconds > 0) {
                boolean finished = process.waitFor(request.timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    timedOut = true;
                    appendLog(logFile, String.format(
                            "[%s] 任务[%s/%s] 进程 PID[%d] 执行超时：已运行 %d 秒，上限 %d 秒，将清理该进程及其子进程。",
                            LocalDateTime.now().format(TIME_FORMAT), request.runId, request.taskName,
                            pid, (System.currentTimeMillis() - start) / 1000, request.timeoutSeconds));
                    logger.warn("[RUNNER] 任务[{}/{}] 执行超时({}s)，强制终止 PID={}",
                            request.runId, request.taskName, request.timeoutSeconds, pid);
                    killTree(pid, request);
                } else {
                    exitCode = process.exitValue();
                }
            } else {
                exitCode = process.waitFor();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[RUNNER] 任务[{}/{}] 等待被中断，终止进程 PID={}", request.runId, request.taskName, pid);
            killTree(pid, request);
        }

        long duration = System.currentTimeMillis() - start;
        TaskResult result = new TaskResult();
        result.durationMs = duration;
        result.logFile = logPath;
        result.timedOut = timedOut;

        if (timedOut) {
            result.exitCode = TaskResult.EXIT_TIMEOUT;
            result.errorMessage = "执行超时(" + request.timeoutSeconds + "s)";
        } else if (canceled.get()) {
            result.exitCode = TaskResult.EXIT_CANCELED;
            result.canceled = true;
            result.errorMessage = "任务被中止";
        } else {
            result.exitCode = exitCode == null ? TaskResult.EXIT_START_FAILED : exitCode;
        }

        logger.info("[RUNNER] 任务[{}/{}] 结束: {}", request.runId, request.taskName, result.describe());
        return result;
    }

    /** 主动中止：清理进程树，并让 {@link #execute} 返回"已中止"。 */
    public void cancel() {
        canceled.set(true);
        Process current = process;
        if (current != null && current.isAlive()) {
            logger.info("[RUNNER] 收到中止请求，清理进程树 PID={}", current.pid());
            killTree(current.pid(), null);
        }
    }

    public boolean isCanceled() {
        return canceled.get();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Path resolveWorkDir(TaskRequest request) {
        if (request.workDir != null) {
            return request.workDir;
        }
        return request.runHome;
    }

    /** 构造命令行：HOP 交给 {@link HopCommandBuilder}，其它类型切词后按平台包装。 */
    private List<String> buildCommand(TaskRequest request) {
        String scriptType = request.scriptType == null ? "" : request.scriptType.trim().toUpperCase();
        if ("HOP".equals(scriptType)) {
            return HopCommandBuilder.buildCommand(request);
        }
        if ("SQL".equals(scriptType)) {
            throw new IllegalStateException("SQL 任务不再由调度器直接执行（SQL 由 HOP 工作流内的 SQL action 执行）");
        }
        String script = request.script == null ? "" : request.script.trim();
        if (script.isEmpty()) {
            return List.of();
        }
        List<String> args = splitCommandLine(script);
        if (args.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>();
        if (WINDOWS) {
            // .bat/.cmd 无法被 CreateProcess 直接执行；显式经 cmd /c 包装
            command.add("cmd");
            command.add("/c");
        } else {
            command.add("sh");
            command.add("-c");
        }
        command.addAll(args);
        return command;
    }

    private static void prepareLogFile(Path logFile) throws IOException {
        if (logFile == null) {
            return;
        }
        Path parent = logFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(logFile)) {
            Files.createFile(logFile);
        }
    }

    /** 向日志文件追加一行（用于超时/中止说明；失败不影响主流程）。 */
    private void appendLog(Path logFile, String message) {
        if (logFile == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(logFile.toFile(), StandardCharsets.UTF_8, true)) {
            writer.write(message);
            writer.write(System.lineSeparator());
        } catch (IOException e) {
            logger.warn("[RUNNER] 写入日志文件失败: {}", e.getMessage());
        }
    }

    /**
     * 清理进程及其全部子孙进程：先优雅终止，再强制终止，最后按平台兜底。
     *
     * <p>仅在清理彻底失败时抛异常（与历史实现一致：宁可报错也不留下孤儿进程）。</p>
     */
    private void killTree(long pid, TaskRequest request) {
        Process current = process;
        ProcessHandle handle = current != null && current.pid() == pid
                ? current.toHandle()
                : ProcessHandle.of(pid).orElse(null);
        if (handle == null) {
            return;
        }

        List<ProcessHandle> descendants = handle.descendants().filter(ProcessHandle::isAlive).toList();
        for (ProcessHandle child : descendants) {
            terminate(child, "子进程");
        }

        if (handle.isAlive()) {
            terminate(handle, "主进程");
        }

        List<ProcessHandle> remaining = handle.descendants().filter(ProcessHandle::isAlive).toList();
        if (handle.isAlive() || !remaining.isEmpty()) {
            logger.error("[RUNNER] 进程清理失败: 主进程alive={}, 残留子孙={}", handle.isAlive(), remaining.size());
            throw new IllegalStateException("Process stop failed. it is still running: pid=" + pid);
        }
        logger.info("[RUNNER] 进程 PID={} 及其子进程已清理完毕", pid);
    }

    private void terminate(ProcessHandle handle, String what) {
        if (!handle.isAlive()) {
            return;
        }
        handle.destroy();
        if (waitForExit(handle, 5)) {
            return;
        }
        handle.destroyForcibly();
        if (waitForExit(handle, 2)) {
            return;
        }
        logger.warn("[RUNNER] {}优雅/强制终止均失败, PID={}, 走后端兜底清理", what, handle.pid());
        forceKillByPid(handle.pid());
    }

    private static boolean waitForExit(ProcessHandle handle, long seconds) {
        try {
            return handle.onExit().orTimeout(seconds, TimeUnit.SECONDS)
                    .thenApply(h -> true).exceptionally(e -> false).get();
        } catch (Exception e) {
            return false;
        }
    }

    private static void forceKillByPid(long pid) {
        try {
            List<String> command;
            if (WINDOWS) {
                command = List.of("taskkill", "/F", "/T", "/PID", String.valueOf(pid));
            } else {
                command = List.of("kill", "-9", String.valueOf(pid));
            }
            Process killer = new ProcessBuilder(command).start();
            killer.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Process stop failed. it is still running: pid=" + pid, e);
        }
    }

    /**
     * 命令行切词：按空白切分，引号内保留（引号本身去掉）。
     *
     * <p>仅用于 SHELL/COMMAND 类型的整条命令行。HOP 类型走参数数组，不经过这里，
     * 因此路径含空格也不会被切错。</p>
     */
    static List<String> splitCommandLine(String commandLine) {
        List<String> result = new ArrayList<>();
        if (commandLine == null || commandLine.isBlank()) {
            return result;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = '"';
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (c == '"' || c == '\'') {
                if (!inQuotes) {
                    inQuotes = true;
                    quoteChar = c;
                } else if (c == quoteChar) {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    /** 供测试：把命令数组渲染成可读字符串。 */
    static String describe(List<String> command) {
        return String.join(" ", command);
    }

    /** 供调用方判断日志文件是否可写（避免任务跑完才发现日志目录没权限）。 */
    static boolean isWritable(File dir) {
        return dir != null && dir.isDirectory() && dir.canWrite();
    }
}
