package org.slackerdb.dbsyncer.target;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slackerdb.dbsyncer.config.SyncConfig;
import org.slackerdb.dbsyncer.model.ChangeBatch;
import org.slackerdb.dbsyncer.model.ChangeEvent;
import org.slackerdb.dbsyncer.model.TxPosition;
import org.slackerdb.dbsyncer.model.SourceTable;
import org.slackerdb.dbsyncer.model.TableMapping;
import org.slackerdb.dbsyncer.model.TargetTable;
import org.slackerdb.dbsyncer.state.SyncStateStore;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDbWriterTest {

    private Connection connection;
    private DuckDbTarget target;
    private SyncStateStore stateStore;
    private DuckDbWriter writer;
    private TableMapping mapping;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        SyncStateStore state = new SyncStateStore(connection, "memory");
        state.initialize();
        this.stateStore = state;

        DuckDbHost host = new DuckDbHost() {
            @Override
            public Connection duplicate() {
                return connection;
            }

            @Override
            public String catalog() {
                return "memory";
            }
        };
        this.target = new DuckDbTarget(host, LoggerFactory.getLogger(DuckDbWriterTest.class));
        this.writer = new DuckDbWriter(target, stateStore,
                LoggerFactory.getLogger(DuckDbWriterTest.class));

        SourceTable source = new SourceTable("public", "t", "postgres");
        source.columns.add(new SourceTable.SourceColumn("id", "int8", java.sql.Types.BIGINT, 0, 0));
        source.columns.add(new SourceTable.SourceColumn("name", "text", java.sql.Types.VARCHAR, 0, 0));
        source.columns.add(new SourceTable.SourceColumn("note", "text", java.sql.Types.VARCHAR, 0, 0));
        source.primaryKey.add("id");

        TargetTable targetTable = new TargetTable("memory", "sync", "t");
        targetTable.add(new TargetTable.TargetColumn("id", "BIGINT", "id", null));
        targetTable.add(new TargetTable.TargetColumn("name", "VARCHAR", "name", null));
        targetTable.add(new TargetTable.TargetColumn("note", "VARCHAR", "note", null));
        targetTable.primaryKey.add("id");
        this.mapping = new TableMapping(source, targetTable, "job1");
        writer.prepare(List.of(mapping));
    }

    @AfterEach
    void tearDown() throws Exception {
        target.close();
    }

    @Test
    void snapshotResetsTableState() throws Exception {
        assertTrue(writer.beginSnapshot(mapping, SyncConfig.SnapshotMode.REPLACE));
        SyncStateStore.TableState during = stateStore.loadTableStates("job1").get("public.t");
        assertNotNull(during, "beginSnapshot 必须重置表状态");
        assertFalse(during.snapshotDone, "重灌开始后应标记为未完成");
        assertEquals(0, during.snapshotRows, "重灌开始后行数应清零");

        writer.snapshotChunk(mapping, List.of(row(1, "a"), row(2, "b")), 2);
        writer.finishSnapshot(mapping, 2);

        SyncStateStore.TableState done = stateStore.loadTableStates("job1").get("public.t");
        assertTrue(done.snapshotDone);
        assertEquals(2, done.snapshotRows);
        assertEquals(2, count());
    }

    @Test
    void appliesInsertUpdateDeleteInOneTransaction() throws Exception {
        writer.apply("job1", batch(ChangeEvent.insert("public", "t", row(1, "a"), 1), 1), byKey(),
                Integer.MAX_VALUE);
        assertEquals("a", value(1, "name"));

        writer.apply("job1", batch(ChangeEvent.update("public", "t", row(1, "a"), row(1, "b"),
                java.util.Set.of(), 2), 2), byKey(), Integer.MAX_VALUE);
        assertEquals("b", value(1, "name"));

        writer.apply("job1", batch(ChangeEvent.delete("public", "t", row(1, "b"), 3), 3), byKey(),
                Integer.MAX_VALUE);
        assertEquals(null, value(1, "name"));
        assertEquals(0, count());

        SyncStateStore.Checkpoint checkpoint = stateStore.loadCheckpoint("job1");
        assertNotNull(checkpoint);
        assertEquals(TxPosition.pg(3).lsn, checkpoint.lsn);
    }

    @Test
    void snapshotWritesChunksAndMarksDone() throws Exception {
        writer.beginSnapshot(mapping, SyncConfig.SnapshotMode.REPLACE);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(sourceRow(10, "x"));
        rows.add(sourceRow(11, "y"));
        writer.snapshotChunk(mapping, rows, 2);
        writer.finishSnapshot(mapping, 2);

        assertEquals(2, count());
        Map<String, SyncStateStore.TableState> states = stateStore.loadTableStates("job1");
        assertNotNull(states.get("public.t"));
        assertTrue(states.get("public.t").snapshotDone);
    }

    @Test
    void insertIsAppendAndDuplicateKeyFailsTheBatch() throws Exception {
        List<ChangeEvent> events = new ArrayList<>();
        events.add(ChangeEvent.insert("public", "t", row(1, "old"), 1));
        events.add(ChangeEvent.insert("public", "t", row(1, "new"), 2));
        // INSERT 一律 append，不做冲突检查：同一段里出现同主键会整批失败（不会静默覆盖）。
        assertThrows(SQLException.class, () -> writer.apply("job1",
                new ChangeBatch(events, TxPosition.pg(2)), byKey(), Integer.MAX_VALUE));
        assertEquals(0, count());
        assertNull(stateStore.loadCheckpoint("job1"));
    }

    @Test
    void splitsLargeTransactionsByMaxRows() throws Exception {
        List<ChangeEvent> events = new ArrayList<>();
        for (int i = 10; i < 15; i++) {
            events.add(ChangeEvent.insert("public", "t", row(i, "n" + i), 100 + i));
        }
        long applied = writer.apply("job1", new ChangeBatch(events, TxPosition.pg(200)),
                byKey(), 2);
        assertEquals(5, applied);
        assertEquals(5, count());
        assertEquals(200, stateStore.loadCheckpoint("job1").lsn);
    }

    @Test
    void failedChunkRollsBackWholeBatchWithoutAdvancingCheckpoint() throws Exception {
        writer.apply("job1", batch(ChangeEvent.insert("public", "t", row(0, "base"), 1), 1), byKey(),
                Integer.MAX_VALUE);
        List<ChangeEvent> events = new ArrayList<>();
        events.add(ChangeEvent.insert("public", "t", row(1, "ok"), 2));
        events.add(ChangeEvent.insert("public", "t", Map.of("id", "bad", "name", "bad"), 3));
        assertThrows(SQLException.class, () -> writer.apply("job1",
                new ChangeBatch(events, TxPosition.pg(2)), byKey(), 1));
        assertEquals(1, count());
        assertEquals(1, stateStore.loadCheckpoint("job1").lsn);
    }

    @Test
    void updateKeepsColumnsThatAreMissingFromTheEvent() throws Exception {
        writer.apply("job1", batch(ChangeEvent.insert("public", "t", row(1, "keep-me"), 1), 1),
                byKey(), Integer.MAX_VALUE);

        // PG 的未变化 TOAST 列不会出现在 after 里，只出现在 missingColumns 中。
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", 1L);
        after.put("note", "changed");
        ChangeEvent update = ChangeEvent.update("public", "t", row(1, "keep-me"), after,
                java.util.Set.of("name"), 2);
        writer.apply("job1", batch(update, 2), byKey(), Integer.MAX_VALUE);

        assertEquals("keep-me", value(1, "name"));
        assertEquals("changed", value(1, "note"));
        assertEquals(1, count());
    }

    @Test
    void updateWithMissingColumnsInsertsWhenTargetRowIsAbsent() throws Exception {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", 99L);
        after.put("note", "new");
        ChangeEvent update = ChangeEvent.update("public", "t", Map.of(), after,
                java.util.Set.of("name"), 1);
        writer.apply("job1", batch(update, 1), byKey(), Integer.MAX_VALUE);

        assertEquals(1, count());
        assertEquals(null, value(99, "name"));
        assertEquals("new", value(99, "note"));
    }

    @Test
    @Timeout(30)
    void ddlInsideTransactionRunsInlineAndCommitsCheckpoint() throws Exception {
        // MySQL DDL 在单写线程的 DuckDB 事务里执行；期间再调用 prepare() 必须就地执行，
        // 而不是再次提交到同一个单线程执行器（那会自锁）。
        TableMapping other = mappingFor("u");
        writer.applyDdlTransaction("job1", TxPosition.pg(7), () -> writer.prepare(List.of(other)));

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM information_schema.tables WHERE table_name='u'")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
        assertEquals(7, stateStore.loadCheckpoint("job1").lsn);
    }

    private ChangeBatch batch(ChangeEvent event, long lsn) {
        return new ChangeBatch(List.of(event), TxPosition.pg(lsn));
    }

    private Map<String, TableMapping> byKey() {
        Map<String, TableMapping> result = new LinkedHashMap<>();
        result.put("public.t", mapping);
        return result;
    }

    private static TableMapping mappingFor(String table) {
        SourceTable source = new SourceTable("public", table, "postgres");
        source.columns.add(new SourceTable.SourceColumn("id", "int8", java.sql.Types.BIGINT, 0, 0));
        source.primaryKey.add("id");
        TargetTable targetTable = new TargetTable("memory", "sync", table);
        targetTable.add(new TargetTable.TargetColumn("id", "BIGINT", "id", null));
        targetTable.primaryKey.add("id");
        return new TableMapping(source, targetTable, "job1");
    }

    private static Map<String, Object> row(long id, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        return row;
    }

    private static Map<String, Object> sourceRow(long id, String name) {
        return row(id, name);
    }

    private Object value(long id, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT " + column + " FROM \"memory\".\"sync\".\"t\" WHERE id=" + id)) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    private int count() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM \"memory\".\"sync\".\"t\"")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
