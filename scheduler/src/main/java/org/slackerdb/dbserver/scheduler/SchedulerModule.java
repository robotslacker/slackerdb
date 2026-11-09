package org.slackerdb.dbserver.scheduler;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slackerdb.dbserver.scheduler.controller.SchedulerController;
import org.slackerdb.dbserver.scheduler.meta.SchedulerMeta;
import org.slackerdb.dbserver.scheduler.quartz.DynamicQuartzScheduler;
import org.slackerdb.dbserver.scheduler.runner.TaskRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;

/**
 * 调度能力（ETL）：内置在 dbserver 里，按配置决定是否在场。
 *
 * <p>提供基于 cron 的作业调度：可以运行 HOP 工作流（{@code .hwf}/{@code .hpl}）以及
 * SHELL / DBCLI 类型的任务，项目模板放在文件系统上。（SQL 不是任务类型：SQL 在 HOP 工作流
 * 的 SQL 动作里执行。）</p>
 *
 * <h2>启用规则（重要）</h2>
 * <ul>
 *   <li><b>缺配置 = 没有这个能力</b>：{@code plugin-scheduler.home} 或
 *       {@code plugin-scheduler.projectHome} 为空时，本类不会被构造，路由不注册、
 *       不建表、不起 Quartz，也不向 {@code /capabilities} 声明能力（见 {@link #disabledReason}）。</li>
 *   <li><b>配置错 = 拒绝启动</b>：键有值但不可用（目录不存在、hop 脚本不存在等）时，
 *       构造期抛 {@link SchedulerConfigException}，宿主把它变成启动失败。</li>
 *   <li><b>只读实例 = 没有这个能力</b>：{@code access_mode=READ_ONLY} 时不启用，也不校验配置
 *       （只读优先于校验；建表本来就做不了）。</li>
 *   <li><b>HOP 不是启用前提</b>：{@code plugin-scheduler.hop.runScript} 为空时照常启用，
 *       只是 HOP 类型任务不可执行（SHELL 不受影响）；一旦给了值就必须有效。</li>
 * </ul>
 *
 * <p>配置键沿用 {@code <pluginId>.<param>} 形式（例如 {@code plugin-scheduler.home}）：
 * 它既是命令行参数，也是 dbserver.conf 里的配置项，老部署无需迁移。</p>
 */
public final class SchedulerModule {

    /** 路由前缀；客户端据此拼端点。 */
    public static final String ROUTE_PREFIX = "/scheduler";

    /** 对外契约版本；只在破坏性变更时递增。 */
    public static final int CONTRACT_VERSION = 1;

    /** 配置键前缀，与历史插件的 pluginId 保持一致。 */
    public static final String KEY_PREFIX = "plugin-scheduler.";

    private final Javalin app;
    private final Logger logger;
    private final Connection dbConnection;
    private final Map<String, String> properties;

    /** 工作目录：runs/、日志等运行期产物的根。 */
    private final String workHome;
    /** 项目模板目录：{workHome}/projects/{projectType} 的模板来源。 */
    private final String projectHome;
    /** HOP 运行环境配置（方案 A：Hop 独立目录 + hop-run 命令行，非内嵌）。 */
    private final TaskRequest.HopConfig hopConfig;

    /** DBCLI 任务连回本实例的连接串；为空表示没有可用的数据服务端口。 */
    private final String dbcliConnect;

    private SchedulerMeta meta;
    private DynamicQuartzScheduler quartzScheduler;
    private SchedulerController controller;

    private java.util.concurrent.ScheduledExecutorService retentionExecutor;

    /**
     * 判断本实例是否具备调度能力。
     *
     * <p>纯函数（只读配置与只读标记，不碰磁盘），便于单测。</p>
     *
     * @return {@code null} 表示应当启用；否则返回"未启用"的可读原因（用于日志与 /status）
     */
    public static String disabledReason(Map<String, String> properties, boolean readOnly) {
        if (readOnly) {
            return "read-only instance (access_mode=READ_ONLY)";
        }
        if (value(properties, "home") == null) {
            return KEY_PREFIX + "home is not configured";
        }
        if (value(properties, "projectHome") == null) {
            return KEY_PREFIX + "projectHome is not configured";
        }
        return null;
    }

    /**
     * 构造并校验。调用方必须先用 {@link #disabledReason} 判断是否应当启用。
     *
     * @throws SchedulerConfigException 配置有值但不可用（拒绝启动）
     */
    public SchedulerModule(Javalin app, Logger logger, Connection dbConnection,
                           Map<String, String> properties) throws SchedulerConfigException {
        this(app, logger, dbConnection, properties, null);
    }

    /**
     * @param dbcliConnect DBCLI 任务连回本实例的连接串（由宿主按数据服务端口生成）；
     *                     {@code null} 表示本实例没有可供 DBCLI 连接的端口，DBCLI 任务会在执行时明确失败
     */
    public SchedulerModule(Javalin app, Logger logger, Connection dbConnection,
                           Map<String, String> properties, String dbcliConnect)
            throws SchedulerConfigException {
        if (app == null) {
            throw new IllegalArgumentException("management application is required by the scheduler module");
        }
        this.app = app;
        this.logger = logger;
        this.dbConnection = dbConnection;
        this.properties = properties == null ? Map.of() : properties;
        this.dbcliConnect = dbcliConnect;

        this.workHome = value(this.properties, "home");
        this.projectHome = value(this.properties, "projectHome");
        validate();

        this.hopConfig = new TaskRequest.HopConfig();
        hopConfig.runScript = value(this.properties, "hop.runScript");
        hopConfig.javaHome = value(this.properties, "hop.javaHome");
        hopConfig.projectName = value(this.properties, "hop.projectName");
        hopConfig.environmentName = value(this.properties, "hop.environmentName");
        String hopRunConfig = value(this.properties, "hop.runConfig");
        if (hopRunConfig != null) {
            hopConfig.runConfig = hopRunConfig;
        }
        String hopInMemoryProject = value(this.properties, "hop.inMemoryProject");
        if (hopInMemoryProject != null) {
            hopConfig.inMemoryProject = Boolean.parseBoolean(hopInMemoryProject);
        }
    }

    /**
     * 启动调度器：建表 → 起 Quartz → 注册路由 → 重启对账 → 起保留策略。
     *
     * <p>任何一步失败都向上抛（宿主据此拒绝启动），不做"静默降级"。</p>
     */
    public void start() throws Exception {
        logger.info("[SCHEDULER] Starting: workHome={}, projectHome={}, hopRunScript={}",
                workHome, projectHome, hopConfig.runScript);
        if (hopConfig.isUsable()) {
            logger.info("[SCHEDULER] HOP 运行环境: runScript={}, javaHome={}, projectName={}, "
                            + "environmentName={}, runConfig={}, inMemoryProject={}",
                    hopConfig.runScript, hopConfig.javaHome, hopConfig.projectName,
                    hopConfig.environmentName, hopConfig.runConfig, hopConfig.inMemoryProject);
        } else {
            logger.warn("[SCHEDULER] 未配置 {}hop.runScript，HOP 类型任务将无法执行"
                    + "（SHELL 类型不受影响）", KEY_PREFIX);
        }

        // 元数据层：sysaux.v$scheduler_* 三张表
        this.meta = new SchedulerMeta(dbConnection, logger);
        meta.initializeTables();

        // Quartz（RAMJobStore）
        this.quartzScheduler = new DynamicQuartzScheduler(logger, 10, meta);
        quartzScheduler.start();

        // REST 路由
        this.controller = new SchedulerController(app, meta, quartzScheduler, logger,
                workHome, projectHome, hopConfig, dbcliConnect);
        controller.registerRoutes();

        // 重启 reconcile：Quartz 用 RAMJobStore，重启后调度状态全丢。
        // 交给 controller 处理（它持有任务定义装配逻辑）。
        // 历史数据/工作目录的问题不该拦住服务启动，因此这里只告警。
        try {
            controller.reconcileAfterRestart();
        } catch (Exception e) {
            logger.warn("[SCHEDULER] Reconcile after restart failed: {}", e.getMessage(), e);
        }

        // 保留策略：历史与任务日志的定时清理
        startRetentionTask();

        logger.info("[SCHEDULER] Started successfully.");
    }

    /** 停止调度器；可重复调用，且在 start() 中途失败时也能安全收尾。 */
    public void stop() {
        stopRetentionTask();
        if (quartzScheduler != null) {
            try {
                logger.info("[SCHEDULER] Stopping...");
                quartzScheduler.shutdown();
            } catch (Exception e) {
                logger.warn("[SCHEDULER] Error shutting down Quartz: {}", e.getMessage(), e);
            }
            quartzScheduler = null;
        }
        if (dbConnection != null) {
            try {
                dbConnection.close();
            } catch (Exception e) {
                logger.warn("[SCHEDULER] Error closing DB connection: {}", e.getMessage());
            }
        }
        controller = null;
        meta = null;
        logger.info("[SCHEDULER] Stopped.");
    }

    /** 当前是否已启动（宿主用于 /status 与 /capabilities）。 */
    public boolean isStarted() {
        return quartzScheduler != null;
    }

    /** HOP 运行环境是否就绪（配了 hop.runScript 才算）。 */
    public boolean isHopReady() {
        return hopConfig.isUsable();
    }

    // ========== 配置校验 ==========

    private void validate() throws SchedulerConfigException {
        // 目录必须已经存在：不自动创建，避免把"路径写错"变成"在错误位置悄悄建了一棵树"。
        requireExistingDirectory("home", workHome);
        requireExistingDirectory("projectHome", projectHome);

        // 工作目录必须真的可写：这里做一次写入探针，而不用 Files.isWritable ——
        // 后者在受限令牌/ACL 环境（含本项目的沙箱）下会误报 false，导致好目录被判成"配错"。
        requireWritableDirectory("home", workHome);

        String hopRunScript = value(properties, "hop.runScript");
        if (hopRunScript != null) {
            Path path = Path.of(hopRunScript);
            if (!Files.isRegularFile(path)) {
                throw new SchedulerConfigException(KEY_PREFIX + "hop.runScript", hopRunScript,
                        "an existing file (hop-run.bat|sh)");
            }
        }
        String hopJavaHome = value(properties, "hop.javaHome");
        if (hopJavaHome != null) {
            Path path = Path.of(hopJavaHome);
            if (!Files.isDirectory(path)) {
                throw new SchedulerConfigException(KEY_PREFIX + "hop.javaHome", hopJavaHome,
                        "an existing directory (a JDK home)");
            }
        }
        String hopInMemory = value(properties, "hop.inMemoryProject");
        if (hopInMemory != null && !hopInMemory.equalsIgnoreCase("true")
                && !hopInMemory.equalsIgnoreCase("false")) {
            throw new SchedulerConfigException(KEY_PREFIX + "hop.inMemoryProject", hopInMemory,
                    "true or false");
        }
        requireNonNegativeInt("retention.historyDays");
        requireNonNegativeInt("retention.logDays");
    }

    private void requireExistingDirectory(String name, String value) throws SchedulerConfigException {
        String key = KEY_PREFIX + name;
        if (value == null) {
            // 走到这里说明 disabledReason 已经放行，属于调用方用法错误；按"配错"处理更安全
            throw new SchedulerConfigException(key, "", "an existing directory");
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (Exception e) {
            throw new SchedulerConfigException(key, value, "a valid filesystem path");
        }
        if (!Files.isDirectory(path)) {
            throw new SchedulerConfigException(key, value, "an existing directory");
        }
    }

    private void requireWritableDirectory(String name, String value) throws SchedulerConfigException {
        String key = KEY_PREFIX + name;
        Path path = Path.of(value);
        Path probe = path.resolve(".slackerdb-scheduler-write-probe");
        try {
            Files.writeString(probe, "probe",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);
            Files.deleteIfExists(probe);
        } catch (Exception e) {
            throw new SchedulerConfigException(key, value, "an existing writable directory", e);
        }
    }

    private void requireNonNegativeInt(String name) throws SchedulerConfigException {
        String key = KEY_PREFIX + name;
        String value = value(properties, name);
        if (value == null) {
            return;
        }
        try {
            if (Integer.parseInt(value) < 0) {
                throw new SchedulerConfigException(key, value, "a non-negative integer");
            }
        } catch (NumberFormatException e) {
            throw new SchedulerConfigException(key, value, "a non-negative integer");
        }
    }

    private static String value(Map<String, String> properties, String name) {
        if (properties == null) {
            return null;
        }
        String v = properties.get(KEY_PREFIX + name);
        if (v == null || v.isBlank()) {
            return null;
        }
        return v.trim();
    }

    // ========== 保留策略：历史与日志清理 ==========

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
        String value = value(properties, name);
        if (value == null) {
            return fallback;
        }
        return Integer.parseInt(value);
    }
}
