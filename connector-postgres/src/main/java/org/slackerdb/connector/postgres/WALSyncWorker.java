package org.slackerdb.connector.postgres;

import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.jms.JMSException;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.postgresql.copy.CopyOut;
import org.slackerdb.common.utils.DBUtil;
import org.slackerdb.common.utils.Sleeper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

public class WALSyncWorker extends Thread implements IWALMessage{
    // 目标数据库连接
    private Connection targetConnection;

    private final ConnectorTask connectorTask;
    private final Logger logger;
    private final ConcurrentHashMap<String, String> syncObjectMap = new ConcurrentHashMap<>();
    private final EmbeddedActiveMQ embeddedBrokerService;
    private long  messageProcessed = 0;
    private long  messageLastCommitTime = Instant.now().toEpochMilli();
    private HashMap<String, PreparedStatement> insertStmtCacheMap = new HashMap<>();
    private long  batchInserted = 0;

    public WALSyncWorker(ConnectorTask connectorTask, EmbeddedActiveMQ embeddedBrokerService)
    {
        this.connectorTask = connectorTask;
        this.logger = connectorTask.getLogger();
        this.embeddedBrokerService = embeddedBrokerService;
    }

    @Override
    public void consumeMessage(String textMessage)
    {
        JSONObject changeEvent = JSONObject.parseObject(textMessage);
        String schemaName = changeEvent.getString("schema");
        String tableName = changeEvent.getString("table");
        JSONArray columnNames = changeEvent.getJSONArray("columnnames");
        JSONArray columnTypes = changeEvent.getJSONArray("columntypes");
        JSONArray columnValues = changeEvent.getJSONArray("columnvalues");
        PreparedStatement insertPStmt = null;

        System.out.println("textMessage = " + textMessage);
        try {
            if (changeEvent.get("kind").equals("insert")) {
                if (insertStmtCacheMap.containsKey(schemaName + "." + tableName))
                {
                    // 构建插入语句, 并缓存起来。减少下次开销
                    String sql = "INSERT INTO " + schemaName + "." + tableName + "({columns}) VALUES({values})";
                    StringBuilder columns, values;
                    columns = new StringBuilder();
                    values = new StringBuilder();
                    int nPos = 1;
                    for (Object columnObj : columnNames) {
                        String columnName = columnObj.toString();
                        nPos = nPos + 1;
                        if (columns.toString().isEmpty()) {
                            columns = new StringBuilder(columnName);
                            values = new StringBuilder("?");
                        } else {
                            columns.append(",").append(columnName);
                            values.append(",?");
                        }
                    }
                    sql = sql.replace("{columns}", columns.toString());
                    sql = sql.replace("{values}", values.toString());
                    PreparedStatement preparedStatement = targetConnection.prepareStatement(sql);
                    insertStmtCacheMap.put(schemaName + "." + tableName, preparedStatement);
                    insertPStmt = preparedStatement;
                }
                else
                {
                    insertPStmt = insertStmtCacheMap.get(schemaName + "." + tableName);
                }
                // 绑定列
                for (int nPos = 0; nPos < columnValues.size(); nPos++) {
                    insertPStmt.setObject(nPos + 1, columnValues.get(nPos));
                }
                insertPStmt.addBatch();
                batchInserted = batchInserted + 1;
            }
            else
            {
                System.out.println("not imple");
            }
        }
        catch (SQLException sqlException)
        {
            // 同步出现了错误
            logger.error("[WALSyncWorker] Consume message failed.", sqlException);
            connectorTask.setStatus("ERROR");
            connectorTask.setErrorMsg(sqlException.getMessage());
            try {
                connectorTask.save();
            }
            catch (SQLException sqlException1)
            {
                logger.error("[WALSyncWorker] Consume message failed.", sqlException1);
            }
            return;
        }

        // 记录数超过5000或者上次提交时间超过5秒，则提交
        messageProcessed = messageProcessed + 1;
        try {
            if (messageProcessed > 5000) {
                if (changeEvent.get("kind").equals("insert") && batchInserted != 0)
                {
                    if (insertPStmt != null) {
                        insertPStmt.executeBatch();
                    }
                }
                this.targetConnection.commit();
                messageProcessed = 0;
                messageLastCommitTime = Instant.now().toEpochMilli();
            }
        }
        catch (SQLException sqlException)
        {
            // 同步出现了错误
            logger.error("[WALSyncWorker] Consume message failed.", sqlException);
            connectorTask.setStatus("ERROR");
            connectorTask.setErrorMsg(sqlException.getMessage());
            try {
                connectorTask.save();
            }
            catch (SQLException sqlException1)
            {
                logger.error("[WALSyncWorker] Consume message failed.", sqlException1);
            }
        }
    }

    @Override
    public void run()
    {
        // 连接源数据库
        try {
            // 设置线程名称
            this.setName(this.connectorTask.getConnector().connectorName + "-" + this.connectorTask.taskName);

            logger.trace("[POSTGRES-WAL] : Sync worker starting ...");
            String sql; Statement stmt; ResultSet rs; PreparedStatement pStmt;

            // 获取数据源的连接
            Connection sourceConnection = DBUtil.getJdbcConnection(this.connectorTask.getConnector().getConnectorURL());
            sourceConnection.setAutoCommit(false);
            this.targetConnection = ((DuckDBConnection) this.connectorTask.getConnector().getTargetDBConnection()).duplicate();
            targetConnection.setAutoCommit(false);

            // 从数据字典中检索需要同步的对象清单，并构建映射关系
            List<String> schemaList = new ArrayList<>();
            stmt = sourceConnection.createStatement();
            rs = stmt.executeQuery("SELECT nspname FROM pg_namespace");
            while (rs.next())
            {
                String schemaName = rs.getString("nspName");
                if (Pattern.compile(connectorTask.getSourceSchemaRule()).matcher(schemaName).matches())
                {
                    schemaList.add(schemaName);
                }
            }
            rs.close();
            stmt.close();
            List<String> schemaAndTableList = new ArrayList<>();
            stmt = sourceConnection.createStatement();
            for (String schemaName : schemaList)
            {
                rs = stmt.executeQuery("SELECT tablename FROM pg_tables where schemaname = '" + schemaName + "'");
                while (rs.next())
                {
                    if (Pattern.compile(connectorTask.getSourceTableRule()).matcher(rs.getString("tablename")).matches())
                    {
                        schemaAndTableList.add(schemaName + "." + rs.getString("tablename"));
                    }
                }
                rs.close();
            }
            stmt.close();
            for (String schemaAndTableName : schemaAndTableList)
            {
                String schemaName = schemaAndTableName.split("\\.")[0];
                String tableName = schemaAndTableName.split("\\.")[1];
                String targetSchemaName = schemaName.replace(connectorTask.getSourceSchemaRule(), connectorTask.getTargetSchemaRule());
                String targetTableName = tableName.replace(connectorTask.getSourceTableRule(), connectorTask.getTargetTableRule());
                syncObjectMap.put(schemaAndTableName, targetSchemaName + "." + targetTableName);
            }
            // 如果目标表在目标数据库不存在，则创建目标表
            for (Map.Entry<String, String> syncObject: syncObjectMap.entrySet())
            {
                String sourceSchemaName = syncObject.getKey().split("\\.")[0];
                String sourceTableName = syncObject.getKey().split("\\.")[1];
                String targetSchemaName = syncObject.getValue().split("\\.")[0];
                String targetTableName = syncObject.getValue().split("\\.")[1];
                if (!DuckdbUtil.isSchemaExists(targetConnection, targetSchemaName))
                {
                    // 如果schema不存在,自动创建新的schema
                    Statement statement = targetConnection.createStatement();
                    statement.execute("CREATE SCHEMA " + targetSchemaName);
                    statement.close();
                }
                if (!DuckdbUtil.isTableExists(targetConnection, targetSchemaName, targetTableName))
                {
                    // 如果目标表不存在，自动创新的目标表
                    Statement statement = targetConnection.createStatement();
                    String tableDDL = PGUtil.getTableDDL(sourceConnection, sourceSchemaName, sourceTableName);
                    tableDDL = tableDDL.replace("CREATE TABLE （.*?) (", "CREATE TABLE " + targetSchemaName + "." + targetTableName + "(");
                    statement.execute(tableDDL);
                    statement.close();
                }
            }

            // 每一个任务工作在不同的槽中
            String slotName = connectorTask.getConnector().connectorName.toLowerCase() + "_" + connectorTask.taskName.toLowerCase();

            boolean bInitSyncing = false;

            // 如果任务状态为CREATED或者之前就处于初始同步中，需要同步第一次数据
            if (
                    // 第一次建立或者之前正在同步中
                    (connectorTask.getStatus().equalsIgnoreCase("CREATED")) &&
                            (connectorTask.getStatus().equalsIgnoreCase("INITIAL SYNCING"))
            ) {
                bInitSyncing = true;
            }
            else
            {
                sql = """
                            SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) AS replication_lag
                            FROM pg_replication_slots
                            WHERE slot_name = ?;
                        """;
                pStmt = sourceConnection.prepareStatement(sql);
                pStmt.setString(1, slotName);
                rs = pStmt.executeQuery();
                if (!rs.next())
                {
                    // 复制槽不存在, 需要重新全量同步
                    bInitSyncing = true;
                }
                rs.close();
                stmt.close();
            }

            if (bInitSyncing)
            {
                logger.trace("[POSTGRES-WAL] : Sync worker will do initial syncing ...");

                // 开始第一次同步
                connectorTask.setStatus("INITIAL SYNCING");
                connectorTask.save();
                stmt = sourceConnection.createStatement();

                // 首先删除之前的残留数据槽
                rs = stmt.executeQuery(
                        "SELECT * FROM pg_replication_slots " +
                                "WHERE slot_name = '" + slotName + "'");
                if (rs.next()) {
                    // 存在之前建立的复制槽，将尝试删除
                    stmt.executeQuery("SELECT * FROM pg_drop_replication_slot('" + slotName + "')");
                }
                rs.close();
                stmt.close();

                // 设置隔离模式必须是事务语句中的第一句, 所以这里首先执行commit后再执行
                stmt = sourceConnection.createStatement();
                stmt.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ ONLY, DEFERRABLE");
                // 创建临时数据槽
                rs = stmt.executeQuery("SELECT * FROM pg_create_logical_replication_slot('" + slotName + "', 'wal2json', false)");
                if (rs.next())
                {
                    connectorTask.setLatestLSN(rs.getString("lsn"));
                }
                stmt.close();

                // 开始初始化复制表
                // 使用 PostgresSQL 特有的 COPY 命令执行
                for (Map.Entry<String, String> syncObject: syncObjectMap.entrySet()) {
                    Path tempFile = Files.createTempFile("pg_export_" + connectorTask.taskName, ".csv");

                    // 执行COPY TO命令
                    String copySQL = "COPY " + syncObject.getKey() + " TO STDOUT WITH (FORMAT CSV, HEADER)";
                    CopyManager copyManager = new CopyManager((BaseConnection) sourceConnection);
                    CopyOut copyOut = copyManager.copyOut(copySQL);
                    OutputStream outputStream = new FileOutputStream(tempFile.toFile());

                    byte[] buffer;
                    while ((buffer = copyOut.readFromCopy()) != null) {
                        outputStream.write(buffer);
                    }
                    outputStream.close();

                    // 执行数据加载
                    Statement statement = targetConnection.createStatement();
                    statement.execute("COPY " + syncObject.getValue() + " FROM '" + tempFile.toAbsolutePath() + "'");
                    statement.close();

                    // 删除数据文件
                    var ignored = tempFile.toFile().delete();

                    logger.trace("[POSTGRES-WAL] : Sync worker finished initial syncing.");
                }

                // 标记任务开始进行后续同步...
                connectorTask.setStatus("SYNCING");
                connectorTask.save();
                logger.trace("[POSTGRES-WAL] : Connector slot [{}] has created for sync.", slotName);

                // 提交源数据库连接，避免长期持有
                sourceConnection.commit();
            }

            // 创建一个消息队列，用来异步处理接收到的消息
            embeddedBrokerService.newMessageChannel(connectorTask.taskName, this);

            // 如果任务状态为SYNCING， 需要接收后面的消息，放入消息队列
            if (connectorTask.getStatus().equalsIgnoreCase("SYNCING"))
            {
                logger.trace("[POSTGRES-WAL] : Sync worker start syncing ...");

                String latestlsn = "";
                // noinspection InfiniteLoopStatement
                while (true) {
                    try {
                        int prefetchSize = 5000;
                        boolean bHasWalRecored = false;
                        stmt = sourceConnection.createStatement();
                        rs = stmt.executeQuery(
                                "SELECT * FROM pg_logical_slot_peek_changes('" + slotName + "', NULL, " + prefetchSize + ", " +
                                        "'include-timestamp', '1', 'include-schemas', '1', 'include-pk', '1')");
                        while (rs.next()) {
                            bHasWalRecored = true;
                            latestlsn = rs.getString("lsn");

                            JSONObject changeDataEvent = JSONObject.parseObject(rs.getString("data"));
                            JSONArray changeDataArray = changeDataEvent.getJSONArray("change");
                            for (Object changeRow : changeDataArray) {
                                embeddedBrokerService.sendMessage(connectorTask.taskName, changeRow.toString());
                            }
                        }
                        rs.close();

                        // 推进序列
                        if (bHasWalRecored) {
                            rs = stmt.executeQuery(
                                    "SELECT * FROM pg_replication_slot_advance('" + slotName + "', '" + latestlsn + "')");
                            rs.close();
                        } else {
                            // 没有任何数据需要处理，则休息10秒钟
                            logger.trace("[WALSyncWorker] No more data received. Waiting ... ");
                            Sleeper.sleep(3 * 1000);
                        }
                    }
                    catch (InterruptedException ignored)
                    {
                        Thread.currentThread().interrupt();
                    }
                    catch (SQLException sqlException)
                    {
                        connectorTask.setStatus("ERROR");
                        connectorTask.setErrorMsg(sqlException.getMessage());
                        connectorTask.save();
                        logger.error("[WALSyncWorker] Consume message failed.", sqlException);
                    }
                }
            }
        }
        catch (SQLException | IOException sqlException)
        {
            logger.error("[POSTGRES-WAL] : Connector task [{}] sync fail.", connectorTask, sqlException);
        } catch (JMSException e) {
            throw new RuntimeException(e);
        }
        try { Sleeper.sleep(3 * 1000);} catch (InterruptedException ignored) {}
    }
}
