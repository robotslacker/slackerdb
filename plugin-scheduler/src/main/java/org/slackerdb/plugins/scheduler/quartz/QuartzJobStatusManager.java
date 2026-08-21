package org.slackerdb.plugins.scheduler.quartz;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages runtime status for Quartz jobs.
 *
 * <p>Provides thread-safe tracking of job execution status including
 * running state, queued state, exit codes, and timing information.
 * This is used by {@link QuartzJob} and {@link QuartzJobListener}.</p>
 */
public class QuartzJobStatusManager {

    private static final ConcurrentHashMap<String, JobStatus> jobStatusMap = new ConcurrentHashMap<>();

    private static class JobStatus {
        final AtomicBoolean isRunning = new AtomicBoolean(false);
        final AtomicBoolean isQueued = new AtomicBoolean(false);
        final AtomicInteger previousExitCode = new AtomicInteger(0);
        final AtomicReference<String> previousStartTime = new AtomicReference<>(null);
        final AtomicReference<String> previousEndTime = new AtomicReference<>(null);
        final AtomicReference<String> logFile = new AtomicReference<>(null);
    }

    private static JobStatus getOrCreate(String jobKey) {
        return jobStatusMap.computeIfAbsent(jobKey, k -> new JobStatus());
    }

    /**
     * Check if a job is currently running.
     */
    public static boolean isRunning(String jobKey) {
        JobStatus status = jobStatusMap.get(jobKey);
        return status != null && status.isRunning.get();
    }

    /**
     * Check if a job is queued (waiting for serial execution).
     */
    public static boolean isQueued(String jobKey) {
        JobStatus status = jobStatusMap.get(jobKey);
        return status != null && status.isQueued.get();
    }

    /**
     * Get the previous exit code for a job.
     */
    public static int getPreviousExitCode(String jobKey) {
        JobStatus status = jobStatusMap.get(jobKey);
        return status != null ? status.previousExitCode.get() : 0;
    }

    /**
     * Get the previous start time for a job (as String).
     */
    public static String getPreviousStartTime(String jobKey) {
        JobStatus status = jobStatusMap.get(jobKey);
        return status != null ? status.previousStartTime.get() : null;
    }

    /**
     * Get the start time for a job as LocalDateTime.
     */
    public static LocalDateTime getStartTime(String jobKey) {
        String timeStr = getPreviousStartTime(jobKey);
        if (timeStr == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the previous end time for a job.
     */
    public static String getPreviousEndTime(String jobKey) {
        JobStatus status = jobStatusMap.get(jobKey);
        return status != null ? status.previousEndTime.get() : null;
    }

    /**
     * Get the log file path for a job.
     */
    public static String getLogFile(String jobKey) {
        JobStatus status = jobStatusMap.get(jobKey);
        return status != null ? status.logFile.get() : null;
    }

    /**
     * Mark a job as queued (waiting for serial execution).
     */
    public static void markQueued(String jobKey) {
        JobStatus status = getOrCreate(jobKey);
        status.isQueued.set(true);
    }

    /**
     * Mark a job as no longer queued.
     */
    public static void unmarkQueued(String jobKey) {
        JobStatus status = jobStatusMap.get(jobKey);
        if (status != null) {
            status.isQueued.set(false);
        }
    }

    /**
     * Record the start of a job execution.
     */
    public static void recordStart(String jobKey, String logFile) {
        JobStatus status = getOrCreate(jobKey);
        status.isQueued.set(false);
        status.isRunning.set(true);
        status.previousStartTime.set(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        status.previousEndTime.set(null);
        status.logFile.set(logFile);
    }

    /**
     * Record the end of a job execution.
     */
    public static void recordEnd(String jobKey, int exitCode, String exitMsg) {
        JobStatus status = jobStatusMap.get(jobKey);
        if (status != null) {
            status.isRunning.set(false);
            status.isQueued.set(false);
            status.previousExitCode.set(exitCode);
            status.previousEndTime.set(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
    }

    /**
     * Clear all status for a job.
     */
    public static void clear(String jobKey) {
        jobStatusMap.remove(jobKey);
    }

    /**
     * Clear all status for all jobs.
     */
    public static void clearAll() {
        jobStatusMap.clear();
    }
}
