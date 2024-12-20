package org.slackerdb.connector.mysql;

import java.sql.*;
import java.util.regex.Pattern;

public class ConnectorTask {
    private int          taskId;
    public  String       taskName;
    public  long         checkpointInterval = 0;

    private String       sourceSchemaRule;
    private String       sourceTableRule;
    private String       targetSchemaRule;
    private String       targetTableRule;

    public String       sourceSchema;
    public String       sourceTable;
    public String       targetSchema;
    public String       targetTable;

    public  long        binLogPosition;
    public  String      binlogFileName;
    public  long        binlogTimeStamp;

    public  String      status;
    public  String      errorMsg;
    private Pattern     sourceSchemaPattern = null;
    private Pattern     sourceObjectPattern = null;
    private Connection  targetDBConnection = null;
    private Connector   connectorHandler;

    private ConnectorTask() {}

    public void setConnectorHandler(Connector connector)
    {
        this.connectorHandler = connector;
    }

    public void setTargetDBConnection(Connection targetDBConnection)
    {
        this.targetDBConnection = targetDBConnection;
    }

    public void setSourceSchemaRule(String pSourceSchemaRule)
    {
        this.sourceSchemaRule = pSourceSchemaRule;
        this.sourceSchemaPattern = Pattern.compile(this.sourceSchemaRule);
    }

    public void setSourceTableRule(String pSourceTableRule)
    {
        this.sourceTableRule = pSourceTableRule;
        this.sourceObjectPattern = Pattern.compile(this.sourceTableRule);
    }

    public Pattern getSourceSchemaPattern()
    {
        return this.sourceSchemaPattern;
    }

    public Pattern getSourceObjectPattern()
    {
        return this.sourceObjectPattern;
    }

    public static ConnectorTask newTask(String taskName)
    {
        ConnectorTask connectorTask = new ConnectorTask();

        // 返回一个任务实例
        connectorTask.taskId = 0;
        connectorTask.taskName = taskName;
        return connectorTask;
    }

    public void setTaskId(int taskId)
    {
        this.taskId = taskId;
    }

    public void setBinlogFileName(String binlogFileName)
    {
        this.binlogFileName = binlogFileName;
    }

    public void setBinLogPosition(long binLogPosition)
    {
        this.binLogPosition = binLogPosition;
    }

    public void setBinlogTimeStamp(long binlogTimeStamp)
    {
        this.binlogTimeStamp = binlogTimeStamp;
    }

    public void setTargetSchemaRule(String pTargetSchemaRule)
    {
        this.targetSchemaRule = pTargetSchemaRule;
    }

    public void setTargetTableRule(String pTargetTableRule)
    {
        this.targetTableRule = pTargetTableRule;
    }

    public void setCheckpointInterval(long checkpointInterval)
    {
        this.checkpointInterval = checkpointInterval;
    }

    public void save() throws SQLException
    {
        if (!this.connectorHandler.saveCheckPoint)
        {
            // 不需要保存内容
            return;
        }

        if (this.taskId == 0)
        {
            // 获得一个唯一序号
            String  sql = "SELECT nextval('taskIdSeq')";
            Statement stmt = this.targetDBConnection.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            rs.next();
            this.taskId = rs.getInt(1);
            rs.close();
            stmt.close();
        }

        String sql = """
                Insert or replace into sysaux.connector_mysql_task
                (
                    taskId, taskName, connectorName, checkpointInterval,
                    sourceSchemaRule, sourceTableRule, targetSchemaRule, targetTableRule,
                    binlogFileName, binLogPosition, binlogTimeStamp,
                    status, errorMsg
                )
                Values( ?,?,?,?, ?,?,?,?, ?,?,?, ?,?)
                """;
        PreparedStatement pStmt = this.targetDBConnection.prepareStatement(sql);
        pStmt.setInt(1, this.taskId);
        pStmt.setString(2, this.taskName);
        pStmt.setString(3, this.connectorHandler.connectorName);
        pStmt.setLong(4, this.checkpointInterval);

        pStmt.setString(5, this.sourceSchemaRule);
        pStmt.setString(6, this.sourceTableRule);
        pStmt.setString(7, this.targetSchemaRule);
        pStmt.setString(8, this.targetTableRule);

        pStmt.setString(9, this.binlogFileName);
        pStmt.setLong(10, this.binLogPosition);
        pStmt.setLong(11, this.binlogTimeStamp);

        pStmt.setString(12, this.status);
        pStmt.setString(13, this.errorMsg);

        pStmt.execute();
        pStmt.close();
    }
}
