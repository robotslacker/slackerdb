package org.slackerdb.plugins.scheduler;

import io.javalin.Javalin;
import org.pf4j.PluginWrapper;
import org.slackerdb.plugins.scheduler.controller.SchedulerController;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slackerdb.plugins.scheduler.quartz.DynamicQuartzScheduler;
import org.slackerdb.plugin.DBPlugin;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;

/**
 * Scheduler plugin for Slackerdb.
 *
 * <p>Provides a cron-based job scheduling service that can execute SQL, HOP, and shell scripts
 * based on project templates defined on the filesystem.</p>
 *
 * <p>Key concepts:</p>
 * <ul>
 *   <li><b>Project</b> - A template directory on the filesystem under {workHome}/projects/{projectType}/</li>
 *   <li><b>Run</b> - A runtime instance created from a project, with its own working directory and Quartz tasks</li>
 *   <li><b>Task</b> - A scheduled job within a Run, defined by cron expression, script path, timeout, etc.</li>
 * </ul>
 */
public class PluginScheduler extends DBPlugin {

    private Connection dbConnection;
    private Javalin app;
    private Logger logger;
    private SchedulerMeta meta;
    private DynamicQuartzScheduler quartzScheduler;
    private SchedulerController controller;
    private String workHome;

    /** 项目目录，存放项目模板（sql、hop 文件等） */
    private String projectHome;

    /** HOP 运行环境配置（方案 A：Hop 独立目录 + hop-run 命令行，非内嵌） */
    private org.slackerdb.plugins.scheduler.runner.TaskRequest.HopConfig hopConfig;

    public PluginScheduler(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    protected void onStart() {
        try {
            this.logger = getLogger();
            this.app = getJavalinApp();
            this.dbConnection = getDbConnection();

            // 读取 workHome（工作目录）配置 - 必须配置
            this.workHome = getPluginProperty("home");
            if (workHome == null || workHome.isEmpty()) {
                throw new RuntimeException("scheduler.home is required but not configured");
            }
            logger.info("[SCHEDULER] workHome: {}", workHome);

            // 读取 projectHome（项目目录）配置 - 必须配置
            this.projectHome = getPluginProperty("projectHome");
            if (projectHome == null || projectHome.isEmpty()) {
                throw new RuntimeException("scheduler.projectHome is required but not configured");
            }
            logger.info("[SCHEDULER] projectHome: {}", projectHome);

            // 读取 Hop 运行环境配置（方案 A：Hop 目录 + hop-run 命令行）
            this.hopConfig = new org.slackerdb.plugins.scheduler.runner.TaskRequest.HopConfig();
            hopConfig.runScript = getPluginProperty("hop.runScript");
            hopConfig.javaHome = getPluginProperty("hop.javaHome");
            hopConfig.projectName = getPluginProperty("hop.projectName");
            hopConfig.environmentName = getPluginProperty("hop.environmentName");
            String hopRunConfig = getPluginProperty("hop.runConfig");
            if (hopRunConfig != null && !hopRunConfig.isBlank()) {
                hopConfig.runConfig = hopRunConfig;
            }
            if (hopConfig.isUsable()) {
                logger.info("[SCHEDULER] HOP 运行环境: runScript={}, javaHome={}, projectName={}, environmentName={}, runConfig={}",
                        hopConfig.runScript, hopConfig.javaHome, hopConfig.projectName,
                        hopConfig.environmentName, hopConfig.runConfig);
            } else {
                logger.warn("[SCHEDULER] 未配置 scheduler.hop.runScript，HOP 类型任务将无法执行"
                        + "（SHELL/COMMAND 类型不受影响）");
            }

            logger.info("[SCHEDULER] Plugin starting... workHome={}, projectHome={}", workHome, projectHome);

            // Initialize metadata layer
            this.meta = new SchedulerMeta(dbConnection, logger);
            meta.initializeTables();

            // Initialize Quartz scheduler with metadata layer for history persistence
            this.quartzScheduler = new DynamicQuartzScheduler(logger, 10, meta);
            quartzScheduler.start();

            // Register REST routes
            this.controller = new SchedulerController(app, meta, quartzScheduler, logger,
                    workHome, projectHome, hopConfig);
            controller.registerRoutes();

            logger.info("[SCHEDULER] Plugin started successfully.");

            // 重启 reconcile：Quartz 用 RAMJobStore，重启后调度状态全丢。
            // 交给 controller 处理（它持有任务定义装配逻辑）。
            controller.reconcileAfterRestart();

            // 保留策略：历史与任务日志的定时清理
            startRetentionTask();
        } catch (Exception e) {
            logger.error("[SCHEDULER] Failed to start plugin: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to start scheduler plugin", e);
        }
    }

    // ========== 保留策略：历史与日志清理 ==========

    private java.util.concurrent.ScheduledExecutorService retentionExecutor;

    /**
     * 启动保留策略清理：按天清理历史记录与任务日志。
     *
     * <p>配置（0 = 关闭）：{@code plugin-scheduler.retention.historyDays}（默认 30）、
     * {@code plugin-scheduler.retention.logDays}（默认 30）。启动时先跑一次，之后每 6 小时一次。</p>
     */
    private void startRetentionTask() {
        int historyDays = intProperty("retention.historyDays", 30);
        int logDays = intProperty("retention.logDays", 30);
        if (historyDays <= 0 && logDays <= 0) {
            logger.info("[SCHEDULER] 保留策略已关闭（historyDays={}, logDays={}）", historyDays, logDays);
            return;
        }
        retentionExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "scheduler-retention");
            thread.setDaemon(true);
            return thread;
        });
        Runnable task = () -> purge(historyDays, logDays);
        retentionExecutor.scheduleWithFixedDelay(task, 0, 6, java.util.concurrent.TimeUnit.HOURS);
        logger.info("[SCHEDULER] 保留策略已启用：历史保留 {} 天，日志保留 {} 天", historyDays, logDays);
    }

    private void stopRetentionTask() {
        if (retentionExecutor != null) {
            retentionExecutor.shutdownNow();
            retentionExecutor = null;
        }
    }

    private void purge(int historyDays, int logDays) {
        try {
            if (historyDays > 0) {
                int deleted = meta.deleteHistoryBefore(java.time.LocalDateTime.now().minusDays(historyDays));
                logger.info("[SCHEDULER] 保留策略：清理 {} 条历史记录（早于 {} 天前）", deleted, historyDays);
            }
        } catch (Exception e) {
            logger.warn("[SCHEDULER] 清理历史记录失败: {}", e.getMessage());
        }
        if (logDays > 0) {
            try {
                long cutoff = System.currentTimeMillis() - logDays * 24L * 3600L * 1000L;
                Path runsDir = Path.of(workHome, "runs");
                int deleted = 0;
                if (java.nio.file.Files.isDirectory(runsDir)) {
                    try (var runDirs = java.nio.file.Files.list(runsDir)) {
                        for (Path runDir : runDirs.toList()) {
                            Path logDir = runDir.resolve("logs");
                            if (!java.nio.file.Files.isDirectory(logDir)) {
                                continue;
                            }
                            try (var logs = java.nio.file.Files.list(logDir)) {
                                for (Path log : logs.toList()) {
                                    if (java.nio.file.Files.isRegularFile(log)
                                            && log.toFile().lastModified() < cutoff) {
                                        java.nio.file.Files.deleteIfExists(log);
                                        deleted++;
                                    }
                                }
                            }
                        }
                    }
                }
                logger.info("[SCHEDULER] 保留策略：清理 {} 个过期日志文件（早于 {} 天前）", deleted, logDays);
            } catch (Exception e) {
                logger.warn("[SCHEDULER] 清理日志文件失败: {}", e.getMessage());
            }
        }
    }

    private int intProperty(String name, int fallback) {
        String value = getPluginProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("[SCHEDULER] 配置 [{}}] 不是整数，按默认值 {} 处理", name, fallback);
            return fallback;
        }
    }

    @Override
    protected void onStop() {
        stopRetentionTask();        logger.info("[SCHEDULER] Plugin stopping...");
        if (quartzScheduler != null) {
            quartzScheduler.shutdown();
        }
        if (dbConnection != null) {
            try {
                dbConnection.close();
            } catch (Exception e) {
                logger.warn("[SCHEDULER] Error closing DB connection: {}", e.getMessage());
            }
        }
        logger.info("[SCHEDULER] Plugin stopped.");
    }

    @Override
    protected void onDelete() {
        logger.info("[SCHEDULER] Plugin deleted.");
    }

    // ========== Standalone Running Support ==========

    /**
     * Create a standalone instance for testing.
     */
    public static PluginScheduler standAloneInstance() throws Exception {
        return DBPlugin.Standalone.createInstance(PluginScheduler.class);
    }
}
