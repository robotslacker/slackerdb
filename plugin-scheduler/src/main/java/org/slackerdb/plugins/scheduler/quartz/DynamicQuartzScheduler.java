package org.slackerdb.plugins.scheduler.quartz;

import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slf4j.Logger;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Dynamic Quartz scheduler wrapper.
 *
 * <p>Manages Quartz scheduler lifecycle and provides methods to dynamically
 * create, update, and delete scheduled jobs for scheduler Runs.
 * Supports CRONTAB, INTERVAL, and RUN_ONCE policies with parallel/serial execution modes.</p>
 */
public class DynamicQuartzScheduler {

    private final Scheduler quartzScheduler;
    private final Logger logger;
    private final SchedulerMeta meta;

    public DynamicQuartzScheduler(Logger logger) throws SchedulerException {
        this.logger = logger;
        this.meta = null;
        this.quartzScheduler = createScheduler(10);
    }

    public DynamicQuartzScheduler(Logger logger, int threadCount) throws SchedulerException {
        this.logger = logger;
        this.meta = null;
        this.quartzScheduler = createScheduler(threadCount);
    }

    public DynamicQuartzScheduler(Logger logger, int threadCount, SchedulerMeta meta) throws SchedulerException {
        this.logger = logger;
        this.meta = meta;
        this.quartzScheduler = createScheduler(threadCount);
    }

    private Scheduler createScheduler(int threadCount) throws SchedulerException {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "SlackerDBScheduler");
        props.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", String.valueOf(threadCount));
        props.setProperty("org.quartz.threadPool.threadPriority", "5");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");

        StdSchedulerFactory factory = new StdSchedulerFactory(props);
        Scheduler scheduler = factory.getScheduler();

        // Add job listener for tracking execution lifecycle
        QuartzJobListener jobListener = new QuartzJobListener(meta);
        scheduler.getListenerManager().addJobListener(jobListener);

        logger.info("[SCHEDULER] Quartz scheduler initialized with {} threads.", threadCount);
        return scheduler;
    }

    /**
     * Start the Quartz scheduler.
     */
    public void start() throws SchedulerException {
        quartzScheduler.start();
        logger.info("[SCHEDULER] Quartz scheduler started.");
    }

    /**
     * Get the thread count of the scheduler's thread pool.
     */
    public int getThreadCount() {
        try {
            return quartzScheduler.getMetaData().getThreadPoolSize();
        } catch (SchedulerException e) {
            logger.warn("[SCHEDULER] Could not get thread count: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Shutdown the Quartz scheduler.
     */
    public void shutdown() {
        try {
            quartzScheduler.shutdown(true);
            logger.info("[SCHEDULER] Quartz scheduler shutdown.");
        } catch (SchedulerException e) {
            logger.error("[SCHEDULER] Error shutting down Quartz scheduler: {}", e.getMessage());
        }
    }

    /**
     * Schedule a job for a specific run with full task configuration.
     *
     * @param runId            the run ID (used as group name)
     * @param taskName         the task name (used as job key name)
     * @param scriptType       the script type (HOP, SHELL, COMMAND)
     * @param script           HOP: workflow file name relative to PROJECT_HOME; SHELL/COMMAND: command line
     * @param logFile          the log file path (relative to the run home is allowed)
     * @param taskRunPolicy    RUN_ONCE, CRONTAB, INTERVAL
     * @param taskFailPolicy   STOP, CONTINUE
     * @param taskInterval     interval in seconds (for INTERVAL policy)
     * @param taskParallelPolicy PARALLEL, SERIAL_DISCARD, SERIAL_DELAY, SERIAL_CATCHUP
     * @param taskCrontabExpr  cron expression (for CRONTAB policy)
     * @param taskTimeout      timeout in seconds (0 = no timeout)
     * @param taskGroup        task group name
     * @param taskStartupOrder startup order within group
     * @param configJson       additional JSON configuration
     * @param workDir          the run home (working directory for execution)
     * @param jobDataExtras    附加 JobData：projectType / variablesJson / hopRunScript / hopJavaHome /
     *                         hopProjectName / hopEnvironmentName / hopRunConfig（方案 A：HOP 走命令行）
     */
    public void scheduleJob(
            String runId,
            String taskName,
            String scriptType,
            String script,
            String logFile,
            String taskRunPolicy,
            String taskFailPolicy,
            int taskInterval,
            String taskParallelPolicy,
            String taskCrontabExpr,
            int taskTimeout,
            String taskGroup,
            int taskStartupOrder,
            String configJson,
            String workDir,
            java.util.Map<String, String> jobDataExtras
    ) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);

        // Build job detail with all task data
        JobBuilder jobBuilder = JobBuilder.newJob(QuartzJob.class)
                .withIdentity(jobKey)
                .storeDurably(true)
                .usingJobData("runId", runId)
                .usingJobData("taskName", taskName)
                .usingJobData("scriptType", scriptType)
                .usingJobData("script", script)
                .usingJobData("logFile", logFile)
                .usingJobData("taskRunPolicy", taskRunPolicy)
                .usingJobData("taskFailPolicy", taskFailPolicy)
                .usingJobData("taskInterval", taskInterval)
                .usingJobData("taskParallelPolicy", taskParallelPolicy)
                .usingJobData("taskCrontabExpr", taskCrontabExpr != null ? taskCrontabExpr : "")
                .usingJobData("taskTimeout", taskTimeout)
                .usingJobData("taskGroup", taskGroup != null ? taskGroup : "")
                .usingJobData("taskStartupOrder", taskStartupOrder)
                .usingJobData("configJson", configJson != null ? configJson : "")
                .usingJobData("workDir", workDir != null ? workDir : "");
        if (jobDataExtras != null) {
            for (java.util.Map.Entry<String, String> entry : jobDataExtras.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if ("taskRetryTimes".equals(entry.getKey()) || "taskRetryInterval".equals(entry.getKey())) {
                    continue;   // 数值型，统一在下面按 int 写入（JobData 的 getInt 需要数值类型）
                }
                jobBuilder.usingJobData(entry.getKey(), entry.getValue());
            }
            jobBuilder.usingJobData("taskRetryTimes", parseInt(jobDataExtras.get("taskRetryTimes"), 0));
            jobBuilder.usingJobData("taskRetryInterval", parseInt(jobDataExtras.get("taskRetryInterval"), 60));
        }
        JobDetail jobDetail = jobBuilder.build();

        // Add job to scheduler (replace if exists)
        quartzScheduler.addJob(jobDetail, true);

        // Pause job immediately to prevent auto-start
        quartzScheduler.pauseJob(jobKey);

        // Create trigger based on run policy
        Trigger trigger = null;

        switch (taskRunPolicy.toUpperCase()) {
            case "CRONTAB":
                trigger = createCronTrigger(runId, taskName, taskCrontabExpr, taskParallelPolicy);
                break;
            case "INTERVAL":
                trigger = createIntervalTrigger(runId, taskName, taskInterval);
                break;
            case "RUN_ONCE":
                // RUN_ONCE jobs are started manually via startJob(), no trigger needed here
                logger.info("[SCHEDULER] Added RUN_ONCE job {}/{} (will be started manually).", runId, taskName);
                break;
            default:
                logger.warn("[SCHEDULER] Unknown task run policy: {} for job {}/{}", taskRunPolicy, runId, taskName);
        }

        if (trigger != null) {
            // Remove old trigger if exists
            TriggerKey oldTriggerKey = trigger.getKey();
            if (quartzScheduler.checkExists(oldTriggerKey)) {
                quartzScheduler.unscheduleJob(oldTriggerKey);
            }
            quartzScheduler.scheduleJob(trigger);
            // Re-pause after scheduling trigger
            quartzScheduler.pauseJob(jobKey);
        }

        logger.info("[SCHEDULER] Scheduled job {}/{} with policy={}, parallel={}",
                runId, taskName, taskRunPolicy, taskParallelPolicy);
    }

    /**
     * Create a cron trigger with misfire handling based on parallel policy.
     *
     * <p>触发器必须用 {@code forJob} 绑定到作业，否则 {@code scheduleJob(trigger)} 会抛
     * {@code Trigger's related Job's name cannot be null} —— 历史实现漏了这一句，
     * 导致 CRONTAB/INTERVAL 任务在编排阶段就直接失败。</p>
     */
    private CronTrigger createCronTrigger(String runId, String taskName,
                                           String cronExpression, String parallelPolicy) {
        CronScheduleBuilder scheduleBuilder = CronScheduleBuilder.cronSchedule(cronExpression);

        switch (parallelPolicy.toUpperCase()) {
            case "SERIAL_DISCARD":
                // If previous execution is still running, skip this trigger
                scheduleBuilder = scheduleBuilder.withMisfireHandlingInstructionDoNothing();
                break;
            case "SERIAL_DELAY":
                // Wait for previous to finish, then fire immediately
                scheduleBuilder = scheduleBuilder.withMisfireHandlingInstructionFireAndProceed();
                break;
            case "SERIAL_CATCHUP":
                // Catch up on missed executions
                scheduleBuilder = scheduleBuilder.withMisfireHandlingInstructionIgnoreMisfires();
                break;
            case "PARALLEL":
            default:
                // Default behavior - allow concurrent executions
                break;
        }

        return TriggerBuilder.newTrigger()
                .withIdentity(taskName + "_trigger", runId)
                .forJob(new JobKey(taskName, runId))
                .withSchedule(scheduleBuilder)
                .build();
    }

    /** Create a simple interval trigger. */
    private SimpleTrigger createIntervalTrigger(String runId, String taskName, int intervalSeconds) {
        return TriggerBuilder.newTrigger()
                .withIdentity(taskName + "_trigger", runId)
                .forJob(new JobKey(taskName, runId))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .repeatForever()
                        .withIntervalInSeconds(intervalSeconds))
                .build();
    }

    /**
     * 启动/激活一个已编排的任务。
     *
     * <p><b>按策略分支</b>（修正历史实现的缺陷：以前只处理 RUN_ONCE 与 CRONTAB，INTERVAL 永远
     * 停留在暂停态；且 RUN_ONCE 用 {@code addJob(replace=true)} 覆盖定义，会连带删掉周期触发器）：</p>
     * <ul>
     *   <li>{@code CRONTAB} / {@code INTERVAL}：{@code resumeJob} —— 恢复其周期触发器；</li>
     *   <li>{@code RUN_ONCE}：{@code triggerJob} —— 只触发一次，<b>不动</b>任务定义与触发器。</li>
     * </ul>
     */
    public void startJob(String runId, String taskName, String taskRunPolicy) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);
        if (!quartzScheduler.checkExists(jobKey)) {
            logger.warn("[SCHEDULER] 任务 {}/{} 尚未编排，无法启动", runId, taskName);
            return;
        }
        String policy = taskRunPolicy == null ? "" : taskRunPolicy.trim().toUpperCase();
        if ("RUN_ONCE".equals(policy)) {
            quartzScheduler.triggerJob(jobKey);
            logger.info("[SCHEDULER] 一次性触发任务 {}/{}", runId, taskName);
        } else {
            quartzScheduler.resumeJob(jobKey);
            logger.info("[SCHEDULER] 恢复周期任务 {}/{} (policy={})", runId, taskName, policy);
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 延迟触发的 JobKey 里包含的标记（用于区分"任务本体"与"延迟重试/排队"实例）。
     */
    public static final String DEFERRED_MARK = "#defer#";

    /**
     * 安排一次延迟触发（RETRY 重试 / SERIAL_DELAY 排队用）。
     *
     * <p>为什么不用 {@code Thread.sleep} 等待：历史实现是在 Quartz 工作线程里 sleep 轮询，
     * 10 个排队任务就能把线程池占满，导致全局调度停摆。这里改为"登记一个延迟触发器后立即返回"，
     * 工作线程立刻释放；延迟实例执行时会重新做并发判定，因此仍然保证串行。</p>
     *
     * <p>同一任务最多存在一个延迟实例（等价于"队列深度 1"），已在排队时返回 false。</p>
     *
     * @param retryAttempt 重试序号（写回 JobData，供下次判定用尽次数）；非重试场景传 -1
     * @return 是否成功登记
     */
    public static boolean scheduleDeferred(org.quartz.Scheduler scheduler, String runId, String taskName,
                                           org.quartz.JobDataMap sourceData, long delayMillis,
                                           String reason, int retryAttempt, Logger logger) {
        try {
            JobKey sourceKey = new JobKey(taskName, runId);
            if (!scheduler.checkExists(sourceKey)) {
                logger.warn("[SCHEDULER] 延迟触发失败：任务 {}/{} 未编排", runId, taskName);
                return false;
            }
            for (JobKey existing : scheduler.getJobKeys(GroupMatcher.groupEquals(runId))) {
                if (existing.getName().startsWith(taskName + DEFERRED_MARK)) {
                    logger.info("[SCHEDULER] 任务 {}/{} 已有延迟实例（{}），本次不再登记", runId, taskName, reason);
                    return false;
                }
            }

            JobDataMap data = new JobDataMap(sourceData);
            data.put("deferredReason", reason == null ? "" : reason);
            if (retryAttempt >= 0) {
                data.put("retryAttempt", retryAttempt);
            }

            JobKey deferredKey = new JobKey(taskName + DEFERRED_MARK + System.nanoTime(), runId);
            JobDetail detail = JobBuilder.newJob(QuartzJob.class)
                    .withIdentity(deferredKey)
                    .usingJobData(data)
                    .build();
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(deferredKey.getName() + "_trigger", runId)
                    .forJob(deferredKey)
                    .startAt(new java.util.Date(System.currentTimeMillis() + Math.max(delayMillis, 0)))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule().withRepeatCount(0))
                    .build();
            scheduler.scheduleJob(detail, trigger);
            logger.info("[SCHEDULER] 任务 {}/{} 已登记延迟触发：{}，延迟 {}ms", runId, taskName, reason, delayMillis);
            return true;
        } catch (SchedulerException e) {
            logger.error("[SCHEDULER] 登记延迟触发失败 {}/{}: {}", runId, taskName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 暂停一个 run 下的全部任务（保留定义与触发器，可再次 start）。
     */
    public void pauseAllJobs(String runId) throws SchedulerException {
        Set<JobKey> jobKeys = quartzScheduler.getJobKeys(GroupMatcher.groupEquals(runId));
        for (JobKey jobKey : jobKeys) {
            quartzScheduler.pauseJob(jobKey);
        }
        logger.info("[SCHEDULER] 已暂停 run {} 下的 {} 个任务", runId, jobKeys.size());
    }

    /** 该任务是否有待执行的延迟实例（重试/排队）。 */
    public boolean hasDeferred(String runId, String taskName) throws SchedulerException {
        for (JobKey jobKey : quartzScheduler.getJobKeys(GroupMatcher.groupEquals(runId))) {
            if (jobKey.getName().startsWith(taskName + DEFERRED_MARK)) {
                return true;
            }
        }
        return false;
    }

    /** 暂停任务（保留定义与触发器，可再次 start）。 */
    public void pauseJob(String runId, String taskName) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);
        if (quartzScheduler.checkExists(jobKey)) {
            quartzScheduler.pauseJob(jobKey);
            logger.info("[SCHEDULER] 任务 {}/{} 已暂停", runId, taskName);
        }
    }

    /** 触发一个一次性执行并等待其结束（编排器按组顺序推进时使用）。 */
    public boolean triggerAndWait(String runId, String taskName, long timeoutMillis) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);
        if (!quartzScheduler.checkExists(jobKey)) {
            return false;
        }
        quartzScheduler.triggerJob(jobKey);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        boolean observed = false;
        while (System.currentTimeMillis() < deadline) {
            boolean executing = false;
            for (JobExecutionContext context : quartzScheduler.getCurrentlyExecutingJobs()) {
                if (context.getJobDetail().getKey().equals(jobKey)) {
                    executing = true;
                    break;
                }
            }
            if (executing) {
                observed = true;
            } else if (observed) {
                return true;    // 已观察到执行且已结束
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logger.warn("[SCHEDULER] 等待任务 {}/{} 结束超时({}ms)", runId, taskName, timeoutMillis);
        return false;
    }

    /**
     * Stop (pause) a specific job.
     */
    public void stopJob(String runId, String taskName) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);
        if (quartzScheduler.checkExists(jobKey)) {
            quartzScheduler.pauseJob(jobKey);
            logger.info("[SCHEDULER] Paused job {}/{}", runId, taskName);
        }
    }

    /**
     * Stop a job and optionally wait for it to finish.
     */
    public void stopJob(String runId, String taskName, boolean wait) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);
        if (quartzScheduler.checkExists(jobKey)) {
            quartzScheduler.pauseJob(jobKey);
            logger.info("[SCHEDULER] Paused job {}/{}", runId, taskName);

            if (wait) {
                // Wait for currently executing job to finish
                while (true) {
                    boolean anyRunning = false;
                    List<JobExecutionContext> currentlyExecuting = quartzScheduler.getCurrentlyExecutingJobs();
                    for (JobExecutionContext ctx : currentlyExecuting) {
                        if (ctx.getJobDetail().getKey().equals(jobKey)) {
                            anyRunning = true;
                            break;
                        }
                    }
                    if (!anyRunning) {
                        break;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Abort (interrupt) a specific job.
     */
    public void abortJob(String runId, String taskName) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);
        if (quartzScheduler.checkExists(jobKey)) {
            quartzScheduler.interrupt(jobKey);
            quartzScheduler.pauseJob(jobKey);
            logger.info("[SCHEDULER] Aborted job {}/{}", runId, taskName);
        }
    }

    /**
     * Unschedule (delete) a specific job.
     */
    public void unscheduleJob(String runId, String taskName) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);
        if (quartzScheduler.checkExists(jobKey)) {
            quartzScheduler.deleteJob(jobKey);
            logger.info("[SCHEDULER] Unscheduled job {}/{}", runId, taskName);
        }
    }

    /**
     * Unschedule all jobs for a specific run.
     */
    public void unscheduleAllJobs(String runId) throws SchedulerException {
        Set<JobKey> jobKeys = quartzScheduler.getJobKeys(GroupMatcher.groupEquals(runId));
        for (JobKey jobKey : jobKeys) {
            quartzScheduler.deleteJob(jobKey);
        }
        logger.info("[SCHEDULER] Unscheduled all jobs for run {}", runId);
    }

    /**
     * Abort all jobs for a specific run.
     */
    public void abortAllJobs(String runId) throws SchedulerException {
        Set<JobKey> jobKeys = quartzScheduler.getJobKeys(GroupMatcher.groupEquals(runId));
        for (JobKey jobKey : jobKeys) {
            quartzScheduler.interrupt(jobKey);
            quartzScheduler.pauseJob(jobKey);
        }
        logger.info("[SCHEDULER] Aborted all jobs for run {}", runId);
    }

    /**
     * Start all jobs for a specific run.
     */
    public void startAllJobs(String runId) throws SchedulerException {
        Set<JobKey> jobKeys = quartzScheduler.getJobKeys(GroupMatcher.groupEquals(runId));
        for (JobKey jobKey : jobKeys) {
            quartzScheduler.resumeJob(jobKey);
        }
        logger.info("[SCHEDULER] Started all jobs for run {}", runId);
    }

    /**
     * Check if any job in a run is currently executing.
     */
    public boolean isRunActive(String runId) throws SchedulerException {
        List<JobExecutionContext> currentlyExecuting = quartzScheduler.getCurrentlyExecutingJobs();
        for (JobExecutionContext ctx : currentlyExecuting) {
            if (ctx.getJobDetail().getKey().getGroup().equals(runId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all scheduled jobs for a run with their status.
     */
    public List<Map<String, Object>> getJobsForRun(String runId) throws SchedulerException {
        List<Map<String, Object>> jobs = new ArrayList<>();
        Set<JobKey> jobKeys = quartzScheduler.getJobKeys(GroupMatcher.groupEquals(runId));

        for (JobKey jobKey : jobKeys) {
            if (jobKey.getName().contains(DEFERRED_MARK)) {
                continue;   // 延迟实例不进任务列表
            }
            JobDetail jobDetail = quartzScheduler.getJobDetail(jobKey);
            List<? extends Trigger> triggers = quartzScheduler.getTriggersOfJob(jobKey);
            JobDataMap jobData = jobDetail.getJobDataMap();

            Map<String, Object> jobInfo = new HashMap<>();
            jobInfo.put("taskName", jobData.getString("taskName"));
            jobInfo.put("scriptType", jobData.getString("scriptType"));
            jobInfo.put("script", jobData.getString("script"));
            jobInfo.put("taskRunPolicy", jobData.getString("taskRunPolicy"));
            jobInfo.put("taskFailPolicy", jobData.getString("taskFailPolicy"));
            jobInfo.put("taskParallelPolicy", jobData.getString("taskParallelPolicy"));
            jobInfo.put("taskCrontabExpr", jobData.getString("taskCrontabExpr"));
            jobInfo.put("taskInterval", jobData.getInt("taskInterval"));
            jobInfo.put("taskTimeout", jobData.getInt("taskTimeout"));
            jobInfo.put("taskGroup", jobData.getString("taskGroup"));
            jobInfo.put("taskStartupOrder", jobData.getInt("taskStartupOrder"));

            // Check if currently executing
            boolean isRunning = false;
            List<JobExecutionContext> executingJobs = quartzScheduler.getCurrentlyExecutingJobs();
            for (JobExecutionContext ctx : executingJobs) {
                if (ctx.getJobDetail().getKey().equals(jobKey)) {
                    isRunning = true;
                    break;
                }
            }
            jobInfo.put("running", isRunning);

            // Get trigger state and next fire time
            if (!triggers.isEmpty()) {
                Trigger trigger = triggers.get(0);
                Trigger.TriggerState state = quartzScheduler.getTriggerState(trigger.getKey());
                jobInfo.put("state", state.toString());

                if (trigger.getNextFireTime() != null) {
                    jobInfo.put("nextFireTime", DateTimeFormatter
                            .ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(ZoneId.systemDefault())
                            .format(trigger.getNextFireTime().toInstant()));
                }
                if (trigger.getPreviousFireTime() != null) {
                    jobInfo.put("previousFireTime", DateTimeFormatter
                            .ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(ZoneId.systemDefault())
                            .format(trigger.getPreviousFireTime().toInstant()));
                }
            } else {
                jobInfo.put("state", "NONE");
            }

            jobs.add(jobInfo);
        }

        return jobs;
    }

    /**
     * Check if a specific job is scheduled.
     */
    public boolean isJobScheduled(String runId, String taskName) throws SchedulerException {
        return quartzScheduler.checkExists(new JobKey(taskName, runId));
    }

    /**
     * Check if a specific job is currently running.
     */
    public boolean isJobRunning(String runId, String taskName) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);
        List<JobExecutionContext> executingJobs = quartzScheduler.getCurrentlyExecutingJobs();
        for (JobExecutionContext ctx : executingJobs) {
            if (ctx.getJobDetail().getKey().equals(jobKey)) {
                return true;
            }
        }
        return false;
    }
}
