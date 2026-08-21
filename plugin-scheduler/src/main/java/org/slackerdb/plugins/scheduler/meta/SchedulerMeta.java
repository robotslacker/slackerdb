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
                "task_parallel_policy VARCHAR DEFAULT 'PARALLEL', " +
                "task_crontab_expr VARCHAR, " +
                "task_interval INTEGER DEFAULT 0, " +
                "task_timeout INTEGER DEFAULT 0, " +
                "task_enabled BOOLEAN DEFAULT true, " +
                "task_group VARCHAR, " +
                "task_startup_order INTEGER DEFAULT 0, " +
                "task_description VARCHAR, " +
                "config_json VARCHAR, " +
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
     * Update run status.
     */
    public void updateRunStatus(String runId, String status) throws SQLException {
        String sql = "UPDATE sysaux.v$scheduler_run SET status = ?, update_time = ? WHERE run_id = ?";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setObject(2, LocalDateTime.now());
            ps.setString(3, runId);
            ps.executeUpdate();
        }
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
                "task_fail_policy, task_parallel_policy, task_crontab_expr, task_interval, " +
                "task_timeout, task_enabled, task_group, task_startup_order, task_description, " +
                "config_json, create_time, update_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setString(1, task.getRunId());
            ps.setString(2, task.getTaskName());
            ps.setString(3, task.getTaskScript());
            ps.setString(4, task.getTaskScriptType());
            ps.setString(5, task.getTaskRunPolicy());
            ps.setString(6, task.getTaskFailPolicy());
            ps.setString(7, task.getTaskParallelPolicy());
            ps.setString(8, task.getTaskCrontabExpr());
            ps.setInt(9, task.getTaskInterval());
            ps.setInt(10, task.getTaskTimeout());
            ps.setBoolean(11, task.isTaskEnabled());
            ps.setString(12, task.getTaskGroup());
            ps.setInt(13, task.getTaskStartupOrder());
            ps.setString(14, task.getTaskDescription());
            ps.setString(15, task.getConfigJson());
            ps.setObject(16, task.getCreateTime());
            ps.setObject(17, task.getUpdateTime());
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
        task.setTaskParallelPolicy(rs.getString("task_parallel_policy"));
        task.setTaskCrontabExpr(rs.getString("task_crontab_expr"));
        task.setTaskInterval(rs.getInt("task_interval"));
        task.setTaskTimeout(rs.getInt("task_timeout"));
        task.setTaskEnabled(rs.getBoolean("task_enabled"));
        task.setTaskGroup(rs.getString("task_group"));
        task.setTaskStartupOrder(rs.getInt("task_startup_order"));
        task.setTaskDescription(rs.getString("task_description"));
        task.setConfigJson(rs.getString("config_json"));
        task.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        task.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return task;
    }

    // ========== Task History CRUD ==========

    /**
     * Insert a task history record.
     */
    public void insertTaskHistory(TaskHistory history) throws SQLException {
        String sql = "INSERT INTO sysaux.v$scheduler_task_history (" +
                "task_id, run_id, task_name, run_id_ref, ret_code, ret_msg, start_time, end_time, log_file) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            ps.setLong(1, history.getTaskId());
            ps.setString(2, history.getRunId());
            ps.setString(3, history.getTaskName());
            ps.setString(4, history.getRunIdRef());
            ps.setInt(5, history.getRetCode());
            ps.setString(6, history.getRetMsg());
            ps.setObject(7, history.getStartTime());
            ps.setObject(8, history.getEndTime());
            ps.setString(9, history.getLogFile());
            ps.executeUpdate();
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
