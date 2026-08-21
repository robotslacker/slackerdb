package org.slackerdb.plugins.scheduler.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Run entity - represents a running instance of a scheduler project.
 *
 * <p>A Run is created based on a projectType, which references a project template
 * on the filesystem. Each Run has its own working directory and Quartz scheduling tasks.</p>
 */
@Data
public class SchedulerRun {
    /** Unique run identifier */
    private String runId;

    /** Project type, referencing a project template directory on the filesystem */
    private String projectType;

    /** Run status: CREATED, STARTED, STOPPED, FAILED */
    private String status;

    /** Runtime working directory path */
    private String workDir;

    /** JSON configuration for this run */
    private String configJson;

    /** Creation timestamp */
    private LocalDateTime createTime;

    /** Last update timestamp */
    private LocalDateTime updateTime;
}
