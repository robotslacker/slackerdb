package org.slackerdb.dbserver.scheduler.runner;

import org.slackerdb.dbcli.embed.DbcliRunner;
import org.slf4j.Logger;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code DBCLI} 任务执行器：在**调度器进程内**运行一个 dbcli 脚本。
 *
 * <p>执行本身交给 dbcli 的嵌入入口 {@link DbcliRunner}（与 {@code dbcli --execute <file>}
 * 同一条路径），本类只负责调度侧的事情：脚本定位、任务日志、超时/中止、退出码归一化。</p>
 *
 * <h2>连接</h2>
 * <p>脚本是"客户端"，所以它连回**本实例的数据服务端口**（{@code jdbc:slackerdb://host:port/db}），
 * 而不是共享服务端的内部连接：脚本里的会话级操作（事务、{@code _SESSION}、{@code _SET}）
 * 与一个真实客户端完全等价，也不会污染服务端连接池里的连接。</p>
 *
 * <p>连接串由宿主生成（{@link TaskRequest#dbcliConnect}），也可用
 * {@code plugin-scheduler.dbcli.connect} 覆盖。没有数据服务端口（{@code --port -1}）时，
 * DBCLI 任务会以一条可操作的错误失败。</p>
 *
 * <h2>退出码</h2>
 * <p>沿用 dbcli 语义：只有脚本里的显式 {@code _EXIT n} / {@code _WHENEVER ERROR EXIT n}
 * 会改变退出码；脚本内部发生过的 SQL 错误只计数（任务日志里会写一行提示），不改变退出码。
 * 要让调度任务因 SQL 错误而失败，就在脚本里写 {@code _WHENEVER ERROR EXIT 1}。</p>
 *
 * <h2>超时</h2>
 * <p>协作式停止：到点后置位停止信号（下一条语句不再执行、{@code _SLEEP}/{@code _HOST} 提前醒来）
 * 并取消正在执行的 SQL；给 {@link #STOP_GRACE_MILLIS} 毫秒收尾，仍不退出则按超时返回
 * （工作线程是 daemon，不会阻止 JVM 退出）。</p>
 */
public final class DbcliTaskExecutor {

    /** 到点后等待脚本自行收尾的宽限时间。 */
    static final long STOP_GRACE_MILLIS = 5_000L;

    /** 覆盖自动生成的连接串的配置键。 */
    public static final String CONNECT_KEY = "plugin-scheduler.dbcli.connect";

    private final Logger logger;

    /** 协作式停止信号（超时或调用方 {@link #cancel()} 时置位）。 */
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    /** dbcli 注册的"取消当前 SQL"回调；停止时调用。 */
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>();
    /** 调用方是否请求了中止（与"超时"区分开，落不同的退出码）。 */
    private final AtomicBoolean canceled = new AtomicBoolean(false);

    public DbcliTaskExecutor(Logger logger) {
        this.logger = logger;
    }

    /** 中止当前任务（与 SHELL/HOP 的"中止"同一语义）：协作式停止 + 取消正在执行的 SQL。 */
    public void cancel() {
        canceled.set(true);
        stopRequested.set(true);
        Runnable running = cancelAction.get();
        if (running != null) {
            running.run();
        }
    }

    /**
     * 执行一次 DBCLI 任务。
     *
     * @return 归一化结果；不会抛出业务异常（准备失败以 {@link TaskResult#EXIT_START_FAILED} 返回）
     */
    public TaskResult execute(TaskRequest request) {
        long start = System.currentTimeMillis();
        Path logFile = request.logFile;
        String logPath = logFile == null ? null : logFile.toAbsolutePath().toString();

        if (request.dbcliConnect == null || request.dbcliConnect.isBlank()) {
            String message = "DBCLI 任务需要一个可连接的数据服务端口：请启用 --port（或用 "
                    + CONNECT_KEY + " 指定连接串）";
            logger.error("[RUNNER] 任务[{}/{}] {}", request.runId, request.taskName, message);
            return TaskResult.failed(message, System.currentTimeMillis() - start, logPath);
        }

        Path script;
        try {
            script = resolveScript(request);
            if (!Files.isRegularFile(script)) {
                throw new IllegalStateException("DBCLI 脚本不存在: " + script);
            }
            prepareLogFile(logFile);
        } catch (Exception e) {
            logger.error("[RUNNER] 任务[{}/{}]准备 dbcli 脚本失败: {}",
                    request.runId, request.taskName, e.getMessage(), e);
            return TaskResult.failed(e.getMessage(), System.currentTimeMillis() - start, logPath);
        }

        try (PrintWriter log = logFile == null ? new PrintWriter(System.out, true)
                : new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND), true)) {
            log.printf("[DBCLI] connect=%s%n", request.dbcliConnect);
            log.printf("[DBCLI] script=%s%n", script);
            logger.info("[RUNNER] 任务[{}/{}] 启动 DBCLI: script={}, connect={}, timeout={}s",
                    request.runId, request.taskName, script, request.dbcliConnect,
                    request.timeoutSeconds > 0 ? request.timeoutSeconds : "无限制");
            TaskResult result = run(request, script, log, start, logPath);
            logger.info("[RUNNER] 任务[{}/{}] DBCLI 结束: {}",
                    request.runId, request.taskName, result.describe());
            return result;
        } catch (Exception e) {
            logger.error("[RUNNER] 任务[{}/{}] DBCLI 执行失败", request.runId, request.taskName, e);
            return TaskResult.failed(e.getMessage(), System.currentTimeMillis() - start, logPath);
        }
    }

    private TaskResult run(TaskRequest request, Path script, PrintWriter log, long start, String logPath)
            throws Exception {
        Map<String, String> variables = request.variables == null ? Map.of() : request.variables.asMap();
        AtomicInteger exitCode = new AtomicInteger(TaskResult.EXIT_START_FAILED);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicBoolean timedOut = new AtomicBoolean(false);

        Thread worker = new Thread(() -> {
            try {
                DbcliRunner.Result result = DbcliRunner.run(DbcliRunner.Request.builder()
                        .script(script)
                        .workingDirectory(script.getParent())
                        .logon(request.dbcliConnect)
                        .abortOnLogonFailure(true)
                        .variables(variables)
                        .out(log)
                        .err(log)
                        .stopRequested(stopRequested::get)
                        .onCancelReady(cancelAction::set)
                        .build());
                if (result.logonFailed()) {
                    failure.set("DBCLI 无法连接数据库: " + request.dbcliConnect
                            + "（数据服务端口是否可达？）");
                    exitCode.set(TaskResult.EXIT_START_FAILED);
                    return;
                }
                exitCode.set(result.exitCode());
                if (result.exitCode() == 0 && result.errorCount() > 0) {
                    // dbcli 语义：脚本内的错误不改退出码。这里只提示，避免"看起来全绿"
                    log.printf("[DBCLI] 脚本退出码 0，但记录到 %d 个错误（需要失败请在脚本里写 "
                            + "_WHENEVER ERROR EXIT n）%n", result.errorCount());
                    log.flush();
                }
            } catch (DbcliRunner.StoppedException stopped) {
                // 停止请求（超时/中止）：终态由调用方按超时或中止落地
                exitCode.set(1);
            } catch (DbcliRunner.DbcliException e) {
                failure.set(e.getMessage());
                exitCode.set(TaskResult.EXIT_START_FAILED);
            } catch (Exception e) {
                failure.set(e.getMessage() == null ? e.toString() : e.getMessage());
                exitCode.set(TaskResult.EXIT_START_FAILED);
            } finally {
                cancelAction.set(null);
            }
        }, "dbcli-task-" + request.runId + "-" + request.taskName);
        worker.setDaemon(true);
        worker.start();

        if (request.timeoutSeconds > 0) {
            worker.join(request.timeoutSeconds * 1000L);
            if (worker.isAlive()) {
                timedOut.set(true);
                cancel();   // 协作式停止 + 取消正在执行的 SQL
                worker.join(STOP_GRACE_MILLIS);
                if (worker.isAlive()) {
                    log.printf("[DBCLI] 超时 %.0fs 后脚本仍未退出（已请求停止，工作线程为 daemon）%n",
                            request.timeoutSeconds);
                    log.flush();
                }
            }
        } else {
            worker.join();
        }

        long duration = System.currentTimeMillis() - start;
        if (canceled.get()) {
            TaskResult result = TaskResult.of(TaskResult.EXIT_CANCELED, duration, logPath);
            result.canceled = true;
            result.errorMessage = "canceled";
            return result;
        }
        if (timedOut.get()) {
            TaskResult result = TaskResult.of(TaskResult.EXIT_TIMEOUT, duration, logPath);
            result.timedOut = true;
            result.errorMessage = "timeout after " + request.timeoutSeconds + "s";
            return result;
        }
        if (failure.get() != null) {
            return TaskResult.failed(failure.get(), duration, logPath);
        }
        return TaskResult.of(exitCode.get(), duration, logPath);
    }

    /**
     * 脚本路径：绝对路径直接用；相对路径与 HOP 同口径，落在本次 run 的
     * {@code <runHome>/flow/<projectType>}（即项目模板被复制过去的位置）。
     */
    static Path resolveScript(TaskRequest request) {
        String raw = request.script == null ? "" : request.script.trim();
        if (raw.isEmpty()) {
            throw new IllegalStateException("DBCLI 任务的 taskScript 为空");
        }
        Path path = Path.of(raw);
        if (path.isAbsolute()) {
            return path;
        }
        return HopCommandBuilder.projectFlowDir(request).resolve(raw).toAbsolutePath();
    }

    private static void prepareLogFile(Path logFile) throws Exception {
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
}
