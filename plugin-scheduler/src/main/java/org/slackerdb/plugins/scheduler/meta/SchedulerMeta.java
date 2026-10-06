package org.slackerdb.plugins.scheduler.meta;

import org.slackerdb.plugins.scheduler.entity.SchedulerRun;
import org.slackerdb.plugins.scheduler.entity.SchedulerTask;
import org.slackerdb.plugins.scheduler.entity.TaskHistory;
import org.slf4j.Logger;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
                "task_script_type VARCHAR DEFAULT 'SQL', " +
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
                    logger.warn("[SCHEDULER] 元数据迁移跳过[{}]: {}", migration, e.getMessage());
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
