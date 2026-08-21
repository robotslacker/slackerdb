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
     * @param scriptType       the script type (SQL, HOP, SHELL)
     * @param script           the script path or content
     * @param logFile          the log file path
     * @param taskRunPolicy    RUN_ONCE, CRONTAB, INTERVAL
     * @param taskFailPolicy   STOP, CONTINUE, RETRY
     * @param taskInterval     interval in seconds (for INTERVAL policy)
     * @param taskParallelPolicy PARALLEL, SERIAL_DISCARD, SERIAL_DELAY, SERIAL_CATCHUP
     * @param taskCrontabExpr  cron expression (for CRONTAB policy)
     * @param taskTimeout      timeout in seconds (0 = no timeout)
     * @param taskGroup        task group name
     * @param taskStartupOrder startup order within group
     * @param configJson       additional JSON configuration
     * @param workDir          working directory for execution
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
            String workDir
    ) throws SchedulerException {
        scheduleJob(runId, taskName, scriptType, script, logFile,
                taskRunPolicy, taskFailPolicy, taskInterval,
                taskParallelPolicy, taskCrontabExpr, taskTimeout,
                taskGroup, taskStartupOrder, configJson, workDir,
                null, null);
    }

    /**
     * Schedule a job for a specific run with full task configuration,
     * including Hop environment settings.
     *
     * @param runId            the run ID (used as group name)
     * @param taskName         the task name (used as job key name)
     * @param scriptType       the script type (SQL, HOP, SHELL)
     * @param script           the script path or content
     * @param logFile          the log file path
     * @param taskRunPolicy    RUN_ONCE, CRONTAB, INTERVAL
     * @param taskFailPolicy   STOP, CONTINUE, RETRY
     * @param taskInterval     interval in seconds (for INTERVAL policy)
     * @param taskParallelPolicy PARALLEL, SERIAL_DISCARD, SERIAL_DELAY, SERIAL_CATCHUP
     * @param taskCrontabExpr  cron expression (for CRONTAB policy)
     * @param taskTimeout      timeout in seconds (0 = no timeout)
     * @param taskGroup        task group name
     * @param taskStartupOrder startup order within group
     * @param configJson       additional JSON configuration
     * @param workDir          working directory for execution
     * @param hopWorkDir       Hop 工作目录（用于嵌入式 HopService，null 则使用外部命令）
     * @param hopPluginDir     Hop 插件目录
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
            String hopWorkDir,
            String hopPluginDir
    ) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);

        // Build job detail with all task data
        JobDetail jobDetail = JobBuilder.newJob(QuartzJob.class)
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
                .usingJobData("workDir", workDir != null ? workDir : "")
                .usingJobData("hopWorkDir", hopWorkDir != null ? hopWorkDir : "")
                .usingJobData("hopPluginDir", hopPluginDir != null ? hopPluginDir : "")
                .build();

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
                .withSchedule(scheduleBuilder)
                .build();
    }

    /**
     * Create a simple interval trigger.
     */
    private SimpleTrigger createIntervalTrigger(String runId, String taskName, int intervalSeconds) {
        return TriggerBuilder.newTrigger()
                .withIdentity(taskName + "_trigger", runId)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .repeatForever()
                        .withIntervalInSeconds(intervalSeconds))
                .build();
    }

    /**
     * Start a specific job (resume from paused state).
     * For RUN_ONCE jobs, this also binds a one-time trigger.
     */
    public void startJob(String runId, String taskName) throws SchedulerException {
        JobKey jobKey = new JobKey(taskName, runId);

        if (!quartzScheduler.checkExists(jobKey)) {
            logger.warn("[SCHEDULER] Cannot start job {}/{} - not found.", runId, taskName);
            return;
        }

        JobDetail jobDetail = quartzScheduler.getJobDetail(jobKey);
        JobDataMap jobData = jobDetail.getJobDataMap();
        String taskRunPolicy = jobData.getString("taskRunPolicy");

        if ("RUN_ONCE".equalsIgnoreCase(taskRunPolicy)) {
            // For RUN_ONCE, create a one-time trigger that fires immediately
            Trigger onceTrigger = TriggerBuilder.newTrigger()
                    .withIdentity(taskName + "_trigger-once", runId)
                    .forJob(jobKey)
                    .startNow()
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule().withRepeatCount(0))
                    .build();

            TriggerKey oldTriggerKey = onceTrigger.getKey();
            if (quartzScheduler.checkExists(oldTriggerKey)) {
                quartzScheduler.unscheduleJob(oldTriggerKey);
            }
            quartzScheduler.scheduleJob(onceTrigger);
            logger.info("[SCHEDULER] Started RUN_ONCE job {}/{}", runId, taskName);
        } else {
            // For CRONTAB/INTERVAL, resume the paused job
            quartzScheduler.resumeJob(jobKey);
            logger.info("[SCHEDULER] Resumed job {}/{}", runId, taskName);
        }
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
