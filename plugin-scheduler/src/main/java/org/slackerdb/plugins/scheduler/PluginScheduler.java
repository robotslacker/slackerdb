package org.slackerdb.plugins.scheduler;

import io.javalin.Javalin;
import org.pf4j.PluginWrapper;
import org.slackerdb.plugins.scheduler.controller.SchedulerController;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slackerdb.plugins.scheduler.quartz.DynamicQuartzScheduler;
import org.slackerdb.plugin.DBPlugin;
import org.slf4j.Logger;

import java.io.File;
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

    /** Hop 工作目录（用于嵌入式 HopService） */
    private String hopWorkDirectory;

    /** Hop 插件目录（用于嵌入式 HopService） */
    private String hopPluginDirectory;

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

            // 读取 Hop 环境配置
            this.hopWorkDirectory = getPluginProperty("hop.workDirectory");
            this.hopPluginDirectory = getPluginProperty("hop.pluginDirectory");
            if (hopWorkDirectory != null && !hopWorkDirectory.isEmpty()
                    && hopPluginDirectory != null && !hopPluginDirectory.isEmpty()) {
                logger.info("[SCHEDULER] Using embedded HopService: workDir={}, pluginDir={}",
                        hopWorkDirectory, hopPluginDirectory);
            } else {
                logger.info("[SCHEDULER] Hop environment not configured, will use external hop-run command");
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
                    workHome, projectHome, hopWorkDirectory, hopPluginDirectory);
            controller.registerRoutes();

            logger.info("[SCHEDULER] Plugin started successfully.");
        } catch (Exception e) {
            logger.error("[SCHEDULER] Failed to start plugin: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to start scheduler plugin", e);
        }
    }

    @Override
    protected void onStop() {
        logger.info("[SCHEDULER] Plugin stopping...");
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
