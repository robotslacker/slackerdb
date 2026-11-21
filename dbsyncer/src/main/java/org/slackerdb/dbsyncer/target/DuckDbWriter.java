package org.slackerdb.dbsyncer.target;

import org.slackerdb.dbsyncer.config.SyncConfig;
import org.slackerdb.dbsyncer.model.ChangeBatch;
import org.slackerdb.dbsyncer.model.ChangeEvent;
import org.slackerdb.dbsyncer.model.DdlAction;
import org.slackerdb.dbsyncer.model.DdlWriter;
import org.slackerdb.dbsyncer.model.TableMapping;
import org.slackerdb.dbsyncer.model.TargetTable;
import org.slackerdb.dbsyncer.model.TxPosition;
import org.slackerdb.dbsyncer.state.SyncStateStore;
import org.slackerdb.dbsyncer.util.SqlIdentifiers;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * DuckDB 写入器：集合式 INSERT（append）/ INSERT OR REPLACE / DELETE，全作业共用一个事务连接。
 *
 * <p>所有方法都必须经由 {@link DuckDbTarget} 的单写线程执行；DDL 事务上下文按线程保存，
 * 保证一个作业的 DDL 不会被另一个作业看到。</p>
 */
public final class DuckDbWriter implements DdlWriter {

    private static final int MAX_ROWS_PER_STATEMENT = 1000;
    private static final int MAX_PARAMS_PER_STATEMENT = 32_000;

    private final DuckDbTarget target;
    private final SyncStateStore stateStore;
    private final Logger logger;
    /** 当前线程正在执行的 DDL 事务连接；非 DDL 场景为 null。 */
    private final ThreadLocal<Connection> ddlTransaction = new ThreadLocal<>();

    public DuckDbWriter(DuckDbTarget target, SyncStateStore stateStore, Logger logger) {
        this.target = target;
        this.stateStore = stateStore;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ DDL

    public void addColumn(TableMapping mapping, TargetTable.TargetColumn column) throws SQLException {
        runDdl(() -> DuckDbDdlGenerator.addColumnIfMissing(target.connection(), mapping.target(),
                column.name(), column.duckType()));
        logger.info("[DBSYNCER][{}] target column added: {}.{} {}",
                mapping.jobName(), mapping.target().table, column.name(), column.duckType());
    }

    public void dropColumn(TableMapping mapping, String column) throws SQLException {
        runDdl(() -> {
            try (Statement statement = target.connection().createStatement()) {
                statement.execute("ALTER TABLE " + mapping.target().qualifiedName()
                        + " DROP COLUMN IF EXISTS " + SqlIdentifiers.quote(column));
            }
        });
        logger.info("[DBSYNCER][{}] target column dropped: {}.{}",
                mapping.jobName(), mapping.target().table, column);
    }

    public void renameColumn(TableMapping mapping, String oldName, String newName) throws SQLException {
        runDdl(() -> {
            try (Statement statement = target.connection().createStatement()) {
                statement.execute("ALTER TABLE " + mapping.target().qualifiedName()
                        + " RENAME COLUMN " + SqlIdentifiers.quote(oldName)
                        + " TO " + SqlIdentifiers.quote(newName));
            }
        });
        logger.info("[DBSYNCER][{}] target column renamed: {}.{} -> {}",
                mapping.jobName(), mapping.target().table, oldName, newName);
    }

    public void alterColumnType(TableMapping mapping, String column, String duckType) throws SQLException {
        runDdl(() -> {
            try (Statement statement = target.connection().createStatement()) {
                statement.execute("ALTER TABLE " + mapping.target().qualifiedName()
                        + " ALTER COLUMN " + SqlIdentifiers.quote(column) + " TYPE " + duckType);
            }
        });
        logger.info("[DBSYNCER][{}] target column type changed: {}.{} {}",
                mapping.jobName(), mapping.target().table, column, duckType);
    }

    public void truncateTable(TableMapping mapping) throws SQLException {
        runDdl(() -> DuckDbDdlGenerator.truncate(target.connection(), mapping.target()));
    }

    public void dropTable(TableMapping mapping) throws SQLException {
        runDdl(() -> {
            try (Statement statement = target.connection().createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + mapping.target().qualifiedName());
            }
        });
    }

    public void renameTable(TableMapping mapping, TargetTable newTarget) throws SQLException {
        runDdl(() -> {
            try (Statement statement = target.connection().createStatement()) {
                statement.execute("ALTER TABLE " + mapping.target().qualifiedName()
                        + " RENAME TO " + SqlIdentifiers.quote(newTarget.table));
            }
        });
    }

    /** DDL 与检查点在同一 DuckDB 事务内提交，避免 DDL 已生效但位点未推进。 */
    public void applyDdlTransaction(String jobName, TxPosition position, DdlAction action) throws SQLException {
        target.run(() -> {
            Connection connection = target.connection();
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            ddlTransaction.set(connection);
            try {
                action.run();
                stateStore.saveCheckpoint(connection, jobName, position, 0);
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                if (e instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("DDL transaction failed: " + e.getMessage(), e);
            } finally {
                ddlTransaction.remove();
                connection.setAutoCommit(previous);
            }
        });
    }

    /** DDL 已在事务中就地执行；否则提交到 DuckDB 单写线程。 */
    private void runDdl(DuckDbTarget.SqlRunnable action) throws SQLException {
        if (ddlTransaction.get() != null) {
            action.run();
        } else {
            target.run(action);
        }
    }

    // ------------------------------------------------------------------ 建表

    public void prepare(List<TableMapping> mappings) throws SQLException {
        runDdl(() -> {
            Connection connection = target.connection();
            for (TableMapping mapping : mappings) {
                DuckDbDdlGenerator.ensureSchema(connection, mapping.target().catalog,
                        mapping.target().schema);
                DuckDbDdlGenerator.createTable(connection, mapping.target());
            }
        });
    }

    /** resync：在同一事务内删除并重建所有目标表。 */
    public void rebuild(List<TableMapping> mappings) throws SQLException {
        if (ddlTransaction.get() != null) {
            throw new IllegalStateException("rebuild() must not run inside a DDL transaction");
        }
        target.run(() -> {
            Connection connection = target.connection();
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (TableMapping mapping : mappings) {
                    DuckDbDdlGenerator.ensureSchema(connection, mapping.target().catalog,
                            mapping.target().schema);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("DROP TABLE IF EXISTS " + mapping.target().qualifiedName());
                    }
                }
                for (TableMapping mapping : mappings) {
                    DuckDbDdlGenerator.createTable(connection, mapping.target());
                }
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                connection.setAutoCommit(previous);
            }
        });
    }

    // ------------------------------------------------------------------ DML

    /**
     * 按 maxRows 拆成多个 SQL 块执行，但所有块处于同一个 DuckDB 事务：中间块不提交，
     * 只有整个源事务应用成功后才统一提交数据和检查点。任一块失败则整批回滚。
     */
    public long apply(String jobName, ChangeBatch batch, Map<String, TableMapping> mappings, int maxRows)
            throws SQLException {
        return target.call(() -> {
            Connection connection = target.connection();
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            long applied = 0;
            Map<TableMapping, ChangeEvent> touched = new LinkedHashMap<>();
            try {
                List<ChangeEvent> events = batch.events();
                int chunkSize = maxRows <= 0 ? Math.max(1, events.size()) : maxRows;
                for (int offset = 0; offset < events.size(); offset += chunkSize) {
                    int end = Math.min(events.size(), offset + chunkSize);
                    applied += applyEvents(connection, jobName, events.subList(offset, end),
                            mappings, touched);
                }
                for (Map.Entry<TableMapping, ChangeEvent> entry : touched.entrySet()) {
                    TableMapping mapping = entry.getKey();
                    ChangeEvent event = entry.getValue();
                    stateStore.touchTableState(connection, jobName, mapping.source().schema,
                            mapping.source().table, event.op().name(), new Timestamp(event.timestamp()));
                }
                stateStore.saveCheckpoint(connection, jobName, batch.position(), applied);
                connection.commit();
                return applied;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                if (e instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("failed to apply batch: " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    private long applyEvents(Connection connection, String jobName, List<ChangeEvent> events,
                             Map<String, TableMapping> mappings,
                             Map<TableMapping, ChangeEvent> touched) throws SQLException {
        long applied = 0;
        int index = 0;
        while (index < events.size()) {
            ChangeEvent first = events.get(index);
            TableMapping mapping = mappingFor(mappings, first);
            if (mapping == null) {
                logger.debug("[DBSYNCER][{}] skip event for unmapped table {}.{}", jobName,
                        first.sourceSchema(), first.sourceTable());
                index++;
                continue;
            }
            touched.put(mapping, first);
            int end = index + 1;
            while (end < events.size()
                    && events.get(end).op() == first.op()
                    && mapping.equals(mappingFor(mappings, events.get(end)))) {
                end++;
            }
            List<ChangeEvent> segment = events.subList(index, end);
            applied += switch (first.op()) {
                case INSERT -> applyInsert(connection, mapping, segment);
                case UPDATE -> applyUpdate(connection, mapping, segment);
                case DELETE -> applyDelete(connection, mapping, segment);
                case TRUNCATE -> {
                    DuckDbDdlGenerator.truncate(connection, mapping.target());
                    yield 0;
                }
            };
            index = end;
        }
        return applied;
    }

    private TableMapping mappingFor(Map<String, TableMapping> mappings, ChangeEvent event) {
        return mappings.get((event.sourceSchema() + "." + event.sourceTable()).toLowerCase(Locale.ROOT));
    }

    private long applyInsert(Connection connection, TableMapping mapping, List<ChangeEvent> events)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>(events.size());
        for (ChangeEvent event : events) {
            rows.add(mapping.mapRow(event.after(), true));
        }
        // 一律 append：不做主键冲突检查（比 upsert 快得多）；源端主键保证同一段不会重复。
        insertRows(connection, mapping, rows, "INSERT INTO ");
        return rows.size();
    }

    private long applyUpdate(Connection connection, TableMapping mapping, List<ChangeEvent> events)
            throws SQLException {
        if (!mapping.hasPrimaryKey()) {
            logger.warn("[DBSYNCER][{}] skip UPDATE for {}: no primary key",
                    mapping.jobName(), mapping.display());
            return 0;
        }
        long applied = 0;
        List<Map<String, Object>> replaced = new ArrayList<>();
        for (ChangeEvent event : events) {
            if (primaryKeyChanged(mapping, event)) {
                applied += flushReplaced(connection, mapping, replaced);
                deleteByPrimaryKey(connection, mapping,
                        List.of(mapping.mapRow(event.before(), true)));
                replaceRows(connection, mapping, List.of(mapping.mapRow(event.after(), true)));
                applied++;
            } else if (event.missingColumns().isEmpty()) {
                replaced.add(mapping.mapRow(event.after(), true));
            } else {
                // 事件里缺少未变化的列（PG 未变化的 TOAST）：整行替换会把它们写成 null。
                applied += flushReplaced(connection, mapping, replaced);
                applied += updatePartialRow(connection, mapping, event);
            }
        }
        applied += flushReplaced(connection, mapping, replaced);
        return applied;
    }

    private long flushReplaced(Connection connection, TableMapping mapping,
                               List<Map<String, Object>> replaced) throws SQLException {
        if (replaced.isEmpty()) {
            return 0;
        }
        long size = replaced.size();
        replaceRows(connection, mapping, replaced);
        replaced.clear();
        return size;
    }

    /**
     * 只更新事件里真实出现的列，保留目标表里未随事件下发的值；目标行不存在时补插一行。
     */
    private long updatePartialRow(Connection connection, TableMapping mapping, ChangeEvent event)
            throws SQLException {
        Map<String, Object> row = mapping.mapRow(event.after(), false);
        for (String pk : mapping.target().primaryKey) {
            if (row.get(pk) == null) {
                logger.warn("[DBSYNCER][{}] skip UPDATE for {}: primary key [{}] missing from event",
                        mapping.jobName(), mapping.display(), pk);
                return 0;
            }
        }
        List<String> assignments = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (isPrimaryKeyColumn(mapping, entry.getKey())) {
                continue;
            }
            assignments.add(SqlIdentifiers.quote(entry.getKey()) + " = CAST(? AS "
                    + mapping.target().target(entry.getKey()).duckType() + ")");
            values.add(entry.getValue());
        }
        if (assignments.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(mapping.target().qualifiedName())
                .append(" SET ").append(String.join(", ", assignments)).append(" WHERE ");
        appendPkWhere(sql, mapping);
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (Object value : values) {
                bind(statement, index++, value);
            }
            for (String pk : mapping.target().primaryKey) {
                bind(statement, index++, row.get(pk));
            }
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            // 目标行不存在（例如快照未覆盖该行）：按事件里的已知列补插，缺失列为 null。
            replaceRows(connection, mapping, List.of(mapping.mapRow(event.after(), true)));
            return 1;
        }
        return updated;
    }

    private static boolean isPrimaryKeyColumn(TableMapping mapping, String column) {
        for (String pk : mapping.target().primaryKey) {
            if (pk.equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    private boolean primaryKeyChanged(TableMapping mapping, ChangeEvent event) {
        if (event.before().isEmpty() || !mapping.hasPrimaryKey()) {
            return false;
        }
        for (String pk : mapping.target().primaryKey) {
            String sourceColumn = mapping.sourceColumnOf(pk);
            if (sourceColumn == null) {
                continue;
            }
            Object before = event.before().get(sourceColumn);
            Object after = event.after().get(sourceColumn);
            if (!Objects.equals(stringValue(before), stringValue(after))) {
                return true;
            }
        }
        return false;
    }

    private long applyDelete(Connection connection, TableMapping mapping, List<ChangeEvent> events)
            throws SQLException {
        if (!mapping.hasPrimaryKey()) {
            logger.warn("[DBSYNCER][{}] skip DELETE for {}: no primary key",
                    mapping.jobName(), mapping.display());
            return 0;
        }
        long applied = 0;
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(mapping.target().qualifiedName())
                .append(" WHERE ");
        appendPkWhere(sql, mapping);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (ChangeEvent event : events) {
                Map<String, Object> before = mapping.mapRow(event.before(), true);
                int index = 1;
                for (String pk : mapping.target().primaryKey) {
                    bind(statement, index++, before.get(pk));
                }
                int deleted = statement.executeUpdate();
                if (deleted == 0) {
                    logger.warn("[DBSYNCER][{}] DELETE affected 0 rows for {}",
                            mapping.jobName(), mapping.display());
                }
                applied += deleted;
            }
        }
        return applied;
    }

    private void deleteByPrimaryKey(Connection connection, TableMapping mapping,
                                    List<Map<String, Object>> rows) throws SQLException {
        List<Map<String, Object>> deduplicated = deduplicate(mapping, rows);
        if (deduplicated.isEmpty()) {
            return;
        }
        int columns = mapping.target().primaryKey.size();
        int chunkRows = Math.max(1, Math.min(MAX_ROWS_PER_STATEMENT,
                MAX_PARAMS_PER_STATEMENT / columns));
        for (int offset = 0; offset < deduplicated.size(); offset += chunkRows) {
            int size = Math.min(chunkRows, deduplicated.size() - offset);
            StringBuilder sql = new StringBuilder("DELETE FROM ").append(mapping.target().qualifiedName())
                    .append(" AS t USING (VALUES ");
            appendValueRows(sql, size, columns);
            sql.append(") AS v(");
            for (int c = 0; c < columns; c++) {
                if (c > 0) {
                    sql.append(',');
                }
                sql.append(SqlIdentifiers.quote(mapping.target().primaryKey.get(c)));
            }
            sql.append(") WHERE ");
            for (int c = 0; c < columns; c++) {
                if (c > 0) {
                    sql.append(" AND ");
                }
                String pk = mapping.target().primaryKey.get(c);
                sql.append("t.").append(SqlIdentifiers.quote(pk)).append(" = CAST(v.")
                        .append(SqlIdentifiers.quote(pk)).append(" AS ")
                        .append(mapping.target().target(pk).duckType()).append(')');
            }
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                for (int r = 0; r < size; r++) {
                    Map<String, Object> row = deduplicated.get(offset + r);
                    for (String pk : mapping.target().primaryKey) {
                        bind(statement, index++, row.get(pk));
                    }
                }
                statement.executeUpdate();
            }
        }
    }

    // ------------------------------------------------------------------ SQL builders

    private void replaceRows(Connection connection, TableMapping mapping,
                             List<Map<String, Object>> rows) throws SQLException {
        insertRows(connection, mapping, rows, "INSERT OR REPLACE INTO ");
    }

    private void insertRows(Connection connection, TableMapping mapping, List<Map<String, Object>> rows,
                            String prefix) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        List<TargetTable.TargetColumn> columns = mapping.target().columns;
        int chunkRows = Math.max(1, Math.min(MAX_ROWS_PER_STATEMENT,
                MAX_PARAMS_PER_STATEMENT / columns.size()));
        for (int offset = 0; offset < rows.size(); offset += chunkRows) {
            int size = Math.min(chunkRows, rows.size() - offset);
            StringBuilder sql = new StringBuilder(prefix).append(mapping.target().qualifiedName())
                    .append(" (");
            appendColumnList(sql, columns);
            sql.append(") SELECT ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append("CAST(v.").append(SqlIdentifiers.quote(columns.get(i).name()))
                        .append(" AS ").append(columns.get(i).duckType()).append(')');
            }
            sql.append(" FROM (VALUES ");
            appendValueRows(sql, size, columns.size());
            sql.append(") AS v(");
            appendColumnList(sql, columns);
            sql.append(')');
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                for (int r = 0; r < size; r++) {
                    Map<String, Object> row = rows.get(offset + r);
                    for (TargetTable.TargetColumn column : columns) {
                        bind(statement, index++, row.get(column.name()));
                    }
                }
                statement.executeUpdate();
            }
        }
    }

    private static void appendColumnList(StringBuilder sql, List<TargetTable.TargetColumn> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(SqlIdentifiers.quote(columns.get(i).name()));
        }
    }

    private static void appendValueRows(StringBuilder sql, int rows, int columns) {
        for (int r = 0; r < rows; r++) {
            if (r > 0) {
                sql.append(',');
            }
            sql.append('(');
            for (int c = 0; c < columns; c++) {
                if (c > 0) {
                    sql.append(',');
                }
                sql.append('?');
            }
            sql.append(')');
        }
    }

    private void appendPkWhere(StringBuilder sql, TableMapping mapping) {
        for (int i = 0; i < mapping.target().primaryKey.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            String pk = mapping.target().primaryKey.get(i);
            sql.append(SqlIdentifiers.quote(pk)).append(" = CAST(? AS ")
                    .append(mapping.target().target(pk).duckType()).append(')');
        }
    }

    private List<Map<String, Object>> deduplicate(TableMapping mapping, List<Map<String, Object>> rows) {
        if (!mapping.hasPrimaryKey() || rows.size() <= 1) {
            return rows;
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<String> key = new ArrayList<>();
            for (String pk : mapping.target().primaryKey) {
                key.add(stringValue(row.get(pk)));
            }
            result.put(key.toString(), row);
        }
        return new ArrayList<>(result.values());
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return Arrays.toString(bytes);
        }
        return value.toString();
    }

    private static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        switch (value) {
            case null -> statement.setNull(index, Types.NULL);
            case Timestamp timestamp -> statement.setTimestamp(index, timestamp);
            case java.time.LocalDateTime dateTime -> statement.setTimestamp(index, Timestamp.valueOf(dateTime));
            case java.time.LocalDate date -> statement.setDate(index, java.sql.Date.valueOf(date));
            case java.time.LocalTime time -> statement.setTime(index, java.sql.Time.valueOf(time));
            case java.time.OffsetDateTime offsetDateTime ->
                    statement.setTimestamp(index, Timestamp.from(offsetDateTime.toInstant()));
            case java.util.Date date -> statement.setTimestamp(index, new Timestamp(date.getTime()));
            case byte[] bytes -> statement.setBytes(index, bytes);
            case BigInteger bigInteger -> statement.setBigDecimal(index, new BigDecimal(bigInteger));
            case Temporal temporal -> statement.setObject(index, temporal);
            case Boolean bool -> statement.setBoolean(index, bool);
            case Number number -> statement.setObject(index, number);
            default -> statement.setString(index, value.toString());
        }
    }

    // ------------------------------------------------------------------ 快照

    /** @return true 表示需要执行快照；false 表示 skipIfExists 命中、已直接标记完成。 */
    public boolean beginSnapshot(TableMapping mapping, SyncConfig.SnapshotMode mode) throws SQLException {
        return target.call(() -> {
            Connection connection = target.connection();
            boolean exists = DuckDbDdlGenerator.tableExists(connection, mapping.target().catalog,
                    mapping.target().schema, mapping.target().table);
            if (exists && mode == SyncConfig.SnapshotMode.SKIP_IF_EXISTS
                    && DuckDbDdlGenerator.tableHasRows(connection, mapping.target())) {
                stateStore.upsertTableState(connection, mapping.jobName(), mapping.source().schema,
                        mapping.source().table, mapping.target().schema, mapping.target().table,
                        true, 0, null, null);
                return false;
            }
            if (mode == SyncConfig.SnapshotMode.REPLACE) {
                // TRUNCATE + 状态重置同事务：重灌期间 /jobs 不应继续显示上一次的行数，
                // 也不能出现"表已清空但状态还是已完成"。
                boolean previous = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    DuckDbDdlGenerator.truncate(connection, mapping.target());
                    stateStore.upsertTableState(connection, mapping.jobName(), mapping.source().schema,
                            mapping.source().table, mapping.target().schema, mapping.target().table,
                            false, 0, null, null);
                    connection.commit();
                } catch (Exception e) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) {
                    }
                    if (e instanceof SQLException sqlException) {
                        throw sqlException;
                    }
                    throw new SQLException("begin snapshot failed: " + e.getMessage(), e);
                } finally {
                    connection.setAutoCommit(previous);
                }
                return true;
            }
            stateStore.upsertTableState(connection, mapping.jobName(), mapping.source().schema,
                    mapping.source().table, mapping.target().schema, mapping.target().table,
                    false, 0, null, null);
            return true;
        });
    }

    /** 单块快照写入；调用方负责最后一块的 snapshotDone。 */
    public void snapshotChunk(TableMapping mapping, List<Map<String, Object>> rows, long totalRows)
            throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        target.run(() -> {
            Connection connection = target.connection();
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<Map<String, Object>> targetRows = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    targetRows.add(mapping.mapRow(row, true));
                }
                insertRows(connection, mapping, targetRows, "INSERT INTO ");
                stateStore.upsertTableState(connection, mapping.jobName(), mapping.source().schema,
                        mapping.source().table, mapping.target().schema, mapping.target().table,
                        false, totalRows, null, null);
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                if (e instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("snapshot chunk failed: " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(previous);
            }
        });
    }

    public void finishSnapshot(TableMapping mapping, long totalRows) throws SQLException {
        target.run(() -> {
            Connection connection = target.connection();
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                stateStore.upsertTableState(connection, mapping.jobName(), mapping.source().schema,
                        mapping.source().table, mapping.target().schema, mapping.target().table,
                        true, totalRows, null, null);
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                if (e instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("finish snapshot failed: " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(previous);
            }
        });
    }
}
