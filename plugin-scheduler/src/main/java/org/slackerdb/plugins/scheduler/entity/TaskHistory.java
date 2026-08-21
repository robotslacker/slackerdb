package org.slackerdb.plugins.scheduler.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Task history entity - records the execution history of a scheduled task.
 */
@Data
public class TaskHistory {
    /** Auto-increment primary key */
    private long taskId;

    /** Associated run ID */
    private String runId;

    /** Task name */
    private String taskName;

    /** Run ID reference (for tracking related runs) */
    private String runIdRef;

    /** Return code: 0 = success, non-zero = failure */
    private int retCode;

    /** Return message */
    private String retMsg;

    /** Start time */
    private LocalDateTime startTime;

    /** End time */
    private LocalDateTime endTime;

    /** Log file path */
    private String logFile;
}
