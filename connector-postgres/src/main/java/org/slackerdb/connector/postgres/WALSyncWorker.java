package org.slackerdb.connector.postgres;

import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.jms.JMSException;
import org.duckdb.DuckDBConnection;
import org.postgresql.copy.CopyOut;
import org.slackerdb.common.utils.DBUtil;
import org.slackerdb.common.utils.Sleeper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

public class WALSyncWorker extends Thread implements IWALMessage{
    private final ConnectorTask connectorTask;
    private Connection sourceConnection;
    private Connection targetConnection;
    private final Logger logger;
    private final ConcurrentHashMap<String, String> syncObjectMap = new ConcurrentHashMap<>();
    private final EmbeddedActiveMQ embeddedBrokerService;

    public WALSyncWorker(ConnectorTask connectorTask, EmbeddedActiveMQ embeddedBrokerService)
    {
        this.connectorTask = connectorTask;
        this.logger = connectorTask.getLogger();
        this.embeddedBrokerService = embeddedBrokerService;
    }

    @Override
    public void consumeMessage(String textMessage)
    {
        System.out.println("HaHa " + textMessage);
    }

    @Override
    public void run()
    {
        // 连接源数据库
        try {
            // 获取数据源的连接
            sourceConnection = DBUtil.getJdbcConnection(this.connectorTask.getConnector().getConnectorURL());
            targetConnection = ((DuckDBConnection)this.connectorTask.getConnector().getTargetDBConnection()).duplicate();
            String slotName = connectorTask.getConnector().connectorName.toLowerCase() + "_" + connectorTask.taskName.toLowerCase();

            // 从数据字典中检索需要同步的对象清单，并构建映射关系
            List<String> schemaList = new ArrayList<>();
            Statement stmt = sourceConnection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT nspname FROM pg_namespace");
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
                        embeddedBrokerService.newMessageChannel(schemaName + "." + rs.getString("tablename"), this);
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

            // 如果任务状态为CREATED，表示这是第一次创建，需要建立复制槽
            if (connectorTask.getStatus().equalsIgnoreCase("CREATED")) {
                logger.trace(
                        "[POSTGRES-WAL] : Connector task [{}] first start, will create new replication slot ...",
                        connectorTask.taskName);
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
                }

                // 标记任务开始进行后续同步...
                connectorTask.setStatus("SYNCING");
                connectorTask.save();
                logger.trace("[POSTGRES-WAL] : Connector slot [{}] has created.", slotName);

                // 提交源数据库连接，避免长期持有
                sourceConnection.commit();
            }

            // 如果任务状态为SYNCING， 需要接收后面的消息，放入消息队列
            if (connectorTask.getStatus().equalsIgnoreCase("SYNCING"))
            {
                String latestlsn = "";
                while (true) {
                    int prefetchSize = 5000;
                    boolean bHasWalRecored =false;
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
                            String tableName = ((JSONObject) changeRow).getString("schema") + "." +
                                    ((JSONObject) changeRow).getString("table");
                            embeddedBrokerService.sendMessage(tableName, changeRow.toString());
                        }
                        try {
                            Thread.sleep(3 * 1000);
                        } catch (InterruptedException ignored) {}

                    }
                    rs.close();

                    // 推进序列
                    if (bHasWalRecored) {
                        rs = stmt.executeQuery(
                                "SELECT * FROM pg_replication_slot_advance('" + slotName + "', '" + latestlsn + "')");
                        rs.close();
                    }
                    else
                    {
                        try {
                            Thread.sleep(3 * 1000);
                        } catch (InterruptedException ignored) {}
                    }
                }
//                while (rs.next()) {
//                    String latestLsn = rs.getString("lsn");
//                    JSONObject changeDataEvent = JSONObject.parseObject(rs.getString("data"));
//                    String latestTimeStamp = changeDataEvent.getString("timestamp");
//                    JSONArray changeDataArray = changeDataEvent.getJSONArray("change");
//                    for (Object changeRow : changeDataArray) {
//                        String eventKind = ((JSONObject) changeRow).getString("kind");
//                        String tableName = ((JSONObject) changeRow).getString("schema") + "." +
//                                ((JSONObject) changeRow).getString("table");
//                        if (syncObjectMap.containsKey(tableName))
//                        {
//                            SyncBaseHandler cdcSyncHandler = cdcSyncMap.get(tableName);
//                            if (eventKind.equals("update") || eventKind.equals("delete")) {
//                                if (cdcSyncHandler.getPrimaryKeyColumns().size() == 0) {
//                                    // 在没有主键的情况下，没法处理update和delete事件，跳过
//                                    continue;
//                                }
//                            }
//                            cdcSyncHandler.pushEvent((JSONObject) changeRow);
//                            processRowCount = processRowCount + 1;
//                        }
//                    }
//                }
//                rs.close();
            }

            int processRowCount = 0;
            int processEventCount = 0;


        }
        catch (SQLException | IOException sqlException)
        {
            logger.error("[POSTGRES-WAL] : Connector task [{}] sync fail", connectorTask, sqlException);
        } catch (JMSException e) {
            throw new RuntimeException(e);
        }
        try {
            Sleeper.sleep(30000);
        }
        catch (InterruptedException ignored) {}
    }
}
