package org.slackerdb.dbsyncer.state;

import org.slackerdb.dbsyncer.model.TxPosition;
import org.slackerdb.dbsyncer.util.SqlIdentifiers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * sysaux.v$dbsync_* 的读写。
 *
 * <p>检查点写入方法接收一个外部 {@link Connection}：它必须与目标数据写入处于同一个
 * DuckDB 事务，才能保证"整批数据 + 位点"原子提交。</p>
 */
public final class SyncStateStore {

    private final Connection connection;
    private final String catalog;

    public SyncStateStore(Connection connection, String catalog) {
        this.connection = connection;
        this.catalog = catalog;
    }

    private String sysaux() {
        return SqlIdentifiers.qualifiedSchema(catalog, "sysaux");
    }

    private String table(String name) {
        return sysaux() + "." + name;
    }

    public synchronized void initialize() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + sysaux());
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                      job_name VARCHAR PRIMARY KEY,
                      config_hash VARCHAR,
                      state VARCHAR,
                      phase VARCHAR,
                      source_type VARCHAR,
                      target_catalog VARCHAR,
                      target_schema VARCHAR,
                      started_at TIMESTAMP,
                      stopped_at TIMESTAMP,
                      last_error VARCHAR,
                      updated_at TIMESTAMP
                    )
                    """.formatted(table("v$dbsync_job")));
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                      job_name VARCHAR PRIMARY KEY,
                      source_type VARCHAR,
                      lsn BIGINT,
                      binlog_file VARCHAR,
                      binlog_pos BIGINT,
                      rows_applied BIGINT,
                      updated_at TIMESTAMP
                    )
                    """.formatted(table("v$dbsync_checkpoint")));
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                      job_name VARCHAR,
                      source_schema VARCHAR,
                      source_table VARCHAR,
                      target_schema VARCHAR,
                      target_table VARCHAR,
                      snapshot_done BOOLEAN,
                      snapshot_rows BIGINT,
                      last_op VARCHAR,
                      last_event_at TIMESTAMP,
                      PRIMARY KEY (job_name, source_schema, source_table)
                    )
                    """.formatted(table("v$dbsync_table")));
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                      run_id VARCHAR PRIMARY KEY,
                      job_name VARCHAR,
                      started_at TIMESTAMP,
                      ended_at TIMESTAMP,
                      state VARCHAR,
                      error VARCHAR,
                      rows_read BIGINT,
                      rows_applied BIGINT
                    )
                    """.formatted(table("v$dbsync_run")));
        }
    }

    /** 服务重启后，把上次残留的运行态统一标为 FAILED，并关闭未结束的运行记录。 */
    public synchronized void recoverInterruptedJobs() throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table("v$dbsync_job")
                        + " SET state = 'FAILED', phase = 'FAILED', last_error = 'server restart', updated_at = ?"
                        + " WHERE state IN ('STARTING','SNAPSHOT','STREAMING','STOPPING')")) {
            statement.setTimestamp(1, now);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table("v$dbsync_run")
                        + " SET ended_at = ?, state = 'FAILED', error = 'server restart'"
                        + " WHERE ended_at IS NULL")) {
            statement.setTimestamp(1, now);
            statement.executeUpdate();
        }
    }

    public synchronized void upsertJob(String jobName, String configHash, String state, String phase,
                                       String sourceType, String targetCatalog, String targetSchema)
            throws SQLException {
        String sql = "INSERT INTO " + table("v$dbsync_job")
                + " (job_name, config_hash, state, phase, source_type, target_catalog, target_schema,"
                + "  started_at, stopped_at, last_error, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (job_name) DO UPDATE SET"
                + "  config_hash=excluded.config_hash, state=excluded.state, phase=excluded.phase,"
                + "  source_type=excluded.source_type, target_catalog=excluded.target_catalog,"
                + "  target_schema=excluded.target_schema, started_at=excluded.started_at,"
                + "  stopped_at=NULL, updated_at=excluded.updated_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setString(1, jobName);
            statement.setString(2, configHash);
            statement.setString(3, state);
            statement.setString(4, phase);
            statement.setString(5, sourceType);
            statement.setString(6, targetCatalog);
            statement.setString(7, targetSchema);
            statement.setTimestamp(8, now);
            statement.setTimestamp(9, null);
            statement.setString(10, null);
            statement.setTimestamp(11, now);
            statement.executeUpdate();
        }
    }

    public synchronized void updateJobState(String jobName, String state, String phase, String error)
            throws SQLException {
        String sql = "UPDATE " + table("v$dbsync_job")
                + " SET state=?, phase=?, last_error=?, updated_at=? WHERE job_name=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state);
            statement.setString(2, phase);
            statement.setString(3, truncate(error, 4000));
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.setString(5, jobName);
            statement.executeUpdate();
        }
    }

    public synchronized void markStopped(String jobName, String state) throws SQLException {
        String sql = "UPDATE " + table("v$dbsync_job")
                + " SET state=?, phase=?, stopped_at=?, updated_at=? WHERE job_name=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setString(1, state);
            // phase 与 state 保持一致（历史上这里固定写 'STOPPED'，和 state 会矛盾）。
            statement.setString(2, state);
            statement.setTimestamp(3, now);
            statement.setTimestamp(4, now);
            statement.setString(5, jobName);
            statement.executeUpdate();
        }
    }

    public synchronized Checkpoint loadCheckpoint(String jobName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source_type, lsn, binlog_file, binlog_pos"
                        + " FROM " + table("v$dbsync_checkpoint") + " WHERE job_name=?")) {
            statement.setString(1, jobName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Checkpoint(
                        rs.getString(1),
                        rs.getLong(2),
                        rs.getString(3),
                        rs.getLong(4)
                );
            }
        }
    }

    /** 必须在目标写入事务内调用。 */
    public void saveCheckpoint(Connection tx, String jobName, TxPosition position, long rowsApplied)
            throws SQLException {
        String sql = "INSERT INTO " + table("v$dbsync_checkpoint")
                + " (job_name, source_type, lsn, binlog_file, binlog_pos, rows_applied, updated_at)"
                + " VALUES (?,?,?,?,?,?,?)"
                + " ON CONFLICT (job_name) DO UPDATE SET"
                + "  source_type=excluded.source_type, lsn=excluded.lsn, binlog_file=excluded.binlog_file,"
                + "  binlog_pos=excluded.binlog_pos, rows_applied=excluded.rows_applied,"
                + "  updated_at=excluded.updated_at";
        try (PreparedStatement statement = tx.prepareStatement(sql)) {
            statement.setString(1, jobName);
            statement.setString(2, position.typeName());
            statement.setLong(3, position.lsn);
            statement.setString(4, position.binlogFile);
            statement.setLong(5, position.binlogPosition);
            statement.setLong(6, rowsApplied);
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    /**
     * 必须在目标写入连接上调用。
     *
     * <p>{@code v$dbsync_checkpoint} / {@code v$dbsync_table} 也由批次事务写；DuckDB 不允许两个连接
     * 同时写同一张表，所以这两张表的写入（含清理）只能走 {@code DuckDbTarget} 的单写线程。</p>
     */
    public void clearCheckpoint(Connection tx, String jobName) throws SQLException {
        try (PreparedStatement statement = tx.prepareStatement(
                "DELETE FROM " + table("v$dbsync_checkpoint") + " WHERE job_name=?")) {
            statement.setString(1, jobName);
            statement.executeUpdate();
        }
    }

    /** 必须在目标写入连接上调用，理由见 {@link #clearCheckpoint(Connection, String)}。 */
    public void clearTableStates(Connection tx, String jobName) throws SQLException {
        try (PreparedStatement statement = tx.prepareStatement(
                "DELETE FROM " + table("v$dbsync_table") + " WHERE job_name=?")) {
            statement.setString(1, jobName);
            statement.executeUpdate();
        }
    }

    public synchronized Map<String, TableState> loadTableStates(String jobName) throws SQLException {
        Map<String, TableState> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source_schema, source_table, target_schema, target_table, snapshot_done,"
                        + " snapshot_rows, last_op, last_event_at FROM " + table("v$dbsync_table")
                        + " WHERE job_name=?")) {
            statement.setString(1, jobName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    TableState state = new TableState(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getBoolean(5), rs.getLong(6), rs.getString(7), rs.getTimestamp(8));
                    result.put(key(state.sourceSchema, state.sourceTable), state);
                }
            }
        }
        return result;
    }

    /** 必须在目标写入事务内调用。 */
    public void upsertTableState(Connection tx, String jobName, String sourceSchema, String sourceTable,
                                 String targetSchema, String targetTable, boolean snapshotDone,
                                 long snapshotRows, String lastOp, Timestamp lastEventAt)
            throws SQLException {
        String sql = "INSERT INTO " + table("v$dbsync_table")
                + " (job_name, source_schema, source_table, target_schema, target_table,"
                + "  snapshot_done, snapshot_rows, last_op, last_event_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (job_name, source_schema, source_table) DO UPDATE SET"
                + "  target_schema=excluded.target_schema, target_table=excluded.target_table,"
                + "  snapshot_done=excluded.snapshot_done, snapshot_rows=excluded.snapshot_rows,"
                + "  last_op=excluded.last_op, last_event_at=excluded.last_event_at";
        try (PreparedStatement statement = tx.prepareStatement(sql)) {
            statement.setString(1, jobName);
            statement.setString(2, sourceSchema);
            statement.setString(3, sourceTable);
            statement.setString(4, targetSchema);
            statement.setString(5, targetTable);
            statement.setBoolean(6, snapshotDone);
            statement.setLong(7, snapshotRows);
            statement.setString(8, lastOp);
            statement.setTimestamp(9, lastEventAt);
            statement.executeUpdate();
        }
    }

    /** 增量批提交时更新表级最近操作；必须在目标事务内调用。 */
    public void touchTableState(Connection tx, String jobName, String sourceSchema, String sourceTable,
                                String lastOp, Timestamp lastEventAt) throws SQLException {
        try (PreparedStatement statement = tx.prepareStatement(
                "UPDATE " + table("v$dbsync_table")
                        + " SET last_op=?, last_event_at=? WHERE job_name=? AND source_schema=? AND source_table=?")) {
            statement.setString(1, lastOp);
            statement.setTimestamp(2, lastEventAt);
            statement.setString(3, jobName);
            statement.setString(4, sourceSchema);
            statement.setString(5, sourceTable);
            statement.executeUpdate();
        }
    }

    public synchronized void insertRun(String runId, String jobName, String state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table("v$dbsync_run")
                        + " (run_id, job_name, started_at, ended_at, state, error, rows_read, rows_applied)"
                        + " VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setString(1, runId);
            statement.setString(2, jobName);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.setTimestamp(4, null);
            statement.setString(5, state);
            statement.setString(6, null);
            statement.setLong(7, 0);
            statement.setLong(8, 0);
            statement.executeUpdate();
        }
    }

    public synchronized void finishRun(String runId, String state, String error,
                                       long rowsRead, long rowsApplied) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table("v$dbsync_run")
                        + " SET ended_at=?, state=?, error=?, rows_read=?, rows_applied=? WHERE run_id=?")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setString(2, state);
            statement.setString(3, truncate(error, 4000));
            statement.setLong(4, rowsRead);
            statement.setLong(5, rowsApplied);
            statement.setString(6, runId);
            statement.executeUpdate();
        }
    }

    /** 最近的运行记录（新到旧）。 */
    public synchronized List<RunRecord> loadRuns(String jobName, int limit) throws SQLException {        List<RunRecord> result = new ArrayList<>();
        String sql = "SELECT run_id, started_at, ended_at, state, error, rows_read, rows_applied"
                + " FROM " + table("v$dbsync_run")
                + " WHERE job_name=? ORDER BY started_at DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jobName);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new RunRecord(
                            rs.getString(1),
                            rs.getTimestamp(2),
                            rs.getTimestamp(3),
                            rs.getString(4),
                            rs.getString(5),
                            rs.getLong(6),
                            rs.getLong(7)));
                }
            }
        }
        return result;
    }

    private static String key(String schema, String table) {
        return (schema + "." + table).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public static final class RunRecord {
        public final String runId;
        public final Timestamp startedAt;
        public final Timestamp endedAt;
        public final String state;
        public final String error;
        public final long rowsRead;
        public final long rowsApplied;

        RunRecord(String runId, Timestamp startedAt, Timestamp endedAt, String state, String error,
                  long rowsRead, long rowsApplied) {
            this.runId = runId;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.state = state;
            this.error = error;
            this.rowsRead = rowsRead;
            this.rowsApplied = rowsApplied;
        }
    }

    public static final class Checkpoint {
        public final String sourceType;
        public final long lsn;
        public final String binlogFile;
        public final long binlogPosition;

        Checkpoint(String sourceType, long lsn, String binlogFile, long binlogPosition) {
            this.sourceType = sourceType;
            this.lsn = lsn;
            this.binlogFile = binlogFile;
            this.binlogPosition = binlogPosition;
        }

        public TxPosition toPosition() {
            if ("pg_lsn".equalsIgnoreCase(sourceType)) {
                return TxPosition.pg(lsn);
            }
            return TxPosition.mysql(binlogFile, binlogPosition);
        }
    }

    public static final class TableState {
        public final String sourceSchema;
        public final String sourceTable;
        public final String targetSchema;
        public final String targetTable;
        public final boolean snapshotDone;
        public final long snapshotRows;
        public final String lastOp;
        public final Timestamp lastEventAt;

        TableState(String sourceSchema, String sourceTable, String targetSchema, String targetTable,
                   boolean snapshotDone, long snapshotRows, String lastOp, Timestamp lastEventAt) {
            this.sourceSchema = sourceSchema;
            this.sourceTable = sourceTable;
            this.targetSchema = targetSchema;
            this.targetTable = targetTable;
            this.snapshotDone = snapshotDone;
            this.snapshotRows = snapshotRows;
            this.lastOp = lastOp;
            this.lastEventAt = lastEventAt;
        }
    }
}
