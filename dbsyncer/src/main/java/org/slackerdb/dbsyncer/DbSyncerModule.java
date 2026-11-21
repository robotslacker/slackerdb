package org.slackerdb.dbsyncer;

import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import org.slackerdb.dbsyncer.api.SyncController;
import org.slackerdb.dbsyncer.config.ConfigFingerprint;
import org.slackerdb.dbsyncer.config.SyncConfig;
import org.slackerdb.dbsyncer.config.SyncConfigLoader;
import org.slackerdb.dbsyncer.engine.JobManager;
import org.slackerdb.dbsyncer.engine.JobRuntime;
import org.slackerdb.dbsyncer.state.SyncStateStore;
import org.slackerdb.dbsyncer.target.DuckDbHost;
import org.slackerdb.dbsyncer.target.DuckDbTarget;
import org.slackerdb.dbsyncer.target.DuckDbWriter;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * dbsyncer 模块：配置加载、状态表、作业管理、API 装配。
 *
 * <p>缺配置 = 没有这个能力；配置存在但非法 = 宿主拒绝启动。</p>
 */
public final class DbSyncerModule {

    public static final String ROUTE_PREFIX = "/dbsyncer";
    public static final String KEY_PREFIX = "plugin-dbsyncer.";
    public static final int CONTRACT_VERSION = 1;

    /** 模块认识的 {@code plugin-dbsyncer.*} 键；其它键一律报错，避免拼写错误被静默忽略。 */
    private static final List<String> KNOWN_PROPERTIES = List.of(
            "config", "autoStart", "maxJobs", "stopTimeoutMs", "queryTimeoutMs");

    private final Javalin app;
    private final Logger logger;
    private final DuckDbHost host;
    private final Path configPath;
    private final boolean autoStart;
    private final long stopTimeoutMs;
    private final int maxJobs;
    private final long queryTimeoutMs;

    private final Connection stateConnection;
    private final SyncStateStore stateStore;
    private final DuckDbTarget target;
    private final DuckDbWriter writer;
    private final JobManager jobManager;
    private final SyncController controller;

    private volatile SyncConfig config;
    private volatile String configHash;
    private volatile boolean started;
    private volatile boolean stopping;
    private volatile boolean closed;

    public DbSyncerModule(Javalin app, Logger logger, DuckDbHost host, Map<String, String> properties)
            throws DbSyncerConfigException {
        if (app == null) {
            throw new IllegalArgumentException("management Javalin app is required");
        }
        if (host == null) {
            throw new IllegalArgumentException("DuckDB host is required");
        }
        this.app = app;
        this.logger = logger;
        this.host = host;
        // 先校验并解析全部 plugin-dbsyncer.*，再做任何有副作用的动作（读配置、建状态表），
        // 这样配置写错时不会留下"建了 schema / 把残留作业标成 FAILED"的痕迹。
        checkProperties(properties);
        String configValue = value(properties, "config");
        if (configValue == null) {
            throw new DbSyncerConfigException(KEY_PREFIX + "config", "is required to enable dbsyncer");
        }
        this.configPath = Path.of(configValue);
        this.autoStart = booleanProperty(properties, "autoStart", false);
        this.stopTimeoutMs = longProperty(properties, "stopTimeoutMs", 10_000, 1_000, 3_600_000);
        this.maxJobs = intProperty(properties, "maxJobs", 16, 1, 1_024);
        this.queryTimeoutMs = longProperty(properties, "queryTimeoutMs",
                DuckDbTarget.DEFAULT_QUERY_TIMEOUT_MS, 0, 86_400_000);
        this.config = SyncConfigLoader.load(configPath);
        this.configHash = ConfigFingerprint.ofConfig(config);

        try {
            this.stateConnection = host.duplicate();
            // backendSysConnection may be in a manual transaction; state tables must be
            // visible to the target writer connection immediately.
            this.stateConnection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new DbSyncerConfigException("plugin-dbsyncer.config",
                    "cannot open DuckDB state connection: " + e.getMessage(), e);
        }
        this.stateStore = new SyncStateStore(stateConnection, host.catalog());
        try {
            stateStore.initialize();
            stateStore.recoverInterruptedJobs();
        } catch (SQLException e) {
            closeQuietly(stateConnection);
            throw new DbSyncerConfigException("plugin-dbsyncer.config",
                    "cannot initialize dbsync state tables: " + e.getMessage(), e);
        }

        this.target = new DuckDbTarget(host, logger, queryTimeoutMs);
        this.writer = new DuckDbWriter(target, stateStore, logger);
        this.jobManager = new JobManager(config, target, stateStore, writer, logger, maxJobs);
        this.controller = new SyncController(this, app, logger);
    }

    /** 未知的 {@code plugin-dbsyncer.*} 键直接拒绝：拼写错误不应被静默忽略。 */
    private static void checkProperties(Map<String, String> properties) throws DbSyncerConfigException {
        if (properties == null) {
            return;
        }
        for (String key : properties.keySet()) {
            if (key == null || !key.startsWith(KEY_PREFIX)) {
                continue;
            }
            if (!KNOWN_PROPERTIES.contains(key.substring(KEY_PREFIX.length()))) {
                throw new DbSyncerConfigException(key, "unknown dbsyncer property; known keys are "
                        + KNOWN_PROPERTIES.stream().map(name -> KEY_PREFIX + name).toList());
            }
        }
    }

    public static String disabledReason(Map<String, String> properties, boolean readOnly,
                                        boolean managementEnabled) {
        if (readOnly) {
            return "read-only instance (access_mode=READ_ONLY)";
        }
        if (!managementEnabled) {
            return "management port is disabled (port_x=-1)";
        }
        if (value(properties, "config") == null) {
            return KEY_PREFIX + "config is not configured";
        }
        return null;
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("dbsyncer module is closed");
        }
        if (started) {
            return;
        }
        controller.registerRoutes();
        started = true;
        if (autoStart) {
            jobManager.startAutoStart();
        } else {
            logger.info("[DBSYNCER] autoStart=false; all jobs stay STOPPED");
        }
        logger.info("[DBSYNCER] started: config={}, jobs={}, autoStart={}",
                configPath, jobManager.jobs().size(), autoStart);
    }

    public void stop() {
        if (closed) {
            return;
        }
        stopping = true;
        if (started) {
            logger.info("[DBSYNCER] stopping...");
        }
        jobManager.stopAll(stopTimeoutMs);
        target.close();
        closeQuietly(stateConnection);
        started = false;
        closed = true;
        logger.info("[DBSYNCER] stopped.");
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isStopping() {
        return stopping;
    }

    public SyncConfig config() {
        return config;
    }

    public Path configPath() {
        return configPath;
    }

    public String configHash() {
        return configHash;
    }

    public JobManager jobManager() {
        return jobManager;
    }

    public JobManager.ReloadResult reload() throws DbSyncerConfigException {
        if (closed) {
            throw new DbSyncerConfigException("plugin-dbsyncer.config", "dbsyncer module is closed");
        }
        SyncConfig newConfig = SyncConfigLoader.load(configPath);
        JobManager.ReloadResult result = jobManager.reload(newConfig);
        this.config = newConfig;
        this.configHash = ConfigFingerprint.ofConfig(newConfig);
        logger.info("[DBSYNCER] reloaded: added={}, updated={}, staleRunning={}, removed={}",
                result.added(), result.updated(), result.staleRunning(), result.removed());
        for (String warning : result.warnings()) {
            logger.warn("[DBSYNCER] reload: {}", warning);
        }
        return result;
    }

    public JSONObject status() {
        JSONObject json = new JSONObject();
        json.put("enabled", true);
        json.put("contractVersion", CONTRACT_VERSION);
        json.put("configPath", configPath.toString());
        json.put("configHash", configHash);
        json.put("autoStart", autoStart);
        json.put("started", started);
        json.put("jobCount", jobManager.jobs().size());
        long running = 0;
        for (JobRuntime runtime : jobManager.jobs()) {
            if (runtime.state().isRunning()) {
                running++;
            }
        }
        json.put("runningJobs", running);
        return json;
    }

    private static String value(Map<String, String> properties, String name) {
        if (properties == null) {
            return null;
        }
        String value = properties.get(KEY_PREFIX + name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean booleanProperty(Map<String, String> properties, String name, boolean fallback)
            throws DbSyncerConfigException {
        String value = value(properties, name);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new DbSyncerConfigException(KEY_PREFIX + name, "must be true or false");
    }

    private static long longProperty(Map<String, String> properties, String name, long fallback,
                                     long min, long max) throws DbSyncerConfigException {
        String value = value(properties, name);
        if (value == null) {
            return fallback;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new DbSyncerConfigException(KEY_PREFIX + name, "must be an integer", e);
        }
        if (parsed < min || parsed > max) {
            throw new DbSyncerConfigException(KEY_PREFIX + name,
                    "must be between " + min + " and " + max + ", got " + parsed);
        }
        return parsed;
    }

    private static int intProperty(Map<String, String> properties, String name, int fallback,
                                   int min, int max) throws DbSyncerConfigException {
        return (int) longProperty(properties, name, fallback, min, max);
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    /** 供 Controller 做统一错误响应。 */
    public List<JobRuntime> jobs() {
        return jobManager.jobs();
    }

    /** 最近的运行记录（状态表只读，供 /jobs/{name}/runs 使用）。 */
    public List<SyncStateStore.RunRecord> loadRuns(String jobName, int limit) throws SQLException {
        return stateStore.loadRuns(jobName, limit);
    }
}
