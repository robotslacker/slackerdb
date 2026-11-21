package org.slackerdb.dbsyncer.source.mysql;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeader;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.FormatDescriptionEventData;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import org.slackerdb.dbsyncer.config.SyncConfig;
import org.slackerdb.dbsyncer.model.ChangeBatch;
import org.slackerdb.dbsyncer.model.ChangeEvent;
import org.slackerdb.dbsyncer.model.SyncPauseException;
import org.slackerdb.dbsyncer.model.TxPosition;
import org.slackerdb.dbsyncer.source.BatchConsumer;
import org.slackerdb.dbsyncer.source.DdlExecutor;
import org.slackerdb.dbsyncer.source.FlushHandler;
import org.slackerdb.dbsyncer.source.SnapshotTableConsumer;
import org.slackerdb.dbsyncer.source.SourceConnections;
import org.slackerdb.dbsyncer.model.SourceTable;
import org.slackerdb.dbsyncer.source.TableMappingRef;
import org.slackerdb.dbsyncer.source.TableSnapshotReader;
import org.slackerdb.dbsyncer.model.TableMapping;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * MySQL binlog 源：ROW 格式、file+position 位点、快照 + 增量。
 */
public final class MySqlSource {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final SyncConfig.ConnectionConfig connectionConfig;
    private final SyncConfig.JobConfig job;
    private final SyncConfig.ReconnectConfig reconnect;
    private final FlushHandler beforeReconnect;
    private final DdlExecutor ddlExecutor;
    private final List<TableMapping> mappings;
    private final MySqlDdlApplier ddlApplier;
    private final Consumer<String> ddlFailure;
    private final Logger logger;
    private final Endpoint endpoint;

    private volatile boolean stopped;
    private volatile BinaryLogClient client;
    private volatile String currentFile;
    private volatile long currentPosition;
    private volatile RuntimeException failure;
    private BatchConsumer consumer;

    private final Map<Long, TableMapping> tableMap = new HashMap<>();
    private final List<ChangeEvent> events = new ArrayList<>();
    private final Set<String> noPkWarned = new HashSet<>();

    public MySqlSource(SyncConfig.ConnectionConfig connectionConfig, SyncConfig.JobConfig job,
                       List<TableMapping> mappings, SyncConfig.ReconnectConfig reconnect,
                       FlushHandler beforeReconnect, DdlExecutor ddlExecutor,
                       MySqlDdlApplier ddlApplier, Consumer<String> ddlFailure,
                       Logger logger) {
        this.connectionConfig = connectionConfig;
        this.job = job;
        this.mappings = mappings;
        this.reconnect = reconnect;
        this.beforeReconnect = beforeReconnect;
        this.ddlExecutor = ddlExecutor;
        this.ddlApplier = ddlApplier;
        this.ddlFailure = ddlFailure;
        this.logger = logger;
        this.endpoint = Endpoint.parse(connectionConfig.url);
    }

    /** 同一 binlog 文件内的字节差；跨文件无法用字节表达，返回 null。 */
    public Long lagBytes(String file, long position) {
        if (file == null || position <= 0) {
            return null;
        }
        try {
            String[] current = probePosition();
            if (!file.equals(current[0])) {
                return null;
            }
            return Math.max(0, Long.parseLong(current[1]) - position);
        } catch (Exception e) {
            logger.debug("[DBSYNCER][{}] lag query failed: {}", job.name, e.getMessage());
            return null;
        }
    }

    /**
     * 状态接口用的短超时读取：源库半死时不能让 HTTP 线程挂住。
     * MySQL 驱动的 {@code connectTimeout}/{@code socketTimeout} 单位是毫秒。
     */
    private String[] probePosition() throws SQLException {
        try (Connection connection = SourceConnections.open(connectionConfig, Map.of(
                "connectTimeout", "3000",
                "socketTimeout", "5000"))) {
            return readPosition(connection);
        }
    }

    /** 校验源库配置并返回当前 binlog 位点。 */
    public Prepared prepare() throws SQLException {
        warnUnlessCursorFetch();
        try (Connection connection = SourceConnections.open(connectionConfig)) {
            validate(connection);
            validateBinlogFilters(connection);
        }
        String[] position = currentPosition();
        long serverId = deriveServerId();
        try (Connection connection = SourceConnections.open(connectionConfig)) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT @@server_id")) {
                if (rs.next() && rs.getLong(1) == serverId) {
                    throw new SQLException("derived serverId [" + serverId
                            + "] equals the source @@server_id; choose a different job name");
                }
            }
        }
        return new Prepared(position[0], Long.parseLong(position[1]));
    }

    private void validate(Connection connection) throws SQLException {
        String logBin = variable(connection, "log_bin");
        if (!"ON".equalsIgnoreCase(logBin) && !"1".equals(logBin)) {
            throw new SQLException("log_bin must be ON");
        }
        String format = variable(connection, "binlog_format");
        if (!"ROW".equalsIgnoreCase(format)) {
            throw new SQLException("binlog_format must be ROW, got " + format);
        }
        String rowImage = variable(connection, "binlog_row_image");
        if (!"FULL".equalsIgnoreCase(rowImage)) {
            throw new SQLException("binlog_row_image must be FULL, got " + rowImage);
        }
        // 压缩事务会包在 TRANSACTION_PAYLOAD 里，行事件的位点语义与普通事件不同，当前不支持。
        String compression = variable(connection, "binlog_transaction_compression");
        if ("ON".equalsIgnoreCase(compression) || "1".equals(compression)) {
            throw new SQLException("binlog_transaction_compression must be OFF: compressed transactions"
                    + " are not supported");
        }
        // 部分 JSON 更新会在行事件里放 JSON_DIFF，当前解码器不支持。
        String rowValueOptions = variable(connection, "binlog_row_value_options");
        if (rowValueOptions != null && !rowValueOptions.isBlank()) {
            throw new SQLException("binlog_row_value_options must be empty (found ["
                    + rowValueOptions + "]); partial JSON updates are not supported");
        }
    }

    private static String variable(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SHOW VARIABLES LIKE ?")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(2) : null;
            }
        }
    }

    /**
     * 没有 {@code useCursorFetch=true} 时 mysql-connector 会把整个结果集读进内存，
     * 大表快照可能直接把服务端打爆。这里只告警（小表仍然能正常工作），把选择权留给用户。
     */
    private void warnUnlessCursorFetch() {
        String url = connectionConfig.url == null ? "" : connectionConfig.url.toLowerCase(Locale.ROOT);
        boolean enabled = url.contains("usecursorfetch=true") || url.contains("usecursorfetch=1");
        if (!enabled) {
            logger.warn("[DBSYNCER][{}] JDBC URL has no useCursorFetch=true; large table snapshots"
                    + " will buffer the whole result set in memory", job.name);
        }
    }

    /**
     * 源端配置了 {@code --binlog-do-db}/{@code --binlog-ignore-db} 时，被同步的库必须在允许范围内，
     * 否则 binlog 里根本没有这些表的行事件（作业会一直 STREAMING 但什么都不同步）。
     */
    private void validateBinlogFilters(Connection connection) throws SQLException {
        String[] filters = readBinlogFilters(connection);
        String doDb = filters[0];
        String ignoreDb = filters[1];
        if ((doDb == null || doDb.isBlank()) && (ignoreDb == null || ignoreDb.isBlank())) {
            return;
        }
        for (String schema : sourceSchemas()) {
            if (doDb != null && !doDb.isBlank() && !listContains(doDb, schema)) {
                throw new SQLException("source filters binlog to [" + doDb + "] but schema ["
                        + schema + "] is not included; the synced databases must be listed in"
                        + " --binlog-do-db");
            }
            if (ignoreDb != null && !ignoreDb.isBlank() && listContains(ignoreDb, schema)) {
                throw new SQLException("source ignores binlog for schema [" + schema
                        + "] (--binlog-ignore-db=" + ignoreDb + "); remove it from the ignore list");
            }
        }
    }

    private java.util.Set<String> sourceSchemas() {
        Set<String> schemas = new LinkedHashSet<>();
        for (TableMapping mapping : mappings) {
            schemas.add(mapping.source().schema);
        }
        return schemas;
    }

    private static boolean listContains(String list, String value) {
        for (String item : list.split(",")) {
            if (item.trim().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** @return {Binlog_Do_DB, Binlog_Ignore_DB}；取不到时返回 null（不因此让作业启动失败）。 */
    private String[] readBinlogFilters(Connection connection) {
        String[] result = new String[]{null, null};
        for (String sql : new String[]{"SHOW BINARY LOG STATUS", "SHOW MASTER STATUS"}) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                if (rs.next() && rs.getMetaData().getColumnCount() >= 4) {
                    result[0] = rs.getString(3);
                    result[1] = rs.getString(4);
                }
                return result;
            } catch (SQLException e) {
                // 老版本没有 SHOW BINARY LOG STATUS，继续试 SHOW MASTER STATUS。
            }
        }
        logger.debug("[DBSYNCER][{}] cannot read binlog filters (both status statements failed)", job.name);
        return result;
    }

    private String[] currentPosition() throws SQLException {
        try (Connection connection = SourceConnections.open(connectionConfig)) {
            return readPosition(connection);
        }
    }

    /** MySQL 8.4 起 {@code SHOW MASTER STATUS} 改名，按版本降级。 */
    private String[] readPosition(Connection connection) throws SQLException {
        try {
            return readPosition(connection, "SHOW BINARY LOG STATUS");
        } catch (SQLException first) {
            try {
                return readPosition(connection, "SHOW MASTER STATUS");
            } catch (SQLException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private String[] readPosition(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("cannot read binlog position; is binary logging enabled?");
            }
            return new String[]{rs.getString("File"), String.valueOf(rs.getLong("Position"))};
        }
    }

    /** 阻塞读取 binlog；consumer 成功返回后才推进位点。 */
    public void startWal(String file, long position, BatchConsumer batchConsumer) throws Exception {
        this.consumer = batchConsumer;
        this.currentFile = file;
        this.currentPosition = position;
        int failures = 0;
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            failure = null;
            // 上次连接可能读到半个事务就断了：这些事件既没提交也不能保留，
            // 重连后从 currentPosition 重新读取，所以必须清空，否则同一批行会被应用两次。
            events.clear();
            try {
                BinaryLogClient current = new BinaryLogClient(endpoint.host(), endpoint.port(),
                        connectionConfig.username,
                        connectionConfig.password == null ? "" : connectionConfig.password);
                current.setServerId(deriveServerId());
                current.setBinlogFilename(currentFile);
                current.setBinlogPosition(currentPosition);
                EventDeserializer deserializer = new EventDeserializer();
                deserializer.setCompatibilityMode(
                        EventDeserializer.CompatibilityMode.CHAR_AND_BINARY_AS_BYTE_ARRAY);
                current.setEventDeserializer(deserializer);
                current.registerEventListener(this::handleEvent);
                current.registerLifecycleListener(new BinaryLogClient.LifecycleListener() {
                    @Override
                    public void onConnect(BinaryLogClient ignored) {
                    }

                    @Override
                    public void onCommunicationFailure(BinaryLogClient ignored, Exception ex) {
                    }

                    @Override
                    public void onEventDeserializationFailure(BinaryLogClient ignored, Exception ex) {
                        logger.error("[DBSYNCER][{}] binlog deserialization failed: {}", job.name,
                                ex.getMessage());
                    }

                    @Override
                    public void onDisconnect(BinaryLogClient ignored) {
                    }
                });
                client = current;
                logger.info("[DBSYNCER][{}] binlog replication started at {}:{} (serverId={})",
                        job.name, currentFile, currentPosition, current.getServerId());
                current.connect();
            } catch (Exception e) {
                if (stopped) {
                    return;
                }
                if (e instanceof SyncPauseException pause) {
                    throw pause;
                }
                // 事件线程捕获的致命错误（DDL 失败、写入失败等）不应被当成可重连故障。
                RuntimeException captured = failure;
                if (captured != null) {
                    throw captured;
                }
                logger.warn("[DBSYNCER][{}] binlog connection issue: {}", job.name, e.getMessage());
            } finally {
                BinaryLogClient current = client;
                client = null;
                if (current != null) {
                    try {
                        current.disconnect();
                    } catch (IOException ignored) {
                    }
                }
            }
            if (stopped) {
                return;
            }
            failures++;
            if (failures > reconnect.maxAttempts) {
                throw new SQLException("MySQL binlog replication failed after " + failures + " attempts");
            }
            logger.warn("[DBSYNCER][{}] binlog disconnected, reconnect {}/{} in {} ms",
                    job.name, failures, reconnect.maxAttempts, reconnect.intervalMs);
            if (!sleep(reconnect.intervalMs)) {
                return;
            }
            beforeReconnect.flush();
        }
    }

    private synchronized void handleEvent(Event event) {
        if (stopped || consumer == null) {
            return;
        }
        EventHeader header = event.getHeader();
        EventType type = header.getEventType();
        long nextPosition = header instanceof com.github.shyiko.mysql.binlog.event.EventHeaderV4 v4
                ? v4.getNextPosition()
                : currentPosition;
        try {
            switch (type) {
                case TABLE_MAP -> handleTableMap(event.getData());
                case EXT_WRITE_ROWS, PRE_GA_WRITE_ROWS, WRITE_ROWS -> handleWriteRows(event.getData());
                case EXT_UPDATE_ROWS, PRE_GA_UPDATE_ROWS, UPDATE_ROWS -> handleUpdateRows(event.getData());
                case EXT_DELETE_ROWS, PRE_GA_DELETE_ROWS, DELETE_ROWS -> handleDeleteRows(event.getData());
                case QUERY -> handleQuery(event.getData(), nextPosition);
                case TRANSACTION_PAYLOAD -> throw new SyncPauseException(
                        "compressed transaction (binlog_transaction_compression=ON) is not supported;"
                                + " set binlog_transaction_compression=OFF and restart the job");
                case XID -> {
                    flush(nextPosition);
                    logger.debug("[DBSYNCER][{}] commit xid event at {}", job.name, nextPosition);
                }
                case ROTATE -> {
                    RotateEventData data = event.getData();
                    currentFile = data.getBinlogFilename();
                    currentPosition = data.getBinlogPosition();
                }
                case FORMAT_DESCRIPTION -> {
                    FormatDescriptionEventData data = event.getData();
                    // 重连后 table id 会重新分配，旧的映射不能复用。
                    tableMap.clear();
                    logger.debug("[DBSYNCER][{}] binlog format version {}", job.name, data.getBinlogVersion());
                }
                default -> {
                    // HEARTBEAT/GTID/etc.
                }
            }
        } catch (Exception e) {
            if (stopped || e instanceof InterruptedException) {
                // 停止途中回调抛出的中断不是故障：交给连接循环看到 stopped 后退出。
                disconnectQuietly();
                return;
            }
            failure = e instanceof SyncPauseException pause ? pause
                    : new RuntimeException("binlog event " + type + " failed: " + e.getMessage(), e);
            disconnectQuietly();
        }
    }

    private void disconnectQuietly() {
        BinaryLogClient current = client;
        if (current == null) {
            return;
        }
        try {
            current.disconnect();
        } catch (IOException ignored) {
        }
    }

    private void handleTableMap(TableMapEventData data) {
        TableMapping mapping = mapping((data.getDatabase() + "." + data.getTable())
                .toLowerCase(Locale.ROOT));
        if (mapping != null) {
            tableMap.put(data.getTableId(), mapping);
        } else {
            tableMap.remove(data.getTableId());
        }
    }

    private TableMapping mapping(String key) {
        for (TableMapping mapping : mappings) {
            if (mapping.sourceKey().equals(key)) {
                return mapping;
            }
        }
        return null;
    }

    private boolean skipNoPk(TableMapping mapping, String operation) {
        if (mapping.hasPrimaryKey()) {
            return false;
        }
        if (noPkWarned.add(mapping.sourceKey())) {
            logger.warn("[DBSYNCER][{}] source table {} has no primary key; skip {} events",
                    job.name, mapping.source().qualifiedName(), operation);
        }
        return true;
    }

    private void handleWriteRows(WriteRowsEventData data) {
        TableMapping mapping = tableMap.get(data.getTableId());
        if (mapping == null) {
            return;
        }
        for (Object[] row : data.getRows()) {
            events.add(ChangeEvent.insert(mapping.source().schema, mapping.source().table,
                    rowToMap(mapping, row, data.getIncludedColumns()), System.currentTimeMillis()));
        }
    }

    private void handleUpdateRows(UpdateRowsEventData data) {
        TableMapping mapping = tableMap.get(data.getTableId());
        if (mapping == null || skipNoPk(mapping, "UPDATE")) {
            return;
        }
        BitSet beforeColumns = data.getIncludedColumnsBeforeUpdate();
        BitSet afterColumns = data.getIncludedColumns();
        for (Map.Entry<Serializable[], Serializable[]> row : data.getRows()) {
            Map<String, Object> before = rowToMap(mapping, row.getKey(), beforeColumns);
            Map<String, Object> after = rowToMap(mapping, row.getValue(), afterColumns);
            Set<String> missing = new LinkedHashSet<>();
            for (SourceTable.SourceColumn column : mapping.source().columns) {
                if (!after.containsKey(column.name)) {
                    missing.add(column.name);
                }
            }
            events.add(ChangeEvent.update(mapping.source().schema, mapping.source().table, before, after,
                    missing, System.currentTimeMillis()));
        }
    }

    private void handleDeleteRows(DeleteRowsEventData data) {
        TableMapping mapping = tableMap.get(data.getTableId());
        if (mapping == null || skipNoPk(mapping, "DELETE")) {
            return;
        }
        for (Object[] row : data.getRows()) {
            events.add(ChangeEvent.delete(mapping.source().schema, mapping.source().table,
                    rowToMap(mapping, row, data.getIncludedColumns()), System.currentTimeMillis()));
        }
    }

    private Map<String, Object> rowToMap(TableMapping mapping, Object[] row, BitSet included) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (included == null) {
            return result;
        }
        int i = included.nextSetBit(0);
        while (i >= 0) {
            if (i < mapping.source().columns.size() && i < row.length) {
                SourceTable.SourceColumn column = mapping.source().columns.get(i);
                result.put(column.name, MySqlValueDecoder.decode(column, row[i]));
            }
            i = included.nextSetBit(i + 1);
        }
        return result;
    }

    private void handleQuery(QueryEventData data, long nextPosition) throws Exception {
        String sql = data.getSql();
        if (sql == null) {
            return;
        }
        if (isCommit(sql)) {
            // 非事务表（MyISAM 等）没有 XID 事件，事务边界只体现为 COMMIT 语句；
            // 不在这里 flush，事件会一直积压到下一次 DDL 或重连。
            flush(nextPosition);
            return;
        }
        if (!isDdl(sql)) {
            return;
        }
        try {
            // DDL 期间独占目标写入：先提交 DDL 之前的行事件，再在同一事务里提交 DDL 与位点。
            ddlExecutor.applyDdl(() -> ddlApplier.applyAtomically(data.getDatabase(), sql, job.name,
                    TxPosition.mysql(currentFile, nextPosition)));
            currentPosition = nextPosition;
            logger.info("[DBSYNCER][{}] DDL applied and checkpoint advanced: {}", job.name, sql);
        } catch (InterruptedException e) {
            // 作业正在停止：不是 DDL 失败。
            Thread.currentThread().interrupt();
            throw e;
        } catch (SyncPauseException e) {
            // 前置 flush 已经判定要暂停（写入失败等），不要改写成"DDL apply failed"。
            // 真正由 DDL 翻译失败抛出的异常不会走到这里。
            throw e;
        } catch (Exception e) {
            if (ddlFailure != null) {
                ddlFailure.accept(sql);
            }
            logger.error("[DBSYNCER][{}] DDL apply failed: {} -- {}", job.name, sql, e.getMessage());
            throw new SyncPauseException("DDL apply failed at " + currentFile + ":" + nextPosition
                    + " (" + e.getMessage() + "); fix the target schema and restart the job");
        }
    }

    private static boolean isCommit(String sql) {
        String lower = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        lower = WHITESPACE.matcher(lower).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
        while (lower.endsWith(";")) {
            lower = lower.substring(0, lower.length() - 1).trim();
        }
        return switch (lower) {
            case "commit", "commit work", "commit and chain", "commit and no chain", "commit release" -> true;
            default -> false;
        };
    }

    private static boolean isDdl(String sql) {
        String lower = sql.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("alter ") || lower.startsWith("create ") || lower.startsWith("drop ")
                || lower.startsWith("truncate ") || lower.startsWith("rename ");
    }

    private void flush(long nextPosition) throws Exception {
        if (events.isEmpty()) {
            currentPosition = nextPosition;
            return;
        }
        List<ChangeEvent> batchEvents = new ArrayList<>(events);
        events.clear();
        consumer.accept(new ChangeBatch(batchEvents, TxPosition.mysql(currentFile, nextPosition)));
        currentPosition = nextPosition;
    }

    public void stop() {
        stopped = true;
        disconnectQuietly();
    }

    /** resync：所有表在同一个 REPEATABLE READ + CONSISTENT SNAPSHOT 事务内读取。 */
    public TxPosition snapshotAll(SyncConfig.SnapshotConfig snapshot, SnapshotTableConsumer consumer)
            throws Exception {
        try (Connection connection = SourceConnections.open(connectionConfig)) {
            // 关掉 autocommit：整段快照都在同一个显式事务里，最后 rollback 结束（快照只读）。
            connection.setAutoCommit(false);
            // 与 mysqldump --single-transaction --master-data 相同的顺序：
            //   全局读锁 → 开一致性快照 → 读 binlog 位点 → 解锁。
            // 如果先开快照再读位点，两条语句之间提交的事务既不在快照里、又早于位点，
            // CDC 会永久跳过它们（静默漏数据）。
            lockForConsistentSnapshot(connection);
            String[] snapshotPos;
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                    statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");
                }
                snapshotPos = readPosition(connection);
            } finally {
                unlockTables(connection);
            }
            try {
                TxPosition snapshotPosition = TxPosition.mysql(snapshotPos[0],
                        Long.parseLong(snapshotPos[1]));
                for (TableMapping mapping : mappings) {
                    TableMappingRef table = ref(mapping);
                    consumer.begin(table);
                    long total = TableSnapshotReader.read(connection, table,
                            "SELECT * FROM " + quote(mapping.source().schema) + "."
                                    + quote(mapping.source().table),
                            snapshot.fetchSize, consumer::rows);
                    consumer.end(table, total);
                    logger.info("[DBSYNCER][{}] snapshot table {} completed, rows={}", job.name,
                            mapping.source().qualifiedName(), total);
                }
                connection.rollback();
                return snapshotPosition;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        }
    }

    /**
     * 取全局读锁，保证"一致性快照点"和"binlog 位点"之间没有事务提交。
     * 需要 {@code RELOAD} 权限；没有权限时给出可操作的报错，而不是退回有漏数据风险的旧顺序。
     */
    private void lockForConsistentSnapshot(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("FLUSH TABLES WITH READ LOCK");
        } catch (SQLException e) {
            throw new SQLException("cannot start a consistent snapshot: FLUSH TABLES WITH READ LOCK failed ("
                    + e.getMessage() + "); the sync account needs the RELOAD privilege", e);
        }
    }

    private void unlockTables(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("UNLOCK TABLES");
        } catch (SQLException e) {
            logger.warn("[DBSYNCER][{}] cannot release the snapshot read lock: {}", job.name, e.getMessage());
        }
    }

    private static TableMappingRef ref(TableMapping mapping) {
        return new TableMappingRef(mapping.source().schema, mapping.source().table);
    }

    private long deriveServerId() {
        // 把连接串也算进散列：不同服务器/库上同名作业不会再共用一个 server-id。
        // range 100_000..900_100_000 落在 MySQL 的 unsigned 32-bit 范围内。
        String key = connectionConfig.url + "|" + job.name;
        long hash = key.hashCode() & 0xFFFFFFFFL;
        return 100_000L + (hash % 900_000_000L);
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
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

    /** 当前 binlog 位点。 */
    public record Prepared(String file, long position) {
    }

    /** JDBC URL 里的 host/port；多主机取第一个，兼容 IPv6 与查询参数。 */
    record Endpoint(String host, int port) {

        private static final int DEFAULT_PORT = 3306;

        static Endpoint parse(String url) {
            String rest = url == null ? "" : url.trim();
            int scheme = rest.indexOf("//");
            if (scheme >= 0) {
                rest = rest.substring(scheme + 2);
            }
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '/' || c == '?' || c == ';') {
                    end = i;
                    break;
                }
            }
            String authority = rest.substring(0, end);
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                authority = authority.substring(at + 1);
            }
            int comma = authority.indexOf(',');
            if (comma >= 0) {
                authority = authority.substring(0, comma);
            }
            if (authority.startsWith("[")) {
                int close = authority.indexOf(']');
                if (close > 0) {
                    String host = authority.substring(1, close);
                    int port = close + 1 < authority.length() && authority.charAt(close + 1) == ':'
                            ? parsePort(authority.substring(close + 2))
                            : DEFAULT_PORT;
                    return new Endpoint(host, port);
                }
                return new Endpoint(authority, DEFAULT_PORT);
            }
            int colon = authority.lastIndexOf(':');
            if (colon < 0) {
                return new Endpoint(authority, DEFAULT_PORT);
            }
            return new Endpoint(authority.substring(0, colon), parsePort(authority.substring(colon + 1)));
        }

        private static int parsePort(String value) {
            try {
                int port = Integer.parseInt(value.trim());
                return port > 0 && port <= 65535 ? port : DEFAULT_PORT;
            } catch (NumberFormatException e) {
                return DEFAULT_PORT;
            }
        }
    }
}
