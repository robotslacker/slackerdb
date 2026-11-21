package org.slackerdb.dbsyncer.source.postgres;

import org.postgresql.PGConnection;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;
import org.slackerdb.dbsyncer.config.ConfigMerge;
import org.slackerdb.dbsyncer.config.SyncConfig;
import org.slackerdb.dbsyncer.model.ChangeBatch;
import org.slackerdb.dbsyncer.model.ChangeEvent;
import org.slackerdb.dbsyncer.model.SyncPauseException;
import org.slackerdb.dbsyncer.model.TxPosition;
import org.slackerdb.dbsyncer.source.BatchConsumer;
import org.slackerdb.dbsyncer.source.FlushHandler;
import org.slackerdb.dbsyncer.source.SnapshotTableConsumer;
import org.slackerdb.dbsyncer.source.SourceConnections;
import org.slackerdb.dbsyncer.model.SourceTable;
import org.slackerdb.dbsyncer.source.TableMappingRef;
import org.slackerdb.dbsyncer.source.TableSnapshotReader;
import org.slackerdb.dbsyncer.model.TableMapping;
import org.slackerdb.dbsyncer.util.ReplicationNames;
import org.slackerdb.dbsyncer.util.SqlIdentifiers;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * PostgreSQL 源：publication/slot 维护、快照、WAL（pgoutput）读取、LSN 反馈。
 */
public final class PostgresSource {

    private final SyncConfig.ConnectionConfig connectionConfig;
    private final SyncConfig.JobConfig job;
    private final SyncConfig.ReplicationConfig replication;
    private final SyncConfig.ReconnectConfig reconnect;
    private final FlushHandler beforeReconnect;
    private final List<TableMapping> mappings;
    private final Logger logger;
    private final Set<String> abandonedTables = new HashSet<>();
    private final Set<String> noPkWarned = new HashSet<>();
    private final java.util.concurrent.atomic.AtomicBoolean abortRequested =
            new java.util.concurrent.atomic.AtomicBoolean();

    private volatile boolean stopped;
    private volatile PGReplicationStream stream;
    private volatile Connection replicationConnection;
    private volatile int replicationBackendPid;
    private volatile long appliedLsn;

    public PostgresSource(SyncConfig.ConnectionConfig connectionConfig, SyncConfig.JobConfig job,
                          List<TableMapping> mappings, SyncConfig.ReconnectConfig reconnect,
                          FlushHandler beforeReconnect, Logger logger) {
        this.connectionConfig = connectionConfig;
        this.job = job;
        this.mappings = mappings;
        this.replication = ConfigMerge.replication(connectionConfig, job);
        this.reconnect = reconnect;
        this.beforeReconnect = beforeReconnect;
        this.logger = logger;
    }

    /** 当前 WAL 与已应用 LSN 的字节差；用于 /dbsyncer/jobs 状态。 */
    public Long lagBytes(long appliedLsn) {
        if (appliedLsn <= 0) {
            return null;
        }
        try (Connection connection = openProbeJdbc();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn)")) {
            statement.setString(1, LogSequenceNumber.valueOf(appliedLsn).asString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    long lag = (long) rs.getDouble(1);
                    return Math.max(0, lag);
                }
            }
        } catch (SQLException e) {
            logger.debug("[DBSYNCER][{}] lag query failed: {}", job.name, e.getMessage());
        }
        return null;
    }

    /** 删除作业时清理自动派生的 slot/publication；显式配置的名称不自动删除。 */
    public static void cleanupReplication(SyncConfig.JobConfig job, SyncConfig.ConnectionConfig connection,
                                          Logger logger) {
        if (connection == null || connection.type != SyncConfig.SourceType.POSTGRES
                || !job.replication.specified.isEmpty() || !connection.replication.specified.isEmpty()) {
            return;
        }
        String publication = ReplicationNames.publication(job.name);
        String slot = ReplicationNames.slot(job.name);
        try (Connection jdbc = SourceConnections.open(connection)) {
            try (PreparedStatement statement = jdbc.prepareStatement(
                    "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name=?")) {
                statement.setString(1, slot);
                statement.execute();
            }
            try (Statement statement = jdbc.createStatement()) {
                statement.execute("DROP PUBLICATION IF EXISTS " + SqlIdentifiers.quote(publication));
            }
            logger.info("[DBSYNCER][{}] dropped PostgreSQL publication [{}] and slot [{}]",
                    job.name, publication, slot);
        } catch (Exception e) {
            logger.warn("[DBSYNCER][{}] cannot clean PostgreSQL replication objects: {}",
                    job.name, e.getMessage());
        }
    }

    /**
     * 启动 CDC 前的准备工作：校验 wal_level、维护 publication、无主键表设 REPLICA IDENTITY FULL、
     * 确认复制槽。返回本次 CDC 的起点。
     */
    public Prepared prepare(long resumeLsn) throws SQLException {
        String publication = replication.publication == null || replication.publication.isBlank()
                ? ReplicationNames.publication(job.name)
                : replication.publication;
        String slot = replication.slot == null || replication.slot.isBlank()
                ? ReplicationNames.slot(job.name)
                : replication.slot;
        // startWal() 从这里读回最终名称。
        replication.publication = publication;
        replication.slot = slot;

        try (Connection connection = openJdbc()) {
            checkWalLevel(connection);
            ensurePublication(connection, publication);
            applyReplicaIdentityFull(connection);
        }

        long slotLsn = ensureSlot(slot, resumeLsn);
        long startLsn = resumeLsn > 0 ? resumeLsn : slotLsn;
        appliedLsn = startLsn;
        return new Prepared(startLsn);
    }

    private void checkWalLevel(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW wal_level")) {
            if (!rs.next() || !"logical".equalsIgnoreCase(rs.getString(1))) {
                throw new SQLException("wal_level must be logical. Set wal_level=logical and restart PostgreSQL.");
            }
        }
    }

    private void ensurePublication(Connection connection, String publication) throws SQLException {
        List<String> tables = new ArrayList<>();
        for (TableMapping mapping : mappings) {
            tables.add(SqlIdentifiers.quote(mapping.source().schema) + "."
                    + SqlIdentifiers.quote(mapping.source().table));
        }
        if (tables.isEmpty()) {
            throw new SQLException("no target tables for publication");
        }
        String tableList = String.join(", ", tables);
        if (publicationExists(connection, publication)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER PUBLICATION " + SqlIdentifiers.quote(publication)
                        + " SET TABLE " + tableList);
            }
            logger.info("[DBSYNCER][{}] publication [{}] updated: {}", job.name, publication, tables);
        } else {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE PUBLICATION " + SqlIdentifiers.quote(publication)
                        + " FOR TABLE " + tableList);
            }
            logger.info("[DBSYNCER][{}] publication [{}] created: {}", job.name, publication, tables);
        }
    }

    private boolean publicationExists(Connection connection, String publication) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_publication WHERE pubname=?")) {
            statement.setString(1, publication);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 无主键表没有稳定行标识，PG 不会为 UPDATE/DELETE 提供 before image；设为 FULL 让逻辑复制
     * 至少能下发旧行。作业级 {@code primaryKey} 覆盖不影响这里：它不是 PG 的行标识。
     */
    private void applyReplicaIdentityFull(Connection connection) throws SQLException {
        for (TableMapping mapping : mappings) {
            if (!mapping.source().primaryKey.isEmpty()) {
                continue;
            }
            String table = SqlIdentifiers.quote(mapping.source().schema) + "."
                    + SqlIdentifiers.quote(mapping.source().table);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " REPLICA IDENTITY FULL");
            }
            logger.info("[DBSYNCER][{}] set REPLICA IDENTITY FULL on {}",
                    job.name, mapping.source().qualifiedName());
        }
    }

    private long ensureSlot(String slot, long resumeLsn) throws SQLException {
        try (Connection connection = openJdbc()) {
            Long existing = existingSlotLsn(connection, slot);
            if (existing != null) {
                if (resumeLsn > 0 && existing > resumeLsn) {
                    throw new SQLException("replication slot [" + slot + "] is at "
                            + LogSequenceNumber.valueOf(existing).asString()
                            + ", ahead of checkpoint LSN "
                            + LogSequenceNumber.valueOf(resumeLsn).asString()
                            + "; refusing to skip WAL. Run resync.");
                }
                logger.info("[DBSYNCER][{}] reusing replication slot [{}] at {}", job.name, slot,
                        LogSequenceNumber.valueOf(existing).asString());
                return existing;
            }
            if (resumeLsn > 0) {
                throw new SQLException("replication slot [" + slot + "] is missing but checkpoint LSN "
                        + LogSequenceNumber.valueOf(resumeLsn).asString()
                        + " exists; PostgreSQL cannot recreate a logical slot at a historical LSN. "
                        + "Run resync explicitly to rebuild the target and restart CDC.");
            }
            try (Connection replicationConn = openReplication()) {
                PGConnection pg = replicationConn.unwrap(PGConnection.class);
                LogSequenceNumber consistent = pg.getReplicationAPI()
                        .createReplicationSlot()
                        .logical()
                        .withSlotName(slot)
                        .withOutputPlugin("pgoutput")
                        .make()
                        .getConsistentPoint();
                logger.info("[DBSYNCER][{}] replication slot [{}] created at {}", job.name, slot,
                        consistent.asString());
                return consistent.asLong();
            }
        }
    }

    private Long existingSlotLsn(Connection connection, String slot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT confirmed_flush_lsn, restart_lsn FROM pg_replication_slots WHERE slot_name=?")) {
            statement.setString(1, slot);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String lsn = rs.getString(1);
                if (lsn == null) {
                    lsn = rs.getString(2);
                }
                return lsn == null ? null : LogSequenceNumber.valueOf(lsn).asLong();
            }
        }
    }

    /** 阻塞读取 WAL，按事务回调；连接中断按配置重连。 */
    public void startWal(long startLsn, BatchConsumer consumer) throws Exception {
        long lsn = startLsn;
        int failures = 0;
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            long connectedAt = 0;
            try {
                try (Connection replicationConn = openReplication()) {
                    replicationConnection = replicationConn;
                    PGConnection pg = replicationConn.unwrap(PGConnection.class);
                    replicationBackendPid = pg.getBackendPID();
                    ChainedLogicalStreamBuilder builder = pg.getReplicationAPI()
                            .replicationStream()
                            .logical()
                            .withSlotName(replication.slot)
                            .withSlotOption("proto_version", 1)
                            .withSlotOption("publication_names", replication.publication)
                            .withStartPosition(LogSequenceNumber.valueOf(lsn))
                            .withStatusInterval(statusIntervalMillis(), TimeUnit.MILLISECONDS);
                    stream = builder.start();
                    connectedAt = System.currentTimeMillis();
                    logger.info("[DBSYNCER][{}] logical replication started at {} (slot={}, publication={})",
                            job.name, LogSequenceNumber.valueOf(lsn).asString(), replication.slot,
                            replication.publication);
                    readLoop(consumer);
                }
            } catch (Exception e) {
                if (stopped) {
                    return;
                }
                if (e instanceof SyncPauseException) {
                    throw e;
                }
                if (!isConnectionFailure(e)) {
                    throw e;
                }
                long stable = connectedAt == 0 ? 0 : System.currentTimeMillis() - connectedAt;
                if (stable >= 10_000) {
                    failures = 0;
                }
                failures++;
                if (failures > reconnect.maxAttempts) {
                    throw new SQLException("PostgreSQL replication failed after " + failures + " attempts", e);
                }
                logger.warn("[DBSYNCER][{}] replication disconnected ({}), reconnect {}/{} in {} ms",
                        job.name, e.getMessage(), failures, reconnect.maxAttempts, reconnect.intervalMs);
                if (!sleep(reconnect.intervalMs)) {
                    return;
                }
                beforeReconnect.flush();
                lsn = appliedLsn > 0 ? appliedLsn : lsn;
            } finally {
                closeStream();
            }
        }
    }

    private int statusIntervalMillis() {
        long value = Math.max(100, replication.statusIntervalMs);
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private void readLoop(BatchConsumer consumer) throws Exception {
        PgOutputDecoder decoder = new PgOutputDecoder();
        List<ChangeEvent> events = new ArrayList<>();
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            PGReplicationStream current = stream;
            if (current == null) {
                return;
            }
            // 阻塞读：有数据立即返回，到 statusInterval 仍无数据返回 null（驱动负责发心跳/反馈）。
            ByteBuffer buffer = current.read();
            if (buffer == null) {
                continue;
            }
            char messageType = (char) (buffer.get() & 0xFF);
            long[] commitEndLsn = {0};
            PgOutputDecoder.Callback callback = new PgOutputDecoder.Callback() {
                @Override
                public void onInsert(PgOutputDecoder.RelationInfo relation, Map<String, Object> after) {
                    if (isAbandoned(relation)) {
                        return;
                    }
                    events.add(ChangeEvent.insert(relation.schema, relation.table, after,
                            System.currentTimeMillis()));
                }

                @Override
                public void onUpdate(PgOutputDecoder.RelationInfo relation, Map<String, Object> before,
                                     Map<String, Object> after, Set<String> missing) {
                    if (isAbandoned(relation) || skipNoPk(relation, "UPDATE")) {
                        return;
                    }
                    events.add(ChangeEvent.update(relation.schema, relation.table, before, after, missing,
                            System.currentTimeMillis()));
                }

                @Override
                public void onDelete(PgOutputDecoder.RelationInfo relation, Map<String, Object> before) {
                    if (isAbandoned(relation) || skipNoPk(relation, "DELETE")) {
                        return;
                    }
                    events.add(ChangeEvent.delete(relation.schema, relation.table, before,
                            System.currentTimeMillis()));
                }

                @Override
                public void onRelation(PgOutputDecoder.RelationInfo relation) {
                    validateRelation(relation);
                }

                @Override
                public void onTruncate(List<PgOutputDecoder.RelationInfo> relations, boolean cascade) {
                    for (PgOutputDecoder.RelationInfo relation : relations) {
                        if (isAbandoned(relation)) {
                            continue;
                        }
                        events.add(ChangeEvent.truncate(relation.schema, relation.table,
                                System.currentTimeMillis()));
                    }
                }

                @Override
                public void onBegin(long finalLsn) {
                    // 事务最终 LSN；检查点用 Commit 的 end LSN（与它一致）。
                }

                @Override
                public void onCommit(long endLsn) {
                    commitEndLsn[0] = endLsn;
                }
            };
            decoder.decode(messageType, buffer, callback);
            if (commitEndLsn[0] != 0) {
                ChangeBatch batch = new ChangeBatch(new ArrayList<>(events),
                        TxPosition.pg(commitEndLsn[0]));
                consumer.accept(batch);
                events.clear();
            }
        }
    }

    /** 目标批提交/检查点完成后由 JobRuntime 调用，避免提前 ACK 未落盘事务。 */
    public void acknowledge(long lsn) {
        if (lsn > appliedLsn) {
            appliedLsn = lsn;
            feedback(lsn);
        }
    }

    private synchronized void feedback(long lsn) {
        PGReplicationStream current = stream;
        if (current == null || lsn <= 0) {
            return;
        }
        try {
            LogSequenceNumber number = LogSequenceNumber.valueOf(lsn);
            current.setAppliedLSN(number);
            current.setFlushedLSN(number);
            current.forceUpdateStatus();
        } catch (Exception e) {
            logger.debug("[DBSYNCER][{}] feedback failed: {}", job.name, e.getMessage());
        }
    }

    /**
     * 目标表没有主键时无法定位行，UPDATE/DELETE 只能跳过；作业级 {@code primaryKey} 覆盖
     * 同样会写进目标主键，所以这里看目标主键而不是源库主键。
     */
    private boolean skipNoPk(PgOutputDecoder.RelationInfo relation, String operation) {
        String key = (relation.schema + "." + relation.table).toLowerCase(Locale.ROOT);
        TableMapping mapping = mappingFor(key);
        if (mapping == null || mapping.hasPrimaryKey()) {
            return false;
        }
        if (noPkWarned.add(key)) {
            logger.warn("[DBSYNCER][{}] source table {} has no primary key; skip {} events",
                    job.name, mapping.source().qualifiedName(), operation);
        }
        return true;
    }

    private TableMapping mappingFor(String key) {
        for (TableMapping candidate : mappings) {
            if (candidate.sourceKey().equals(key)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isAbandoned(PgOutputDecoder.RelationInfo relation) {
        return abandonedTables.contains((relation.schema + "." + relation.table).toLowerCase(Locale.ROOT));
    }

    /**
     * PG 运行期 DDL 不做 schema evolution：一旦 Relation 消息与启动时的表结构不一致，
     * 该表本次运行内直接放弃，所有后续行事件都忽略；修复后需重启作业。
     */
    private void validateRelation(PgOutputDecoder.RelationInfo relation) {
        String key = (relation.schema + "." + relation.table).toLowerCase(Locale.ROOT);
        if (abandonedTables.contains(key)) {
            return;
        }
        TableMapping mapping = mappingFor(key);
        if (mapping == null) {
            return;
        }
        List<String> differences = new ArrayList<>();
        Set<String> relationNames = new HashSet<>();
        for (PgOutputDecoder.ColumnInfo column : relation.columns) {
            relationNames.add(column.name());
            SourceTable.SourceColumn existing = mapping.source().column(column.name());
            if (existing == null) {
                differences.add("new column " + column.name());
            } else if (existing.pgTypeOid == 0) {
                existing.pgTypeOid = column.typeOid();
            } else if (existing.pgTypeOid != column.typeOid()) {
                differences.add("type changed " + column.name() + " (" + existing.pgTypeOid
                        + " -> " + column.typeOid() + ")");
            }
        }
        for (SourceTable.SourceColumn column : mapping.source().columns) {
            if (!column.generated && !relationNames.contains(column.name)) {
                differences.add("removed column " + column.name);
            }
        }
        if (differences.isEmpty()) {
            return;
        }
        abandonedTables.add(key);
        logger.warn("[DBSYNCER][{}] source schema changed for {}: {}; table abandoned for this run. "
                        + "Fix target/job config and restart the job to resume.",
                job.name, mapping.source().qualifiedName(), String.join("; ", differences));
    }

    /** resync：所有表在同一个 REPEATABLE READ 事务内读取，保证跨表一致性。 */
    public long snapshotAll(SyncConfig.SnapshotConfig snapshot, SnapshotTableConsumer consumer)
            throws Exception {
        try (Connection connection = openJdbc()) {
            connection.setAutoCommit(false);
            try {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            } catch (SQLException ignored) {
            }
            try {
                long snapshotLsn;
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT pg_current_wal_lsn()")) {
                    if (!rs.next()) {
                        throw new SQLException("cannot read snapshot LSN");
                    }
                    snapshotLsn = LogSequenceNumber.valueOf(rs.getString(1)).asLong();
                }
                for (TableMapping mapping : mappings) {
                    TableMappingRef table = ref(mapping);
                    consumer.begin(table);
                    long total = TableSnapshotReader.read(connection, table,
                            "SELECT * FROM " + SqlIdentifiers.quote(mapping.source().schema) + "."
                                    + SqlIdentifiers.quote(mapping.source().table),
                            snapshot.fetchSize, consumer::rows);
                    consumer.end(table, total);
                    logger.info("[DBSYNCER][{}] snapshot table {} completed, rows={}", job.name,
                            mapping.source().qualifiedName(), total);
                }
                connection.rollback();
                return snapshotLsn;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        }
    }

    private static TableMappingRef ref(TableMapping mapping) {
        return new TableMappingRef(mapping.source().schema, mapping.source().table);
    }

    public void stop() {
        stopped = true;
        abortReplication();
    }

    /**
     * 从其它线程中止阻塞中的 WAL 读。
     *
     * <p>pgjdbc 的 {@code read()} 会一直阻塞到服务端发来消息（最坏要等 walsender 的心跳，
     * 实测可达 60s+），而且 {@code stream.close()}/{@code connection.close()} 会和读线程抢同一把锁、
     * 同样卡住。所以这里异步关闭，并主动终止本次复制的后端进程（同角色允许），
     * 让读循环立即出错退出，stop 不会被拖住。</p>
     */
    private void abortReplication() {
        int pid = replicationBackendPid;
        if (stream == null && pid <= 0) {
            return;
        }
        if (!abortRequested.compareAndSet(false, true)) {
            return;
        }
        Thread closer = new Thread(() -> {
            terminateBackend(pid);
            closeStream();
        }, "dbsync-pg-abort-" + job.name);
        closer.setDaemon(true);
        closer.start();
    }

    private void terminateBackend(int pid) {
        if (pid <= 0) {
            return;
        }
        try (Connection admin = openProbeJdbc();
             PreparedStatement statement = admin.prepareStatement("SELECT pg_terminate_backend(?)")) {
            statement.setInt(1, pid);
            statement.execute();
            logger.info("[DBSYNCER][{}] terminated replication backend {} to stop", job.name, pid);
        } catch (SQLException e) {
            logger.debug("[DBSYNCER][{}] cannot terminate replication backend {}: {}",
                    job.name, pid, e.getMessage());
        }
    }

    private void closeStream() {
        PGReplicationStream current = stream;
        stream = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
        Connection connection = replicationConnection;
        replicationConnection = null;
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private Connection openJdbc() throws SQLException {
        return SourceConnections.open(connectionConfig,
                Map.of("ApplicationName", "slackerdb-dbsyncer/" + job.name));
    }

    /**
     * 状态接口/停止用的短超时连接：源库半死时不能让 HTTP 线程或 abort 线程挂住。
     * PG 驱动的 {@code connectTimeout}/{@code socketTimeout} 单位都是秒。
     */
    private Connection openProbeJdbc() throws SQLException {
        return SourceConnections.open(connectionConfig, Map.of(
                "ApplicationName", "slackerdb-dbsyncer/" + job.name,
                "connectTimeout", "3",
                "socketTimeout", "5"));
    }

    private Connection openReplication() throws SQLException {
        return SourceConnections.open(connectionConfig, Map.of(
                "replication", "database",
                "assumeMinServerVersion", "9.4",
                "preferQueryMode", "simple",
                "ApplicationName", "slackerdb-dbsyncer/" + job.name));
    }

    private boolean sleep(long millis) {
        long remaining = millis;
        while (remaining > 0 && !stopped) {
            long chunk = Math.min(remaining, 200);
            try {
                Thread.sleep(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= chunk;
        }
        return !stopped;
    }

    private static boolean isConnectionFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("connection") || lower.contains("broken pipe")
                        || lower.contains("socket") || lower.contains("terminating")
                        || lower.contains("i/o error") || lower.contains("connection reset")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /** 本次 CDC 的起点（复制槽一致点或已有检查点）。 */
    public record Prepared(long startLsn) {
    }
}
