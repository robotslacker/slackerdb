package org.slackerdb.plugins.dbsyncer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.lmax.disruptor.dsl.Disruptor;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.kafka.connect.data.Decimal;
import org.pf4j.PluginWrapper;
import org.slackerdb.plugin.DBPlugin;
import org.slf4j.Logger;
import com.lmax.disruptor.util.DaemonThreadFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/**
 * Duckdb 数据 同步
 */
public class DBSyncer extends DBPlugin {
    private final ConcurrentHashMap<String, SyncEngineManager> syncManagerList = new ConcurrentHashMap<>();

    // 批量处理缓存
    private static class BatchCache {
        final String syncerName;
        final String op;
        final List<JSONObject> payloads = new ArrayList<>();

        BatchCache(String syncerName, String op) {
            this.syncerName = syncerName;
            this.op = op;
        }
        
        void addPayload(JSONObject payload) {
            payloads.add(payload);
        }
        
        void clear() {
            payloads.clear();
        }
    }

    private static class TableColumnTypeCache
    {
        final String syncerName;
        // 数据结构定义， DatabaseName.TableName.ColumnName, ColumnType
        final ConcurrentHashMap<String, String> tableColumns = new ConcurrentHashMap<>();

        TableColumnTypeCache(String syncerName) {
            this.syncerName = syncerName;
        }

        void addTableColumn(String tableColumn, String columnType) {
            tableColumns.put(tableColumn, columnType);
        }

        String getColumnType(String targetDatabase, String targetTable, String columnName) {
            String cacheKey = targetDatabase + "." + targetTable + "." + columnName;
            return tableColumns.get(cacheKey);
        }
    }

    private static class TablePrimaryKeyCache
    {
        final String syncerName;
        // 数据结构定义， DatabaseName.TableName -> List<PrimaryKeyColumnName>
        final ConcurrentHashMap<String, List<String>> tablePrimaryKeys = new ConcurrentHashMap<>();

        TablePrimaryKeyCache(String syncerName) {
            this.syncerName = syncerName;
        }

        void addTablePrimaryKey(String tableName, List<String> primaryKeyColumns) {
            tablePrimaryKeys.put(tableName, new ArrayList<>(primaryKeyColumns));
        }

        List<String> getTablePrimaryKey(String tableName) {
            return tablePrimaryKeys.get(tableName);
        }

        void removeTablePrimaryKey(String tableName) {
            tablePrimaryKeys.remove(tableName);
        }

        void clear() {
            tablePrimaryKeys.clear();
        }
    }
    
    // 数据缓存，数据进行批量攒批操作， syncerName, 数据负载
    private final ConcurrentHashMap<String, BatchCache> batchCacheMap = new ConcurrentHashMap<>();

    // 数据字段定义
    private final ConcurrentHashMap<String, TableColumnTypeCache> targetTableColumnTypeCacheMap = new ConcurrentHashMap<>();

    // 表主键定义缓存
    private final ConcurrentHashMap<String, TablePrimaryKeyCache> targetTablePrimaryKeyCacheMap = new ConcurrentHashMap<>();

    // 批量处理阈值
    private static final int BATCH_THRESHOLD = 1;
    
    /**
     * 将事件添加到缓存，如果需要则触发批量处理
     */
    private void addToBatchCache(
            String syncerName,
            String op,
            JSONObject payload,
            Connection conn)
    {
        // 获取或创建缓存
        String lastOp = null;
        if (!batchCacheMap.containsKey(syncerName))
        {
            batchCacheMap.put(syncerName, new BatchCache(syncerName, op));
        }
        else
        {
            lastOp = batchCacheMap.get(syncerName).op;
        }

        BatchCache batchCache = batchCacheMap.get(syncerName);
        // 检查操作类型是否变化（与缓存中的op比较）
        if (lastOp == null || !lastOp.equals(op)) {
            // 操作类型变化，处理当前缓存
            processBatchCache(syncerName, conn);
            // 清空之前的缓存
            batchCache.clear();
        }
        
        // 添加事件到缓存
        batchCache.addPayload(payload);
        
        // 检查是否超过阈值
        if (batchCache.payloads.size() >= BATCH_THRESHOLD) {
            processBatchCache(syncerName, conn);
            batchCache.clear();
        }
    }
    
    /**
     * 处理批量缓存
     */
    private void processBatchCache(String syncerName, Connection conn)
    {
        BatchCache cache = batchCacheMap.get(syncerName);
        if (cache.payloads.isEmpty()) {
            return;
        }

        String sql = null;
        try {
            switch (cache.op) {
                case "c": // 插入
                case "r": // 读取（快照）
                    batchInsertWithAppender(syncerName, cache.payloads, conn);
                    return;
                case "u": // 更新
                    for (JSONObject payload : cache.payloads) {
                        sql = generateUpdateSQL(syncerName, payload, conn);
                        if (sql != null && !sql.trim().isEmpty()) {
                            try (Statement stmt = conn.createStatement()) {
                                stmt.execute(sql);
                            }
                        }
                    }
                    return;
                case "d": // 删除
                    for (JSONObject payload : cache.payloads) {
                        sql = generateDeleteSQL(syncerName, payload, conn);
                        if (sql != null && !sql.trim().isEmpty()) {
                            try (Statement stmt = conn.createStatement()) {
                                stmt.execute(sql);
                            }
                        }
                    }
                    return;
                default:
                    getLogger().warn("[DB-Syncer] 不支持的操作类型: {} [Syncer: {}]", cache.op, cache.syncerName);
                    return;
            }
        } catch (Exception e) {
            getLogger().error("[DB-Syncer] 批量处理失败: {} {}", sql, e.getMessage(), e);
            System.exit(0);
        }
    }

    /**
     * 注册消息处理回调，启动同步管理器
     *
     */
    public DBSyncer(PluginWrapper wrapper) {
        super(wrapper);
    }

    private void processEvent(DebeziumEvent event, long seq, boolean end)
    {
        // 获取同步消息
        String rawJson = event.getValue();
        String syncerName = event.getSyncerName();
        String sql = null;

        try
        {
            // 获取同步引擎管理器
            SyncEngineManager manager = syncManagerList.get(syncerName);
            if (manager == null) {
                // 管理器可能已经被清理，忽略此事件
                getLogger().warn("[DB-Syncer] 未找到 syncer: {}, 事件已忽略", syncerName);
                return;
            }
            
            List<SyncerRuleInfo> rules = manager.getRules();
            if (rules == null || rules.isEmpty()) {
                getLogger().warn("[DB-Syncer] 未找到 syncer: {} 的规则", syncerName);
                return;
            }

            // 解析JSON
            JSONObject root = JSON.parseObject(rawJson);
            JSONObject payload = root.getJSONObject("payload");
            if (payload == null) return;

            // 检查是否是事务元数据（有status字段�?
            if (payload.containsKey("status")) {
                // 事务事件，不过滤，直接处理（目前仅打印）
                getLogger().trace("Transaction event: {} TXID: {} [Syncer: {}]",
                        payload.getString("status"),
                        payload.getString("id"),
                        syncerName);
                return;
            }

            // 获取数据库链�?
            Connection conn = syncManagerList.get(syncerName).getDbConnection();

            // 检查是否有数据定义(有ddl字段)
            if (payload.containsKey("ddl")) {
                // 获取数据库类�?
                String syncerType = "MYSQL"; // 默认�?
                StartEngineRequest request = manager.getStartEngineRequest();
                if (request != null && request.syncer_type != null) {
                    syncerType = request.syncer_type.toUpperCase();
                }
                
                // 获取要在目标数据库上执行的SQL
                sql = extractDDL(payload, syncerType);
                if (sql == null || sql.trim().isEmpty())
                {
                    // 空语句，不用执行
                    return;
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                }
                
                // DDL执行后，清除相关表的缓存（如果能够确定目标表）
                // 尝试从payload中获取源数据库和表信息
                JSONObject source = payload.getJSONObject("source");
                if (source != null) {
                    String sourceDatabase = source.getString("db");
                    String sourceTable = source.getString("table");
                    
                    // 转换为目标数据库和表
                    String targetDatabase = null;
                    String targetTable = null;
                    
                    for (SyncerRuleInfo rule : rules) {
                        if (sourceDatabase != null && sourceDatabase.matches(rule.source_database)) {
                            targetDatabase = sourceDatabase.replace(rule.source_database, rule.target_database);
                            break;
                        }
                    }
                    
                    for (SyncerRuleInfo rule : rules) {
                        if (sourceTable != null && sourceTable.matches(rule.source_table)) {
                            targetTable = sourceTable.replace(rule.source_table, rule.target_table);
                            break;
                        }
                    }
                    
                    if (targetDatabase != null && targetTable != null) {
                        // 清除该表的缓存，下次访问时会重新查询
                        clearTargetTableColumnCache(syncerName, targetDatabase, targetTable);
                        getLogger().debug("[DB-Syncer] DDL执行后清除表缓存: {}.{} [Syncer: {}]",
                                targetDatabase, targetTable, syncerName);
                    }
                }
            }
            // 检查是否是数据变更（有op字段）
            else if (payload.containsKey("op"))
            {
                JSONObject source = payload.getJSONObject("source");
                if (source == null) return;

                String db = source.getString("db");
                String table = source.getString("table");
                String dbTable = db + "." + table;
                String op = payload.getString("op");

                // 遍历所有规则，找到第一个匹配的
                SyncerRuleInfo matchedRule = null;
                for (SyncerRuleInfo rule : rules) {
                    if (rule.source_table == null || rule.source_table.isEmpty() || rule.source_table.equals("*")) {
                        matchedRule = rule;
                        break;
                    }
                    try {
                        if (dbTable.matches(rule.source_table)) {
                            matchedRule = rule;
                            break;
                        }
                    } catch (Exception e) {
                        getLogger().warn("[DB-Syncer] 无效资源模式: {} 对应规则: {}", rule.source_table, rule.rule_name);
                    }
                }

                if (matchedRule != null) {
                    // 将事件添加到批量缓存
                    addToBatchCache(syncerName, op, payload, conn);
                } else {
                    // 不匹配任何规则，丢弃事件
                    getLogger().debug("[DB-Syncer] 丢弃事件: [{}] {} 不匹配任何源模式 [Syncer: {}]", op, dbTable, syncerName);
                }
            }
            else {
                // 检查是否是通知事件
                if (payload.containsKey("aggregate_type") && payload.containsKey("type")) {
                    String aggregateType = payload.getString("aggregate_type");
                    String notificationType = payload.getString("type");
                    
                    // 处理快照完成通知
                    if ("SnapshotCompleted".equals(aggregateType)) {
                        System.out.println("[DB-Syncer] 快照完成通知: " + notificationType + " [Syncer: " + syncerName + "]");

                        // 更新状态为STREAMING
                        Connection dbConn = this.getDbConnection();
                        sql = """
                            UPDATE  sysaux.v$dbsyncer
                            SET     status = ?,
                                    update_time = CURRENT_TIMESTAMP
                            WHERE   syncer_name = ?
                            """;
                        PreparedStatement pStmt = dbConn.prepareStatement(sql);
                        pStmt.setString(1, "STREAMING");
                        pStmt.setString(2, syncerName);
                        pStmt.executeUpdate();
                        dbConn.commit();
                        pStmt.close();

                        System.out.println("[DB-Syncer] 快照完成，状态已更新为STREAMING [Syncer: " + syncerName + "]");
                    }

                    // 处理其他通知类型
                    System.out.println("[DB-Syncer] 收到通知: aggregate_type=" + aggregateType +
                                     ", type=" + notificationType + " [Syncer: " + syncerName + "]");
                    return;
                }
                
                // 其他未知类型事件
                getLogger().error("[DB-Syncer] 未知事件类型: {} [Syncer: {}]", payload.keySet(), syncerName);
                getLogger().debug("[DB-Syncer] 原始事件内容: {}", rawJson);
            }

            // 提取offset信息并更新latestOffsets映射，同时直接保存到数据�?
            if (payload.containsKey("source")) {
                // 写入数据�?
                JSONObject source = payload.getJSONObject("source");
                if (source != null) {
                    // 直接保存source对象的JSON字符串作为offset
                    // 这包含了所有必要的offset信息：ts_sec, file, pos, row, server_id�?
                    String offsetData = source.toJSONString();
                    // 直接保存offset到数据库
                    saveOffsetToDatabase(syncerName, offsetData);
                }
            }

            // 提交事物
            conn.commit();

        } catch (Exception e)
        {
            getLogger().error("[DB-Syncer] SQL执行失败: {}", sql, e);
        }
    }

    private String extractDDL(JSONObject payload, String syncerType)
    {
        if (payload.containsKey("ddl"))
        {
            String ddl = payload.getString("ddl");
            if (ddl == null || ddl.trim().isEmpty()) {
                return ddl;
            }
            
            // 根据数据库类型选择不同的转换方�?
            if ("POSTGRES".equalsIgnoreCase(syncerType)) {
                return SQLConvert.convertPGScript(ddl);
            } else {
                // 默认为MySQL
                return SQLConvert.convertMysqlScript(ddl);
            }
        }
        return null;
    }

    /**
     * 查询DuckDB表结构并更新缓存
     * @param syncerName 同步器名称
     * @param targetDatabase 目标数据库
     * @param targetTable 目标表
     * @param conn 数据库连接（DuckDB）
     */
    private void updateTargetTableColumnCache(String syncerName, String targetDatabase, String targetTable, Connection conn) {
        try {
            // 获取或创建缓存
            TableColumnTypeCache cache = targetTableColumnTypeCacheMap.get(syncerName);
            if (cache == null) {
                cache = new TableColumnTypeCache(syncerName);
                targetTableColumnTypeCacheMap.put(syncerName, cache);
            }
            
            // 查询DuckDB表结构
            // DuckDB系统表：information_schema.columns
            String sql = """
                    SELECT column_name, data_type
                    FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = ?
                    ORDER BY ordinal_position
                    """;
            
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, targetDatabase);
            pstmt.setString(2, targetTable);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                String columnName = rs.getString("column_name");
                String dataType = rs.getString("data_type");
                String cacheKey = targetDatabase + "." + targetTable + "." + columnName;
                cache.addTableColumn(cacheKey, dataType);
            }
            
            rs.close();
            pstmt.close();
            
            getLogger().debug("[DB-Syncer] 更新表结构缓存: {}.{} [Syncer: {}]", targetDatabase, targetTable, syncerName);
        } catch (Exception e) {
            getLogger().error("[DB-Syncer] 查询DuckDB表结构失败: {}.{} [Syncer: {}]", targetDatabase, targetTable, syncerName, e);
        }
    }

    /**
     * 清除目标表结构缓存
     * @param syncerName 同步器名称
     * @param targetDatabase 目标数据库
     * @param targetTable 目标表
     */
    private void clearTargetTableColumnCache(String syncerName, String targetDatabase, String targetTable) {
        TableColumnTypeCache cache = targetTableColumnTypeCacheMap.get(syncerName);
        if (cache != null) {
            String cacheKeyPrefix = targetDatabase + "." + targetTable + ".";
            // 移除所有以该表名为前缀的缓存项
            cache.tableColumns.keySet().removeIf(key -> key.startsWith(cacheKeyPrefix));
            getLogger().debug("[DB-Syncer] 清除表结构缓存: {}.{} [Syncer: {}]", targetDatabase, targetTable, syncerName);
        }
    }

    /**
     * 查询DuckDB表主键信息并更新缓存
     * @param syncerName 同步器名称
     * @param targetDatabase 目标数据库
     * @param targetTable 目标表
     * @param conn 数据库连接（DuckDB）
     */
    private void updateTargetTablePrimaryKeyCache(String syncerName, String targetDatabase, String targetTable, Connection conn) {
        try {
            // 获取或创建缓存
            TablePrimaryKeyCache cache = targetTablePrimaryKeyCacheMap.get(syncerName);
            if (cache == null) {
                cache = new TablePrimaryKeyCache(syncerName);
                targetTablePrimaryKeyCacheMap.put(syncerName, cache);
            }
            
            // 查询DuckDB表主键信息
            // DuckDB系统表：information_schema.table_constraints 和 information_schema.key_column_usage
            String sql = """
                    SELECT kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.table_schema = kcu.table_schema
                     AND tc.table_name = kcu.table_name
                    WHERE tc.constraint_type = 'PRIMARY KEY'
                      AND tc.table_schema = ?
                      AND tc.table_name = ?
                    ORDER BY kcu.ordinal_position
                    """;
            
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, targetDatabase);
            pstmt.setString(2, targetTable);
            ResultSet rs = pstmt.executeQuery();
            
            List<String> primaryKeyColumns = new ArrayList<>();
            while (rs.next()) {
                String columnName = rs.getString("column_name");
                primaryKeyColumns.add(columnName);
            }
            
            rs.close();
            pstmt.close();
            
            if (!primaryKeyColumns.isEmpty()) {
                String tableKey = targetDatabase + "." + targetTable;
                cache.addTablePrimaryKey(tableKey, primaryKeyColumns);
                getLogger().debug("[DB-Syncer] 更新表主键缓存: {} -> {} [Syncer: {}]",
                    tableKey, primaryKeyColumns, syncerName);
            } else {
                // 如果没有主键，也记录到缓存（空列表）
                String tableKey = targetDatabase + "." + targetTable;
                cache.addTablePrimaryKey(tableKey, primaryKeyColumns);
                getLogger().debug("[DB-Syncer] 表无主键: {}.{} [Syncer: {}]",
                    targetDatabase, targetTable, syncerName);
            }
        } catch (Exception e) {
            getLogger().error("[DB-Syncer] 查询DuckDB表主键失败: {}.{} [Syncer: {}]",
                targetDatabase, targetTable, syncerName, e);
        }
    }

    /**
     * 清除目标表主键缓存
     * @param syncerName 同步器名称
     * @param targetDatabase 目标数据库
     * @param targetTable 目标表
     */
    private void clearTargetTablePrimaryKeyCache(String syncerName, String targetDatabase, String targetTable) {
        TablePrimaryKeyCache cache = targetTablePrimaryKeyCacheMap.get(syncerName);
        if (cache != null) {
            String tableKey = targetDatabase + "." + targetTable;
            cache.removeTablePrimaryKey(tableKey);
            getLogger().debug("[DB-Syncer] 清除表主键缓存: {}.{} [Syncer: {}]",
                targetDatabase, targetTable, syncerName);
        }
    }

    /**
     * 检查并确保目标表主键在缓存中
     * @param syncerName 同步器名称
     * @param targetDatabase 目标数据库
     * @param targetTable 目标表
     * @param conn 数据库连接（DuckDB）
     * @return 如果缓存存在或成功查询则返回true，否则返回false
     */
    private boolean ensureTargetTablePrimaryKeyCache(String syncerName, String targetDatabase, String targetTable, Connection conn) {
        // 检查缓存是否存在
        TablePrimaryKeyCache cache = targetTablePrimaryKeyCacheMap.get(syncerName);
        if (cache != null) {
            String tableKey = targetDatabase + "." + targetTable;
            if (cache.getTablePrimaryKey(tableKey) != null) {
                return true;
            }
        }
        
        // 缓存不存在，查询并更新
        updateTargetTablePrimaryKeyCache(syncerName, targetDatabase, targetTable, conn);
        return true;
    }

    /**
     * 获取表的主键列列表
     * @param syncerName 同步器名称
     * @param targetDatabase 目标数据库
     * @param targetTable 目标表
     * @param conn 数据库连接（DuckDB）
     * @return 主键列列表，如果没有主键则返回空列表
     */
    private List<String> getTablePrimaryKeyColumns(String syncerName, String targetDatabase, String targetTable, Connection conn) {
        ensureTargetTablePrimaryKeyCache(syncerName, targetDatabase, targetTable, conn);
        TablePrimaryKeyCache cache = targetTablePrimaryKeyCacheMap.get(syncerName);
        if (cache != null) {
            String tableKey = targetDatabase + "." + targetTable;
            List<String> primaryKeys = cache.getTablePrimaryKey(tableKey);
            return primaryKeys != null ? primaryKeys : new ArrayList<>();
        }
        return new ArrayList<>();
    }

    /**
     * 检查并确保目标表结构在缓存中
     * @param syncerName 同步器名称
     * @param targetDatabase 目标数据库
     * @param targetTable 目标表
     * @param conn 数据库连接（DuckDB）
     * @return 如果缓存存在或成功查询则返回true，否则返回false
     */
    private boolean ensureTargetTableColumnCache(String syncerName, String targetDatabase, String targetTable, Connection conn) {
        // 检查缓存是否存在
        TableColumnTypeCache cache = targetTableColumnTypeCacheMap.get(syncerName);
        if (cache != null) {
            String cacheKey = targetDatabase + "." + targetTable + ".";
            // 检查是否有该表的任何列缓存（只需要检查是否存在以表名为前缀的键）
            boolean hasCache = cache.tableColumns.keySet().stream()
                .anyMatch(key -> key.startsWith(cacheKey));
            if (hasCache) {
                return true;
            }
        }
        
        // 缓存不存在，查询并更新
        updateTargetTableColumnCache(syncerName, targetDatabase, targetTable, conn);
        return true;
    }

    /**
     * 生成批量INSERT SQL语句
     */
    private String generateInsertSQL(String syncerName, List<JSONObject> payloads, Connection conn) {
        if (payloads == null || payloads.isEmpty()) {
            return null;
        }
        
        // 获取第一行的列名（假设所有行都有相同的列）
        JSONObject firstPayload = payloads.get(0);
        JSONObject source = firstPayload.getJSONObject("source");
        JSONObject firstAfter = firstPayload.getJSONObject("after");
        if (firstAfter == null || firstAfter.isEmpty()) {
            return null;
        }
        
        Set<String> columnNames = firstAfter.keySet();

        String sourceDatabase = source.getString("db");
        String sourceTable = source.getString("table");
        String targetDatabase = null;
        for (SyncerRuleInfo rule : syncManagerList.get(syncerName).getRules())
        {
            if (sourceDatabase.matches(rule.source_database))
            {
                targetDatabase = sourceDatabase.replace(rule.source_database, rule.target_database);
                break;
            }
        }
        String targetTable = null;
        for (SyncerRuleInfo rule : syncManagerList.get(syncerName).getRules())
        {
            if (sourceTable.matches(rule.source_table))
            {
                targetTable = sourceTable.replace(rule.source_table, rule.target_table);
                break;
            }
        }

        // 检查并确保目标表结构在缓存中
        if (targetDatabase != null && targetTable != null) {
            ensureTargetTableColumnCache(syncerName, targetDatabase, targetTable, conn);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(targetDatabase).append(".").append(targetTable).append(" (");
        
        // 添加列名
        boolean firstColumn = true;
        for (String columnName : columnNames) {
            if (!firstColumn) {
                sql.append(", ");
            }
            sql.append(columnName);
            firstColumn = false;
        }
        
        sql.append(") VALUES ");
        
        // 添加多行值
        boolean firstRow = true;
        for (JSONObject payload : payloads) {
            JSONObject after = payload.getJSONObject("after");
            if (after == null || after.isEmpty()) {
                continue;
            }
            
            if (!firstRow) {
                sql.append(", ");
            }
            sql.append("(");
            
            boolean firstValue = true;
            for (String columnName : columnNames) {
                if (!firstValue) {
                    sql.append(", ");
                }
                Object value = after.get(columnName);
                sql.append(formatValue(value));
                firstValue = false;
            }
            
            sql.append(")");
            firstRow = false;
        }
        return sql.toString();
    }

    /**
     * 使用DuckDB Appender进行批量插入
     */
    private void batchInsertWithAppender(String syncerName, List<JSONObject> payloads, Connection conn) throws SQLException {
        if (payloads == null || payloads.isEmpty()) {
            return;
        }
        
        // 获取第一行的列名（假设所有行都有相同的列）
        JSONObject firstPayload = payloads.get(0);
        JSONObject source = firstPayload.getJSONObject("source");
        JSONObject firstAfter = firstPayload.getJSONObject("after");
        if (firstAfter == null || firstAfter.isEmpty()) {
            return;
        }
        
        Set<String> columnNames = firstAfter.keySet();

        String sourceDatabase = source.getString("db");
        String sourceTable = source.getString("table");
        String targetDatabase = null;
        for (SyncerRuleInfo rule : syncManagerList.get(syncerName).getRules())
        {
            if (sourceDatabase.matches(rule.source_database))
            {
                targetDatabase = sourceDatabase.replace(rule.source_database, rule.target_database);
                break;
            }
        }
        String targetTable = null;
        for (SyncerRuleInfo rule : syncManagerList.get(syncerName).getRules())
        {
            if (sourceTable.matches(rule.source_table))
            {
                targetTable = sourceTable.replace(rule.source_table, rule.target_table);
                break;
            }
        }

        if (targetDatabase == null || targetTable == null) {
            getLogger().warn("[DB-Syncer] 无法确定目标表: {} [Syncer: {}]", sourceDatabase + "." + sourceTable, syncerName);
            return;
        }

        // 检查并确保目标表结构在缓存中
        ensureTargetTableColumnCache(syncerName, targetDatabase, targetTable, conn);

        // 创建Appender
        DuckDBAppender appender = null;
        try {
            appender = ((DuckDBConnection) conn).createAppender(targetDatabase, targetTable);
            
            for (JSONObject payload : payloads) {
                JSONObject after = payload.getJSONObject("after");
                if (after == null || after.isEmpty()) {
                    continue;
                }
                
                appender.beginRow();
                for (String columnName : columnNames) {
                    Object value = after.get(columnName);
                    // 从缓存获取列数据类型
                    String columnType = null;
                    TableColumnTypeCache columnCache = targetTableColumnTypeCacheMap.get(syncerName);
                    if (columnCache != null) {
                        columnType = columnCache.getColumnType(targetDatabase, targetTable, columnName);
                    }
                    if (columnType != null && columnType.equalsIgnoreCase("TIMESTAMP") && value instanceof Long) {
                        Instant instant = Instant.ofEpochMilli((Long)value);
                        value = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
                    }
                    else if (columnType != null && columnType.equalsIgnoreCase("TIMESTAMP") && value instanceof Integer) {
                        Instant instant = Instant.ofEpochMilli((Integer)value);
                        value = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
                    }
                    else if (columnType != null && columnType.equalsIgnoreCase("TIMESTAMP") && value instanceof String) {
                        Instant instant = Instant.parse((String)value);
                        value = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
                    }
                    else if (columnType != null && columnType.toUpperCase().startsWith("DECIMAL") && value instanceof String) {
                        value = new BigDecimal((String)value);
                    }
                    else if (columnType != null && columnType.equalsIgnoreCase("DOUBLE") && value instanceof String) {
                        value = Double.valueOf((String)value);
                    }
                    else if (columnType != null && columnType.equalsIgnoreCase("DOUBLE") && value instanceof BigDecimal) {
                        value = ((BigDecimal)value).doubleValue();
                    }
                    else if (columnType != null && columnType.equalsIgnoreCase("BOOLEAN") && value instanceof Integer) {
                        value = (Integer)value == 1;
                    }
                    else if (columnType != null && columnType.equalsIgnoreCase("BIGINT") && value instanceof Integer) {
                        value = Long.valueOf((Integer)value);
                    }
                    else if (columnType != null && columnType.equalsIgnoreCase("TINYINT") && value instanceof Integer) {
                        value = ((Integer)value).byteValue();
                    }
                    else if (columnType != null && columnType.equalsIgnoreCase("BLOB") && value instanceof String) {
                        value = Base64.getDecoder().decode((String)value);
                    }
                    try {
                        appendValue(appender, value);
                    }
                    catch (SQLException sqlException)
                    {
                        System.err.println("ColumnName = " + columnName + "  ColumnType=" + columnType);
                        System.err.println("Type = " +value.getClass().getTypeName());
                        System.err.println("PaylLoad= " + payload);
                        sqlException.printStackTrace();
                        System.exit(0);
                    }

                }
                appender.endRow();
            }
            
            // 关闭appender以提交数据
            appender.close();
            appender = null;
            
            getLogger().trace("[DB-Syncer] 使用Appender批量插入 {} 行到 {}.{} [Syncer: {}]",
                payloads.size(), targetDatabase, targetTable, syncerName);
        } catch (Exception e) {
            getLogger().error("[DB-Syncer] Appender插入失败: {}.{} [Syncer: {}]", targetDatabase, targetTable, syncerName, e);
            if (appender != null) {
                try {
                    appender.close();
                } catch (SQLException ex) {
                    // 忽略关闭异常
                }
            }
            throw e;
        }
    }

    /**
     * 将值追加到Appender，根据类型进行转换
     */
    private void appendValue(DuckDBAppender appender, Object value) throws SQLException {
        if (value == null) {
            appender.appendNull();
            return;
        }

        if (value instanceof String) {
            appender.append((String) value);
        } else if (value instanceof Boolean) {
            appender.append((Boolean) value);
        } else if (value instanceof Integer) {
            appender.append((Integer) value);
        } else if (value instanceof Byte) {
            appender.append((byte)value);
        } else if (value instanceof Long) {
            appender.append((Long) value);
        } else if (value instanceof Double) {
            appender.append((Double) value);
        } else if (value instanceof Float) {
            appender.append((Float) value);
        } else if (value instanceof BigInteger){
            appender.append((BigInteger) value);
        }else if (value instanceof BigDecimal) {
            appender.append((BigDecimal) value);
        } else if (value instanceof byte[]) {
            appender.append((byte[]) value);
        }
        else if (value instanceof LocalDateTime)
        {
            appender.append((LocalDateTime) value);
        }
        else
        {
            // 其他类型转换为字符串
            appender.append(value.toString());
        }
    }

    /**
     * 生成UPDATE SQL语句
     */
    private String generateUpdateSQL(String syncerName, JSONObject payload, Connection conn)
    {
        JSONObject before = payload.getJSONObject("before");
        JSONObject after = payload.getJSONObject("after");
        
        if (before == null || after == null) {
            return null;
        }

        JSONObject source = payload.getJSONObject("source");
        if (source == null) {
            return null;
        }
        
        String sourceDatabase = source.getString("db");
        String sourceTable = source.getString("table");
        String targetDatabase = null;
        for (SyncerRuleInfo rule : syncManagerList.get(syncerName).getRules())
        {
            if (sourceDatabase.matches(rule.source_database))
            {
                targetDatabase = sourceDatabase.replace(rule.source_database, rule.target_database);
                break;
            }
        }
        String targetTable = null;
        for (SyncerRuleInfo rule : syncManagerList.get(syncerName).getRules())
        {
            if (sourceTable.matches(rule.source_table))
            {
                targetTable = sourceTable.replace(rule.source_table, rule.target_table);
                break;
            }
        }

        if (targetDatabase == null || targetTable == null) {
            return null;
        }

        // 获取表的主键列
        List<String> primaryKeyColumns = getTablePrimaryKeyColumns(syncerName, targetDatabase, targetTable, conn);
        
        // 如果没有主键，则不处理更新操作
        if (primaryKeyColumns.isEmpty()) {
            getLogger().warn("[DB-Syncer] 表 {}.{} 无主键，跳过更新操作 [Syncer: {}]",
                targetDatabase, targetTable, syncerName);
            return null;
        }
        
        // 检查主键值是否都为null
        boolean allPrimaryKeyNull = true;
        for (String pkColumn : primaryKeyColumns) {
            if (before.get(pkColumn) != null) {
                allPrimaryKeyNull = false;
                break;
            }
        }
        
        if (allPrimaryKeyNull) {
            getLogger().warn("[DB-Syncer] 表 {}.{} 的所有主键值都为null，跳过更新操作 [Syncer: {}]",
                targetDatabase, targetTable, syncerName);
            return null;
        }
        
        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(targetDatabase).append(".").append(targetTable).append(" SET ");
        
        // SET子句
        StringBuilder setClause = new StringBuilder();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (!setClause.isEmpty()) {
                setClause.append(", ");
            }
            setClause.append(entry.getKey()).append(" = ").append(formatValue(entry.getValue()));
        }
        
        // WHERE子句 - 使用主键列
        StringBuilder whereClause = new StringBuilder();
        for (String pkColumn : primaryKeyColumns) {
            if (!whereClause.isEmpty()) {
                whereClause.append(" AND ");
            }
            Object value = before.get(pkColumn);
            if (value == null) {
                // 主键值为null，无法构建有效的WHERE条件
                getLogger().warn("[DB-Syncer] 主键列 {} 的值为null，跳过更新操作 [Syncer: {}, Table: {}.{}]",
                    pkColumn, syncerName, targetDatabase, targetTable);
                return null;
            }
            whereClause.append(pkColumn).append(" = ").append(formatValue(value));
        }
        
        sql.append(setClause).append(" WHERE ").append(whereClause);
        return sql.toString();
    }

    /**
     * 生成DELETE SQL语句
     */
    private String generateDeleteSQL(String syncerName, JSONObject payload, Connection conn) {
        JSONObject before = payload.getJSONObject("before");
        if (before == null || before.isEmpty()) {
            return null;
        }

        JSONObject source = payload.getJSONObject("source");
        if (source == null) {
            return null;
        }
        
        String sourceDatabase = source.getString("db");
        String sourceTable = source.getString("table");
        String targetDatabase = null;
        for (SyncerRuleInfo rule : syncManagerList.get(syncerName).getRules())
        {
            if (sourceDatabase.matches(rule.source_database))
            {
                targetDatabase = sourceDatabase.replace(rule.source_database, rule.target_database);
                break;
            }
        }
        String targetTable = null;
        for (SyncerRuleInfo rule : syncManagerList.get(syncerName).getRules())
        {
            if (sourceTable.matches(rule.source_table))
            {
                targetTable = sourceTable.replace(rule.source_table, rule.target_table);
                break;
            }
        }

        if (targetDatabase == null || targetTable == null) {
            return null;
        }

        // 获取表的主键列
        List<String> primaryKeyColumns = getTablePrimaryKeyColumns(syncerName, targetDatabase, targetTable, conn);
        
        // 如果没有主键，则不处理删除操作
        if (primaryKeyColumns.isEmpty()) {
            getLogger().warn("[DB-Syncer] 表 {}.{} 无主键，跳过删除操作 [Syncer: {}]",
                targetDatabase, targetTable, syncerName);
            return null;
        }
        
        // 检查主键值是否都为null
        boolean allPrimaryKeyNull = true;
        for (String pkColumn : primaryKeyColumns) {
            if (before.get(pkColumn) != null) {
                allPrimaryKeyNull = false;
                break;
            }
        }
        
        if (allPrimaryKeyNull) {
            getLogger().warn("[DB-Syncer] 表 {}.{} 的所有主键值都为null，跳过删除操作 [Syncer: {}]",
                targetDatabase, targetTable, syncerName);
            return null;
        }
        
        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM ").append(targetDatabase).append(".").append(targetTable).append(" WHERE ");
        
        // WHERE子句 - 使用主键列
        StringBuilder whereClause = new StringBuilder();
        for (String pkColumn : primaryKeyColumns) {
            if (!whereClause.isEmpty()) {
                whereClause.append(" AND ");
            }
            Object value = before.get(pkColumn);
            if (value == null) {
                // 主键值为null，无法构建有效的WHERE条件
                getLogger().warn("[DB-Syncer] 主键列 {} 的值为null，跳过删除操作 [Syncer: {}, Table: {}.{}]",
                    pkColumn, syncerName, targetDatabase, targetTable);
                return null;
            }
            whereClause.append(pkColumn).append(" = ").append(formatValue(value));
        }
        
        sql.append(whereClause);
        return sql.toString();
    }

    /**
     * 格式化值，用于SQL语句
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        
        if (value instanceof String str) {
            // 转义单引�?
            str = str.replace("'", "''");
            return "'" + str + "'";
        }
        
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "TRUE" : "FALSE";
        }
        
        if (value instanceof Number) {
            return value.toString();
        }
        
        // 其他类型转换为字符串
        return "'" + value.toString().replace("'", "''") + "'";
    }

    @Override
    protected void onStart() {
        Connection dbConn = null;

        Logger logger = this.getLogger();

        try {
            dbConn = this.getDbConnection();

            logger.info("[DB-Syncer] Starting ...");

            // 在数据库中创建同步点的配�?
            String sql = """
                    CREATE SCHEMA IF NOT EXISTS sysaux;
                    
                    CREATE TABLE IF NOT EXISTS sysaux.v$dbsyncer
                    (
                        syncer_type     VARCHAR,
                        syncer_name     VARCHAR PRIMARY KEY,
                        syncer_source   VARCHAR,
                        syncer_offset   VARCHAR,
                        snapshot_mode   VARCHAR,
                        auto            BOOLEAN,
                        enabled         BOOLEAN,
                        status          VARCHAR,
                        source_database   VARCHAR,
                        target_database   VARCHAR,
                        source_table      VARCHAR,
                        target_table      VARCHAR,
                        create_time     TIMESTAMP,
                        update_time     TIMESTAMP
                    );
                    COMMENT ON TABLE sysaux.v$dbsyncer IS '数据库同步器配置';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.syncer_type IS '同步类型(MYSQL,POSTGRES)';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.syncer_name IS '同步名称';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.syncer_source IS '同步数据源配置';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.syncer_offset IS '上次同步偏移信息';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.snapshot_mode IS '快照模式(NO_DATA,INCR,INITIAL)';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.auto IS '是否自动启动';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.enabled IS '是否启用';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.status IS '同步器状态 CREATED, SNAPSHOTTING, STREAMING, FAILED';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.source_database IS '源数据库';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.target_database IS '目标数据库';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.source_table IS '源数据表';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.target_table IS '目标数据表';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.create_time IS '创建时间';
                    COMMENT ON COLUMN sysaux.v$dbsyncer.update_time IS '更新时间';
                    
                    """;
            Statement stmt = dbConn.createStatement();
            stmt.execute(sql);
            dbConn.commit();

            // 启动并注册外部服务
            Javalin    app = this.getJavalinApp();

            // 状态查询
            app.unsafe.routes.get("/plugin/syncer/status", this::processSyncerStatus);
            app.unsafe.routes.get("/plugin/syncer/{syncer_name}/status", this::processSyncerStatus);

            // 启动
            app.unsafe.routes.post("/plugin/syncer/start", this::processSyncerStart);
            app.unsafe.routes.post("/plugin/syncer/{syncer_name}/start", this::processSyncerStart);

            // 停止
            app.unsafe.routes.post("/plugin/syncer/stop", this::processSyncerStop);
            app.unsafe.routes.post("/plugin/syncer/{syncer_name}/stop", this::processSyncerStop);

            // 规则查看
            app.unsafe.routes.post("/plugin/syncer/list", this::processSyncerList);
            app.unsafe.routes.post("/plugin/syncer/{syncer_name}/list", this::processSyncerRuleList);

            // 创建、删除、设�?
            app.unsafe.routes.post("/plugin/syncer/create", this::processSyncerCreate);
            app.unsafe.routes.post("/plugin/syncer/{syncer_name}/delete", this::processSyncerDelete);
            app.unsafe.routes.post("/plugin/syncer/{syncer_name}/set", this::processSyncerSet);

            // 从数据库中检索需要自动同步的信息，随后依次调用startEngine
            sql = """
                    SELECT syncer_name, syncer_type, syncer_source as rule_source, syncer_offset, auto,
                           syncer_name as rule_name, source_database, target_database, source_table, target_table, status
                    FROM sysaux.v$dbsyncer
                    WHERE auto = true
                    AND status IN ('CREATED')
                    AND enabled = true
                    ORDER BY syncer_name
                """;
            stmt = dbConn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);

            // 按syncer分组收集规则
            Map<String, StartEngineRequest> syncerMap = new HashMap<>();
            Map<String, List<SyncerRuleInfo>> ruleMap = new HashMap<>();

            while (rs.next())
            {
                String syncerName = rs.getString("syncer_name");
                String ruleName = rs.getString("rule_name");

                // 创建或获取syncer请求
                if (!syncerMap.containsKey(syncerName)) {
                    StartEngineRequest syncerRequest = new StartEngineRequest();
                    syncerRequest.syncer_name = syncerName;
                    syncerRequest.syncer_type = rs.getString("syncer_type");
                    syncerRequest.rule_source = rs.getString("rule_source");
                    syncerRequest.syncer_offset = rs.getString("syncer_offset");
                    syncerRequest.auto = rs.getBoolean("auto");
                    // 使用第一个规则的rule_name作为主要标识（向后兼容）
                    syncerRequest.rule_name = ruleName;
                    syncerMap.put(syncerName, syncerRequest);
                    ruleMap.put(syncerName, new ArrayList<>());
                }

                // 添加规则信息
                SyncerRuleInfo ruleInfo = new SyncerRuleInfo();
                ruleInfo.rule_name = ruleName;
                ruleInfo.source_database = rs.getString("source_database");
                ruleInfo.target_database = rs.getString("target_database");
                ruleInfo.source_table = rs.getString("source_table");
                ruleInfo.target_table = rs.getString("target_table");
                ruleInfo.status = rs.getString("status");
                ruleInfo.enabled = true;

                ruleMap.get(syncerName).add(ruleInfo);
            }
            rs.close();
            stmt.close();

            // 为每个syncer启动引擎
            for (String syncerName : syncerMap.keySet()) {
                StartEngineRequest syncerRequest = syncerMap.get(syncerName);
                syncerRequest.rules = ruleMap.get(syncerName);
                // 启动同步
                startEngine(syncerRequest);
            }

            logger.info("[DB-Syncer] Started successful.");
        }
        catch (Exception ex)
        {
            logger.error("[DB-Syncer] Start failed. ", ex);
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 解析请求中的连接字符�?
    private Properties parseConnectionConfig(String ruleSource, String syncerType) {
        // 验证syncerType
        if (!"MYSQL".equalsIgnoreCase(syncerType) && !"POSTGRES".equalsIgnoreCase(syncerType)) {
            throw new IllegalArgumentException("Unsupported syncer type: " + syncerType + ". Must be MYSQL or POSTGRES.");
        }
        Properties connectionProps = new Properties();
        try {
            // 尝试解析为JSON
            JSONObject json = JSON.parseObject(ruleSource);

            if ("MYSQL".equalsIgnoreCase(syncerType)) {
                connectionProps.put("database.hostname", json.getString("hostname"));
                connectionProps.put("database.port", json.getString("port"));
                connectionProps.put("database.user", json.getString("user"));
                connectionProps.put("database.password", json.getString("password"));
                connectionProps.put("database.server.id", json.getString("serverId"));
                connectionProps.put("database.include.list", json.getString("database"));
            } else if ("POSTGRES".equalsIgnoreCase(syncerType)) {
                connectionProps.put("database.hostname", json.getString("hostname"));
                connectionProps.put("database.port", json.getString("port"));
                connectionProps.put("database.user", json.getString("user"));
                connectionProps.put("database.password", json.getString("password"));
                connectionProps.put("database.dbname", json.getString("database"));
                // PostgreSQL 特定参数
                connectionProps.put("plugin.name", "pgoutput");
                connectionProps.put("slot.name", json.getString("slotName") != null ? json.getString("slotName") : "debezium_slot");
                connectionProps.put("publication.name", json.getString("publicationName") != null ? json.getString("publicationName") : "debezium_publication");
                // 架构包含列表
                if (json.containsKey("schema.include.list")) {
                    connectionProps.put("schema.include.list", json.getString("schema.include.list"));
                }
            }

            // 可选参数
            if (json.containsKey("topic.prefix")) {
                connectionProps.put("topic.prefix", json.getString("topic.prefix"));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported syncer type: " + syncerType + ". Must be MYSQL or POSTGRES.", e);
        }
        return connectionProps;
    }

    /**
     * 验证配置有效性
     * @param request 启动请求
     * @throws IllegalArgumentException 配置无效
     */
    private void validateConfiguration(StartEngineRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("启动请求不能为空");
        }
        
        // 验证必填字段
        if (request.rule_name == null || request.rule_name.trim().isEmpty()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        
        if (request.rule_source == null || request.rule_source.trim().isEmpty()) {
            throw new IllegalArgumentException("规则源配置不能为空");
        }
        
        // 验证规则列表
        if (request.rules == null || request.rules.isEmpty()) {
            throw new IllegalArgumentException("规则列表不能为空");
        }
        
        // 验证每个规则
        for (SyncerRuleInfo rule : request.rules) {
            if (rule.rule_name == null || rule.rule_name.trim().isEmpty()) {
                throw new IllegalArgumentException("规则名称不能为空");
            }
            
            // 源模式可以为空，表示匹配所有表（如果为null则设为空字符串）
            if (rule.source_table == null) {
                rule.source_table = "";
            }
            
            // 目标模式不能为空
            if (rule.target_table == null || rule.target_table.trim().isEmpty()) {
                throw new IllegalArgumentException("规则 " + rule.rule_name + " 的目标模式不能为空");
            }
        }
        
        // 验证数据库连接（可以延迟验证，这里只验证配置）
        // 验证数据库类型
        String syncerType = request.syncer_type != null ? request.syncer_type.toUpperCase() : "NULL";
        try {
            Properties dbConfig = parseConnectionConfig(request.rule_source, syncerType);
            
            // 验证必要参数
            if (syncerType.equals("MYSQL")) {
                if (dbConfig.getProperty("database.hostname") == null) {
                    throw new IllegalArgumentException("MySQL 配置缺少 hostname");
                }
                if (dbConfig.getProperty("database.port") == null) {
                    throw new IllegalArgumentException("MySQL 配置缺少 port");
                }
                if (dbConfig.getProperty("database.user") == null) {
                    throw new IllegalArgumentException("MySQL 配置缺少 user");
                }
                // password 可以为空，某些数据库允许无密码
            } else if (syncerType.equals("POSTGRES")) {
                if (dbConfig.getProperty("database.hostname") == null) {
                    throw new IllegalArgumentException("PostgreSQL 配置缺少 hostname");
                }
                if (dbConfig.getProperty("database.port") == null) {
                    throw new IllegalArgumentException("PostgreSQL 配置缺少 port");
                }
                if (dbConfig.getProperty("database.user") == null) {
                    throw new IllegalArgumentException("PostgreSQL 配置缺少 user");
                }
                if (dbConfig.getProperty("database.dbname") == null) {
                    throw new IllegalArgumentException("PostgreSQL 配置缺少 database name");
                }
            }
            else
            {
                throw new IllegalArgumentException("[DB-Syncer] syncerType is not supportted: {" + syncerType + "}.");
            }
            
            getLogger().info("[DB-Syncer] 配置验证通过: {}", request.rule_name);
        } catch (Exception e) {
            throw new IllegalArgumentException("数据库配置无效: " + e.getMessage(), e);
        }
    }

    private void startEngine(StartEngineRequest startEngineRequest) throws Exception
    {
        Connection dbConn = null;
        try {
            dbConn = this.getDbConnection();

            // 验证配置有效性
            validateConfiguration(startEngineRequest);

            // 使用syncer_name作为关键字
            String syncerKey = startEngineRequest.syncer_name != null ?
                    startEngineRequest.syncer_name : startEngineRequest.rule_name;

            SyncEngineManager syncEngineManager;

            if (syncManagerList.containsKey(syncerKey)) {
                syncEngineManager = syncManagerList.get(syncerKey);
                // 更新规则列表
                syncEngineManager.setRules(startEngineRequest.rules);
            } else {
                // 初始化Disruptor
                Disruptor<DebeziumEvent> disruptor = new Disruptor<>(
                        DebeziumEvent::new, 1024, DaemonThreadFactory.INSTANCE);
                // 处理BinLog消息
                disruptor.handleEventsWith(this::processEvent);
                // 启动队列服务
                disruptor.start();

                // 初始化引擎管理器
                syncEngineManager = new SyncEngineManager(disruptor.getRingBuffer());
                syncEngineManager.setDisruptor(disruptor);
                syncEngineManager.setRules(startEngineRequest.rules);

                // 初始化同步引擎的数据库链�?
                syncEngineManager.setDbConnection(this.getDbConnection());

                // 添加到同步引擎的列表
                syncManagerList.put(syncerKey, syncEngineManager);
            }

            // 启动引擎
            Properties props = new Properties();
            // 写入debezium必须的参�?
            // schemas.enable=false: 禁用schema信息，减少payload大小
            props.putIfAbsent("schemas.enable", "false");
            // provide.transaction.metadata=true: 必须开启，否则收不�?BEGIN/END 事务元数�?
            props.putIfAbsent("provide.transaction.metadata", "true");
            // decimal.handling.mode=string: 将decimal类型作为字符串处理，避免精度丢失
            props.putIfAbsent("decimal.handling.mode", "string");

            // 设置位点存储为文件
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
            Files.createDirectories(tempDir);
            Path offsetFile = tempDir.resolve(startEngineRequest.rule_name + ".offset.json");

            // 检查点处理：程序启动时读取数据库的信息，并把数据库的状态生成检查点文件
            // 如果数据库信息为空，则开始全量同步
            boolean hasValidOffset;
            String syncerName = startEngineRequest.syncer_name != null ?
                    startEngineRequest.syncer_name : startEngineRequest.rule_name;

            // 使用新的方法从数据库加载检查点并生成检查点文件
            hasValidOffset = loadCheckpointAndGenerateFile(syncerName, offsetFile.toString());

            // 如果检查点无效或为空，确保文件是空的（表示需要全量同步）
            if (!hasValidOffset) {
                try {
                    Files.writeString(offsetFile, "{}");
                    System.out.println("[DB-Syncer] 检查点无效，创建空的检查点文件，开始全量同步 " + syncerName);
                } catch (Exception e) {
                    System.err.println("[DB-Syncer] 创建空的检查点文件失败: " + e.getMessage());
                }
            }

            props.putIfAbsent("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
            // offset.storage: 使用文件存储偏移
            props.putIfAbsent("offset.storage.file.filename", offsetFile.toString());

            // 设置 Schema 历史记录为
            // schema.history.internal: 使用内存存储schema历史，重启后需要重新快照
            props.putIfAbsent("schema.history.internal", "io.debezium.relational.history.MemorySchemaHistory");

            // 根据数据库类型设置不同的连接器和参数
            String syncerType = startEngineRequest.syncer_type != null ?
                    startEngineRequest.syncer_type.toUpperCase() : "MYSQL";

            // 设置公共参数
            props.put("name", startEngineRequest.rule_name);

            // 数据库特定配�?
            Properties dbConfig = parseConnectionConfig(startEngineRequest.rule_source, syncerType);

            // 在源数据库中创建debezium_signal�?
            createDebeziumSignalTable(dbConfig, syncerType, startEngineRequest.snapshot_mode,
                                     startEngineRequest.syncer_name != null ? startEngineRequest.syncer_name : startEngineRequest.rule_name,
                                     startEngineRequest.rules);

            if ("MYSQL".equals(syncerType)) {
                props.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
                props.putIfAbsent("database.history", "io.debezium.relational.history.MemoryDatabaseHistory");

                // MySQL 特定参数
                props.put("database.hostname", dbConfig.getProperty("database.hostname"));
                props.put("database.port", dbConfig.getProperty("database.port"));
                props.put("database.user", dbConfig.getProperty("database.user"));
                props.put("database.password", dbConfig.getProperty("database.password"));
                props.put("database.server.id", dbConfig.getProperty("database.server.id"));
                props.put("database.include.list", dbConfig.getProperty("database.include.list"));

                // 根据规则计算并设置过滤参数
                Properties filterParams = calculateFilterParameters(startEngineRequest.rules, syncerType, dbConfig);
                if (filterParams.containsKey("database.include.list")) {
                    // 如果规则中指定了数据库过滤，覆盖原有的database.include.list
                    props.put("database.include.list", filterParams.getProperty("database.include.list"));
                }
                if (filterParams.containsKey("table.include.list")) {
                    props.put("table.include.list", filterParams.getProperty("table.include.list"));
                }
                if (filterParams.containsKey("table.exclude.list")) {
                    props.put("table.exclude.list", filterParams.getProperty("table.exclude.list"));
                }

                // 根据snapshot_mode和检查点状态设置snapshot.mode
                String snapshotMode = determineSnapshotMode(startEngineRequest.snapshot_mode, hasValidOffset);
                props.put("snapshot.mode", snapshotMode);
                
                // 根据snapshot_mode配置增量快照参数
                configureIncrementalSnapshot(props, startEngineRequest.snapshot_mode, dbConfig);
            } else if ("POSTGRES".equals(syncerType)) {
                props.put("connector.class", "io.debezium.connector.postgresql.PostgresConnector");

                // PostgreSQL 特定参数
                props.put("database.hostname", dbConfig.getProperty("database.hostname"));
                props.put("database.port", dbConfig.getProperty("database.port"));
                props.put("database.user", dbConfig.getProperty("database.user"));
                props.put("database.password", dbConfig.getProperty("database.password"));
                props.put("database.dbname", dbConfig.getProperty("database.dbname"));
                props.put("plugin.name", dbConfig.getProperty("plugin.name"));
                props.put("slot.name", dbConfig.getProperty("slot.name"));
                props.put("publication.name", dbConfig.getProperty("publication.name"));

                if (dbConfig.containsKey("schema.include.list")) {
                    props.put("schema.include.list", dbConfig.getProperty("schema.include.list"));
                }

                // 根据规则计算并设置过滤参数
                Properties filterParams = calculateFilterParameters(startEngineRequest.rules, syncerType, dbConfig);
                if (filterParams.containsKey("schema.include.list")) {
                    // 如果规则中指定了schema过滤，覆盖原有的schema.include.list
                    props.put("schema.include.list", filterParams.getProperty("schema.include.list"));
                }
                if (filterParams.containsKey("table.include.list")) {
                    props.put("table.include.list", filterParams.getProperty("table.include.list"));
                }
                if (filterParams.containsKey("table.exclude.list")) {
                    props.put("table.exclude.list", filterParams.getProperty("table.exclude.list"));
                }

                // PostgreSQL 复制槽相关
                props.put("slot.drop.on.stop", "false");
                props.put("publication.autocreate.mode", "filtered");

                // 根据snapshot_mode和检查点状态设置snapshot.mode
                String snapshotMode = determineSnapshotMode(startEngineRequest.snapshot_mode, hasValidOffset);
                props.put("snapshot.mode", snapshotMode);
                
                // 根据snapshot_mode配置增量快照参数
                configureIncrementalSnapshot(props, startEngineRequest.snapshot_mode, dbConfig);

            } else {
                throw new IllegalArgumentException("Unsupported syncer type: " + syncerType);
            }

            // 启用通知机制，用于快照完成时触发状态更�?
            props.putIfAbsent("notification.enabled.channels", "sink");
            
            // 设置通知topic名称（必须提供，否则Debezium会报错）
            String topicPrefix = dbConfig.containsKey("topic.prefix")
                ? dbConfig.getProperty("topic.prefix")
                : startEngineRequest.rule_name;
            props.putIfAbsent("notification.sink.topic.name", topicPrefix + ".notifications");

            // 公共可选参�?
            if (dbConfig.containsKey("topic.prefix")) {
                props.put("topic.prefix", dbConfig.getProperty("topic.prefix"));
            } else {
                props.putIfAbsent("topic.prefix", startEngineRequest.rule_name);
            }

            // 启动引擎
            syncEngineManager.setStartEngineRequest(startEngineRequest);
            syncEngineManager.setOffsetFilePath(offsetFile.toString());
            syncEngineManager.startEngine(props);

            // 标记数据库状态
            String sql = """
                        UPDATE  sysaux.v$dbsyncer
                        SET     status = ?,
                                update_time = CURRENT_TIMESTAMP
                        WHERE   syncer_name = ?
                    """;
            PreparedStatement pStmt = dbConn.prepareStatement(sql);
            pStmt.setString(1, "SNAPSHOTTING");
            syncerName = startEngineRequest.syncer_name != null ?
                    startEngineRequest.syncer_name : startEngineRequest.rule_name;
            pStmt.setString(2, syncerName);
            pStmt.executeUpdate();
            dbConn.commit();
            pStmt.close();
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    /**
     * 根据snapshot_mode、检查点状态和数据库类型确定snapshot.mode
     * @param snapshotMode snapshot_mode字段的�?(NO_DATA, INCR, INITIAL)
     * @param hasValidOffset 是否有有效的检查点
     * @return Debezium snapshot.mode
     * */
    private String determineSnapshotMode(String snapshotMode, boolean hasValidOffset) {
        if (snapshotMode == null || snapshotMode.isEmpty()) {
            snapshotMode = "INITIAL"; // 默认�?
        }
        
        snapshotMode = snapshotMode.toUpperCase();
        
        // 根据snapshot_mode决定快照模式
        return switch (snapshotMode) {
            case "NO_DATA" ->
                // NO_DATA模式：不进行数据快照，只同步schema
                    "no_data";
            case "INCR" ->
                // INCR模式：增量同步，使用no_data模式并依赖信号表触发增量快照
                    "no_data";
            case "INITIAL" ->
                // INITIAL模式：进行全量快照（忽略检查点�?
                    "initial";
            default -> {
                // 未知类型，使用默认逻辑
                System.err.println("[DB-Syncer] 未知的snapshot_mode: " + snapshotMode + "，使用默认逻辑");
                yield hasValidOffset ? "never" : "initial";
            }
        };
    }

    /**
     * 配置增量快照参数
     * @param props Debezium配置属
     * @param snapshotMode snapshot_mode字段的�?(NO_DATA, INCR, INITIAL)
     * @param dbConfig 数据库连接配置
     */
    private void configureIncrementalSnapshot(Properties props, String snapshotMode, Properties dbConfig) {
        if (snapshotMode == null || snapshotMode.isEmpty()) {
            snapshotMode = "INITIAL"; // 默认�?
        }
        
        snapshotMode = snapshotMode.toUpperCase();
        
        // 只有在INCR模式下才配置增量快照参数
        if ("INCR".equals(snapshotMode)) {
            // 关键配置：指定信号表
            // 从dbConfig获取数据库名称，如果没有则使用默认�?
            String databaseName = dbConfig.getProperty("database.name", "your_db_name");
            props.put("signal.data.collection", databaseName + ".debezium_signal");
            
            // 设置并行表数�?
            props.put("snapshot.max.threads", "5");
            
            System.out.println("[DB-Syncer] 配置增量快照参数: signal.data.collection=" +
                databaseName + ".debezium_signal, snapshot.max.threads=5");
        } else {
            // NO_DATA或INITIAL模式：不配置增量快照参数
            System.out.println("[DB-Syncer] snapshot_mode=" + snapshotMode + "，不配置增量快照参数");
        }
    }

    /**
     * 在源数据库中创建debezium_signal表，并根据snapshot_mode插入信号记录
     * @param dbConfig 数据库连接配�?
     * @param syncerType 数据库类�?(MYSQL, POSTGRES)
     * @param snapshotMode 快照模式 (NO_DATA, INCR, INITIAL)
     * @param syncerName 同步器名�?
     * @param rules 规则列表
     */
    private void createDebeziumSignalTable(Properties dbConfig, String syncerType, String snapshotMode,
                                          String syncerName,
                                           List<SyncerRuleInfo> rules) {
        if (snapshotMode == null || snapshotMode.isEmpty()) {
            snapshotMode = "INITIAL";
        }
        snapshotMode = snapshotMode.toUpperCase();
        
        Connection sourceConn = null;
        Statement stmt = null;
        try {
            // 根据数据库类型构建连接URL
            String url;
            String driverClass;
            if ("MYSQL".equalsIgnoreCase(syncerType)) {
                String hostname = dbConfig.getProperty("database.hostname");
                String port = dbConfig.getProperty("database.port");
                String database = dbConfig.getProperty("database.include.list");
                url = "jdbc:mysql://" + hostname + ":" + port + "/" + database;
                driverClass = "com.mysql.cj.jdbc.Driver";
            } else if ("POSTGRES".equalsIgnoreCase(syncerType)) {
                String hostname = dbConfig.getProperty("database.hostname");
                String port = dbConfig.getProperty("database.port");
                String database = dbConfig.getProperty("database.dbname");
                url = "jdbc:postgresql://" + hostname + ":" + port + "/" + database;
                driverClass = "org.postgresql.Driver";
            } else {
                System.err.println("[DB-Syncer] 不支持的数据库类型 " + syncerType);
                return;
            }
            
            String user = dbConfig.getProperty("database.user");
            String password = dbConfig.getProperty("database.password");
            
            // 加载驱动并建立连接
            Class.forName(driverClass);
            sourceConn = DriverManager.getConnection(url, user, password);
            sourceConn.setAutoCommit(true);
            
            // 创建debezium_signal表
            String createTableSQL = """
                CREATE TABLE IF NOT EXISTS debezium_signal
                (
                    id VARCHAR(42) PRIMARY KEY,
                    type VARCHAR(32) NOT NULL,
                    data VARCHAR(2048) NULL
                )
                """;
            stmt = sourceConn.createStatement();
            stmt.execute(createTableSQL);
            System.out.println("[DB-Syncer] Create debezium signal table successful.");
            
            // 根据snapshot_mode决定是否插入信号记录
            if ("INCR".equals(snapshotMode)) {
                // 收集所有需要同步的表，并维护信号信息
                List<String> tables;
                // 查询所有源数据库上的所有表名，并按照匹配规则进行过滤
                if (rules != null && !rules.isEmpty()) {
                    tables = querySourceTables(sourceConn, syncerType, rules);
                }
                else {
                    // 如果没有规则，获取所有表
                    tables = getAllTables(sourceConn, syncerType);
                }
                
                // 如果没有表，则使用默认
                if (tables.isEmpty()) {
                    System.out.println("[DB-Syncer] 警告：没有找到需要同步的表，信号表数据为空");
                } else {
                    System.out.println("[DB-Syncer] 找到 " + tables.size() + " 个需要同步的表");
                }
                
                // 构建JSON数据
                String dataJson = "{\"data-collections\": " + JSON.toJSONString(tables) + "}";
                
                // 插入或更新信号记�?
                String insertSQL = """
                    INSERT INTO debezium_signal (id, type, data)
                    VALUES (?, 'execute-snapshot', ?)
                    ON CONFLICT (id) DO UPDATE SET type = 'execute-snapshot', data = ?
                    """;
                PreparedStatement pstmt = sourceConn.prepareStatement(insertSQL);
                pstmt.setString(1, syncerName);
                pstmt.setString(2, dataJson);
                pstmt.setString(3, dataJson);
                int rows = pstmt.executeUpdate();
                System.out.println("[DB-Syncer] 插入信号表记录，snapshot_mode=INCR，影响行数 " + rows);
                pstmt.close();
            } else {
                System.out.println("[DB-Syncer] snapshot_mode=" + snapshotMode + "，不插入信号表记录");
            }
            
        } catch (Exception e) {
            System.err.println("[DB-Syncer] 创建debezium_signal表失败 " + e.getMessage());
            throw new RuntimeException("初始化debezium_signal表失败", e);
        } finally {
            try {
                if (stmt != null) stmt.close();
                if (sourceConn != null) sourceConn.close();
            } catch (SQLException e) {
                System.err.println("[DB-Syncer] 关闭连接失败: " + e.getMessage());
            }
        }
    }

    /**
     * 获取源数据库中的所有表信息
     * @param sourceConn 源数据库连接
     * @param syncerType 数据库类型
     * @return 表名列表
     */
    private List<String> getAllTables(Connection sourceConn, String syncerType) {
        List<String> tables = new ArrayList<>();
        ResultSet rs = null;
        
        try {
            DatabaseMetaData metaData = sourceConn.getMetaData();
            
            if ("MYSQL".equalsIgnoreCase(syncerType)) {
                // MySQL: 获取当前数据库的所有表
                String catalog = sourceConn.getCatalog();
                rs = metaData.getTables(catalog, null, "%", new String[]{"TABLE"});
                
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String schemaName = rs.getString("TABLE_SCHEM");
                    // 格式化为 数据库名.表名 模式.表名
                    if (schemaName != null && !schemaName.isEmpty()) {
                        tables.add(schemaName + "." + tableName);
                    } else {
                        tables.add(tableName);
                    }
                }
            } else if ("POSTGRES".equalsIgnoreCase(syncerType)) {
                // PostgreSQL: 获取所有表
                rs = metaData.getTables(null, "public", "%", new String[]{"TABLE"});
                
                while (rs.next()) {
                    String schemaName = rs.getString("TABLE_SCHEM");
                    String tableName = rs.getString("TABLE_NAME");
                    // PostgreSQL 使用 模式�?表名 格式
                    tables.add(schemaName + "." + tableName);
                }
            }
            
            System.out.println("[DB-Syncer] 从源数据库获取到 " + tables.size() + " 个表");
        } catch (SQLException e) {
            System.err.println("[DB-Syncer] 查询源数据库表失�? " + e.getMessage());
        } finally {
            try {
                if (rs != null) rs.close();
            } catch (SQLException e) {
                System.err.println("[DB-Syncer] 关闭ResultSet失败: " + e.getMessage());
            }
        }
        
        return tables;
    }

    /**
     * 根据规则查询源数据库中的表
     * @param sourceConn 源数据库连接
     * @param syncerType 数据库类型
     * @param rules 规则列表
     * @return 匹配规则的表名列表
     */
    private List<String> querySourceTables(Connection sourceConn, String syncerType, List<SyncerRuleInfo> rules) {
        // 首先获取所有表
        List<String> allTables = getAllTables(sourceConn, syncerType);
        List<String> matchedTables = new ArrayList<>();
        
        if (rules == null || rules.isEmpty()) {
            return allTables; // 如果没有规则，返回所有表
        }
        
        // 对每个表检查是否匹配任何规则
        for (String table : allTables) {
            boolean matched = false;
            
            for (SyncerRuleInfo rule : rules) {
                // 检查数据库匹配
                String sourceDatabase = rule.source_database;
                String sourceTablePattern = rule.source_table;
                
                // 如果源数据库为空或为"*"，则匹配所有数据库
                boolean databaseMatches = (sourceDatabase == null || sourceDatabase.isEmpty() || sourceDatabase.equals("*"));
                
                // 如果源表模式为空或为"*"，则匹配所有表
                boolean tableMatches = (sourceTablePattern == null || sourceTablePattern.isEmpty() || sourceTablePattern.equals("*"));
                
                // 如果数据库和表都匹配通配符，则直接匹配
                if (databaseMatches && tableMatches) {
                    matched = true;
                    break;
                }
                
                // 解析表名格式：schema.table 或 database.table
                // 根据数据库类型，表名格式可能不同
                String tableToMatch = table;
                
                // 尝试匹配数据库
                if (!databaseMatches) {
                    // 对于MySQL，表名格式可能是 database.table
                    // 对于PostgreSQL，表名格式可能是 schema.table
                    // 这里简单处理：检查表名是否以 database. 开头
                    if (table.startsWith(sourceDatabase + ".")) {
                        databaseMatches = true;
                        // 移除数据库前缀，只比较表名部分
                        tableToMatch = table.substring(sourceDatabase.length() + 1);
                    }
                }
                
                // 尝试匹配表模式
                if (databaseMatches && !tableMatches) {
                    try {
                        // 使用正则表达式匹配
                        if (tableToMatch.matches(sourceTablePattern)) {
                            tableMatches = true;
                        }
                    } catch (Exception e) {
                        // 正则表达式无效，尝试简单字符串匹配
                        if (tableToMatch.equals(sourceTablePattern)) {
                            tableMatches = true;
                        }
                    }
                }
                
                // 如果数据库和表都匹配，则添加该表
                if (databaseMatches && tableMatches) {
                    matched = true;
                    break;
                }
            }
            
            if (matched) {
                matchedTables.add(table);
            }
        }
        
        System.out.println("[DB-Syncer] 根据规则过滤后找到 " + matchedTables.size() + " 个匹配的表");
        return matchedTables;
    }

    // 处理引擎启动
    private void processSyncerStart(Context ctx)
    {
        Logger logger = this.getLogger();
        Connection dbConn = null;

        try {
            // 获取数据库链�?
            dbConn = this.getDbConnection();

            // 验证配置有效性
            // validateConfiguration(startEngineRequest); // startEngineRequest 未定义，暂时注释掉

            // 获取日志句柄
            String sql;
            String syncerNameParam = ctx.pathParamMap().containsKey("syncer_name") ? ctx.pathParam("syncer_name") : null;
            if (syncerNameParam == null || syncerNameParam.isEmpty()) {
                // 从数据库中检索需要自动同步的信息，随后依次调用startEngine
                // 按syncer分组启动
                sql = """
                            SELECT syncer_name, syncer_type, syncer_source as rule_source, syncer_offset, snapshot_mode, auto,
                                   source_database, target_database, source_table, target_table, status
                            FROM sysaux.v$dbsyncer
                            WHERE auto = true
                            AND status IN ('CREATED')
                            AND enabled = true
                            ORDER BY syncer_name
                        """;
                Statement stmt = dbConn.createStatement();
                ResultSet rs = stmt.executeQuery(sql);

                // 收集所有需要启动的syncer
                List<StartEngineRequest> syncerRequests = new ArrayList<>();

                while (rs.next()) {
                    StartEngineRequest syncerRequest = new StartEngineRequest();
                    syncerRequest.syncer_name = rs.getString("syncer_name");
                    syncerRequest.syncer_type = rs.getString("syncer_type");
                    syncerRequest.rule_source = rs.getString("rule_source");
                    syncerRequest.syncer_offset = rs.getString("syncer_offset");
                    syncerRequest.snapshot_mode = rs.getString("snapshot_mode");
                    syncerRequest.auto = rs.getBoolean("auto");
                    // 不再使用rule_name，使用syncer_name作为标识
                    syncerRequest.rule_name = syncerRequest.syncer_name;
                    
                    // 创建单个规则信息
                    SyncerRuleInfo ruleInfo = new SyncerRuleInfo();
                    ruleInfo.rule_name = syncerRequest.syncer_name; // 使用syncer_name作为rule_name
                    ruleInfo.source_database = rs.getString("source_database");
                    ruleInfo.target_database = rs.getString("target_database");
                    ruleInfo.source_table = rs.getString("source_table");
                    ruleInfo.target_table = rs.getString("target_table");
                    ruleInfo.status = rs.getString("status");
                    ruleInfo.enabled = true;
                    
                    syncerRequest.rules = new ArrayList<>();
                    syncerRequest.rules.add(ruleInfo);
                    
                    syncerRequests.add(syncerRequest);
                }
                rs.close();
                stmt.close();

                // 为每个syncer启动引擎
                for (StartEngineRequest syncerRequest : syncerRequests) {
                    // 启动同步
                    logger.info("[DB-Syncer] Starting syncer {} ...", syncerRequest.syncer_name);
                    startEngine(syncerRequest);
                    logger.info("[DB-Syncer] Syncer {} started successful.", syncerRequest.syncer_name);
                }
            }
            else {
                // 从数据库中检索需要自动同步的信息，随后依次调用startEngine
                // 直接使用syncer_name参数
                sql = """
                            SELECT syncer_name, syncer_type, syncer_source as rule_source, syncer_offset, snapshot_mode, auto,
                                   source_database, target_database, source_table, target_table, status
                            FROM sysaux.v$dbsyncer
                            WHERE auto = true
                            AND status IN ('CREATED')
                            AND enabled = true
                            AND syncer_name = '{syncer_name}'
                            ORDER BY syncer_name
                        """;
                sql = sql.replace("{syncer_name}", syncerNameParam);
                Statement stmt = dbConn.createStatement();
                ResultSet rs = stmt.executeQuery(sql);

                // 处理查询结果（应该只有一行，因为syncer_name是唯一的）
                if (rs.next()) {
                    StartEngineRequest syncerRequest = new StartEngineRequest();
                    syncerRequest.syncer_name = rs.getString("syncer_name");
                    syncerRequest.syncer_type = rs.getString("syncer_type");
                    syncerRequest.rule_source = rs.getString("rule_source");
                    syncerRequest.syncer_offset = rs.getString("syncer_offset");
                    syncerRequest.snapshot_mode = rs.getString("snapshot_mode");
                    syncerRequest.auto = rs.getBoolean("auto");
                    // 不再使用rule_name，使用syncer_name作为标识
                    syncerRequest.rule_name = syncerRequest.syncer_name;
                    
                    // 创建单个规则信息
                    SyncerRuleInfo ruleInfo = new SyncerRuleInfo();
                    ruleInfo.rule_name = syncerRequest.syncer_name; // 使用syncer_name作为rule_name
                    ruleInfo.source_database = rs.getString("source_database");
                    ruleInfo.target_database = rs.getString("target_database");
                    ruleInfo.source_table = rs.getString("source_table");
                    ruleInfo.target_table = rs.getString("target_table");
                    ruleInfo.status = rs.getString("status");
                    ruleInfo.enabled = true;
                    
                    syncerRequest.rules = new ArrayList<>();
                    syncerRequest.rules.add(ruleInfo);
                    
                    // 启动同步
                    logger.info("[DB-Syncer] Starting syncer {} ...", syncerRequest.syncer_name);
                    startEngine(syncerRequest);
                    logger.info("[DB-Syncer] Syncer {} started successful.", syncerRequest.syncer_name);
                    
                    ctx.json(Map.of("retCode", 0, "retMsg", "Syncer engine start successful"));
                } else {
                    ctx.json(Map.of("retCode", -1, "retMsg", "Syncer not found or not ready to start"));
                }
                rs.close();
                stmt.close();
            }
        }
        catch (Exception exception)
        {
            ctx.json(Map.of("retCode", -1, "retMsg", "Syncer engine start failed. " + exception.getMessage()));
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 处理引擎停止
    private void processSyncerStop(Context ctx)
    {
        Connection dbConn = null;
        try {
            // 获取数据库连接
            dbConn = this.getDbConnection();

            // 验证配置有效性
            // validateConfiguration(startEngineRequest); // startEngineRequest 未定义，暂时注释掉

            String syncerName = ctx.pathParamMap().containsKey("syncer_name") ? ctx.pathParam("syncer_name") : null;

            if (syncerName == null || syncerName.isEmpty()) {
                // 停止所有的同步配置
                for (String s : syncManagerList.keySet()) {
                    SyncEngineManager manager = syncManagerList.get(s);
                    if (manager.isRunning()) {
                        manager.stopEngine();
                        // 标记数据库状�?- 更新该同步器的状�?
                        String sql = """
                            UPDATE  sysaux.v$dbsyncer
                            SET     status = ?,
                                    update_time = CURRENT_TIMESTAMP
                            WHERE   syncer_name = ?
                        """;
                        PreparedStatement pStmt = dbConn.prepareStatement(sql);
                        pStmt.setString(1, "CREATED");
                        pStmt.setString(2, manager.getSyncerName());
                        pStmt.executeUpdate();
                        dbConn.commit();
                        pStmt.close();
                    }
                }
                ctx.json(Collections.singletonMap("message", "All sync configs stopped"));
            } else {
                // 停止指定的同步配�?
                if (!syncManagerList.containsKey(syncerName))
                {
                    ctx.json(Map.of("retCode", -1, "retMsg", "Syncer " + syncerName + " does not exist!"));
                    return;
                }
                SyncEngineManager manager = syncManagerList.get(syncerName);
                if (manager.isRunning()) {
                    manager.stopEngine();
                    // 标记数据库状�?- 更新该同步器的状�?
                    String sql = """
                            UPDATE  sysaux.v$dbsyncer
                            SET     status = ?,
                                    update_time = CURRENT_TIMESTAMP
                            WHERE   syncer_name = ?
                        """;
                    PreparedStatement pStmt = dbConn.prepareStatement(sql);
                    pStmt.setString(1, "CREATED");
                    pStmt.setString(2, syncerName);
                    pStmt.executeUpdate();
                    dbConn.commit();
                    pStmt.close();
                }
                ctx.json(Collections.singletonMap("message", "Sync config " + syncerName + " stopped"));
            }
        } catch (Exception e) {
            ctx.status(500).json(Collections.singletonMap("error", e.getMessage()));
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 处理引擎的状�?
    private void processSyncerStatus(Context ctx)
    {
        Connection dbConn = null;

        List<JSONObject> ret = new ArrayList<>();

        try {
            dbConn = this.getDbConnection();

            // 验证配置有效性
            // validateConfiguration(startEngineRequest); // startEngineRequest 未定义，暂时注释掉
            String syncerName = null;

            if (ctx.pathParamMap().containsKey("syncer_name")) {
                syncerName = ctx.pathParam("syncer_name");
            }
            String sql;
            if (syncerName == null || syncerName.isEmpty()) {
                sql = """
                        SELECT  syncer_name, syncer_type, syncer_source as rule_source,
                                source_database, target_database, source_table, target_table, auto, syncer_offset,
                                status, create_time, update_time
                        FROM    sysaux.v$dbsyncer
                        ORDER BY syncer_name
                    """;
            } else {
                sql = """
                        SELECT  syncer_name, syncer_type, syncer_source as rule_source,
                                source_database, target_database, source_table, target_table, auto, syncer_offset,
                                status, create_time, update_time
                        FROM    sysaux.v$dbsyncer
                        WHERE   syncer_name = '{syncerName}'
                        ORDER BY syncer_name
                    """;
                sql = sql.replace("{syncerName}", syncerName);
            }
            Statement stmt = dbConn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                JSONObject config = new JSONObject();
                String currentSyncerName = rs.getString("syncer_name");
                config.put("id", currentSyncerName); // 使用syncer_name作为id，因为不再有rule_name
                config.put("syncer_name", currentSyncerName);
                config.put("syncer_type", rs.getString("syncer_type"));
                config.put("rule_name", currentSyncerName); // 为了向后兼容，仍然提供rule_name字段，但值等于syncer_name
                config.put("rule_source", rs.getString("rule_source"));
                config.put("source_database", rs.getString("source_database"));
                config.put("target_database", rs.getString("target_database"));
                config.put("source_table", rs.getString("source_table"));
                config.put("target_table", rs.getString("target_table"));
                config.put("auto", rs.getBoolean("auto"));
                config.put("syncer_offset", rs.getString("syncer_offset"));
                config.put("status", rs.getString("status"));
                config.put("create_time", rs.getTimestamp("create_time").toString());
                config.put("update_time", rs.getTimestamp("update_time").toString());
                ret.add(config);
            }
            rs.close();
            stmt.close();
            ctx.json(Map.of("retCode", 0, "data", ret));
        } catch (Exception e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "Query syncer engine status failed. " + e.getMessage()));
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 处理创建同步配置
    private void processSyncerCreate(Context ctx) {
        Connection dbConn = null;
        try
        {
            JSONObject body = JSON.parseObject(ctx.body());
            String syncerType = body.getString("syncer_type");
            String syncerName = body.getString("syncer_name");
            String syncerSource = body.getString("syncer_source");
            String snapshotMode = body.getString("snapshot_mode");
            if (snapshotMode == null || snapshotMode.isEmpty()) {
                snapshotMode = "INITIAL"; // 默认�?
            }
            boolean auto = body.getBooleanValue("auto");
            boolean enabled = body.getBooleanValue("enabled", true);
            String status = body.getString("status");
            if (status == null || status.isEmpty()) {
                status = "CREATED";
            }

            dbConn = this.getDbConnection();

            // 验证配置有效性
            // validateConfiguration(startEngineRequest); // startEngineRequest 未定义，暂时注释掉
            String syncerOffset = body.getString("syncer_offset");

            // 获取Role字段（可选，如果没有提供则使用空值）
            String sourceDatabase = body.getString("source_database");
            String targetDatabase = body.getString("target_database");
            String sourceTable = body.getString("source_table");
            String targetTable = body.getString("target_table");
            
            String sql = """
                    INSERT INTO sysaux.v$dbsyncer (syncer_type, syncer_name, syncer_source, syncer_offset, snapshot_mode, auto, enabled, status,
                                                   source_database, target_database, source_table, target_table, create_time, update_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """;
            PreparedStatement pStmt = dbConn.prepareStatement(sql);
            pStmt.setString(1, syncerType);
            pStmt.setString(2, syncerName);
            pStmt.setString(3, syncerSource);
            pStmt.setString(4, syncerOffset);
            pStmt.setString(5, snapshotMode);
            pStmt.setBoolean(6, auto);
            pStmt.setBoolean(7, enabled);
            pStmt.setString(8, status);
            pStmt.setString(9, sourceDatabase);
            pStmt.setString(10, targetDatabase);
            pStmt.setString(11, sourceTable);
            pStmt.setString(12, targetTable);
            pStmt.executeUpdate();
            dbConn.commit();
            pStmt.close();

            ctx.json(Map.of("retCode", 0, "retMsg", "Syncer created successfully"));
        } catch (Exception e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to create syncer: " + e.getMessage()));
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 处理删除同步配置
    private void processSyncerDelete(Context ctx) {
        Connection dbConn = null;
        try {
            String syncerName = ctx.pathParamMap().containsKey("syncer_name") ? ctx.pathParam("syncer_name") : null;
            if (syncerName == null || syncerName.isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Missing syncer_name parameter"));
                return;
            }
            dbConn = this.getDbConnection();

            // 验证配置有效性
            // validateConfiguration(startEngineRequest); // startEngineRequest 未定义，暂时注释掉

            // 删除同步器配�?（不再需要删除规则表，因为规则已合并到主表）
            String deleteSyncerSql = "DELETE FROM sysaux.v$dbsyncer WHERE syncer_name = ?";
            PreparedStatement pStmt = dbConn.prepareStatement(deleteSyncerSql);
            pStmt.setString(1, syncerName);
            int rows = pStmt.executeUpdate();
            pStmt.close();
            dbConn.commit();

            if (rows > 0) {
                ctx.json(Map.of("retCode", 0, "retMsg", "Syncer deleted successfully"));
            } else {
                ctx.json(Map.of("retCode", -1, "retMsg", "Syncer not found"));
            }
        } catch (Exception e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to delete syncer: " + e.getMessage()));
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 处理设置（更新）同步配置
    private void processSyncerSet(Context ctx) {
        Connection dbConn = null;
        try {
            String syncerName = ctx.pathParamMap().containsKey("syncer_name") ? ctx.pathParam("syncer_name") : null;
            if (syncerName == null || syncerName.isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Missing syncer_name parameter"));
                return;
            }
            JSONObject body = JSON.parseObject(ctx.body());

            // 构建动态更新语�?
            List<String> updates = new ArrayList<>();
            List<Object> params = new ArrayList<>();

            if (body.containsKey("syncer_type")) {
                updates.add("syncer_type = ?");
                params.add(body.getString("syncer_type"));
            }
            if (body.containsKey("syncer_source")) {
                updates.add("syncer_source = ?");
                params.add(body.getString("syncer_source"));
            }
            if (body.containsKey("syncer_offset")) {
                updates.add("syncer_offset = ?");
                params.add(body.getString("syncer_offset"));
            }
            if (body.containsKey("snapshot_mode")) {
                updates.add("snapshot_mode = ?");
                params.add(body.getString("snapshot_mode"));
            }
            if (body.containsKey("auto")) {
                updates.add("auto = ?");
                params.add(body.getBoolean("auto"));
            }
            if (body.containsKey("enabled")) {
                updates.add("enabled = ?");
                params.add(body.getBoolean("enabled"));
            }
            if (body.containsKey("status")) {
                updates.add("status = ?");
                params.add(body.getString("status"));
            }
            // Role字段
            if (body.containsKey("source_database")) {
                updates.add("source_database = ?");
                params.add(body.getString("source_database"));
            }
            if (body.containsKey("target_database")) {
                updates.add("target_database = ?");
                params.add(body.getString("target_database"));
            }
            if (body.containsKey("source_table")) {
                updates.add("source_table = ?");
                params.add(body.getString("source_table"));
            }
            if (body.containsKey("target_table")) {
                updates.add("target_table = ?");
                params.add(body.getString("target_table"));
            }

            if (updates.isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "No fields to update"));
                return;
            }

            updates.add("update_time = CURRENT_TIMESTAMP");

            dbConn = this.getDbConnection();

            // 验证配置有效性
            // validateConfiguration(startEngineRequest); // startEngineRequest 未定义，暂时注释掉
            String sql = "UPDATE sysaux.v$dbsyncer SET " + String.join(", ", updates) + " WHERE syncer_name = ?";
            params.add(syncerName);

            PreparedStatement pStmt = dbConn.prepareStatement(sql);
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    pStmt.setString(i + 1, (String) param);
                } else if (param instanceof Boolean) {
                    pStmt.setBoolean(i + 1, (Boolean) param);
                }
            }
            int rows = pStmt.executeUpdate();
            dbConn.commit();
            pStmt.close();

            if (rows > 0) {
                ctx.json(Map.of("retCode", 0, "retMsg", "Syncer updated successfully"));
            } else {
                ctx.json(Map.of("retCode", -1, "retMsg", "Syncer not found"));
            }
        } catch (Exception e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to update syncer: " + e.getMessage()));
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 处理引擎规则的添�?
    private void processSyncerRoleCreate(Context ctx) {
        Connection dbConn = null;
        try {
            String syncerName = ctx.pathParamMap().containsKey("syncer_name") ? ctx.pathParam("syncer_name") : null;
            if (syncerName == null || syncerName.isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Missing syncer_name parameter"));
                return;
            }
            JSONObject body = JSON.parseObject(ctx.body());
            String ruleName = body.getString("rule_name");
            String sourceDatabase = body.getString("source_database");
            String targetDatabase = body.getString("target_database");
            String sourceTable = body.getString("source_table");
            String targetTable = body.getString("target_table");
            boolean enabled = body.getBooleanValue("enabled", true);
            String status = body.getString("status");
            if (status == null || status.isEmpty()) {
                status = "CREATED";
            }
            dbConn = this.getDbConnection();

            // 验证配置有效性
            // validateConfiguration(startEngineRequest); // startEngineRequest 未定义，暂时注释掉
            String sql = """
                    INSERT INTO sysaux.v$dbsyncer_rule (syncer_name, rule_name, source_database, target_database, source_table, target_table, enabled, status, create_time, update_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """;
            PreparedStatement pStmt = dbConn.prepareStatement(sql);
            pStmt.setString(1, syncerName);
            pStmt.setString(2, ruleName);
            pStmt.setString(3, sourceDatabase);
            pStmt.setString(4, targetDatabase);
            pStmt.setString(5, sourceTable);
            pStmt.setString(6, targetTable);
            pStmt.setBoolean(7, enabled);
            pStmt.setString(8, status);
            pStmt.executeUpdate();
            dbConn.commit();
            pStmt.close();

            ctx.json(Map.of("retCode", 0, "retMsg", "Rule created successfully"));
        } catch (Exception e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to create rule: " + e.getMessage()));
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 处理引擎规则的删�?
    private void processSyncerRoleDelete(Context ctx) {
        Connection dbConn = null;
        try {
            String syncerName = ctx.pathParamMap().containsKey("syncer_name") ? ctx.pathParam("syncer_name") : null;
            String ruleName = ctx.pathParamMap().containsKey("rule_name") ? ctx.pathParam("rule_name") : null;
            if (syncerName == null || syncerName.isEmpty() || ruleName == null || ruleName.isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Missing syncer_name or rule_name parameter"));
                return;
            }

            dbConn = this.getDbConnection();

            // 验证配置有效性
            // validateConfiguration(startEngineRequest); // startEngineRequest 未定义，暂时注释掉
            String sql = "DELETE FROM sysaux.v$dbsyncer_rule WHERE syncer_name = ? AND rule_name = ?";
            PreparedStatement pStmt = dbConn.prepareStatement(sql);
            pStmt.setString(1, syncerName);
            pStmt.setString(2, ruleName);
            int rows = pStmt.executeUpdate();
            dbConn.commit();
            pStmt.close();

            if (rows > 0) {
                ctx.json(Map.of("retCode", 0, "retMsg", "Rule deleted successfully"));
            } else {
                ctx.json(Map.of("retCode", -1, "retMsg", "Rule not found"));
            }
        } catch (Exception e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to delete rule: " + e.getMessage()));
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 处理引擎规则的设�?
    private void processSyncerRoleSet(Context ctx) {
        Connection dbConn = null;
        try {
            String syncerName = ctx.pathParamMap().containsKey("syncer_name") ? ctx.pathParam("syncer_name") : null;
            String ruleName = ctx.pathParamMap().containsKey("rule_name") ? ctx.pathParam("rule_name") : null;
            if (syncerName == null || syncerName.isEmpty() || ruleName == null || ruleName.isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Missing syncer_name or rule_name parameter"));
                return;
            }
            JSONObject body = JSON.parseObject(ctx.body());

            // 构建动态更新语�?
            List<String> updates = new ArrayList<>();
            List<Object> params = new ArrayList<>();

            if (body.containsKey("source_database")) {
                updates.add("source_database = ?");
                params.add(body.getString("source_database"));
            }
            if (body.containsKey("target_database")) {
                updates.add("target_database = ?");
                params.add(body.getString("target_database"));
            }
            if (body.containsKey("source_table")) {
                updates.add("source_table = ?");
                params.add(body.getString("source_table"));
            }
            if (body.containsKey("target_table")) {
                updates.add("target_table = ?");
                params.add(body.getString("target_table"));
            }
            if (body.containsKey("enabled")) {
                updates.add("enabled = ?");
                params.add(body.getBoolean("enabled"));
            }
            if (body.containsKey("status")) {
                updates.add("status = ?");
                params.add(body.getString("status"));
            }

            if (updates.isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "No fields to update"));
                return;
            }

            updates.add("update_time = CURRENT_TIMESTAMP");

            dbConn = this.getDbConnection();

            // 验证配置有效性
            // validateConfiguration(startEngineRequest); // startEngineRequest 未定义，暂时注释掉
            String sql = "UPDATE sysaux.v$dbsyncer_rule SET " + String.join(", ", updates) + " WHERE syncer_name = ? AND rule_name = ?";
            params.add(syncerName);
            params.add(ruleName);

            PreparedStatement pStmt = dbConn.prepareStatement(sql);
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    pStmt.setString(i + 1, (String) param);
                } else if (param instanceof Boolean) {
                    pStmt.setBoolean(i + 1, (Boolean) param);
                }
            }
            int rows = pStmt.executeUpdate();
            dbConn.commit();
            pStmt.close();

            if (rows > 0) {
                ctx.json(Map.of("retCode", 0, "retMsg", "Rule updated successfully"));
            } else {
                ctx.json(Map.of("retCode", -1, "retMsg", "Rule not found"));
            }
        } catch (Exception e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to update rule: " + e.getMessage()));
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 显示当前同步器的情况
    private void processSyncerList(Context ctx) {
        Connection dbConn = null;
        List<JSONObject> ret = new ArrayList<>();
        try {
            dbConn = this.getDbConnection();

            // 验证配置有效性
            // validateConfiguration(startEngineRequest); // startEngineRequest 未定义，暂时注释掉
            String sql = "SELECT syncer_type, syncer_name, syncer_source, syncer_offset, snapshot_mode, auto, enabled, status, create_time, update_time FROM sysaux.v$dbsyncer ORDER BY syncer_name";
            Statement stmt = dbConn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                JSONObject config = new JSONObject();
                config.put("syncer_type", rs.getString("syncer_type"));
                config.put("syncer_name", rs.getString("syncer_name"));
                config.put("syncer_source", rs.getString("syncer_source"));
                config.put("syncer_offset", rs.getString("syncer_offset"));
                config.put("snapshot_mode", rs.getString("snapshot_mode"));
                config.put("auto", rs.getBoolean("auto"));
                config.put("enabled", rs.getBoolean("enabled"));
                config.put("status", rs.getString("status"));
                config.put("create_time", rs.getTimestamp("create_time").toString());
                config.put("update_time", rs.getTimestamp("update_time").toString());
                ret.add(config);
            }
            rs.close();
            stmt.close();
            ctx.json(Map.of("retCode", 0, "data", ret));
        } catch (Exception e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "Query syncer list failed. " + e.getMessage()));
        }
        finally {
            try {
                if (dbConn != null && !dbConn.isClosed()) {
                    dbConn.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    // 显示当前同步器的具体配置情况
    private void processSyncerRuleList(Context ctx) {
        List<JSONObject> ret = new ArrayList<>();
        try {
            String syncerName = ctx.pathParamMap().containsKey("syncer_name") ? ctx.pathParam("syncer_name") : null;
            if (syncerName == null || syncerName.isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Missing syncer_name parameter"));
                return;
            }
            Connection dbConn = this.getDbConnection();
            String sql = "SELECT syncer_name as rule_name, source_database, target_database, source_table, target_table, enabled, status, create_time, update_time FROM sysaux.v$dbsyncer WHERE syncer_name = ?";
            PreparedStatement pStmt = dbConn.prepareStatement(sql);
            pStmt.setString(1, syncerName);
            ResultSet rs = pStmt.executeQuery();
            while (rs.next()) {
                JSONObject config = new JSONObject();
                config.put("rule_name", rs.getString("rule_name"));
                config.put("source_database", rs.getString("source_database"));
                config.put("target_database", rs.getString("target_database"));
                config.put("source_table", rs.getString("source_table"));
                config.put("target_table", rs.getString("target_table"));
                config.put("enabled", rs.getBoolean("enabled"));
                config.put("status", rs.getString("status"));
                config.put("create_time", rs.getTimestamp("create_time").toString());
                config.put("update_time", rs.getTimestamp("update_time").toString());
                ret.add(config);
            }
            rs.close();
            pStmt.close();
            ctx.json(Map.of("retCode", 0, "data", ret));
        } catch (Exception e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "Query syncer rule list failed. " + e.getMessage()));
        }
    }

    /**
     * Logic when the plugin stops.
     * Resources can be released here, connections closed, tasks stopped, etc.
     */
    protected void onStop() {
        // 停止所有运行中的同步引�?
        for (SyncEngineManager manager : syncManagerList.values()) {
            if (manager.isRunning()) {
                manager.stopEngine();
            }
        }
        // 清空管理器列�?
        syncManagerList.clear();
    }

    // ========== Offset Management ==========

    /**
     * 根据规则计算Debezium过滤参数
     * @param rules 规则列表
     * @param syncerType 数据库类型 (MYSQL/POSTGRES)
     * @param dbConfig 数据库连接配置
     * @return 包含过滤参数的Properties对象
     */
    private Properties calculateFilterParameters(List<SyncerRuleInfo> rules, String syncerType, Properties dbConfig) {
        Properties filters = new Properties();
        
        if (rules == null || rules.isEmpty()) {
            return filters;
        }
        
        try {
            // 查询源数据库获取实际匹配的表
            return resolveActualTablesFromSource(rules, syncerType, dbConfig);
        } catch (Exception e) {
            getLogger().error("[DB-Syncer] 查询源数据库表结构失败，使用通配符模式: {}", e.getMessage());
            // 如果查询失败，回退到通配符模式
            return calculateFilterParametersWithWildcard(rules, syncerType);
        }
    }
    
    /**
     * 查询源数据库获取实际匹配的表名
     */
    private Properties resolveActualTablesFromSource(List<SyncerRuleInfo> rules, String syncerType, Properties dbConfig) throws Exception {
        Properties filters = new Properties();
        
        Set<String> databases = new HashSet<>();
        Set<String> schemas = new HashSet<>();
        Set<String> tables = new HashSet<>();
        
        // 连接到源数据库
        Connection sourceConn = connectToSourceDatabase(syncerType, dbConfig);
        try {
            for (SyncerRuleInfo rule : rules) {
                // 处理source_database模式
                if (rule.source_database != null && !rule.source_database.trim().isEmpty()) {
                    String dbPattern = rule.source_database.trim();
                    Set<String> matchedDbs = queryMatchingDatabases(sourceConn, syncerType, dbPattern);
                    databases.addAll(matchedDbs);
                    
                    if ("POSTGRES".equalsIgnoreCase(syncerType)) {
                        // 对于PostgreSQL，schema通常与database相同
                        schemas.addAll(matchedDbs);
                    }
                }
                
                // 处理source_table模式
                if (rule.source_table != null && !rule.source_table.trim().isEmpty()) {
                    String tablePattern = rule.source_table.trim();
                    Set<String> matchedTables = queryMatchingTables(sourceConn, syncerType, tablePattern, databases);
                    tables.addAll(matchedTables);
                }
            }
        } finally {
            if (sourceConn != null) {
                sourceConn.close();
            }
        }
        
        // 设置过滤参数
        if (!databases.isEmpty()) {
            if ("MYSQL".equalsIgnoreCase(syncerType)) {
                filters.put("database.include.list", String.join(",", databases));
            }
        }
        
        if (!schemas.isEmpty() && "POSTGRES".equalsIgnoreCase(syncerType)) {
            filters.put("schema.include.list", String.join(",", schemas));
        }
        
        if (!tables.isEmpty()) {
            filters.put("table.include.list", String.join(",", tables));
        }
        
        return filters;
    }
    
    /**
     * 连接到源数据库
     */
    private Connection connectToSourceDatabase(String syncerType, Properties dbConfig) throws Exception {
        String url;
        String username = dbConfig.getProperty("database.user");
        String password = dbConfig.getProperty("database.password");
        
        if ("MYSQL".equalsIgnoreCase(syncerType)) {
            String hostname = dbConfig.getProperty("database.hostname");
            String port = dbConfig.getProperty("database.port");
            String database = dbConfig.getProperty("database.include.list");
            url = "jdbc:mysql://" + hostname + ":" + port + "/" + database + "?useSSL=false&serverTimezone=UTC";
            return DriverManager.getConnection(url, username, password);
        } else if ("POSTGRES".equalsIgnoreCase(syncerType)) {
            String hostname = dbConfig.getProperty("database.hostname");
            String port = dbConfig.getProperty("database.port");
            String database = dbConfig.getProperty("database.dbname");
            url = "jdbc:postgresql://" + hostname + ":" + port + "/" + database;
            return DriverManager.getConnection(url, username, password);
        } else {
            throw new IllegalArgumentException("Unsupported database type: " + syncerType);
        }
    }
    
    /**
     * 查询匹配的数据库（MySQL）或schema（PostgreSQL）
     */
    private Set<String> queryMatchingDatabases(Connection conn, String syncerType, String pattern) throws Exception {
        Set<String> result = new HashSet<>();
        
        if ("MYSQL".equalsIgnoreCase(syncerType)) {
            // MySQL: 查询所有数据库，然后进行模式匹配
            String sql = "SHOW DATABASES";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String dbName = rs.getString(1);
                    if (matchesPattern(dbName, pattern)) {
                        result.add(dbName);
                    }
                }
            }
        } else if ("POSTGRES".equalsIgnoreCase(syncerType)) {
            // PostgreSQL: 查询所有schema
            String sql = "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT LIKE 'pg_%' AND schema_name != 'information_schema'";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String schemaName = rs.getString(1);
                    if (matchesPattern(schemaName, pattern)) {
                        result.add(schemaName);
                    }
                }
            }
        }
        
        return result;
    }
    
    /**
     * 查询匹配的表
     */
    private Set<String> queryMatchingTables(Connection conn, String syncerType, String tablePattern, Set<String> databases) throws Exception {
        Set<String> result = new HashSet<>();
        
        // 解析表模式：可能是 "db.table" 或 "schema.table" 或只是 "table"
        boolean hasDatabasePrefix = tablePattern.contains(".");
        String dbPattern = null;
        String pureTablePattern = tablePattern;
        
        if (hasDatabasePrefix) {
            int dotIndex = tablePattern.indexOf('.');
            dbPattern = tablePattern.substring(0, dotIndex);
            pureTablePattern = tablePattern.substring(dotIndex + 1);
        }
        
        if ("MYSQL".equalsIgnoreCase(syncerType)) {
            for (String db : databases) {
                // 如果指定了数据库前缀且不匹配，则跳过
                if (dbPattern != null && !matchesPattern(db, dbPattern)) {
                    continue;
                }
                
                // 切换到该数据库
                try (Statement useStmt = conn.createStatement()) {
                    useStmt.execute("USE `" + db + "`");
                }
                
                // 查询该数据库中的所有表
                String sql = "SHOW TABLES";
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        String tableName = rs.getString(1);
                        if (matchesPattern(tableName, pureTablePattern)) {
                            result.add(db + "." + tableName);
                        }
                    }
                }
            }
        } else if ("POSTGRES".equalsIgnoreCase(syncerType)) {
            for (String schema : databases) { // 对于PostgreSQL，databases实际上是schemas
                // 如果指定了schema前缀且不匹配，则跳过
                if (dbPattern != null && !matchesPattern(schema, dbPattern)) {
                    continue;
                }
                
                // 查询该schema中的所有表
                String sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, schema);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            String tableName = rs.getString(1);
                            if (matchesPattern(tableName, pureTablePattern)) {
                                result.add(schema + "." + tableName);
                            }
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    /**
     * 检查字符串是否匹配模式（支持简单的通配符：* 和 ?）
     */
    private boolean matchesPattern(String str, String pattern) {
        if (pattern.equals("(.*)") || pattern.equals(".*") || pattern.equals("*")) {
            return true;
        }
        
        // 将正则表达式转换为简单的通配符匹配
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".");
        return str.matches(regex);
    }
    
    /**
     * 回退方法：使用通配符模式计算过滤参数
     */
    private Properties calculateFilterParametersWithWildcard(List<SyncerRuleInfo> rules, String syncerType) {
        Properties filters = new Properties();
        
        if (rules == null || rules.isEmpty()) {
            return filters;
        }
        
        Set<String> databases = new HashSet<>();
        Set<String> schemas = new HashSet<>();
        Set<String> tables = new HashSet<>();
        
        for (SyncerRuleInfo rule : rules) {
            // 处理source_database
            if (rule.source_database != null && !rule.source_database.trim().isEmpty()) {
                String dbPattern = regexToWildcard(rule.source_database.trim());
                databases.add(dbPattern);
                
                // 对于PostgreSQL，schema通常与database相同或为"public"
                if ("POSTGRES".equalsIgnoreCase(syncerType)) {
                    schemas.add(dbPattern);
                }
            }
            
            // 处理source_table
            if (rule.source_table != null && !rule.source_table.trim().isEmpty()) {
                String tablePattern = regexToWildcard(rule.source_table.trim());
                tables.add(tablePattern);
            }
        }
        
        // 设置过滤参数
        if (!databases.isEmpty()) {
            if ("MYSQL".equalsIgnoreCase(syncerType)) {
                filters.put("database.include.list", String.join(",", databases));
            }
        }
        
        if (!schemas.isEmpty() && "POSTGRES".equalsIgnoreCase(syncerType)) {
            filters.put("schema.include.list", String.join(",", schemas));
        }
        
        if (!tables.isEmpty()) {
            // 对于MySQL，表名需要包含数据库前缀
            if ("MYSQL".equalsIgnoreCase(syncerType)) {
                Set<String> fullTableNames = new HashSet<>();
                for (String db : databases) {
                    for (String table : tables) {
                        // 如果table已经包含数据库前缀，直接使用
                        if (table.contains(".")) {
                            fullTableNames.add(table);
                        } else {
                            // 否则添加数据库前缀
                            fullTableNames.add(db + "." + table);
                        }
                    }
                }
                if (!fullTableNames.isEmpty()) {
                    filters.put("table.include.list", String.join(",", fullTableNames));
                }
            } else if ("POSTGRES".equalsIgnoreCase(syncerType)) {
                // PostgreSQL使用schema.table格式
                Set<String> fullTableNames = new HashSet<>();
                for (String schema : schemas) {
                    for (String table : tables) {
                        if (table.contains(".")) {
                            fullTableNames.add(table);
                        } else {
                            fullTableNames.add(schema + "." + table);
                        }
                    }
                }
                if (!fullTableNames.isEmpty()) {
                    filters.put("table.include.list", String.join(",", fullTableNames));
                }
            }
        }
        
        return filters;
    }
    
    /**
     * 将简单的正则表达式转换为Debezium通配符模式
     * 支持转换：
     * - (.*) -> *
     * - .* -> *
     * - 其他模式保持原样
     */
    private String regexToWildcard(String regex) {
        if (regex == null || regex.isEmpty()) {
            return "*";
        }
        
        String trimmed = regex.trim();
        
        // 将 (.*) 转换为 *
        if (trimmed.equals("(.*)")) {
            return "*";
        }
        
        // 将 .* 转换为 *
        if (trimmed.equals(".*")) {
            return "*";
        }
        
        // 将 ^.*$ 转换为 *
        if (trimmed.equals("^.*$")) {
            return "*";
        }
        
        // 简单的转义处理：将 \. 转换为 .
        return trimmed.replace("\\.", ".");
    }

    /**
     * 保存offset数据到数据库
     * @param syncerName 同步器名�?
     * @param offsetData offset数据（JSON字符串）
     */
    private void saveOffsetToDatabase(String syncerName, String offsetData) {
        try {
            Connection dbConn = syncManagerList.get(syncerName).getDbConnection();
            String sql = """
                    UPDATE sysaux.v$dbsyncer
                    SET syncer_offset = ?,
                        update_time = CURRENT_TIMESTAMP
                    WHERE syncer_name = ?
                    """;
            PreparedStatement pStmt = dbConn.prepareStatement(sql);
            pStmt.setString(1, offsetData);
            pStmt.setString(2, syncerName);
            pStmt.executeUpdate();
            pStmt.close();
            getLogger().trace("[DB-Syncer] Offset saved to database for syncer: {}", syncerName);
        } catch (Exception e) {
            getLogger().error("[DB-Syncer] Failed to save offset to database for syncer {}: {}", syncerName, e.getMessage());
        }
    }

    /**
     * 从数据库加载检查点并生成检查点文件
     * 程序启动时读取数据库的信息，并把数据库的状态生成检查点文件
     * 如果数据库信息为空，则开始全量同�?
     *
     * @param syncerName 同步器名�?
     * @param offsetFilePath 检查点文件路径
     * @return 是否成功加载了有效的检查点
     */
    private boolean loadCheckpointAndGenerateFile(String syncerName, String offsetFilePath) throws Exception {
        Connection dbConn = syncManagerList.get(syncerName).getDbConnection();
        String sql = """
                SELECT syncer_offset, status, update_time
                FROM sysaux.v$dbsyncer
                WHERE syncer_name = ?
                """;
        PreparedStatement pStmt = dbConn.prepareStatement(sql);
        pStmt.setString(1, syncerName);
        ResultSet rs = pStmt.executeQuery();

        if (rs.next()) {
            String offsetData = rs.getString("syncer_offset");
            String status = rs.getString("status");

            // 检查状态：如果状态不是STREAMING，则强制重新读取快照
            if (status != null && !"STREAMING".equals(status)) {
                System.out.println("[DB-Syncer] 状态为 " + status + "，不是STREAMING，强制重新读取快照 " + syncerName);
                return false;
            }

            // 检查是否有有效的offset数据
            if (offsetData != null && !offsetData.trim().isEmpty()) {
                try {
                    // 验证是否为有效JSON
                    JSON.parseObject(offsetData);

                    // 将检查点数据写入文件
                    Files.writeString(Paths.get(offsetFilePath), offsetData);
                    System.out.println("[DB-Syncer] 检查点已从数据库加载并写入文件: " + syncerName);
                    return true;
                } catch (Exception e) {
                    System.err.println("[DB-Syncer] 检查点数据格式无效，将开始全量同�? " + syncerName);
                    return false;
                }
            }
        }

        // 没有检查点数据或数据为�?
        getLogger().info("[DB-Syncer] 数据库中没有检查点数据，将开始全量同�? {}", syncerName);
        return false;
    }

    // ========== Standalone Running Support ==========
    /**
     * Create standalone plugin instance.
     * This method returns a plugin instance configured with a simulated PluginWrapper, suitable for testing and standalone execution.
     * Users need to manually set DBPluginContext and call start() method.
     *
     * @return Standalone plugin instance
     * @throws Exception if creation fails
     */
    public static DBSyncer standAloneInstance() throws Exception {
        return DBPlugin.Standalone.createInstance(DBSyncer.class);
    }
}













