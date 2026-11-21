package org.slackerdb.dbsyncer.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbsyncer.model.TxPosition;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 状态表读写：检查点、表状态、作业状态、运行历史。 */
class SyncStateStoreTest {

    private Connection connection;
    private SyncStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        store = new SyncStateStore(connection, "memory");
        store.initialize();
    }

    @Test
    void initializeIsIdempotent() throws Exception {
        store.initialize();
        assertTrue(tableExists("v$dbsync_job"));
        assertTrue(tableExists("v$dbsync_checkpoint"));
        assertTrue(tableExists("v$dbsync_table"));
        assertTrue(tableExists("v$dbsync_run"));
    }

    @Test
    void checkpointRoundTripAndClear() throws Exception {
        assertNull(store.loadCheckpoint("job"));

        store.saveCheckpoint(connection, "job", TxPosition.pg(12345), 7);
        SyncStateStore.Checkpoint pg = store.loadCheckpoint("job");
        assertNotNull(pg);
        assertEquals(12345, pg.lsn);
        assertTrue(pg.toPosition().isPg());
        assertEquals("0/3039", pg.toPosition().display());

        store.saveCheckpoint(connection, "job", TxPosition.mysql("binlog.000005", 4321), 0);
        SyncStateStore.Checkpoint mysql = store.loadCheckpoint("job");
        assertEquals("binlog.000005", mysql.binlogFile);
        assertEquals(4321, mysql.binlogPosition);
        assertFalse(mysql.toPosition().isPg());

        store.clearCheckpoint(connection, "job");
        assertNull(store.loadCheckpoint("job"));
    }

    @Test
    void tableStatesRoundTripAndClear() throws Exception {
        store.upsertTableState(connection, "job", "public", "orders", "sync", "orders",
                false, 100, "INSERT", Timestamp.valueOf("2026-01-02 03:04:05"));
        Map<String, SyncStateStore.TableState> states = store.loadTableStates("job");
        SyncStateStore.TableState state = states.get("public.orders");
        assertNotNull(state);
        assertFalse(state.snapshotDone);
        assertEquals(100, state.snapshotRows);
        assertEquals("INSERT", state.lastOp);

        store.touchTableState(connection, "job", "public", "orders", "UPDATE",
                Timestamp.valueOf("2026-01-02 03:05:00"));
        assertEquals("UPDATE", store.loadTableStates("job").get("public.orders").lastOp);

        store.upsertTableState(connection, "job", "public", "orders", "sync", "orders",
                true, 120, "UPDATE", Timestamp.valueOf("2026-01-02 03:06:00"));
        assertTrue(store.loadTableStates("job").get("public.orders").snapshotDone);

        store.clearTableStates(connection, "job");
        assertTrue(store.loadTableStates("job").isEmpty());
    }

    @Test
    void jobStateStartsAndStops() throws Exception {
        store.upsertJob("job", "hash1", "STARTING", "STARTING", "postgres", "memory", "sync");
        Timestamp firstStart = queryTimestamp("SELECT started_at FROM \"memory\".sysaux.v$dbsync_job"
                + " WHERE job_name='job'");
        assertNotNull(firstStart);
        assertNull(queryTimestamp("SELECT stopped_at FROM \"memory\".sysaux.v$dbsync_job"
                + " WHERE job_name='job'"));

        store.markStopped("job", "PAUSED");
        assertEquals("PAUSED", queryString("SELECT state FROM \"memory\".sysaux.v$dbsync_job"
                + " WHERE job_name='job'"));
        assertEquals("PAUSED", queryString("SELECT phase FROM \"memory\".sysaux.v$dbsync_job"
                + " WHERE job_name='job'"), "phase 与 state 必须一致");
        assertNotNull(queryTimestamp("SELECT stopped_at FROM \"memory\".sysaux.v$dbsync_job"
                + " WHERE job_name='job'"));

        Thread.sleep(5);
        store.upsertJob("job", "hash2", "STARTING", "STARTING", "postgres", "memory", "sync");
        Timestamp secondStart = queryTimestamp("SELECT started_at FROM \"memory\".sysaux.v$dbsync_job"
                + " WHERE job_name='job'");
        assertNotNull(secondStart);
        assertTrue(!secondStart.before(firstStart), "重新启动必须刷新 started_at");
        assertNull(queryTimestamp("SELECT stopped_at FROM \"memory\".sysaux.v$dbsync_job"
                + " WHERE job_name='job'"), "重新启动必须清空 stopped_at");
        assertEquals("hash2", queryString("SELECT config_hash FROM \"memory\".sysaux.v$dbsync_job"
                + " WHERE job_name='job'"));
    }

    @Test
    void recoverClosesInterruptedJobsAndRuns() throws Exception {
        store.upsertJob("job", "hash", "STREAMING", "STREAMING", "postgres", "memory", "sync");
        store.insertRun("run-1", "job", "RUNNING");

        store.recoverInterruptedJobs();

        assertEquals("FAILED", queryString("SELECT state FROM \"memory\".sysaux.v$dbsync_job"
                + " WHERE job_name='job'"));
        assertEquals("FAILED", queryString("SELECT state FROM \"memory\".sysaux.v$dbsync_run"
                + " WHERE run_id='run-1'"));
        assertNotNull(queryTimestamp("SELECT ended_at FROM \"memory\".sysaux.v$dbsync_run"
                + " WHERE run_id='run-1'"), "重启后必须闭合未结束的运行记录");
    }

    @Test
    void loadRunsReturnsNewestFirstAndHonoursLimit() throws Exception {
        store.insertRun("run-1", "job", "RUNNING");
        store.finishRun("run-1", "STOPPED", null, 10, 9);
        Thread.sleep(5);
        store.insertRun("run-2", "job", "RUNNING");

        List<SyncStateStore.RunRecord> latest = store.loadRuns("job", 1);
        assertEquals(1, latest.size());
        assertEquals("run-2", latest.get(0).runId);
        assertNull(latest.get(0).endedAt);

        List<SyncStateStore.RunRecord> all = store.loadRuns("job", 10);
        assertEquals(2, all.size());
        assertEquals("run-2", all.get(0).runId);
        assertEquals("run-1", all.get(1).runId);
        assertEquals(10, all.get(1).rowsRead);
        assertEquals(9, all.get(1).rowsApplied);
        assertNotNull(all.get(1).endedAt);

        assertTrue(store.loadRuns("other", 10).isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private boolean tableExists(String name) throws SQLException {
        return queryInt("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_catalog='memory' AND table_schema='sysaux' AND table_name='" + name + "'") > 0;
    }

    private int queryInt(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private Timestamp queryTimestamp(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getTimestamp(1) : null;
        }
    }
}
