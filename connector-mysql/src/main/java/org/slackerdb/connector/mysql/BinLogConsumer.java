package org.slackerdb.connector.mysql;

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.utils.RingBuffer;

import java.sql.*;
import java.util.Map;

public class BinLogConsumer extends Thread
{
    public RingBuffer<RowEventData> consumeQueue = new RingBuffer<>(100000);
    private long lastCheckPointInterval = 0L;
    private Logger logger;
    private Connection targetDbConnection;
    private Map<String, TableInfo> tableInfoCacheByName;
    private ConnectorTask connectorTask;

    // 设置日志句柄
    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    // 设置任务句柄
    public void setConnectorTask(ConnectorTask connectorTask)
    {
        this.connectorTask = connectorTask;
    }

    //
    public void setTableInfoCacheByName(Map<String, TableInfo> tableInfoCache)
    {
        this.tableInfoCacheByName = tableInfoCache;
    }

    // 设置目标数据库（DuckDB）的数据库连接
    public void setTargetDbConnection(Connection targetDbConnection)
    {
        this.targetDbConnection = targetDbConnection;
    }

    // 保存目标数据库的检查点
    private void saveCheckPoint() throws SQLException
    {
        this.connectorTask.save();
        this.targetDbConnection.commit();
    }

    // 每个同步任务在一个线程内工作
    @SuppressWarnings("BusyWait")
    @Override
    public void run()
    {
        BinLogEventConsumer binLogEventConsumer = new BinLogEventConsumer();
        binLogEventConsumer.setLogger(logger);

        // 从队列中依次处理所有的消息
        while (true) {
            try {
                // 获取需要处理的事件
                RowEventData rowEventData = null;
                if (this.connectorTask.checkpointInterval != 0)
                {
                    // 如果不是要立即提交，则最多等待到提交的时间点
                    if ((System.currentTimeMillis() - lastCheckPointInterval) > this.connectorTask.checkpointInterval)
                    {
                        // 已经超时，提交当前数据
                        this.saveCheckPoint();
                        // 继续等待
                        try {
                            rowEventData = this.consumeQueue.take(this.connectorTask.checkpointInterval);
                        } catch (InterruptedException ignored) {}
                    }
                    else
                    {
                        try
                        {
                            rowEventData = this.consumeQueue.take(System.currentTimeMillis() - lastCheckPointInterval);
                        } catch (InterruptedException ignored) {}
                    }
                }
                else
                {
                    // 一直等待消息事件，直到有结果
                    try
                    {
                        rowEventData = this.consumeQueue.take();
                    } catch (InterruptedException ignored) {}
                }
                this.lastCheckPointInterval = System.currentTimeMillis();

                // 在有超时的情况下，可能等不到任何消息，直接休息后重复
                if (rowEventData == null)
                {
                    // 没有任何消息需要来完成
                    try {
                        Thread.sleep(1000L);
                    }
                    catch (InterruptedException ignored) {}
                    continue;
                }

                logger.trace("Got rowEventData: {} {}:{}",
                        rowEventData.eventType, rowEventData.binlogFileName , rowEventData.binLogPosition);
                // 处理数据
                TableInfo tableInfo = tableInfoCacheByName.get(rowEventData.sourceSchemaName + "." + rowEventData.sourceTableName);
                switch (rowEventData.eventType)
                {
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
                } // End Case

                // 记录同步的事件点
                this.connectorTask.binlogFileName = rowEventData.binlogFileName;
                this.connectorTask.binLogPosition = rowEventData.binLogPosition;
                this.connectorTask.binlogTimeStamp = rowEventData.binlogTimestamp;

                // 目标数据库提交
                if (this.connectorTask.checkpointInterval == 0)
                {
                    // 提交当前数据
                    this.saveCheckPoint();
                }
            }
            catch (SQLException sqlException)
            {
                logger.error("Sync error.", sqlException);
                break;
            }
        }
    }
}


