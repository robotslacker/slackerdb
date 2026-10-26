package org.slackerdb.plugins.scheduler.meta;

import org.slackerdb.plugins.scheduler.entity.SchedulerRun;
import org.slackerdb.plugins.scheduler.entity.SchedulerTask;
import org.slackerdb.plugins.scheduler.entity.TaskHistory;
import org.slf4j.Logger;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Metadata persistence layer for the scheduler plugin.
 *
 * <p>Manages the v$scheduler_run, v$scheduler_task, and v$scheduler_task_history tables
 * in the DuckDB backend database.</p>
 */
public class SchedulerMeta {

    private final Connection dbConnection;
    private final Logger logger;

    public SchedulerMeta(Connection dbConnection, Logger logger) {
        this.dbConnection = dbConnection;
        this.logger = logger;
    }

    /**
     * Initialize the metadata tables.
     */
    public void initializeTables() throws SQLException {
        logger.info("[SCHEDULER] Initializing scheduler metadata tables...");

        String createRunTable = "CREATE TABLE IF NOT EXISTS sysaux.v$scheduler_run (" +
                "run_id VARCHAR PRIMARY KEY, " +
                "project_type VARCHAR NOT NULL, " +
                "status VARCHAR DEFAULT 'CREATED', " +
                "work_dir VARCHAR, " +
                "config_json VARCHAR, " +
                "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")";

        String createTaskTable = "CREATE TABLE IF NOT EXISTS sysaux.v$scheduler_task (" +
                "run_id VARCHAR NOT NULL, " +
                "task_name VARCHAR NOT NULL, " +
                "task_script VARCHAR, " +
                // 默认类型与 TaskTemplateLoader.DEFAULT_SCRIPT_TYPE 保持一致（SQL 不支持，见该类注释）；
                // 写入时总是显式 setString，这里的 DEFAULT 只影响"新装库 + 漏填"的极端情况
                "task_script_type VARCHAR DEFAULT 'HOP', " +
                "task_run_policy VARCHAR DEFAULT 'RUN_ONCE', " +
                "task_fail_policy VARCHAR DEFAULT 'STOP', " +
                "task_retry_times INTEGER DEFAULT 0, " +
                "task_retry_interval INTEGER DEFAULT 60, " +
                "task_parallel_policy VARCHAR DEFAULT 'PARALLEL', " +
                "task_crontab_expr VARCHAR, " +
                "task_interval INTEGER DEFAULT 0, " +
                "task_timeout INTEGER DEFAULT 0, " +
                "task_enabled BOOLEAN DEFAULT true, " +
                "task_mandatory BOOLEAN DEFAULT false, " +
                "task_group VARCHAR, " +
                "task_startup_order INTEGER DEFAULT 0, " +
                "task_description VARCHAR, " +
                "config_json VARCHAR, " +
                "variables_json VARCHAR, " +
                "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (run_id, task_name)" +
                ")";

        String createHistoryTable = "CREATE TABLE IF NOT EXISTS sysaux.v$scheduler_task_history (" +
                "task_id BIGINT PRIMARY KEY, " +
                "run_id VARCHAR NOT NULL, " +
                "task_name VARCHAR NOT NULL, " +
                "run_id_ref VARCHAR, " +
                "ret_code INTEGER, " +
                "ret_msg VARCHAR, " +
                "start_time TIMESTAMP, " +
                "end_time TIMESTAMP, " +
                "log_file VARCHAR" +
                ")";

        try (Statement stmt = dbConnection.createStatement()) {
            // 自给自足：不能假设服务端已经建过 sysaux（只有开启 SQL 历史时服务端才会建它，
            // 而 features.sqlHistory 默认 false —— 实测在默认配置的服务端上插件因此起不来）
            stmt.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
            stmt.execute(createRunTable);
            stmt.execute(createTaskTable);
            stmt.execute(createHistoryTable);

            // 增量迁移：老库补齐新增列（DuckDB 支持 ADD COLUMN IF NOT EXISTS）
            for (String migration : new String[]{
                    "ALTER TABLE sysaux.v$scheduler_task ADD COLUMN IF NOT EXISTS task_mandatory BOOLEAN DEFAULT false",
                    "ALTER TABLE sysaux.v$scheduler_task ADD COLUMN IF NOT EXISTS variables_json VARCHAR",
                    "ALTER TABLE sysaux.v$scheduler_task ADD COLUMN IF NOT EXISTS task_retry_times INTEGER DEFAULT 0",
                    "ALTER TABLE sysaux.v$scheduler_task ADD COLUMN IF NOT EXISTS task_retry_interval INTEGER DEFAULT 60",
                    "ALTER TABLE sysaux.v$scheduler_run ADD COLUMN IF NOT EXISTS started_at TIMESTAMP",
                    "ALTER TABLE sysaux.v$scheduler_run ADD COLUMN IF NOT EXISTS stopped_at TIMESTAMP",
            }) {
                try {
                    stmt.execute(migration);
                } catch (SQLException e) {
                    logger.warn("[SCHEDULER] metadata migration skipped [{}]: {}", migration, e.getMessage());
                }
            }
            logger.info("[SCHEDULER] Scheduler metadata tables initialized successfully.");
        }
    }

    // ========== Run CRUD ==========

    /**
     * Insert a new run record.
     */
    public void insertRun(SchedulerRun run) throws SQLException {
        String sql = "INSERT INTO sysaux.v$scheduler_run (run_id, project_type, status, work_dir, config_json, create_time, update_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, run.getRunId());
            ps.setString(2, run.getProjectType());
            ps.setString(3, run.getStatus());
            ps.setString(4, run.getWorkDir());
            ps.setString(5, run.getConfigJson());
            ps.setObject(6, run.getCreateTime());
            ps.setObject(7, run.getUpdateTime());
            ps.executeUpdate();
        }
    }

    /**
     * 更新 run 状态，并维护 {@code started_at}/{@code stopped_at}。
     *
     * <p>状态取值（框架约定）：{@code CREATED / STARTING / STARTED / STOPPED / ABORTED / FAILED}。</p>
     */
    public void updateRunStatus(String runId, String status) throws SQLException {
        boolean started = "STARTED".equalsIgnoreCase(status);
        boolean stopped = "STOPPED".equalsIgnoreCase(status)
                || "ABORTED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status);
        StringBuilder sql = new StringBuilder(
                "UPDATE sysaux.v$scheduler_run SET status = ?, update_time = ?");
        if (started) {
            sql.append(", started_at = ?");
        }
        if (stopped) {
            sql.append(", stopped_at = ?");
        }
        sql.append(" WHERE run_id = ?");

        try (PreparedStatement ps = dbConnection.prepareStatement(sql.toString())) {
            int index = 1;
            ps.setString(index++, status);
            LocalDateTime now = LocalDateTime.now();
            ps.setObject(index++, now);
            if (started) {
                ps.setObject(index++, now);
            }
            if (stopped) {
                ps.setObject(index++, now);
            }
            ps.setString(index, runId);
            ps.executeUpdate();
        }
    }

    /** 按状态列出 run（启动时 reconcile 用）。 */
    public List<SchedulerRun> listRunsByStatus(java.util.Collection<String> statuses) throws SQLException {
        List<SchedulerRun> runs = new ArrayList<>();
        if (statuses == null || statuses.isEmpty()) {
            return runs;
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM sysaux.v$scheduler_run WHERE status IN (");
        for (int i = 0; i < statuses.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(") ORDER BY create_time DESC");
        try (PreparedStatement ps = dbConnection.prepareStatement(sql.toString())) {
            int index = 1;
            for (String status : statuses) {
                ps.setString(index++, status);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(mapRun(rs));
                }
            }
        }
        return runs;
    }

    /**
     * Delete a run record.
     */
    public void deleteRun(String runId) throws SQLException {
        String sql = "DELETE FROM sysaux.v$scheduler_run WHERE run_id = ?";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.executeUpdate();
        }
    }

    /**
     * Get a run by ID.
     */
    public SchedulerRun getRun(String runId) throws SQLException {
        String sql = "SELECT * FROM sysaux.v$scheduler_run WHERE run_id = ?";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRun(rs);
                }
            }
        }
        return null;
    }

    /**
     * List all runs.
     */
    public List<SchedulerRun> listRuns() throws SQLException {
        List<SchedulerRun> runs = new ArrayList<>();
        String sql = "SELECT * FROM sysaux.v$scheduler_run ORDER BY create_time DESC";
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                runs.add(mapRun(rs));
            }
        }
        return runs;
    }

    private SchedulerRun mapRun(ResultSet rs) throws SQLException {
        SchedulerRun run = new SchedulerRun();
        run.setRunId(rs.getString("run_id"));
        run.setProjectType(rs.getString("project_type"));
        run.setStatus(rs.getString("status"));
        run.setWorkDir(rs.getString("work_dir"));
        run.setConfigJson(rs.getString("config_json"));
        run.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        run.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return run;
    }

    // ========== Task CRUD ==========

    /**
     * Insert a new task record.
     */
    public void insertTask(SchedulerTask task) throws SQLException {
        String sql = "INSERT INTO sysaux.v$scheduler_task (" +
                "run_id, task_name, task_script, task_script_type, task_run_policy, " +
                "task_fail_policy, task_retry_times, task_retry_interval, task_parallel_policy, " +
                "task_crontab_expr, task_interval, " +
                "task_timeout, task_enabled, task_mandatory, task_group, task_startup_order, task_description, " +
                "config_json, variables_json, create_time, update_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, task.getRunId());
            ps.setString(2, task.getTaskName());
            ps.setString(3, task.getTaskScript());
            ps.setString(4, task.getTaskScriptType());
            ps.setString(5, task.getTaskRunPolicy());
            ps.setString(6, task.getTaskFailPolicy());
            ps.setInt(7, task.getTaskRetryTimes());
            ps.setInt(8, task.getTaskRetryInterval());
            ps.setString(9, task.getTaskParallelPolicy());
            ps.setString(10, task.getTaskCrontabExpr());
            ps.setInt(11, task.getTaskInterval());
            ps.setInt(12, task.getTaskTimeout());
            ps.setBoolean(13, task.isTaskEnabled());
            ps.setBoolean(14, task.isTaskMandatory());
            ps.setString(15, task.getTaskGroup());
            ps.setInt(16, task.getTaskStartupOrder());
            ps.setString(17, task.getTaskDescription());
            ps.setString(18, task.getConfigJson());
            ps.setString(19, task.getVariablesJson());
            ps.setObject(20, task.getCreateTime());
            ps.setObject(21, task.getUpdateTime());
            ps.executeUpdate();
        }
    }

    /**
     * 按 (runId, taskName) 更新一条任务；返回是否命中。
     *
     * <p>只更新**可编辑字段**（脚本/类型/策略/重试/cron/interval/超时/开关/分组/顺序/描述/变量/配置），
     * `create_time` 保持不动 —— 它记录的是"任务第一次出现的时间"。</p>
     */
    public boolean updateTask(SchedulerTask task) throws SQLException {
        String sql = "UPDATE sysaux.v$scheduler_task SET "
                + "task_script = ?, task_script_type = ?, task_run_policy = ?, task_fail_policy = ?, "
                + "task_retry_times = ?, task_retry_interval = ?, task_parallel_policy = ?, "
                + "task_crontab_expr = ?, task_interval = ?, task_timeout = ?, task_enabled = ?, "
                + "task_mandatory = ?, task_group = ?, task_startup_order = ?, task_description = ?, "
                + "config_json = ?, variables_json = ?, update_time = ? "
                + "WHERE run_id = ? AND task_name = ?";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, task.getTaskScript());
            ps.setString(2, task.getTaskScriptType());
            ps.setString(3, task.getTaskRunPolicy());
            ps.setString(4, task.getTaskFailPolicy());
            ps.setInt(5, task.getTaskRetryTimes());
            ps.setInt(6, task.getTaskRetryInterval());
            ps.setString(7, task.getTaskParallelPolicy());
            ps.setString(8, task.getTaskCrontabExpr());
            ps.setInt(9, task.getTaskInterval());
            ps.setInt(10, task.getTaskTimeout());
            ps.setBoolean(11, task.isTaskEnabled());
            ps.setBoolean(12, task.isTaskMandatory());
            ps.setString(13, task.getTaskGroup());
            ps.setInt(14, task.getTaskStartupOrder());
            ps.setString(15, task.getTaskDescription());
            ps.setString(16, task.getConfigJson());
            ps.setString(17, task.getVariablesJson());
            ps.setObject(18, task.getUpdateTime());
            ps.setString(19, task.getRunId());
            ps.setString(20, task.getTaskName());
            return ps.executeUpdate() > 0;
        }
    }

    /** 按 (runId, taskName) 删除一条任务；返回是否命中。 */
    public boolean deleteTask(String runId, String taskName) throws SQLException {
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "DELETE FROM sysaux.v$scheduler_task WHERE run_id = ? AND task_name = ?")) {
            ps.setString(1, runId);
            ps.setString(2, taskName);
            return ps.executeUpdate() > 0;
        }
    }

    /** 取单条任务；没有返回 null。 */
    public SchedulerTask getTask(String runId, String taskName) throws SQLException {
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT * FROM sysaux.v$scheduler_task WHERE run_id = ? AND task_name = ?")) {
            ps.setString(1, runId);
            ps.setString(2, taskName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapTask(rs) : null;
            }
        }
    }

    /**
     * Delete all tasks for a run.
     */
    public void deleteTasksByRunId(String runId) throws SQLException {
        String sql = "DELETE FROM sysaux.v$scheduler_task WHERE run_id = ?";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.executeUpdate();
        }
    }

    /**
     * Get tasks for a run.
     */
    public List<SchedulerTask> getTasksByRunId(String runId) throws SQLException {
        List<SchedulerTask> tasks = new ArrayList<>();
        String sql = "SELECT * FROM sysaux.v$scheduler_task WHERE run_id = ? ORDER BY task_startup_order";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(mapTask(rs));
                }
            }
        }
        return tasks;
    }

    private SchedulerTask mapTask(ResultSet rs) throws SQLException {
        SchedulerTask task = new SchedulerTask();
        task.setRunId(rs.getString("run_id"));
        task.setTaskName(rs.getString("task_name"));
        task.setTaskScript(rs.getString("task_script"));
        task.setTaskScriptType(rs.getString("task_script_type"));
        task.setTaskRunPolicy(rs.getString("task_run_policy"));
        task.setTaskFailPolicy(rs.getString("task_fail_policy"));
        task.setTaskRetryTimes(rs.getInt("task_retry_times"));
        task.setTaskRetryInterval(rs.getInt("task_retry_interval"));
        task.setTaskParallelPolicy(rs.getString("task_parallel_policy"));
        task.setTaskCrontabExpr(rs.getString("task_crontab_expr"));
        task.setTaskInterval(rs.getInt("task_interval"));
        task.setTaskTimeout(rs.getInt("task_timeout"));
        task.setTaskEnabled(rs.getBoolean("task_enabled"));
        task.setTaskMandatory(rs.getBoolean("task_mandatory"));
        task.setTaskGroup(rs.getString("task_group"));
        task.setTaskStartupOrder(rs.getInt("task_startup_order"));
        task.setTaskDescription(rs.getString("task_description"));
        task.setConfigJson(rs.getString("config_json"));
        task.setVariablesJson(rs.getString("variables_json"));
        task.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        task.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return task;
    }

    // ========== Task History CRUD ==========

    /**
     * Insert a task history record.
     */
    /**
     * 插入任务历史。
     *
     * <p>{@code task_id} 由数据库在同一语句内取 {@code max(task_id)+1} —— 避免多任务在同一毫秒
     * 完成时主键冲突（历史静默丢失），也不依赖表上是否已有序列或自增默认值。</p>
     */
    public void insertTaskHistory(TaskHistory history) throws SQLException {
        String sql = "INSERT INTO sysaux.v$scheduler_task_history (" +
                "task_id, run_id, task_name, run_id_ref, ret_code, ret_msg, start_time, end_time, log_file) " +
                "VALUES ((SELECT coalesce(max(task_id), 0) + 1 FROM sysaux.v$scheduler_task_history), " +
                "?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, history.getRunId());
            ps.setString(2, history.getTaskName());
            ps.setString(3, history.getRunIdRef());
            ps.setInt(4, history.getRetCode());
            ps.setString(5, history.getRetMsg());
            ps.setObject(6, history.getStartTime());
            ps.setObject(7, history.getEndTime());
            ps.setString(8, history.getLogFile());
            ps.executeUpdate();
        }
    }

    /**
     * 删除指定时间之前的历史记录（保留策略用），返回删除行数。
     */
    public int deleteHistoryBefore(LocalDateTime before) throws SQLException {
        String sql = "DELETE FROM sysaux.v$scheduler_task_history WHERE coalesce(end_time, start_time) < ?";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setObject(1, before);
            return ps.executeUpdate();
        }
    }

    /**
     * Get task history for a run.
     */
    public List<TaskHistory> getTaskHistoryByRunId(String runId) throws SQLException {
        List<TaskHistory> histories = new ArrayList<>();
        String sql = "SELECT * FROM sysaux.v$scheduler_task_history WHERE run_id = ? ORDER BY start_time DESC";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    histories.add(mapHistory(rs));
                }
            }
        }
        return histories;
    }

    /**
     * 跨 run 的执行流水（M2 的 {@code globalHistory}）。
     *
     * <p>时间窗口用 {@code coalesce(start_time, end_time)} 过滤：正在跑的任务只有 start_time，
     * 老数据可能只有 end_time，两者都算"发生在这个窗口里"。</p>
     *
     * @param from     起始（含），null 表示不限
     * @param to       结束（不含），null 表示不限
     * @param runId    可选：只看某个实例
     * @param taskName 可选：只看某个任务
     * @param limit    最多返回多少条（服务端已 clamp）
     */
    public List<TaskHistory> listHistory(java.time.LocalDateTime from, java.time.LocalDateTime to,
                                         String runId, String taskName, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM sysaux.v$scheduler_task_history WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append(" AND coalesce(start_time, end_time) >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND coalesce(start_time, end_time) < ?");
            params.add(to);
        }
        if (runId != null && !runId.isBlank()) {
            sql.append(" AND run_id = ?");
            params.add(runId);
        }
        if (taskName != null && !taskName.isBlank()) {
            sql.append(" AND task_name = ?");
            params.add(taskName);
        }
        sql.append(" ORDER BY coalesce(start_time, end_time) DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 2000)));

        List<TaskHistory> histories = new ArrayList<>();
        try (PreparedStatement ps = dbConnection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    histories.add(mapHistory(rs));
                }
            }
        }
        return histories;
    }

    /** 时间窗口内的成功/失败/进行中计数（控制台顶部一句话用）。 */
    public Map<String, Integer> historySummary(java.time.LocalDateTime from, java.time.LocalDateTime to)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT sum(CASE WHEN end_time IS NULL THEN 1 ELSE 0 END) AS running, "
                        + "sum(CASE WHEN end_time IS NOT NULL AND ret_code = 0 THEN 1 ELSE 0 END) AS success, "
                        + "sum(CASE WHEN end_time IS NOT NULL AND coalesce(ret_code, -1) <> 0 THEN 1 ELSE 0 END) AS failed "
                        + "FROM sysaux.v$scheduler_task_history WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append(" AND coalesce(start_time, end_time) >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND coalesce(start_time, end_time) < ?");
            params.add(to);
        }
        Map<String, Integer> summary = new java.util.LinkedHashMap<>();
        summary.put("running", 0);
        summary.put("success", 0);
        summary.put("failed", 0);
        try (PreparedStatement ps = dbConnection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    summary.put("running", rs.getInt("running"));
                    summary.put("success", rs.getInt("success"));
                    summary.put("failed", rs.getInt("failed"));
                }
            }
        }
        return summary;
    }

    private TaskHistory mapHistory(ResultSet rs) throws SQLException {
        TaskHistory history = new TaskHistory();
        history.setTaskId(rs.getLong("task_id"));
        history.setRunId(rs.getString("run_id"));
        history.setTaskName(rs.getString("task_name"));
        history.setRunIdRef(rs.getString("run_id_ref"));
        history.setRetCode(rs.getInt("ret_code"));
        history.setRetMsg(rs.getString("ret_msg"));
        history.setStartTime(rs.getObject("start_time", LocalDateTime.class));
        history.setEndTime(rs.getObject("end_time", LocalDateTime.class));
        history.setLogFile(rs.getString("log_file"));
        return history;
    }
}
