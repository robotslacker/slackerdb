package org.slackerdb.connector.mysql;

import ch.qos.logback.classic.Logger;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slackerdb.common.utils.RingBuffer;
import org.slackerdb.common.utils.Sleeper;

import java.sql.*;

public class TableFullSyncWorker extends Thread
{
    public Connection   sourceDbConnection;
    public Connection   targetDbConnection;
    public TableInfo    tableInfo;
    public Connector    connectorHandler;
    public RingBuffer<RowEventData>  cachedBinLogEvents = new RingBuffer<>(100000);
    public Exception    syncException = null;
    public boolean      syncCompleted = false;
    private boolean     abortSync = false;
    private boolean     abortCompleted = false;
    private final BinLogEventConsumer binLogEventConsumer = new BinLogEventConsumer();
    private String binlogFile;
    private long binlogPosition;

    private Logger logger;
    public void setLogger(Logger logger)
    {
        this.logger = logger;
        binLogEventConsumer.setLogger(logger);
    }

    public void pushRowEvent(RowEventData rowEventData)
    {
        cachedBinLogEvents.put(rowEventData);
    }

    public void abort()
    {
        this.abortSync = true;
        while (!abortCompleted)
        {
            try {
                Sleeper.sleep(500);
            }
            catch (InterruptedException ignored) {}
        }
    }

    // 消费之前积压的消息队列
    public void consumeEventQueue() throws SQLException
    {
        while (true) {
            try {
                RowEventData rowEventData = cachedBinLogEvents.take(0);
                if (rowEventData == null) {
                    // 积压的消息队列已经不包含任何数据了
                    break;
                }
                if (
                        (rowEventData.binlogFileName.compareTo(binlogFile) < 0) ||
                                ((rowEventData.binlogFileName.equals(binlogFile) && rowEventData.binLogPosition < binlogPosition))
                ) {
                    continue;
                }
                switch (rowEventData.eventType) {
                    case "DDL-DROP":
                        binLogEventConsumer.consumeDDLDropEvent(targetDbConnection, rowEventData);
                        break;
                    case "DDL-TRUNCATE":
                        binLogEventConsumer.consumeDDLTruncateEvent(targetDbConnection, rowEventData);
                        break;
                    case "DDL-CREATE":
                        binLogEventConsumer.consumeDDLCreateEvent(targetDbConnection, rowEventData);
                        break;
                    case "INSERT":
                        binLogEventConsumer.consumeDMLInsertEvent(targetDbConnection, rowEventData, tableInfo);
                        break;
                    case "UPDATE":
                        binLogEventConsumer.consumeDMLUpdateEvent(targetDbConnection, rowEventData, tableInfo);
                        break;
                    case "DELETE":
                        binLogEventConsumer.consumeDMLDeleteEvent(targetDbConnection, rowEventData, tableInfo);
                        break;
                }
            }
            catch (InterruptedException ignored)
            {
                break;
            }
            // End Case
        }
    }

    public void saveCheckPoint() throws SQLException
    {
        // 删除全量同步信息，以确保下次重启后仍然继续全量同步该表数据
        if (this.connectorHandler.saveCheckPoint) {
            String sql = """
                        DELETE  FROM sysaux.connector_mysql_pending_tables
                        Where   connectorName = ?
                        And     sourceSchema = ?
                        And     sourceTable = ?
                        """;
            PreparedStatement preparedStatement = this.targetDbConnection.prepareStatement(sql);
            preparedStatement.setString(1, this.connectorHandler.connectorName);
            preparedStatement.setString(2, tableInfo.sourceSchemaName);
            preparedStatement.setString(3, tableInfo.sourceTableName);
            preparedStatement.execute();
            preparedStatement.close();
            this.targetDbConnection.commit();
        }
    }

    @Override
    public void run()
    {
        try {
            Thread.currentThread().setName(this.getClass().getSimpleName());
            logger.trace("Start full sync (pull) for [{}.{}]/{} ...",
                    tableInfo.sourceSchemaName, tableInfo.sourceTableName, tableInfo.sourceTableId);

            // 记录当前表正在同步中，以确保下次重启后仍然继续全量同步该表数据
            if (this.connectorHandler.saveCheckPoint) {
                String sql = "Insert Into sysaux.connector_mysql_pending_tables (connectorName, sourceSchema, sourceTable) values (?,?,?) ";
                PreparedStatement preparedStatement = this.targetDbConnection.prepareStatement(sql);
                preparedStatement.setString(1, this.connectorHandler.connectorName);
                preparedStatement.setString(2, tableInfo.sourceSchemaName);
                preparedStatement.setString(3, tableInfo.sourceTableName);
                preparedStatement.execute();
                preparedStatement.close();
                this.targetDbConnection.commit();
            }
            // 用一致性快照的方式来读取数据表
            this.sourceDbConnection.setAutoCommit(false);
            Statement statement = this.sourceDbConnection.createStatement();
            statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");

            // 获取当前 Binlog 位点
            ResultSet rs = statement.executeQuery("SHOW MASTER STATUS");
            rs.next();
            binlogFile = rs.getString("File");
            binlogPosition = rs.getLong("Position");
            rs.close();

            // 检查目标表是否存在，如果不存在，则先创建表; 如果存在，也要清空
            Statement targetStmt = this.targetDbConnection.createStatement();
            if (!DuckdbUtil.isTableExists(this.targetDbConnection,tableInfo.targetSchemaName, tableInfo.targetTableName)) {
                targetStmt.execute(tableInfo.targetTableDDL);
            }
            else
            {
                targetStmt.execute("TRUNCATE TABLE " + tableInfo.targetSchemaName + "." + tableInfo.targetTableName);
            }
            targetStmt.close();

            // 一次性读取所有的数据
            long ret = 0L;
            String querySQL = "SELECT * FROM " + tableInfo.sourceSchemaName + "." + tableInfo.sourceTableName;
            rs = statement.executeQuery(querySQL);
            DuckDBAppender duckDBAppender = new DuckDBAppender(
                    (DuckDBConnection) this.targetDbConnection,
                    tableInfo.targetSchemaName, tableInfo.targetTableName);
            while (rs.next()) {
                if (abortSync)
                {
                    this.syncCompleted = false;
                    this.abortCompleted = true;
                    this.cachedBinLogEvents.clear();
                    return;
                }
                duckDBAppender.beginRow();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    switch (rs.getMetaData().getColumnTypeName(i)) {
                        case "INT":
                            duckDBAppender.append(rs.getInt(i));
                            break;
                        case "VARCHAR":
                            duckDBAppender.append(rs.getString(i));
                            break;
                        default:
                            throw new RuntimeException("TODO::: unknown column type: " + rs.getMetaData().getColumnTypeName(i));
                    }
                }
                duckDBAppender.endRow();
                ret = ret + 1;
            }
            rs.close();

            // 结束一致性快照
            this.sourceDbConnection.commit();
            logger.trace("End full sync (pull) for {}.{}.", tableInfo.sourceSchemaName, tableInfo.sourceTableName);

            // 消费掉在同步过程中产生的新数据，追加同步
            this.consumeEventQueue();

            this.syncException = null;
            this.syncCompleted = true;
        }
        catch (SQLException sqlException)
        {
            logger.error("[MYSQL-BINLOG] full sync table error. [{}.{}]/{} ...",
                    tableInfo.sourceSchemaName, tableInfo.sourceTableName, tableInfo.sourceTableId,
                    sqlException);
            this.syncException = sqlException;
        }
        finally {
            try {
                this.sourceDbConnection.close();
            } catch (SQLException ignored) {}
        }
        logger.trace("End full sync (update) for {}.{}/{}.",
                tableInfo.sourceSchemaName, tableInfo.sourceTableName, tableInfo.sourceTableId);
    }
}

