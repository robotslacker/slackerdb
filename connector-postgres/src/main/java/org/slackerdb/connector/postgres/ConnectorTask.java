package org.slackerdb.connector.postgres;

import ch.qos.logback.classic.Logger;
import org.duckdb.DuckDBConnection;
import org.slackerdb.connector.postgres.Connector;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ConnectorTask {
    private Connector       connector;
    private Connection      conn;
    public  String          taskName;
    public  long            pullInterval = 0; // wal的刷新时间间隔
    private String          sourceSchemaRule;
    private String          sourceTableRule;
    private String          targetSchemaRule;
    private String          targetTableRule;
    private String          latestLSN;
    private String          status;
    private String          errorMsg;
    private LocalDateTime   updateTime;
    private WALSyncWorker   walSyncWorker;
    private Logger          logger;

    private ConnectorTask() {}

    public Logger getLogger()
    {
        return this.logger;
    }

    public Connector getConnector()
    {
        return connector;
    }

    public String  getStatus()
    {
        return this.status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public void setLatestLSN(String latestLSN)
    {
        this.latestLSN = latestLSN;
    }

    public static ConnectorTask newTask(
            Connector connector,
            String taskName,
            String sourceSchemaRule,
            String sourceTableRule,
            String targetSchemaRule,
            String targetTableRule,
            int    pullInterval
    ) throws SQLException {
        ConnectorTask connectorTask = new ConnectorTask();

        // 返回一个任务实例
        connectorTask.connector = connector;
        connectorTask.taskName = taskName;
        connectorTask.sourceSchemaRule = sourceSchemaRule;
        connectorTask.sourceTableRule = sourceTableRule;
        connectorTask.targetSchemaRule = targetSchemaRule;
        connectorTask.targetTableRule = targetTableRule;
        connectorTask.pullInterval = pullInterval;

        String sql = """
                Insert into sysaux.connector_postgres_task
                (
                    taskName, connectorName, pullInterval,
                    sourceSchemaRule, sourceTableRule,
                    targetSchemaRule, targetTableRule,
                    status, updateTime
                )
                Values( ?,?,?, ?,?,?,?, 'CREATED', now())
                """;
        PreparedStatement pStmt = connector.getTargetDBConnection().prepareStatement(sql);
        pStmt.setString(1, taskName);
        pStmt.setString(2, connector.connectorName);
        pStmt.setInt(3, pullInterval);
        pStmt.setString(4, sourceSchemaRule);
        pStmt.setString(5, sourceTableRule);
        pStmt.setString(6, targetSchemaRule);
        pStmt.setString(7, targetTableRule);
        pStmt.execute();
        pStmt.close();

        // 创建同步线程
        connectorTask.conn = ((DuckDBConnection)connector.getTargetDBConnection()).duplicate();
        connectorTask.logger = connector.getLogger();
        connectorTask.walSyncWorker = new WALSyncWorker(connectorTask);
        return connectorTask;
    }

    public static ConnectorTask loadTask(
            Connector connector,
            String taskName
    ) throws SQLException {
        ConnectorTask connectorTask = new ConnectorTask();
        connectorTask.connector = connector;

        String sql = """
                SELECT * FROM sysaux.connector_postgres_task
                Where  connectorName = ? And taskName = ?
                """;
        try (PreparedStatement pStmt = connector.getTargetDBConnection().prepareStatement(sql)) {
            pStmt.setString(1, connector.connectorName);
            pStmt.setString(2, taskName);
            try (ResultSet rs = pStmt.executeQuery()) {
                ;
                if (rs.next()) {
                    // 返回一个任务实例
                    connectorTask.taskName = taskName;
                    connectorTask.sourceSchemaRule = rs.getString("sourceSchemaRule");
                    connectorTask.sourceTableRule = rs.getString("sourceTableRule");
                    connectorTask.targetSchemaRule = rs.getString("targetSchemaRule");
                    connectorTask.targetTableRule = rs.getString("targetTableRule");
                    connectorTask.pullInterval = rs.getInt("pullInterval");
                    connectorTask.latestLSN = rs.getString("latestLSN");
                    connectorTask.status = rs.getString("status");
                    connectorTask.errorMsg = rs.getString("errorMsg");
                    connectorTask.updateTime = rs.getTimestamp("updateTime").toLocalDateTime();
                } else {
                    rs.close();
                    throw new RuntimeException("[POSTGRES-WAL] Connector task [" + taskName + "] does not exist!");
                }
            }
        }
        // 创建同步线程
        connectorTask.conn = ((DuckDBConnection)connector.getTargetDBConnection()).duplicate();
        connectorTask.conn.setAutoCommit(false);
        connectorTask.logger = connector.getLogger();
        connectorTask.walSyncWorker = new WALSyncWorker(connectorTask);
        return connectorTask;
    }

    public void start()
    {
        this.walSyncWorker.start();
    }

    public String getSourceSchemaRule()
    {
        return sourceSchemaRule;
    }

    public String getSourceTableRule()
    {
        return sourceTableRule;
    }

    public String getTargetSchemaRule()
    {
        return targetSchemaRule;
    }

    public String getTargetTableRule()
    {
        return targetTableRule;
    }

    public void save() throws SQLException {
        String sql = """
                UPDATE sysaux.connector_postgres_task
                SET    status = ?, latestLSN = ?
                Where  connectorName = ? And taskName = ?
                """;
        PreparedStatement pStmt = conn.prepareStatement(sql);
        pStmt.setString(1, this.status);
        pStmt.setString(2, this.latestLSN);
        pStmt.setString(3, this.connector.connectorName);
        pStmt.setString(4, this.taskName);
        pStmt.execute();
        pStmt.close();
        conn.commit();
    }
}
