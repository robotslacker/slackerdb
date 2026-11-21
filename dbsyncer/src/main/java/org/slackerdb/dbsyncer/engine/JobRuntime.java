package org.slackerdb.dbsyncer.engine;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slackerdb.dbsyncer.config.SyncConfig;
import org.slackerdb.dbsyncer.model.ChangeBatch;
import org.slackerdb.dbsyncer.model.DdlAction;
import org.slackerdb.dbsyncer.model.SyncPauseException;
import org.slackerdb.dbsyncer.model.TableMapping;
import org.slackerdb.dbsyncer.model.TargetTable;
import org.slackerdb.dbsyncer.model.TxPosition;
import org.slackerdb.dbsyncer.source.SnapshotTableConsumer;
import org.slackerdb.dbsyncer.source.SourceConnections;
import org.slackerdb.dbsyncer.source.TableMappingRef;
import org.slackerdb.dbsyncer.source.mysql.MySqlDdlApplier;
import org.slackerdb.dbsyncer.source.mysql.MySqlSource;
import org.slackerdb.dbsyncer.source.postgres.PostgresSource;
import org.slackerdb.dbsyncer.state.SyncStateStore;
import org.slackerdb.dbsyncer.target.DuckDbDdlGenerator;
import org.slackerdb.dbsyncer.target.DuckDbTarget;
import org.slackerdb.dbsyncer.target.DuckDbWriter;
import org.slackerdb.dbsyncer.target.TableRouter;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单作业运行时：状态机、表映射解析、快照、CDC 读取、攒批 flush、检查点与错误处理。
 *
 * <p>线程模型：一个 runner 线程负责快照与源读取，一个定时 flusher 线程负责攒批写入，
 * 目标写入统一交给 {@link DuckDbTarget} 的单写线程。</p>
 */
public final class JobRuntime {

    private SyncConfig config;
    private SyncConfig.JobConfig job;
    private SyncConfig.TargetConfig targetConfig;
    private String configHash;
    private final DuckDbTarget target;
    private final SyncStateStore stateStore;
    private final DuckDbWriter writer;
    private final Logger logger;
    private final SyncMetrics metrics = new SyncMetrics();
    private final Object stateLock = new Object();
    /** 目标 flush 互斥：DDL 期间由源线程持有，暂停 flush 线程。 */
    private final ReentrantLock flushLock = new ReentrantLock();

    private volatile JobState state = JobState.STOPPED;
    private volatile String lastError;
    private volatile String lastFailedDdl;
    private volatile boolean resyncRequested;
    private volatile Map<String, TableMapping> currentByKey;
    private volatile ScheduledExecutorService flusher;
    private final BatchBuffer buffer = new BatchBuffer();
    private volatile boolean flushFailed;
    private volatile boolean stopRequested;
    private volatile boolean orphaned;
    private volatile TxPosition position;
    private volatile long startedAt;
    private volatile String runId;
    private volatile long completedAt;

    private volatile Thread runner;
    private volatile PostgresSource source;
    private volatile MySqlSource mysqlSource;
    private volatile List<TableMapping> mappings = List.of();

    public JobRuntime(SyncConfig config, SyncConfig.JobConfig job, String configHash,
                      DuckDbTarget target, SyncStateStore stateStore, DuckDbWriter writer, Logger logger) {
        this.config = config;
        this.job = job;
        this.targetConfig = config.targets.get(job.target);
        this.configHash = configHash;
        this.target = target;
        this.stateStore = stateStore;
        this.writer = writer;
        this.logger = logger;
    }

    public String name() {
        return job.name;
    }

    public JobState state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    public boolean orphaned() {
        return orphaned;
    }

    public SyncConfig.JobConfig job() {
        return job;
    }

    public String configHash() {
        return configHash;
    }

    public void cleanupReplication() {
        PostgresSource.cleanupReplication(job, config.connections.get(job.source), logger);
    }

    public void markOrphaned() {
        this.orphaned = true;
    }

    /** 被删掉的作业重新回到配置里时解除 orphan，否则它永远不能 start。 */
    public void clearOrphaned() {
        this.orphaned = false;
    }

    /** 仅对未运行的作业替换定义（reload 时使用）。 */
    public void replaceDefinition(SyncConfig config, SyncConfig.JobConfig job, String configHash) {
        this.config = config;
        this.job = job;
        this.targetConfig = config.targets.get(job.target);
        this.configHash = configHash;
        this.orphaned = false;
        // 上一次运行的临时状态（映射、错误、计时、计数）都属于旧定义，必须清掉，
        // 否则 /jobs 会显示已经不存在于新定义里的表或旧的失败原因。
        this.mappings = List.of();
        this.currentByKey = null;
        this.lastFailedDdl = null;
        this.lastError = null;
        this.startedAt = 0;
        this.completedAt = 0;
        this.metrics.reset();
    }

    // ------------------------------------------------------------------ start/stop

    public JobState start(boolean resync) {
        synchronized (stateLock) {
            if (state.isRunning()) {
                if (resync) {
                    throw new IllegalStateException("job is running; stop it before resync");
                }
                return state;
            }
            if (state == JobState.STOPPING) {
                return state;
            }
            if (orphaned) {
                return state;
            }
            // 上一轮 runner 的 finally 还没跑完时再起一轮会出现两个线程共享同一组字段。
            if (runner != null && runner.isAlive()) {
                throw new IllegalStateException("job is still finishing the previous run; retry later");
            }
            stopRequested = false;
            lastError = null;
            resyncRequested = resync;
            flushFailed = false;
            completedAt = 0;
            startedAt = System.currentTimeMillis();
            metrics.reset();
            runId = UUID.randomUUID().toString();
            setState(JobState.STARTING);
            try {
                stateStore.insertRun(runId, job.name, "RUNNING");
            } catch (SQLException e) {
                logger.warn("[DBSYNCER][{}] cannot insert run record: {}", job.name, e.getMessage());
            }
            persistJob();
            runner = new Thread(this::run, "dbsync-" + job.name);
            runner.setDaemon(true);
            runner.start();
            return state;
        }
    }

    public JobState stop(boolean wait, long timeoutMs) {
        synchronized (stateLock) {
            if (state == JobState.STOPPED && runner == null) {
                return state;
            }
            if (state.isTerminal()) {
                setState(JobState.STOPPED);
                markStopped();
                return state;
            }
            if (state != JobState.STOPPING) {
                setState(JobState.STOPPING);
            }
            stopRequested = true;
            PostgresSource current = source;
            if (current != null) {
                current.stop();
            }
            MySqlSource currentMysql = mysqlSource;
            if (currentMysql != null) {
                currentMysql.stop();
            }
            Thread thread = runner;
            if (thread != null) {
                thread.interrupt();
            }
            if (!wait) {
                return state;
            }
            long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
            while (state == JobState.STOPPING && System.currentTimeMillis() < deadline) {
                try {
                    stateLock.wait(Math.max(50, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return state;
        }
    }

    private void run() {
        lastFailedDdl = null;
        try {
            runJob();
        } catch (SyncPauseException e) {
            lastError = e.getMessage();
            transition(JobState.PAUSED);
            logger.warn("[DBSYNCER][{}] paused: {}", job.name, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transition(flushFailed ? JobState.PAUSED : JobState.STOPPED);
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            transition(flushFailed ? JobState.PAUSED
                    : stopRequested ? JobState.STOPPED : JobState.FAILED);
            logger.error("[DBSYNCER][{}] job failed: {}", job.name, lastError, e);
        } finally {
            PostgresSource current = source;
            if (current != null) {
                current.stop();
            }
            MySqlSource currentMysql = mysqlSource;
            if (currentMysql != null) {
                currentMysql.stop();
            }
            stopFlusher();
            if (!flushFailed) {
                flushBufferQuietly();
            }
            if (state != JobState.PAUSED && state != JobState.FAILED && state != JobState.COMPLETED) {
                transition(JobState.STOPPED);
            }
            if (orphaned) {
                cleanupReplication();
            }
            completedAt = System.currentTimeMillis();
            try {
                stateStore.finishRun(runId, state.name(), lastError,
                        metrics.eventsRead.get(), metrics.eventsApplied.get());
            } catch (SQLException e) {
                logger.warn("[DBSYNCER][{}] cannot finish run record: {}", job.name, e.getMessage());
            }
            markStopped();
        }
    }

    // ------------------------------------------------------------------ 启动流程

    private void runJob() throws Exception {
        logger.info("[DBSYNCER][{}] starting (source={}, target={}, mode={})",
                job.name, job.source, job.target, job.mode.yaml);
        SyncConfig.ConnectionConfig connection = config.connections.get(job.source);
        if (connection == null) {
            throw new IllegalStateException("unknown source connection [" + job.source + "]");
        }
        boolean snapshotMode = job.mode.includesSnapshot();
        SyncStateStore.Checkpoint startupCheckpoint = stateStore.loadCheckpoint(job.name);
        boolean requestedResync = resyncRequested || startupCheckpoint == null;
        resyncRequested = false;
        if (requestedResync) {
            // Clear before mapping resolution: a failed resync leaves no checkpoint,
            // so the next start retries resync automatically.
            clearState();
        }
        try (Connection sourceConnection = SourceConnections.open(connection)) {
            TableRouter router = new TableRouter(config, connection, targetConfig, job, target.catalog());
            mappings = router.resolve(sourceConnection);
        }

        Map<String, TableMapping> byKey = new ConcurrentHashMap<>();
        List<TableMapping> active = new ArrayList<>();

        Map<String, SyncStateStore.TableState> startupStates = stateStore.loadTableStates(job.name);
        // 只支持整作业 resync：任何"需要快照"的情形（显式 resync、无检查点、有表状态缺失/未完成）
        // 都清检查点并重建全部目标表。不做逐表补快照——那会与重放重叠，而 INSERT 是 append，
        // 重叠的 INSERT 会撞主键。
        boolean resync = snapshotMode && (requestedResync || needsSnapshot(startupStates));
        if (!resync && snapshotMode) {
            for (TableMapping mapping : mappings) {
                boolean missing = target.call(() -> !DuckDbDdlGenerator.tableExists(
                        target.connection(), mapping.target().catalog, mapping.target().schema,
                        mapping.target().table));
                if (missing) {
                    logger.info("[DBSYNCER][{}] target {} does not exist; full resync required",
                            job.name, mapping.target().qualifiedName());
                    resync = true;
                    break;
                }
            }
        }
        logger.info("[DBSYNCER][{}] resync decision: requested={}, checkpoint={}, states={}, resync={}",
                job.name, requestedResync, startupCheckpoint != null, startupStates.size(), resync);
        if (resync) {
            clearState();
            logger.info("[DBSYNCER][{}] resync: clearing checkpoint and rebuilding all target tables",
                    job.name);
            active.addAll(mappings);
        } else {
            for (TableMapping mapping : mappings) {
                SchemaCheck check = target.call(() -> {
                    Connection targetConnection = target.connection();
                    if (!DuckDbDdlGenerator.tableExists(targetConnection,
                            mapping.target().catalog, mapping.target().schema, mapping.target().table)) {
                        return new SchemaCheck(true, List.of("table missing"));
                    }
                    return new SchemaCheck(false, schemaDifferences(targetConnection, mapping.target()));
                });
                if (check.missing()) {
                    // 只有 cdc 模式会走到这里（snapshot 模式已在上面升级为整作业 resync）。
                    logger.info("[DBSYNCER][{}] target {} does not exist, creating it empty for CDC",
                            job.name, mapping.target().qualifiedName());
                    active.add(mapping);
                } else if (!check.differences().isEmpty()) {
                    logger.warn("[DBSYNCER][{}] target schema mismatch for {}: {}; table abandoned for this run",
                            job.name, mapping.target().qualifiedName(),
                            String.join("; ", check.differences()));
                } else {
                    active.add(mapping);
                }
            }
        }
        if (active.isEmpty()) {
            throw new SyncPauseException("all tables abandoned due to target schema mismatch");
        }
        mappings = new CopyOnWriteArrayList<>(active);
        for (TableMapping mapping : mappings) {
            byKey.put(mapping.sourceKey(), mapping);
        }
        if (resync) {
            writer.rebuild(mappings);
        } else {
            writer.prepare(mappings);
        }
        if (connection.type == SyncConfig.SourceType.POSTGRES) {
            runPostgres(connection, byKey, resync);
        } else {
            runMySql(connection, byKey, resync);
        }
    }

    private record SchemaCheck(boolean missing, List<String> differences) {
    }

    /** 列/类型 + 主键都要一致，否则写入的定位假设不成立。 */
    private static List<String> schemaDifferences(Connection connection, TargetTable table)
            throws SQLException {
        List<String> differences = new ArrayList<>(
                DuckDbDdlGenerator.schemaMismatch(connection, table));
        differences.addAll(DuckDbDdlGenerator.primaryKeyMismatch(connection, table));
        return differences;
    }

    private void runPostgres(SyncConfig.ConnectionConfig connection, Map<String, TableMapping> byKey,
                             boolean resync) throws Exception {
        SyncStateStore.Checkpoint checkpoint = stateStore.loadCheckpoint(job.name);
        source = new PostgresSource(connection, job, mappings, config.defaults.reconnect,
                this::flushBuffer, logger);
        PostgresSource.Prepared prepared = source.prepare(
                checkpoint == null ? 0 : checkpoint.toPosition().lsn);
        TxPosition checkpointPosition = checkpoint == null
                ? TxPosition.pg(prepared.startLsn())
                : checkpoint.toPosition();
        position = checkpointPosition;

        if (resync) {
            transition(JobState.SNAPSHOT);
            long snapshotLsn = source.snapshotAll(job.snapshot, resyncConsumer(byKey));
            checkpointPosition = TxPosition.pg(snapshotLsn);
            position = checkpointPosition;
            saveCheckpoint(checkpointPosition);
            if (job.mode == SyncConfig.JobMode.SNAPSHOT) {
                transition(JobState.COMPLETED);
                return;
            }
        }
        if (!job.mode.includesCdc()) {
            transition(JobState.COMPLETED);
            return;
        }

        transition(JobState.STREAMING);
        long startLsn = checkpointPosition.lsn;
        if (startLsn <= 0) {
            startLsn = prepared.startLsn();
        }
        currentByKey = byKey;
        startFlusher();
        source.startWal(startLsn, batch -> {
            if (stopRequested) {
                throw new InterruptedException("stopped");
            }
            buffer.add(batch);
            if (buffer.size() >= config.defaults.batch.maxBufferedEvents
                    || buffer.sizeBytes() >= config.defaults.batch.maxBufferedBytes) {
                flushBuffer();
            }
        });
    }

    private void runMySql(SyncConfig.ConnectionConfig connection, Map<String, TableMapping> byKey,
                          boolean resync) throws Exception {
        TableRouter dynamicRouter = new TableRouter(config, connection, targetConfig, job, target.catalog());
        MySqlDdlApplier ddlApplier = new MySqlDdlApplier(mappings, byKey, writer,
                (schema, table) -> {
                    try (Connection sourceConnection = SourceConnections.open(connection)) {
                        return dynamicRouter.resolveOne(sourceConnection, schema, table);
                    }
                }, logger);
        mysqlSource = new MySqlSource(connection, job, mappings, config.defaults.reconnect,
                this::flushBuffer, this::applyDdl, ddlApplier, this::recordFailedDdl, logger);
        MySqlSource.Prepared prepared = mysqlSource.prepare();
        SyncStateStore.Checkpoint checkpoint = stateStore.loadCheckpoint(job.name);
        TxPosition checkpointPosition = checkpoint == null
                ? TxPosition.mysql(prepared.file(), prepared.position())
                : checkpoint.toPosition();
        position = checkpointPosition;

        if (resync) {
            transition(JobState.SNAPSHOT);
            checkpointPosition = mysqlSource.snapshotAll(job.snapshot, resyncConsumer(byKey));
            position = checkpointPosition;
            saveCheckpoint(checkpointPosition);
            if (job.mode == SyncConfig.JobMode.SNAPSHOT) {
                transition(JobState.COMPLETED);
                return;
            }
        }
        if (!job.mode.includesCdc()) {
            transition(JobState.COMPLETED);
            return;
        }

        transition(JobState.STREAMING);
        TxPosition start = position == null ? checkpointPosition : position;
        currentByKey = byKey;
        startFlusher();
        mysqlSource.startWal(start.binlogFile, start.binlogPosition, batch -> {
            if (stopRequested) {
                throw new InterruptedException("stopped");
            }
            buffer.add(batch);
            if (buffer.size() >= config.defaults.batch.maxBufferedEvents
                    || buffer.sizeBytes() >= config.defaults.batch.maxBufferedBytes) {
                flushBuffer();
            }
        });
    }

    // ------------------------------------------------------------------ 快照

    /** resync 快照：源端在同一个一致性事务内逐表回调。 */
    private SnapshotTableConsumer resyncConsumer(Map<String, TableMapping> byKey) {
        return new SnapshotTableConsumer() {
            /** 当前表是否命中 skipIfExists；逐表顺序回调，因此可以是普通字段。 */
            private boolean skipped;

            @Override
            public void begin(TableMappingRef table) throws Exception {
                TableMapping mapping = require(byKey, table);
                skipped = !writer.beginSnapshot(mapping, job.snapshot.mode);
                if (skipped) {
                    logger.info("[DBSYNCER][{}] snapshot skipped (skipIfExists) for {}", job.name,
                            mapping.source().qualifiedName());
                }
            }

            @Override
            public void rows(TableMappingRef table, List<Map<String, Object>> rows, long totalRows)
                    throws Exception {
                if (!skipped) {
                    writer.snapshotChunk(require(byKey, table), rows, totalRows);
                }
            }

            @Override
            public void end(TableMappingRef table, long totalRows) throws Exception {
                if (!skipped) {
                    writer.finishSnapshot(require(byKey, table), totalRows);
                }
            }
        };
    }

    private static TableMapping require(Map<String, TableMapping> byKey, TableMappingRef table) {
        TableMapping mapping = byKey.get(keyOf(table));
        if (mapping == null) {
            throw new IllegalStateException("no target mapping for source table " + table);
        }
        return mapping;
    }

    private static String keyOf(TableMappingRef table) {
        return (table.sourceSchema() + "." + table.sourceTable()).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 攒批

    private void startFlusher() {
        long interval = Math.max(100, config.defaults.batch.flushIntervalMs);
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "dbsync-flush-" + job.name);
            thread.setDaemon(true);
            return thread;
        });
        flusher.scheduleWithFixedDelay(this::flushBufferQuietly, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void stopFlusher() {
        ScheduledExecutorService current = flusher;
        flusher = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    private void flushBufferQuietly() {
        try {
            flushBuffer();
        } catch (SyncPauseException e) {
            // 暂停流程已经在 flushBuffer 内完成（源已停、状态已置 PAUSED）。
            logger.warn("[DBSYNCER][{}] flush paused: {}", job.name, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            lastError = "batch flush failed: " + e.getMessage();
            logger.error("[DBSYNCER][{}] batch flush failed: {}", job.name, e.getMessage(), e);
        }
    }

    /**
     * 取出缓冲批次并写入目标。
     *
     * <p>整批失败时**不推进检查点**：先按 {@code batch.maxAttempts} 重试同一批（{@code writer.apply}
     * 失败会整批回滚，所以重试是安全的）；重试用尽后清空内存缓冲、停止源、置 {@code PAUSED}，
     * 由操作者修复后重新 start —— 重启会从上一个成功提交的检查点重放，不会丢数据。</p>
     */
    private void flushBuffer() throws Exception {
        flushLock.lock();
        try {
            BatchBuffer.Pending pending = buffer.take();
            if (pending == null) {
                return;
            }
            ChangeBatch combined = new ChangeBatch(pending.events(), pending.position());
            long applied = applyBatchWithRetry(combined);
            metrics.recordBatch(combined, applied);
            position = pending.position();
            if (source != null && pending.position().isPg()) {
                source.acknowledge(pending.position().lsn);
            }
        } finally {
            flushLock.unlock();
        }
    }

    private long applyBatchWithRetry(ChangeBatch batch) throws Exception {
        int maxAttempts = Math.max(1, config.defaults.batch.maxAttempts);
        long retryInterval = Math.max(0, config.defaults.batch.retryIntervalMs);
        for (int attempt = 1; ; attempt++) {
            if (stopRequested) {
                throw new InterruptedException("stopped");
            }
            try {
                return writer.apply(job.name, batch, currentByKey, config.defaults.batch.maxRows);
            } catch (SQLException e) {
                if (stopRequested) {
                    throw new InterruptedException("stopped");
                }
                lastError = "batch apply failed (" + attempt + "/" + maxAttempts + "): " + e.getMessage();
                if (attempt >= maxAttempts) {
                    logger.error("[DBSYNCER][{}] batch apply failed at {} after {} attempt(s); "
                                    + "checkpoint NOT advanced, pausing job: {}",
                            job.name, batch.position(), maxAttempts, e.getMessage(), e);
                    pauseAfterApplyFailure();
                    throw new SyncPauseException("batch apply failed after " + maxAttempts
                            + " attempt(s) at " + batch.position() + ": " + e.getMessage());
                }
                logger.warn("[DBSYNCER][{}] batch apply failed at {} ({}/{}): {}; retrying in {} ms",
                        job.name, batch.position(), attempt, maxAttempts, e.getMessage(), retryInterval);
                if (!sleepRetry(retryInterval)) {
                    throw new InterruptedException("stopped");
                }
            }
        }
    }

    /**
     * 写入持续失败：停读源、丢弃内存缓冲（检查点未推进，重启后重放），进入 PAUSED。
     */
    private void pauseAfterApplyFailure() {
        flushFailed = true;
        stopRequested = true;
        PostgresSource currentPg = source;
        if (currentPg != null) {
            currentPg.stop();
        }
        MySqlSource currentMySql = mysqlSource;
        if (currentMySql != null) {
            currentMySql.stop();
        }
        buffer.clear();
        Thread currentRunner = runner;
        if (currentRunner != null && currentRunner != Thread.currentThread()) {
            currentRunner.interrupt();
        }
        transition(JobState.PAUSED);
    }

    private boolean sleepRetry(long millis) {
        long remaining = millis;
        while (remaining > 0) {
            if (stopRequested) {
                return false;
            }
            long chunk = Math.min(remaining, 200);
            try {
                Thread.sleep(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= chunk;
        }
        return !stopRequested;
    }

    private boolean needsSnapshot(Map<String, SyncStateStore.TableState> states) {
        for (TableMapping mapping : mappings) {
            SyncStateStore.TableState tableState = states.get(mapping.sourceKey());
            if (tableState == null || !tableState.snapshotDone) {
                return true;
            }
        }
        return false;
    }

    private void saveCheckpoint(TxPosition checkpointPosition) throws SQLException {
        target.call(() -> {
            stateStore.saveCheckpoint(target.connection(), job.name, checkpointPosition, 0);
            return null;
        });
    }

    /**
     * 清理检查点与表状态：这两张表也由批次事务写，必须走目标单写连接
     * （DuckDB 不允许两个连接同时写同一张表）。
     */
    private void clearState() throws SQLException {
        target.call(() -> {
            Connection connection = target.connection();
            stateStore.clearCheckpoint(connection, job.name);
            stateStore.clearTableStates(connection, job.name);
            return null;
        });
    }

    /**
     * DDL 准入：独占 flush 锁（定时 flush 线程会在锁上等待），先提交 DDL 之前的行事件，
     * 再执行「DDL + 位点」的原子提交。这样 DDL 不会与批写入并发修改同一份 {@code TargetTable}。
     */
    private void applyDdl(DdlAction action) throws Exception {
        flushLock.lock();
        try {
            flushBuffer();
            action.run();
        } finally {
            flushLock.unlock();
        }
    }

    // ------------------------------------------------------------------ 状态

    private void transition(JobState next) {
        synchronized (stateLock) {
            setState(next);
            persistState();
            stateLock.notifyAll();
        }
        logger.info("[DBSYNCER][{}] state -> {}", job.name, next);
    }

    private void setState(JobState next) {
        this.state = next;
    }

    private void persistJob() {
        try {
            stateStore.upsertJob(job.name, configHash, state.name(), state.name(), job.source,
                    target.catalog(), targetConfig.schema);
        } catch (SQLException e) {
            logger.warn("[DBSYNCER][{}] cannot persist job state: {}", job.name, e.getMessage());
        }
    }

    private void persistState() {
        try {
            stateStore.updateJobState(job.name, state.name(), state.name(), lastError);
        } catch (SQLException e) {
            logger.warn("[DBSYNCER][{}] cannot persist state: {}", job.name, e.getMessage());
        }
    }

    private void markStopped() {
        try {
            stateStore.markStopped(job.name, state.name());
        } catch (SQLException e) {
            logger.warn("[DBSYNCER][{}] cannot mark stopped: {}", job.name, e.getMessage());
        }
    }

    private void recordFailedDdl(String ddl) {
        this.lastFailedDdl = ddl;
    }

    // ------------------------------------------------------------------ 状态快照

    public JSONObject status() {
        JSONObject json = new JSONObject();
        json.put("name", job.name);
        json.put("description", job.description);
        json.put("state", state.name());
        json.put("phase", state == JobState.STREAMING ? "CDC" : state.name());
        json.put("mode", job.mode.yaml);
        json.put("configHash", configHash);
        json.put("orphaned", orphaned);
        json.put("startedAt", startedAt <= 0 ? null : Instant.ofEpochMilli(startedAt).toString());
        json.put("stoppedAt", completedAt <= 0 ? null : Instant.ofEpochMilli(completedAt).toString());
        json.put("uptimeSeconds", uptimeSeconds());
        json.put("lastError", lastError);
        json.put("lastFailedDdl", lastFailedDdl);
        json.put("source", sourceStatus());
        json.put("target", targetStatus());
        putCheckpointAndLag(json);
        json.put("metrics", metricsStatus());
        json.put("tables", tableStatus());
        return json;
    }

    /** 运行中按当前时间算，已结束的运行按 stoppedAt 冻结（停止后不再增长）。 */
    private long uptimeSeconds() {
        if (startedAt <= 0) {
            return 0;
        }
        long end = completedAt > 0 ? completedAt : System.currentTimeMillis();
        return Math.max(0, (end - startedAt) / 1000);
    }

    private JSONObject sourceStatus() {
        JSONObject json = new JSONObject();
        json.put("name", job.source);
        SyncConfig.ConnectionConfig connection = config.connections.get(job.source);
        json.put("type", connection == null ? null : connection.type.name().toLowerCase(Locale.ROOT));
        return json;
    }

    private JSONObject targetStatus() {
        JSONObject json = new JSONObject();
        json.put("name", job.target);
        json.put("catalog", target.catalog());
        json.put("schema", targetConfig.schema);
        return json;
    }

    private void putCheckpointAndLag(JSONObject json) {
        TxPosition current = position;
        if (current == null) {
            return;
        }
        JSONObject checkpoint = new JSONObject();
        checkpoint.put("type", current.typeName());
        checkpoint.put("value", current.display());
        json.put("checkpoint", checkpoint);
        Long lag = null;
        try {
            if (current.isPg() && source != null) {
                lag = source.lagBytes(current.lsn);
            } else if (!current.isPg() && mysqlSource != null) {
                lag = mysqlSource.lagBytes(current.binlogFile, current.binlogPosition);
            }
        } catch (Exception e) {
            logger.debug("[DBSYNCER][{}] lag calculation failed: {}", job.name, e.getMessage());
        }
        if (lag != null) {
            JSONObject lagJson = new JSONObject();
            lagJson.put("bytes", lag);
            lagJson.put("updatedAt", Instant.now().toString());
            json.put("lag", lagJson);
        }
    }

    private JSONObject metricsStatus() {
        JSONObject json = new JSONObject();
        json.put("eventsRead", metrics.eventsRead.get());
        json.put("eventsApplied", metrics.eventsApplied.get());
        json.put("inserts", metrics.inserts.get());
        json.put("updates", metrics.updates.get());
        json.put("deletes", metrics.deletes.get());
        json.put("batches", metrics.batches.get());
        json.put("lastCommitAt", metrics.lastCommitAt <= 0 ? null
                : Instant.ofEpochMilli(metrics.lastCommitAt).toString());
        return json;
    }

    private JSONArray tableStatus() {
        JSONArray tables = new JSONArray();
        Map<String, SyncStateStore.TableState> states;
        try {
            states = stateStore.loadTableStates(job.name);
        } catch (SQLException e) {
            return tables;
        }
        for (TableMapping mapping : mappings) {
            JSONObject table = new JSONObject();
            table.put("source", mapping.source().qualifiedName());
            table.put("target", mapping.target().schema + "." + mapping.target().table);
            SyncStateStore.TableState tableState = states.get(mapping.sourceKey());
            table.put("snapshotDone", tableState != null && tableState.snapshotDone);
            table.put("snapshotRows", tableState == null ? 0 : tableState.snapshotRows);
            table.put("lastOp", tableState == null ? null : tableState.lastOp);
            tables.add(table);
        }
        return tables;
    }
}
