package org.slackerdb.plugins.scheduler.quartz;

import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.slackerdb.plugins.scheduler.entity.TaskHistory;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;

/**
 * Quartz job listener for tracking execution lifecycle.
 *
 * <p>This listener captures job start/end events and persists task history
 * records via {@link SchedulerMeta}.</p>
 */
public class QuartzJobListener implements JobListener {

    private static final Logger logger = LoggerFactory.getLogger(QuartzJobListener.class);

    private final SchedulerMeta meta;

    public QuartzJobListener(SchedulerMeta meta) {
        this.meta = meta;
    }

    @Override
    public String getName() {
        return "QuartzJobListener";
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        JobDataMap jobData = context.getJobDetail().getJobDataMap();
        String runId = jobData.getString("runId");
        String taskName = jobData.getString("taskName");
        String logFile = jobData.getString("logFile");

        String jobKey = runId + ":" + taskName;

        // Record start time in status manager
        QuartzJobStatusManager.recordStart(jobKey, logFile);

        logger.info("[SCHEDULER] Job {}/{} started execution.", runId, taskName);
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        // Not used
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        JobDataMap jobData = context.getJobDetail().getJobDataMap();
        String runId = jobData.getString("runId");
        String taskName = jobData.getString("taskName");
        String jobKey = runId + ":" + taskName;

        int exitCode;
        String exitMsg;

        if (jobException == null) {
            // Check if job was skipped (parallel policy)
            if (jobData.containsKey("skipped") && jobData.getBoolean("skipped")) {
                logger.info("[SCHEDULER] Job {}/{} was skipped (serial discard).", runId, taskName);
                return;
            }

            exitCode = jobData.getInt("exitCode");
            exitMsg = jobData.getString("exitMsg");
            QuartzJobStatusManager.recordEnd(jobKey, exitCode, exitMsg);
            logger.info("[SCHEDULER] Job {}/{} completed with exit code {}.", runId, taskName, exitCode);
        } else {
            StringWriter sw = new StringWriter();
            jobException.printStackTrace(new PrintWriter(sw));
            exitMsg = sw.toString();
            exitCode = -1;
            QuartzJobStatusManager.recordEnd(jobKey, exitCode, exitMsg);
            logger.error("[SCHEDULER] Job {}/{} failed with exception.", runId, taskName, jobException);
        }

        // Persist task history record
        if (meta != null) {
            try {
                TaskHistory history = new TaskHistory();
                history.setTaskId(System.currentTimeMillis()); // Use timestamp as unique ID
                history.setRunId(runId);
                history.setTaskName(taskName);
                history.setRunIdRef(runId);
                history.setRetCode(exitCode);
                history.setRetMsg(exitMsg != null && exitMsg.length() > 1000
                        ? exitMsg.substring(0, 1000) : exitMsg);
                history.setStartTime(QuartzJobStatusManager.getStartTime(jobKey));
                history.setEndTime(LocalDateTime.now());
                history.setLogFile(jobData.getString("logFile"));
                meta.insertTaskHistory(history);
            } catch (Exception e) {
                logger.warn("[SCHEDULER] Failed to persist task history for {}/{}: {}",
                        runId, taskName, e.getMessage());
            }
        }
    }
}
