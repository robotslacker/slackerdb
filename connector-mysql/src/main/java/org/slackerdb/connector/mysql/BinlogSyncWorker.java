package org.slackerdb.connector.mysql;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.truncate.Truncate;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.connector.mysql.exception.ConnectorException;

import java.io.IOException;
import java.sql.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

public class BinlogSyncWorker extends Thread
{
    private Connector connectorHandler;
    private Logger logger;
    private String binlogFileName;

    private Connection sourceDbConnection;
    private Connection targetDBConnection;

    // 记录源表和目标表的映射关系，避免反复的正则计算
    // SourceSchemaName.SourceTableName, TableInfo
    private final Map<String, TableInfo> tableInfoCacheByName = new HashMap<>();
    // 记录表的ID信息和基础信息之间的关系
    // Table_id, SourceSchemaName.SourceTableName
    // 这些信息只在一个binlog文件内有效，一旦ROTATE，之前的记录就没有了意义
    private final Map<Long, String> tableInfoCacheById = new HashMap<>();
    // 记录所有需要全量同步的数据
    // SourceSchemaName.SourceTableName, FullSyncWorker
    private final TableFullSyncScheduler tableFullSyncScheduler = new TableFullSyncScheduler();

    // 记录每个任务所对应的处理实例
    private final Map<String, BinLogConsumer> binLogConsumerMap = new HashMap<>();

    public void setConnectorHandler(Connector connectorHandler) throws ConnectorException
    {
        try {
            this.sourceDbConnection = connectorHandler.newSourceDbConnection();
            this.targetDBConnection = connectorHandler.newTargetDBConnection();
        }
        catch (SQLException sqlException)
        {
            throw new ConnectorException("Create connection failed. ", sqlException);
        }
        this.connectorHandler = connectorHandler;
    }

    private String migrateMySQLDDLToDuckDDL(
            net.sf.jsqlparser.statement.Statement statement,
            String targetFullyQualifiedName)
    {
        switch (statement.getClass().getSimpleName()) {
            case "Drop":
                Drop drop = (Drop) statement;
                return "DROP " + drop.getType() + " " + targetFullyQualifiedName;
            case "Truncate":
                Truncate truncate = (Truncate) statement;
                String ret = "TRUNCATE TABLE " + targetFullyQualifiedName;
                if (truncate.getCascade())
                {
                    ret = ret + " CASCADE";
                }
                return ret;
            case "CreateTable":
                CreateTable createTable = (CreateTable) statement;
                StringBuilder newCreateTableSql = new StringBuilder("CREATE TABLE " + targetFullyQualifiedName + "(");
                List<ColumnDefinition> columnDefinitions = createTable.getColumnDefinitions();
                for (int i=0; i< columnDefinitions.size(); i++)
                {
                    String columnName = columnDefinitions.get(i).getColumnName();
                    if (columnName.startsWith("`") && columnName.endsWith("`")) {
                        columnName = columnName.substring(1, columnName.length() - 1);
                    }
                    newCreateTableSql.append(columnName);
                    newCreateTableSql.append(" ");
                    newCreateTableSql.append(columnDefinitions.get(i).toStringDataTypeAndSpec());
                    if (i != columnDefinitions.size() - 1 )
                    {
                        newCreateTableSql.append(",");
                    }
                }
                if (createTable.getIndexes() != null && !createTable.getIndexes().isEmpty())
                {
                    List<Index> indexList = createTable.getIndexes();
                    for (Index index : indexList) {
                        if (index.getType().equalsIgnoreCase("primary key")) {
                            newCreateTableSql.append(", primary key (");
                            List<String> indexColumnNames = index.getColumnsNames();
                            for (int i=0; i<indexColumnNames.size(); i++)
                            {
                                if (indexColumnNames.get(i).startsWith("`") && indexColumnNames.get(i).endsWith("`")) {
                                    indexColumnNames.set(i, indexColumnNames.get(i).substring(1, indexColumnNames.get(i).length() - 1));
                                }
                            }
                            newCreateTableSql.append(String.join(",", indexColumnNames)).append(")");
                            break;
                        }
                    }
                }
                newCreateTableSql.append(")");
                return newCreateTableSql.toString();
            default:
                logger.error("what stttt {}", statement.getClass().getSimpleName());
        }
        return "";
    }

    private String generateDuckTemporaryTableDDL(
            net.sf.jsqlparser.statement.Statement statement,
            String targetTableName
    )
    {
        CreateTable createTable = (CreateTable) statement;
        List<ColumnDefinition> columnDefinitions = createTable.getColumnDefinitions();

        StringBuilder newCreateTableSql = new StringBuilder("CREATE OR REPLACE TEMPORARY TABLE " + targetTableName + " (");
        boolean bFirstPrimaryKeyColumn = true;
        for (ColumnDefinition columnDefinition : columnDefinitions) {
            String columnName = columnDefinition.getColumnName();
            if (columnName.startsWith("`") && columnName.endsWith("`")) {
                columnName = columnName.substring(1, columnName.length() - 1);
            }
            if (!bFirstPrimaryKeyColumn) {
                newCreateTableSql.append(",");
            }
            bFirstPrimaryKeyColumn = false;
            newCreateTableSql.append(columnName);
            newCreateTableSql.append(" ");
            newCreateTableSql.append(columnDefinition.toStringDataTypeAndSpec());
        }
        newCreateTableSql.append(")");
        return newCreateTableSql.toString();
    }

    private TableInfo getTableInfo(
            String sourceSchemaName, String sourceTableName,
            net.sf.jsqlparser.statement.Statement parseSQLStatement) throws SQLException
    {
        TableInfo tableInfo;
        // 首先根据表名查找缓存，如果存在，则拿到表的信息，否则重新获得一次
        if (tableInfoCacheByName.containsKey(sourceSchemaName + "." + sourceTableName)) {
            // 如果有缓存，从缓存中获得相关信息
            tableInfo = tableInfoCacheByName.get(sourceSchemaName + "." + sourceTableName);
        }
        else
        {
            // 缓存中不存在这样的数据
            tableInfo = new TableInfo();
            tableInfo.sourceSchemaName = sourceSchemaName;
            tableInfo.sourceTableName = sourceTableName;
            tableInfo.syncTaskName = null;
            if (this.connectorHandler.fullSync)
            {
                tableInfo.fullSyncStatus = true;
            }
            for (ConnectorTask connectorTask : this.connectorHandler.connectorTaskList)
            {
                // 判断任务的归属
                Matcher schemaMatcher = connectorTask.getSourceSchemaPattern().matcher(sourceSchemaName);
                Matcher objectMatcher = connectorTask.getSourceObjectPattern().matcher(sourceTableName);
                if (schemaMatcher.matches() && objectMatcher.matches())
                {
                    tableInfo.targetSchemaName = schemaMatcher.replaceAll(sourceSchemaName);
                    tableInfo.targetTableName = objectMatcher.replaceAll(sourceTableName);
                    tableInfo.syncTaskName = connectorTask.taskName;
                    tableInfo.sourceTableColumns = MysqlUtil.getTableColumns(this.sourceDbConnection, sourceSchemaName, sourceTableName);
                    tableInfo.sourceTablePrimaryKeyColumns = MysqlUtil.getTablePrimaryKeyColumns(this.sourceDbConnection, sourceSchemaName, sourceTableName);
                    if (
                            (parseSQLStatement == null) || (!parseSQLStatement.getClass().getSimpleName().equals("Drop")))
                    {
                        tableInfo.sourceTableDDL = MysqlUtil.getTableDDL(this.sourceDbConnection, sourceSchemaName, sourceTableName);
                        net.sf.jsqlparser.statement.Statement parserDDLStatement;
                        try {
                            parserDDLStatement = CCJSqlParserUtil.parse(tableInfo.sourceTableDDL);
                        } catch (JSQLParserException jsqlParserException) {
                            throw new RuntimeException(
                                    "Analyze QueryEvent error: " + tableInfo.sourceTableDDL + " " + jsqlParserException);
                        }
                        tableInfo.targetTableDDL = migrateMySQLDDLToDuckDDL(parserDDLStatement, tableInfo.targetSchemaName + "." + tableInfo.targetTableName);
                        tableInfo.targetTemporaryTableDDL = generateDuckTemporaryTableDDL(parserDDLStatement, tableInfo.targetTableName + "_$$");

                        // 如果目标表不存在，则需要创建该表，并做全量同步
                        if (!DuckdbUtil.isTableExists(this.targetDBConnection, tableInfo.targetSchemaName, tableInfo.targetTableName))
                        {
                            logger.trace("[MYSQL-BINLOG] Target {}.{} does not exist, will create then full sync it.", tableInfo.targetSchemaName, tableInfo.targetTableName);
                            Statement statement = this.targetDBConnection.createStatement();
                            statement.execute(tableInfo.targetTableDDL);
                            statement.close();
                            tableInfo.fullSyncStatus = true;
                            // 需要提交事务来保证后续数据的消费
                            this.targetDBConnection.commit();
                        }
                    }
                    break;
                }
            }

            // 放入缓存中，省的下次查找
            tableInfoCacheByName.put(sourceSchemaName + "." + sourceTableName, tableInfo);
        }
        return tableInfo;
    }

    private void processBinaryLogEvent(Event event)
    {
        logger.trace("[MYSQL-BINLOG] Received BinLogEvent {} {}", event.getHeader().getEventType(), event.getData().toString());
        try {
            switch (event.getHeader().getEventType()) {
                case FORMAT_DESCRIPTION, GTID, XID, PREVIOUS_GTIDS -> // 暂时不处理
                        logger.trace("[MYSQL-BINLOG] Skip event {}.", event.getHeader().getEventType());
                case ROTATE -> {
                    RotateEventData rotateEventData = event.getData();

                    // 记录新的binlog文件名称
                    this.binlogFileName = rotateEventData.getBinlogFilename();
                    // 必须等当前所有事件都消费完毕（包括涉及到的全量同步），否则无法跳转到下一个binlog文件
                    while (true) {
                        if (!tableFullSyncScheduler.tableFullSyncWorkers.isEmpty())
                        {
                            logger.info("[MYSQL-BINLOG] Binlog rotate is waiting full sync thread ... {}:{}",
                                    rotateEventData.getBinlogFilename(), tableFullSyncScheduler.tableFullSyncWorkers.size());
                            Sleeper.sleep(1000L);
                            continue;
                        }
                        int queueSize = 0;
                        for (BinLogConsumer binLogConsumer : this.binLogConsumerMap.values()) {
                            queueSize = queueSize + binLogConsumer.consumeQueue.size();
                        }
                        if (queueSize == 0)
                        {
                            logger.info("[MYSQL-BINLOG] Binlog has switched to {}.", rotateEventData.getBinlogFilename());
                            break;
                        }
                        else
                        {
                            logger.info("[MYSQL-BINLOG] Binlog rotate is waiting consume thread ... {}:{}",
                                    rotateEventData.getBinlogFilename(), queueSize);
                            Sleeper.sleep(1000L);
                        }
                    }
                }
                case QUERY -> {
                    QueryEventData queryEventData = event.getData();
                    if (queryEventData.getSql().equals("BEGIN")) {
                        // 暂时不处理
                        break;
                    }
                    net.sf.jsqlparser.statement.Statement statement;
                    try {
                        statement = CCJSqlParserUtil.parse(queryEventData.getSql());
                    } catch (JSQLParserException jsqlParserException) {
                        throw new RuntimeException(
                                "Analyze QueryEvent error: " + event + jsqlParserException);
                    }

                    String sourceSchema = queryEventData.getDatabase();
                    String sourceObject;
                    switch (statement.getClass().getSimpleName()) {
                        case "Drop" -> {
                            Drop drop = (Drop) statement;
                            sourceObject = drop.getName().getName();
                            if (sourceObject.startsWith("`") && sourceObject.endsWith("`")) {
                                sourceObject = sourceObject.substring(1, sourceObject.length() - 1);
                            }
                        }
                        case "CreateTable" -> {
                            CreateTable createTable = (CreateTable) statement;
                            sourceObject = createTable.getTable().getName();
                            if (sourceObject.startsWith("`") && sourceObject.endsWith("`")) {
                                sourceObject = sourceObject.substring(1, sourceObject.length() - 1);
                            }
                        }
                        case "Truncate" -> {
                            Truncate truncate = (Truncate) statement;
                            sourceObject = truncate.getTable().getName();
                            if (sourceObject.startsWith("`") && sourceObject.endsWith("`")) {
                                sourceObject = sourceObject.substring(1, sourceObject.length() - 1);
                            }
                        }
                        default -> throw new RuntimeException("xxxxx " + statement.getClass().getSimpleName());
                    }

                    // 获取表的基本信息
                    TableInfo tableInfo = this.getTableInfo(sourceSchema, sourceObject, statement);
                    if (tableInfo.syncTaskName == null)
                    {
                        // 这个表不需要同步
                        logger.trace("[MYSQL-BINLOG] Skip table {}.{}, does not match sync rule.", tableInfo.sourceSchemaName, tableInfo.sourceTableName);
                        return;
                    }
                    RowEventData rowEventData = new RowEventData();
                    switch (statement.getClass().getSimpleName()) {
                        case "Drop" -> {
                            // 不在同步的范围内
                            String newDDLScript = migrateMySQLDDLToDuckDDL(statement,
                                    tableInfo.targetSchemaName + "." + tableInfo.targetTableName);

                            // 放入消费队列中
                            rowEventData.sourceSchemaName = sourceSchema;
                            rowEventData.sourceTableName = sourceObject;
                            rowEventData.eventType = "DDL-DROP";
                            rowEventData.eventSql = newDDLScript;
                            rowEventData.binlogFileName = this.binlogFileName;
                            rowEventData.binLogPosition = ((EventHeaderV4) event.getHeader()).getNextPosition();
                            rowEventData.binlogTimestamp = event.getHeader().getTimestamp();
                        }
                        case "CreateTable" -> {
                            // 由于数据库的不同，DDL写法不一致，需要重写
                            String targetFullyQualifiedName =
                                    tableInfo.targetSchemaName + "." + tableInfo.targetTableName;
                            String newDDLScript = migrateMySQLDDLToDuckDDL(statement, targetFullyQualifiedName);
                            // 放入消息队列中
                            rowEventData.eventType = "DDL-CREATE";
                            rowEventData.eventSql = newDDLScript;
                            rowEventData.sourceSchemaName = sourceSchema;
                            rowEventData.sourceTableName = sourceObject;
                            rowEventData.binlogFileName = this.binlogFileName;
                            rowEventData.binLogPosition = ((EventHeaderV4) event.getHeader()).getNextPosition();
                            rowEventData.binlogTimestamp = event.getHeader().getTimestamp();
                        }
                        case "Truncate" -> {
                            // 由于数据库的不同，DDL写法不一致，需要重写
                            String targetFullyQualifiedName =
                                    tableInfo.targetSchemaName + "." + tableInfo.targetTableName;
                            String newDDLScript = migrateMySQLDDLToDuckDDL(statement, targetFullyQualifiedName);
                            // 放入消息队列中
                            rowEventData.eventType = "DDL-TRUNCATE";
                            rowEventData.eventSql = newDDLScript;
                            rowEventData.sourceSchemaName = sourceSchema;
                            rowEventData.sourceTableName = sourceObject;
                            rowEventData.binlogFileName = this.binlogFileName;
                            rowEventData.binLogPosition = ((EventHeaderV4) event.getHeader()).getNextPosition();
                            rowEventData.binlogTimestamp = event.getHeader().getTimestamp();
                        }
                    }

                    if (tableFullSyncScheduler.tableFullSyncWorkers.containsKey(sourceSchema + "." + sourceObject))
                    {
                        // 如果这个表已经正在全量同步
                        TableFullSyncWorker tableFullSyncWorker = tableFullSyncScheduler.tableFullSyncWorkers.get(sourceSchema + "." + sourceObject);
                        // 无论这个表之前的状态是什么，只要收到DDL命令，之前的全量同步都已经毫无意义，需要停止
                        logger.trace("[MYSQL-BINLOG] Will abort full sync {}.{}/{}, because new DDL has came.",
                                tableInfo.sourceTableId, tableInfo.sourceTableName, tableInfo.sourceTableId
                                );
                        tableFullSyncWorker.abort();
                        tableFullSyncScheduler.tableFullSyncWorkers.remove(sourceSchema + "." + sourceObject);
                        tableInfoCacheById.put(tableInfo.sourceTableId, sourceSchema + "." + sourceObject);
                        tableInfoCacheByName.put(tableInfo.sourceSchemaName + "." + tableInfo.sourceTableName, tableInfo);
                        this.binLogConsumerMap.get(tableInfo.syncTaskName).consumeQueue.put(rowEventData);
                    }
                    else
                    {
                        // 推给消费者
                        this.binLogConsumerMap.get(tableInfo.syncTaskName).consumeQueue.put(rowEventData);
                    }
                }
                case TABLE_MAP -> {
                    // 缓存记录的tableId和表名
                    TableMapEventData tableMapEventData = event.getData();
                    String sourceSchema = tableMapEventData.getDatabase();
                    String sourceObject = tableMapEventData.getTable();

                    // 获取表的基本信息
                    TableInfo tableInfo = this.getTableInfo(sourceSchema, sourceObject, null);
                    if (tableInfo.syncTaskName == null)
                    {
                        // 这个表不需要同步
                        logger.trace("[MYSQL-BINLOG] Skip table {}.{}, does not match sync rule.",
                                tableInfo.sourceSchemaName, tableInfo.sourceTableName);
                        return;
                    }

                    RowEventData rowEventData = new RowEventData();
                    rowEventData.sourceSchemaName = tableMapEventData.getDatabase();
                    rowEventData.sourceTableName = tableMapEventData.getTable();
                    rowEventData.tableId = tableMapEventData.getTableId();
                    rowEventData.eventType = "TABLE_MAP";
                    rowEventData.binlogFileName = this.binlogFileName;
                    rowEventData.binLogPosition = ((EventHeaderV4)event.getHeader()).getNextPosition();
                    rowEventData.binlogTimestamp = event.getHeader().getTimestamp();
                    if (tableFullSyncScheduler.tableFullSyncWorkers.containsKey(sourceSchema + "." + sourceObject))
                    {
                        TableFullSyncWorker tableFullSyncWorker = tableFullSyncScheduler.tableFullSyncWorkers.get(sourceSchema + "." + sourceObject);
                        // 全量同步还没有完成，暂时无法处理该事件，积压到队列中处理
                        tableFullSyncWorker.pushRowEvent(rowEventData);
                    }
                    else
                    {
                        if (tableInfo.fullSyncStatus)
                        {
                            // 在新的线程内部完成全量同步
                            TableFullSyncWorker tableFullSyncWorker = new TableFullSyncWorker();
                            tableFullSyncWorker.sourceDbConnection = this.connectorHandler.newSourceDbConnection();
                            tableFullSyncWorker.targetDbConnection = this.connectorHandler.newTargetDBConnection();
                            tableFullSyncWorker.setLogger(this.logger);
                            tableFullSyncWorker.connectorHandler = this.connectorHandler;
                            tableFullSyncWorker.tableInfo = tableInfo;
                            tableFullSyncScheduler.addWorker(sourceSchema + "." + sourceObject, tableFullSyncWorker);
                            // 本次事件也放到消费队列中去完成
                            tableFullSyncWorker.pushRowEvent(rowEventData);
                        }
                        // 更新缓存信息
                        tableInfo.sourceTableId = tableMapEventData.getTableId();
                        tableInfoCacheById.put(tableInfo.sourceTableId, sourceSchema + "." + sourceObject);
                    }
                }
                case EXT_DELETE_ROWS -> {
                    DeleteRowsEventData deleteRowsEventData = event.getData();

                    String tableFullName = tableInfoCacheById.get(deleteRowsEventData.getTableId());
                    if (tableFullName == null)
                    {
                        logger.error("[MYSQL-BINLOG] unknown table id {}", deleteRowsEventData.getTableId());
                        throw new RuntimeException("xxxxx ");
                    }
                    TableInfo tableInfo = tableInfoCacheByName.get(tableFullName);
                    if (tableInfo == null)
                    {
                        logger.error("[MYSQL-BINLOG] unknown table id {}", deleteRowsEventData.getTableId());
                        throw new RuntimeException("xxxxx ");
                    }
                    if (tableInfo.syncTaskName == null)
                    {
                        // 不需要同步
                        return;
                    }

                    RowEventData rowEventData = new RowEventData();
                    rowEventData.sourceSchemaName = tableInfo.sourceSchemaName;
                    rowEventData.sourceTableName = tableInfo.sourceTableName;
                    rowEventData.tableId = deleteRowsEventData.getTableId();
                    rowEventData.eventType = "DELETE";
                    rowEventData.rows = deleteRowsEventData.getRows();
                    rowEventData.binlogFileName = this.binlogFileName;
                    rowEventData.binLogPosition = ((EventHeaderV4)event.getHeader()).getNextPosition();
                    rowEventData.binlogTimestamp = event.getHeader().getTimestamp();

                    if (tableFullSyncScheduler.tableFullSyncWorkers.containsKey(tableInfo.sourceSchemaName + "." + tableInfo.sourceTableName))
                    {
                        TableFullSyncWorker tableFullSyncWorker =
                                tableFullSyncScheduler.tableFullSyncWorkers.get(tableInfo.sourceSchemaName + "." + tableInfo.sourceTableName);
                        // 全量同步还没有完成，暂时无法处理该事件，积压到队列中处理
                        tableFullSyncWorker.pushRowEvent(rowEventData);
                    }
                    else
                    {
                        // 推给消费者
                        this.binLogConsumerMap.get(tableInfo.syncTaskName).consumeQueue.put(rowEventData);
                    }
                }
                case EXT_UPDATE_ROWS -> {
                    UpdateRowsEventData updateRowsEventData = event.getData();

                    String tableFullName = tableInfoCacheById.get(updateRowsEventData.getTableId());
                    if (tableFullName == null)
                    {
                        logger.error("[MYSQL-BINLOG] unknown table id {}", updateRowsEventData.getTableId());
                        throw new RuntimeException("xxxxx ");
                    }
                    TableInfo tableInfo = tableInfoCacheByName.get(tableFullName);
                    if (tableInfo == null)
                    {
                        logger.error("[MYSQL-BINLOG] unknown table id {}", updateRowsEventData.getTableId());
                        throw new RuntimeException("xxxxx ");
                    }
                    if (tableInfo.syncTaskName == null)
                    {
                        // 不需要同步
                        return;
                    }

                    RowEventData rowEventData = new RowEventData();
                    rowEventData.sourceSchemaName = tableInfo.sourceSchemaName;
                    rowEventData.sourceTableName = tableInfo.sourceTableName;
                    rowEventData.tableId = updateRowsEventData.getTableId();
                    rowEventData.eventType = "UPDATE";
                    rowEventData.updateRows = updateRowsEventData.getRows();
                    rowEventData.binlogFileName = this.binlogFileName;
                    rowEventData.binLogPosition = ((EventHeaderV4)event.getHeader()).getNextPosition();
                    rowEventData.binlogTimestamp = event.getHeader().getTimestamp();

                    if (tableFullSyncScheduler.tableFullSyncWorkers.containsKey(tableInfo.sourceSchemaName + "." + tableInfo.sourceTableName))
                    {
                        TableFullSyncWorker tableFullSyncWorker =
                                tableFullSyncScheduler.tableFullSyncWorkers.get(tableInfo.sourceSchemaName + "." + tableInfo.sourceTableName);
                        // 全量同步还没有完成，暂时无法处理该事件，积压到队列中处理
                        tableFullSyncWorker.pushRowEvent(rowEventData);
                    }
                    else
                    {
                        // 推给消费者
                        this.binLogConsumerMap.get(tableInfo.syncTaskName).consumeQueue.put(rowEventData);
                    }
                }
                case EXT_WRITE_ROWS -> {
                    WriteRowsEventData writeRowsEventData = event.getData();

                    String tableFullName = tableInfoCacheById.get(writeRowsEventData.getTableId());
                    if (tableFullName == null)
                    {
                        logger.error("[MYSQL-BINLOG] unknown table id {}", writeRowsEventData.getTableId());
                        throw new RuntimeException("xxxxx ");
                    }
                    TableInfo tableInfo = tableInfoCacheByName.get(tableFullName);
                    if (tableInfo == null)
                    {
                        logger.error("[MYSQL-BINLOG] unknown table id {}", writeRowsEventData.getTableId());
                        throw new RuntimeException("xxxxx ");
                    }
                    if (tableInfo.syncTaskName == null)
                    {
                        // 不需要同步
                        return;
                    }

                    RowEventData rowEventData = new RowEventData();
                    rowEventData.sourceSchemaName = tableInfo.sourceSchemaName;
                    rowEventData.sourceTableName = tableInfo.sourceTableName;
                    rowEventData.targetSchemaName = tableInfo.targetSchemaName;
                    rowEventData.targetTableName = tableInfo.targetTableName;
                    rowEventData.tableId = writeRowsEventData.getTableId();
                    rowEventData.eventType = "INSERT";
                    rowEventData.rows = writeRowsEventData.getRows();
                    rowEventData.binlogFileName = this.binlogFileName;
                    rowEventData.binLogPosition = ((EventHeaderV4)event.getHeader()).getNextPosition();
                    rowEventData.binlogTimestamp = event.getHeader().getTimestamp();

                    if (tableFullSyncScheduler.tableFullSyncWorkers.containsKey(tableInfo.sourceSchemaName + "." + tableInfo.sourceTableName))
                    {
                        TableFullSyncWorker tableFullSyncWorker =
                                tableFullSyncScheduler.tableFullSyncWorkers.get(tableInfo.sourceSchemaName + "." + tableInfo.sourceTableName);
                        // 全量同步还没有完成，暂时无法处理该事件，积压到队列中处理
                        tableFullSyncWorker.pushRowEvent(rowEventData);
                    }
                    else
                    {
                        // 推给消费者
                        this.binLogConsumerMap.get(tableInfo.syncTaskName).consumeQueue.put(rowEventData);
                    }
                }
                default -> {
                    logger.error(" TODO::: {}", event.getHeader().getEventType());
                    logger.error(" TODO::: {}", event);
                }
            }
        }
        catch (SQLException sqlException)
        {
            logger.error("process binlog event errror ", sqlException);
        } catch (Exception e) {
            logger.error("process binlog event errror 22222", e);
            throw new RuntimeException(e);
        }
    }

    public void shutdown()
    {
        BinaryLogClient binaryLogClient = connectorHandler.binlogClient;
        try {
            binaryLogClient.disconnect();
        } catch (IOException ioe)
        {
            logger.warn("disconnect error.", ioe);
        }
    }

    @Override
    public void run()
    {
        // 禁用控制台上的无用Logger信息
        if (!this.logger.getLevel().equals(Level.TRACE)) {
            java.util.logging.Logger binlogLogger = java.util.logging.Logger.getLogger("com.github.shyiko.mysql.binlog");
            if (binlogLogger != null) {
                binlogLogger.setLevel(java.util.logging.Level.OFF);
            }
        }

        // 添加事件处理回调函数
        BinaryLogClient binaryLogClient = connectorHandler.binlogClient;
        binaryLogClient.registerEventListener(this::processBinaryLogEvent);

        // 启动新的线程用来检查全量同步的状态，并维护全量同步的信息
        tableFullSyncScheduler.setLogger(logger);
        tableFullSyncScheduler.start();

        try {
            // 处理逻辑为一个生产者，负责监听消息，若干消费者负责处理消息
            // 每个任务启用一个消费者
            for (ConnectorTask connectorTask : this.connectorHandler.connectorTaskList) {
                BinLogConsumer binLogConsumer = new BinLogConsumer();
                binLogConsumer.setLogger(this.logger);
                binLogConsumer.setConnectorTask(connectorTask);
                binLogConsumer.setTargetDbConnection(this.connectorHandler.newTargetDBConnection());
                binLogConsumer.setTableInfoCacheByName(this.tableInfoCacheByName);
                binLogConsumer.start();
                // 缓存消费者句柄
                this.binLogConsumerMap.put(connectorTask.taskName, binLogConsumer);
            }
        }
        catch (SQLException sqlException)
        {
            throw new RuntimeException("start consumer failed." + sqlException);
        }

        // 连接
        try {
            binaryLogClient.connect();
        }
        catch (IOException ioe)
        {
            logger.error("Fatal connect error: ", ioe);
        }
    }

    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }
}
