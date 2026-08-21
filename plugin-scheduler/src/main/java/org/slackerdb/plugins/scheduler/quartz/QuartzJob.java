package org.slackerdb.plugins.scheduler.quartz;

import org.quartz.*;
import org.slackerdb.plugins.scheduler.runner.SingleProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Quartz job implementation for executing scheduler tasks.
 *
 * <p>This job is triggered by Quartz and delegates the actual script execution
 * to {@link SingleProcessRunner}. It supports parallel/serial execution policies,
 * failure policies (STOP/CONTINUE), and timeout handling.</p>
 *
 * <p>Implements {@link InterruptableJob} to support job interruption/abortion.</p>
 */
public class QuartzJob implements InterruptableJob {

    private static final Logger logger = LoggerFactory.getLogger(QuartzJob.class);

    private SingleProcessRunner processRunner;
    private volatile boolean interrupted = false;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap jobData = context.getJobDetail().getJobDataMap();

        String runId = jobData.getString("runId");
        String taskName = jobData.getString("taskName");
        String scriptType = jobData.getString("scriptType");
        String script = jobData.getString("script");
        String logFile = jobData.getString("logFile");
        String taskParallelPolicy = jobData.getString("taskParallelPolicy");
        String taskFailPolicy = jobData.getString("taskFailPolicy");
        int taskTimeout = jobData.getInt("taskTimeout");
        String workDir = jobData.getString("workDir");

        String hopWorkDir = jobData.getString("hopWorkDir");
        String hopPluginDir = jobData.getString("hopPluginDir");

        String jobKey = runId + ":" + taskName;

        // Resolve log file path
        String resolvedLogFile = logFile;
        if (resolvedLogFile != null && !resolvedLogFile.isEmpty()) {
            // If logFile is a relative path, resolve it against workDir
            Path logPath = Path.of(resolvedLogFile);
            if (!logPath.isAbsolute() && workDir != null && !workDir.isEmpty()) {
                resolvedLogFile = Path.of(workDir, resolvedLogFile).toString();
            }
        }

        // --- Parallel Policy Handling ---

        // SERIAL_DISCARD: If already running, skip this execution
        if (QuartzJobStatusManager.isRunning(jobKey) && "SERIAL_DISCARD".equalsIgnoreCase(taskParallelPolicy)) {
            writeLog(resolvedLogFile, String.format(
                    "Task [%s:%s] reached trigger condition but previous execution is still running, skipped.", runId, taskName));
            logger.info("[SCHEDULER] Job {}/{} skipped (serial discard).", runId, taskName);
            jobData.put("exitCode", 0);
            jobData.put("exitMsg", "Task reached trigger condition but previous execution is still running, skipped.");
            jobData.put("skipped", true);
            return;
        }

        // SERIAL_DELAY with queue already full: skip
        if (QuartzJobStatusManager.isRunning(jobKey) && QuartzJobStatusManager.isQueued(jobKey)
                && "SERIAL_DELAY".equalsIgnoreCase(taskParallelPolicy)) {
            writeLog(resolvedLogFile, String.format(
                    "Task [%s:%s] reached trigger condition but queue is full, skipped.", runId, taskName));
            logger.info("[SCHEDULER] Job {}/{} skipped (queue full, serial delay).", runId, taskName);
            jobData.put("exitCode", 0);
            jobData.put("exitMsg", "Task reached trigger condition but queue is full, skipped.");
            jobData.put("skipped", true);
            return;
        }

        // SERIAL_DELAY or SERIAL_CATCHUP: Wait for previous execution to finish
        if (QuartzJobStatusManager.isRunning(jobKey)
                && ("SERIAL_DELAY".equalsIgnoreCase(taskParallelPolicy)
                || "SERIAL_CATCHUP".equalsIgnoreCase(taskParallelPolicy))) {
            writeLog(resolvedLogFile, String.format(
                    "Task [%s:%s] reached trigger condition but previous execution is still running, queuing...", runId, taskName));
            logger.info("[SCHEDULER] Job {}/{} queued, waiting for previous execution...", runId, taskName);
            QuartzJobStatusManager.markQueued(jobKey);

            // Wait with polling
            while (!interrupted) {
                if (!QuartzJobStatusManager.isRunning(jobKey)) {
                    break;
                }
                try {
                    Thread.sleep(10000); // Check every 10 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    break;
                }
            }

            if (interrupted) {
                QuartzJobStatusManager.unmarkQueued(jobKey);
                logger.info("[SCHEDULER] Job {}/{} queue wait interrupted.", runId, taskName);
                jobData.put("exitCode", -1);
                jobData.put("exitMsg", "Task queue wait interrupted.");
                return;
            }
        }

        // --- Execute the script ---
        logger.info("[SCHEDULER] Executing job: runId={}, taskName={}, scriptType={}, script={}, timeout={}s",
                runId, taskName, scriptType, script, taskTimeout);

        try {
            // Resolve workDir
            String resolvedWorkDir = workDir;
            if (resolvedWorkDir == null || resolvedWorkDir.isEmpty()) {
                resolvedWorkDir = ".";
            }

            // Resolve script path against workDir if it's a relative path
            String resolvedScript = script;
            if (resolvedScript != null && !resolvedScript.isEmpty()) {
                Path scriptPath = Path.of(resolvedScript);
                if (!scriptPath.isAbsolute()) {
                    resolvedScript = Path.of(resolvedWorkDir, resolvedScript).toString();
                }
            }

            // Create the runner and execute
            processRunner = new SingleProcessRunner(logger);
            // 如果配置了 Hop 环境，设置到 runner 中
            if (hopWorkDir != null && !hopWorkDir.isEmpty()
                    && hopPluginDir != null && !hopPluginDir.isEmpty()) {
                processRunner.setHopEnvironment(hopWorkDir, hopPluginDir);
                logger.info("[SCHEDULER] Using embedded HopService: workDir={}, pluginDir={}",
                        hopWorkDir, hopPluginDir);
            }
            int exitCode = processRunner.execute(resolvedWorkDir, resolvedScript, scriptType, taskTimeout);

            jobData.put("exitCode", exitCode);
            jobData.put("exitMsg", "");

            logger.info("[SCHEDULER] Job {}/{} completed with exit code {}.", runId, taskName, exitCode);

            // --- Failure Policy Handling ---
            if (exitCode != 0 && "STOP".equalsIgnoreCase(taskFailPolicy)) {
                logger.info("[SCHEDULER] Job {}/{} failed (exitCode={}), pausing job due to STOP policy.",
                        runId, taskName, exitCode);
                try {
                    context.getScheduler().pauseJob(context.getJobDetail().getKey());
                } catch (SchedulerException e) {
                    logger.error("[SCHEDULER] Failed to pause job {}/{}: {}", runId, taskName, e.getMessage());
                }
            }

        } catch (Exception e) {
            // Handle execution exception
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String errorMsg = sw.toString();

            jobData.put("exitCode", -1);
            jobData.put("exitMsg", errorMsg);

            logger.error("[SCHEDULER] Job {}/{} execution error: {}", runId, taskName, e.getMessage(), e);

            // Write error to log file
            if (resolvedLogFile != null && !resolvedLogFile.isEmpty()) {
                try {
                    Path logPath = Path.of(resolvedLogFile);
                    if (logPath.getParent() != null) {
                        Files.createDirectories(logPath.getParent());
                    }
                    try (PrintWriter pw = new PrintWriter(new FileWriter(resolvedLogFile, true))) {
                        e.printStackTrace(pw);
                    }
                } catch (IOException ioe) {
                    logger.error("[SCHEDULER] Failed to write error to log file: {}", ioe.getMessage());
                }
            }

            // Failure policy: STOP on error
            if ("STOP".equalsIgnoreCase(taskFailPolicy)) {
                logger.info("[SCHEDULER] Job {}/{} failed with exception, pausing job due to STOP policy.",
                        runId, taskName);
                try {
                    context.getScheduler().pauseJob(context.getJobDetail().getKey());
                } catch (SchedulerException se) {
                    logger.error("[SCHEDULER] Failed to pause job {}/{}: {}", runId, taskName, se.getMessage());
                }
            }
        }
    }

    @Override
    public void interrupt() {
        interrupted = true;
        if (processRunner != null) {
            logger.info("[SCHEDULER] Interrupting job execution...");
            // Note: SingleProcessRunner currently doesn't support termination.
            // This is a placeholder for future enhancement.
        }
    }

    /**
     * Write a message to the log file.
     */
    private void writeLog(String logFile, String message) {
        if (logFile == null || logFile.isEmpty()) {
            return;
        }
        try {
            Path logPath = Path.of(logFile);
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write(message);
                writer.newLine();
            }
        } catch (IOException e) {
            logger.warn("[SCHEDULER] Failed to write to log file {}: {}", logFile, e.getMessage());
        }
    }
}
