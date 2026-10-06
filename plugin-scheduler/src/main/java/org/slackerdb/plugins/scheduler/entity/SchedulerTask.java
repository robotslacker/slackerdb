package org.slackerdb.plugins.scheduler.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Task entity - represents a scheduled task within a Run.
 *
 * <p>Each Run can have multiple tasks. Tasks define what script to execute,
 * when to execute (cron/interval), timeout, and failure handling policies.</p>
 */
@Data
public class SchedulerTask {
    /** Associated run ID */
    private String runId;

    /** Task name, unique within a Run */
    private String taskName;

    /** Script path (relative to run's flow directory, or absolute) */
    private String taskScript;

    /** Script type: SQL, HOP, SHELL */
    private String taskScriptType;

    /** Run policy: RUN_ONCE, CRONTAB, INTERVAL */
    private String taskRunPolicy;

    /** Failure policy: STOP, CONTINUE, RETRY */
    private String taskFailPolicy;

    /** RETRY 策略下的重试次数（0 表示不重试） */
    private int taskRetryTimes;

    /** RETRY 策略下的重试间隔（秒） */
    private int taskRetryInterval = 60;

    /** Parallel policy: PARALLEL, SEQUENTIAL */
    private String taskParallelPolicy;

    /** Cron expression for scheduled execution */
    private String taskCrontabExpr;

    /** Interval in seconds for interval-based execution */
    private int taskInterval;

    /** Timeout in seconds, 0 means no timeout */
    private int taskTimeout;

    /** Whether this task is enabled */
    private boolean taskEnabled;

    /** 是否为必选任务：必选任务失败时整个 run 视为失败（编排语义） */
    private boolean taskMandatory;

    /** Task group for grouping related tasks */
    private String taskGroup;

    /** Startup order within the same group */
    private int taskStartupOrder;

    /** Task description */
    private String taskDescription;

    /** JSON configuration for this task */
    private String configJson;

    /** 任务级变量（JSON 对象字符串）；参与变量三层合并时优先级高于模板/全局 */
    private String variablesJson;

    /** Creation timestamp */
    private LocalDateTime createTime;

    /** Last update timestamp */
    private LocalDateTime updateTime;
}
