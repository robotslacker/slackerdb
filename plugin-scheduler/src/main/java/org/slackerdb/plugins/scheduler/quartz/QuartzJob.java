package org.slackerdb.plugins.scheduler.quartz;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.quartz.InterruptableJob;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.slackerdb.plugins.scheduler.runner.ProcessTaskExecutor;
import org.slackerdb.plugins.scheduler.runner.TaskRequest;
import org.slackerdb.plugins.scheduler.runner.TaskResult;
import org.slackerdb.plugins.scheduler.runner.VariableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 调度任务执行体：由 Quartz 触发，负责并发策略、变量装配，并把实际执行交给
 * {@link ProcessTaskExecutor}（方案 A：HOP 走 hop-run 命令行，SHELL/COMMAND 直接跑命令行）。
 *
 * <p>退出码来自 {@link TaskResult}，写入 JobData 供 {@link QuartzJobListener} 落历史。</p>
 */
public class QuartzJob implements InterruptableJob {

    private static final Logger logger = LoggerFactory.getLogger(QuartzJob.class);

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** SERIAL_DELAY/SERIAL_CATCHUP 排队时的延迟实例间隔（毫秒）。 */
    private static final long DEFER_POLL_MILLIS = 10_000L;

    /** 当前执行器（{@link #interrupt()} 可能来自其它线程）。 */
    private volatile ProcessTaskExecutor executor;
    private volatile boolean interrupted = false;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap jobData = context.getJobDetail().getJobDataMap();

        String runId = jobData.getString("runId");
        String taskName = jobData.getString("taskName");
        String projectType = jobData.getString("projectType");
        String scriptType = jobData.getString("scriptType");
        String script = jobData.getString("script");
        String logFile = jobData.getString("logFile");
        String taskParallelPolicy = jobData.getString("taskParallelPolicy");
        String taskFailPolicy = jobData.getString("taskFailPolicy");
        int taskTimeout = jobData.getInt("taskTimeout");
        String workDir = jobData.getString("workDir");
        String variablesJson = jobData.getString("variablesJson");

        String jobKey = runId + ":" + taskName;

        // ---------- 并发策略 ----------
        // SERIAL_DELAY / SERIAL_CATCHUP：上一次未结束时**登记一个延迟实例后立即返回**，
        // 不再像历史实现那样在工作线程里 sleep 轮询（10 个排队任务就能占满线程池导致全局停摆）。
        if (QuartzJobStatusManager.isRunning(jobKey)
                && ("SERIAL_DELAY".equalsIgnoreCase(taskParallelPolicy)
                || "SERIAL_CATCHUP".equalsIgnoreCase(taskParallelPolicy))) {
            writeLog(resolveLogFile(logFile, workDir), String.format(
                    "任务[%s:%s]已有实例运行，登记延迟实例排队等待（策略=%s）", runId, taskName, taskParallelPolicy));
            boolean deferred = DynamicQuartzScheduler.scheduleDeferred(
                    context.getScheduler(), runId, taskName, jobData,
                    DEFER_POLL_MILLIS, "queue(" + taskParallelPolicy + ")", -1, logger);
            if (!deferred) {
                writeLog(resolveLogFile(logFile, workDir), String.format(
                        "任务[%s:%s]已有延迟实例，本次放弃（队列深度 1）", runId, taskName));
            }
            markSkipped(jobData, "已有实例运行，本次以延迟实例排队");
            return;
        }
        if (QuartzJobStatusManager.isRunning(jobKey) && "SERIAL_DISCARD".equalsIgnoreCase(taskParallelPolicy)) {
            writeLog(resolveLogFile(logFile, workDir), String.format(
                    "任务[%s:%s]已经到达启动条件，但上次运行未结束，本次已放弃.", runId, taskName));
            logger.info("[SCHEDULER] 任务[{}/{}] 跳过（SERIAL_DISCARD）", runId, taskName);
            markSkipped(jobData, "任务已经到达启动条件，但上次运行未结束，本次已放弃.");
            return;
        }

        // ---------- 执行 ----------
        Path runHome = (workDir == null || workDir.isBlank()) ? Path.of(".") : Path.of(workDir);
        Path logPath = resolveLogFile(logFile, workDir);
        String taskBeginTime = LocalDateTime.now().format(TIME_FORMAT);
        // 落历史时用绝对路径（历史里存相对路径会导致查看日志时找不到文件）
        jobData.put("logFileResolved", logPath.toString());

        TaskRequest request = buildRequest(context, runHome, logPath, taskBeginTime);
        executor = new ProcessTaskExecutor(logger);

        logger.info("[SCHEDULER] 执行任务[{}/{}]: type={}, script={}, timeout={}s, log={}",
                runId, taskName, scriptType, script, taskTimeout, logPath);

        TaskResult result;
        try {
            result = executor.execute(request);
        } catch (Exception e) {
            logger.error("[SCHEDULER] 任务[{}/{}] 执行异常", runId, taskName, e);
            result = TaskResult.failed(e.getMessage(), 0L, logPath.toAbsolutePath().toString());
        } finally {
            executor = null;
            QuartzJobStatusManager.unmarkQueued(jobKey);
        }

        jobData.put("exitCode", result.exitCode);
        jobData.put("exitMsg", result.errorMessage == null ? "" : result.errorMessage);
        jobData.put("elapsed", result.durationMs);

        logger.info("[SCHEDULER] 任务[{}/{}] 完成: {}", runId, taskName, result.describe());

        // ---------- 失败策略 ----------
        if (!result.isSuccess()) {
            if ("STOP".equalsIgnoreCase(taskFailPolicy)) {
                logger.info("[SCHEDULER] 任务[{}/{}] 失败({}), 按 STOP 策略暂停该作业",
                        runId, taskName, result.exitCode);
                pauseSelf(context, runId, taskName);
            } else if ("RETRY".equalsIgnoreCase(taskFailPolicy)) {
                handleRetry(context, jobData, runId, taskName, result);
            }
        }
    }

    /**
     * RETRY 策略：失败后按间隔登记延迟实例重试，最多 {@code taskRetryTimes} 次。
     *
     * <p>重试次数用尽后暂停该任务（等价于 STOP）—— 避免坏任务在 cron 上无限触发。</p>
     */
    private void handleRetry(JobExecutionContext context, JobDataMap jobData, String runId,
                             String taskName, TaskResult result) {
        int maxRetries = intValue(jobData, "taskRetryTimes", 0);
        int interval = Math.max(intValue(jobData, "taskRetryInterval", 60), 1);
        int attempt = intValue(jobData, "retryAttempt", 0);
        if (maxRetries <= 0) {
            logger.warn("[SCHEDULER] 任务[{}/{}] 使用 RETRY 策略但 taskRetryTimes<=0，按 CONTINUE 处理",
                    runId, taskName);
            return;
        }
        if (attempt >= maxRetries) {
            logger.error("[SCHEDULER] 任务[{}/{}] 重试 {} 次仍失败({}), 暂停该作业",
                    runId, taskName, attempt, result.exitCode);
            writeLog(resolveLogFile(jobData.getString("logFile"), jobData.getString("workDir")),
                    String.format("重试 %d 次仍失败(exitCode=%d)，已暂停该任务", attempt, result.exitCode));
            pauseSelf(context, runId, taskName);
            return;
        }
        logger.warn("[SCHEDULER] 任务[{}/{}] 失败({}), 按 RETRY 策略安排第 {} 次重试，间隔 {}s",
                runId, taskName, result.exitCode, attempt + 1, interval);
        DynamicQuartzScheduler.scheduleDeferred(context.getScheduler(), runId, taskName, jobData,
                interval * 1000L, "retry#" + (attempt + 1), attempt + 1, logger);
    }

    /**
     * 暂停"任务本体"。
     *
     * <p>注意：延迟实例（重试/排队）的 JobKey 与任务本体不同，因此这里显式用
     * {@code (taskName, runId)} 定位，否则会去暂停一个马上就要结束的延迟实例，任务本体仍在周期触发。</p>
     */
    private void pauseSelf(JobExecutionContext context, String runId, String taskName) {
        JobKey taskKey = new JobKey(taskName, runId);
        try {
            if (context.getScheduler().checkExists(taskKey)) {
                context.getScheduler().pauseJob(taskKey);
                logger.info("[SCHEDULER] 已暂停任务 {}/{}", runId, taskName);
            }
        } catch (SchedulerException e) {
            logger.error("[SCHEDULER] 暂停任务[{}/{}]失败: {}", runId, taskName, e.getMessage());
        }
    }

    @Override
    public void interrupt() {
        interrupted = true;
        ProcessTaskExecutor current = executor;
        if (current != null) {
            logger.info("[SCHEDULER] 收到中止请求，清理任务进程");
            current.cancel();
        }
    }

    // ------------------------------------------------------------------

    /** 装配执行请求：框架变量 + 三层业务变量（业务变量随 JobData 下发）。 */
    private TaskRequest buildRequest(JobExecutionContext context, Path runHome, Path logPath, String taskBeginTime) {
        JobDataMap jobData = context.getJobDetail().getJobDataMap();
        String runId = jobData.getString("runId");
        String taskName = jobData.getString("taskName");
        String projectType = jobData.getString("projectType");

        java.util.Map<String, String> businessVars = new java.util.LinkedHashMap<>();
        String variablesJson = jobData.getString("variablesJson");
        if (variablesJson != null && !variablesJson.isBlank()) {
            try {
                JSONObject json = JSON.parseObject(variablesJson);
                for (String key : json.keySet()) {
                    Object value = json.get(key);
                    businessVars.put(key, value == null ? "" : String.valueOf(value));
                }
            } catch (Exception e) {
                logger.warn("[SCHEDULER] 任务变量解析失败，忽略: {}", e.getMessage());
            }
        }

        java.util.Map<String, String> frameworkVars = new java.util.LinkedHashMap<>();
        frameworkVars.put(VariableSet.RUN_HOME, runHome.toAbsolutePath().toString());
        frameworkVars.put(VariableSet.RUN_ID, runId == null ? "" : runId);
        frameworkVars.put(VariableSet.PROJECT_TYPE, projectType == null ? "" : projectType);
        frameworkVars.put(VariableSet.TASK_NAME, taskName == null ? "" : taskName);
        frameworkVars.put(VariableSet.PROJECT_HOME, runHome.resolve("flow")
                .resolve(projectType == null ? "" : projectType).toAbsolutePath().toString());
        frameworkVars.put(VariableSet.LOG_DIR, runHome.resolve("logs").toAbsolutePath().toString());
        frameworkVars.put(VariableSet.AUDIT_DIR, runHome.resolve("audit").toAbsolutePath().toString());
        frameworkVars.put(VariableSet.TASK_BEGIN_TIME, taskBeginTime);

        TaskRequest request = new TaskRequest();
        request.runId = runId;
        request.projectType = projectType;
        request.taskName = taskName;
        request.scriptType = jobData.getString("scriptType");
        request.script = jobData.getString("script");
        request.runHome = runHome;
        request.workDir = runHome;
        request.logFile = logPath;
        request.timeoutSeconds = jobData.getInt("taskTimeout");
        request.variables = VariableSet.resolve(businessVars, frameworkVars);

        TaskRequest.HopConfig hop = new TaskRequest.HopConfig();
        hop.runScript = jobData.getString("hopRunScript");
        hop.javaHome = jobData.getString("hopJavaHome");
        hop.projectName = jobData.getString("hopProjectName");
        hop.environmentName = jobData.getString("hopEnvironmentName");
        String runConfig = jobData.getString("hopRunConfig");
        if (runConfig != null && !runConfig.isBlank()) {
            hop.runConfig = runConfig;
        }
        request.hop = hop;
        return request;
    }

    /**
     * 防御性读取 int 型 JobData。
     *
     * <p>Quartz 的 {@code JobDataMap.getInt} 遇到<b>缺失键</b>会抛 {@code ClassCastException}
     * 而不是返回 0，且延迟实例可能带着字符串型数值，因此统一走这里。</p>
     */
    private static int intValue(JobDataMap jobData, String key, int fallback) {
        Object value = jobData.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void markSkipped(JobDataMap jobData, String message) {        jobData.put("exitCode", TaskResult.EXIT_OK);
        jobData.put("exitMsg", message);
        jobData.put("skipped", true);
    }

    /** 日志文件：相对路径按实例根目录解析（历史实现存的是相对路径，这里统一成绝对路径）。 */
    private static Path resolveLogFile(String logFile, String workDir) {
        Path base = (workDir == null || workDir.isBlank()) ? Path.of(".") : Path.of(workDir);
        if (logFile == null || logFile.isBlank()) {
            return base.resolve("logs").resolve("task.log").toAbsolutePath();
        }
        Path path = Path.of(logFile);
        return path.isAbsolute() ? path : base.resolve(path).toAbsolutePath();
    }

    private static void writeLog(Path logFile, String message) {
        if (logFile == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.getParent());
            try (BufferedWriter writer = new BufferedWriter(
                    new FileWriter(logFile.toFile(), StandardCharsets.UTF_8, true))) {
                writer.write(String.format("[%s] %s", LocalDateTime.now().format(TIME_FORMAT), message));
                writer.newLine();
            }
        } catch (IOException e) {
            logger.warn("[SCHEDULER] 写入日志文件失败 {}: {}", logFile, e.getMessage());
        }
    }
}
