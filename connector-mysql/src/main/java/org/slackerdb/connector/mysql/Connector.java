package org.slackerdb.connector.mysql;

import ch.qos.logback.classic.Logger;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import org.duckdb.DuckDBConnection;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.connector.mysql.exception.ConnectorException;
import org.slackerdb.connector.mysql.exception.ConnectorMysqlBinlogNotExist;
import org.slackerdb.connector.mysql.exception.ConnectorMysqlBinlogOffsetError;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class Connector {
    public  String      connectorName;
    private String      hostName;
    private int         port;
    private String      userName;
    private String      password;
    private String      database;
    private String      status;
    private Connector() {}
    public boolean      fullSync = false;
    public boolean      saveCheckPoint = false;

    public BinaryLogClient binlogClient;
    public List<ConnectorTask> connectorTaskList = new ArrayList<>();
    private Connection targetDBConnection;
    private Logger logger;
    private final BinlogSyncWorker binlogSyncWorker = new BinlogSyncWorker();

    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    public void setTargetDBConnection(Connection targetDBConnection)
    {
        this.targetDBConnection = targetDBConnection;
    }

    // 创建同步进程需要的数据字典
    private void createCatalogIfNotExist(Connection catalogConnection) throws SQLException
    {
        Statement stmt = catalogConnection.createStatement();

        // 创建数据字典，如果不存在的话
        stmt.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
        stmt.execute("CREATE SEQUENCE IF NOT EXISTS taskIdSeq");
        stmt.execute(
                """
                        CREATE TABLE IF NOT EXISTS sysaux.connector_mysql
                        (
                        	connectorName  			text primary key,
                        	hostName   				text,
                        	port       				int,
                        	userName   				text,
                        	password   				text,
                        	database   				text,
                        	status     				text
                        )
                        """
        );
        stmt.execute(
                """
                        CREATE TABLE IF NOT EXISTS sysaux.connector_mysql_task
                        (
                        	connectorName			text,
                        	taskId                  Int,
                        	taskName   				text,
                        	sourceSchemaRule        text,
                        	sourceTableRule         text,
                        	targetSchemaRule        text,
                        	targetTableRule         text,
                        	checkpointInterval      bigint,
                        	binlogFileName          text,
                        	binlogPosition          long,
                        	binlogTimeStamp			long,
                        	status     				text,
                        	errorMsg   				text,
                        	primary key (taskId)
                        )
                        """
        );
        stmt.execute("""
                        CREATE TABLE IF NOT EXISTS sysaux.connector_mysql_pending_tables
                        (
                        	connectorName			text,
                        	sourceSchema            text,
                        	sourceTable             text
                        )
                """
        );
        stmt.close();
        catalogConnection.commit();

    }

    public Connection newSourceDbConnection() throws SQLException
    {
        return DriverManager.getConnection(
                "jdbc:mysql://" + this.hostName + ":" + this.port + "/" + this.database,
                this.userName, this.password);
    }

    public Connection newTargetDBConnection() throws SQLException
    {
        Connection conn = ((DuckDBConnection)this.targetDBConnection).duplicate();
        conn.setAutoCommit(false);
        return conn;
    }

    public void startBinLogClient(String lastBinlogFileName, long lastBinlogPosition) {
        this.binlogClient = new BinaryLogClient(hostName, port, userName, password);
        if (lastBinlogFileName != null) {
            this.binlogClient.setBinlogFilename(lastBinlogFileName);
            this.binlogClient.setBinlogPosition(lastBinlogPosition);
        }
    }

    public void validateBinlogFile(Connection sourceConnection, String lastBinlogFileName, long lastBinlogPosition)
            throws ConnectorMysqlBinlogOffsetError, SQLException
    {
        if (lastBinlogFileName.isEmpty() && lastBinlogPosition == 0)
        {
            return;
        }
        Statement statement = null;
        ResultSet rs = null;
        try {
            // 验证指定的binlog是否存在，有效
            statement = sourceConnection.createStatement();

            boolean fileExists = false;
            rs = statement.executeQuery("SHOW BINARY LOGS");
            while (rs.next()) {
                if (rs.getString("Log_name").equals(lastBinlogFileName)) {
                    fileExists = true;
                    long fileSize = rs.getLong("File_size");
                    // 检查位点是否在文件范围内
                    if (lastBinlogPosition > fileSize) {
                        throw new ConnectorMysqlBinlogOffsetError(
                                String.format("lastBinlogPosition %s:%d exceed currentBinlogPosition %s:%d.",
                                        lastBinlogFileName, lastBinlogPosition, lastBinlogFileName, fileSize));
                    }
                    break;
                }
            }
            rs.close();
            statement.close();
            if (!fileExists) {
                throw new ConnectorMysqlBinlogNotExist(
                        String.format("lastBinlogFileName [%s] does not exist.", lastBinlogFileName));
            }
        }
        finally {
            try {
                if (rs != null && !rs.isClosed()) {
                    rs.close();
                }
            }
            catch (SQLException ignored) {}
            try {
                if (statement != null && !statement.isClosed()) {
                    statement.close();
                }
            }
            catch (SQLException ignored) {}
        }
    }

    public static Connector newConnector(String connectorName)
    {
        Connector connector = new Connector();
        connector.connectorName = connectorName;
        connector.status = "STARTED";
        return connector;
    }

    public void setHostName(String hostName)
    {
        this.hostName = hostName;
    }

    public void setPort(int port)
    {
        this.port = port;
    }

    public void setUserName(String userName)
    {
        this.userName = userName;
    }

    public void setPassword(String password)
    {
        this.password = password;
    }

    public void setDatabase(String database)
    {
        this.database = database;
    }

    public void addTask(ConnectorTask connectorTask)
    {
        connectorTask.setConnectorHandler(this);
        connectorTaskList.add(connectorTask);
    }

    public static List<String> list(Connection targetDBConnection) throws SQLException
    {
        List<String> connectorLists = new ArrayList<>();

        if (!DuckdbUtil.isTableExists(targetDBConnection, "sysaux", "connector_mysql"))
        {
            throw new ConnectorException("Target database has not enabled for any binlog connector.");
        }

        String sql = """
                SELECT  Distinct connectorName
                FROM    sysaux.connector_mysql
                """;
        PreparedStatement preparedStatement = targetDBConnection.prepareStatement(sql);
        ResultSet rs = preparedStatement.executeQuery();
        while (rs.next())
        {
            connectorLists.add(rs.getString("connectorName"));
        }
        rs.close();

        return connectorLists;
    }

    // 从数据库中加载
    public static Connector load(Connection targetDBConnection, String connectorName) throws SQLException
    {
        Connector connector = new Connector();

        // 创建数据字典，如果不存在
        connector.setTargetDBConnection(targetDBConnection);
        connector.createCatalogIfNotExist(targetDBConnection);

        // 数据保存到数据字典中
        String sql = """
                SELECT  *
                FROM    sysaux.connector_mysql
                WHERE   ConnectorName = ?
                """;
        PreparedStatement preparedStatement = targetDBConnection.prepareStatement(sql);
        preparedStatement.setString(1, connectorName);
        ResultSet rs = preparedStatement.executeQuery();
        if (rs.next())
        {
            connector.setHostName(rs.getString("hostName"));
            connector.setPort(rs.getInt("port"));
            connector.setUserName(rs.getString("userName"));
            connector.setPassword(rs.getString("password"));
            connector.setDatabase(rs.getString("database"));
            connector.connectorName = connectorName;
        }
        rs.close();

        sql = """
            SELECT  *
            FROM    sysaux.connector_mysql_task
            WHERE   ConnectorName = ?
            """;
        preparedStatement = targetDBConnection.prepareStatement(sql);
        preparedStatement.setString(1, connectorName);
        rs = preparedStatement.executeQuery();
        if (rs.next())
        {
            ConnectorTask connectorTask = ConnectorTask.newTask(rs.getString("taskName"));

            connectorTask.setConnectorHandler(connector);
            connectorTask.setTaskId(rs.getInt("taskId"));
            connectorTask.setSourceSchemaRule(rs.getString("sourceSchemaRule"));
            connectorTask.setSourceTableRule(rs.getString("sourceTableRule"));
            connectorTask.setTargetSchemaRule(rs.getString("targetSchemaRule"));
            connectorTask.setTargetTableRule(rs.getString("targetTableRule"));
            connectorTask.setCheckpointInterval(rs.getLong("checkpointInterval"));
            connectorTask.setBinlogTimeStamp(rs.getLong("binlogTimeStamp"));
            connectorTask.setBinlogFileName(rs.getString("binlogFileName"));
            connectorTask.setBinLogPosition(rs.getLong("binlogPosition"));
            connector.addTask(connectorTask);
        }
        rs.close();
        return connector;
    }

    // 保存到数据库
    public void save() throws SQLException
    {
        // 标记需要保存检查点
        this.saveCheckPoint = true;

        // 创建数据字典，如果不存在
        this.createCatalogIfNotExist(this.targetDBConnection);

        // 数据保存到数据字典中
        String sql = """
                Insert or replace into sysaux.connector_mysql
                (connectorName, hostName, port, userName, password, database, status)
                values(?, ?, ?, ?, ?, ?, ?)
                """;
        PreparedStatement pStmt = this.targetDBConnection.prepareStatement(sql);
        pStmt.setString(1, this.connectorName);
        pStmt.setString(2, this.hostName);
        pStmt.setInt(3, this.port);
        pStmt.setString(4, this.userName);
        pStmt.setString(5, this.password);
        pStmt.setString(6, this.database);
        pStmt.setString(7, this.status);
        pStmt.execute();
        pStmt.close();

        // 保存所有的任务信息
        for (ConnectorTask connectorTask : this.connectorTaskList)
        {
            connectorTask.setTargetDBConnection(this.targetDBConnection);
            connectorTask.save();
        }

        // 保存提交
        this.targetDBConnection.commit();
    }

    public void start() throws ConnectorException
    {
        if (!this.saveCheckPoint)
        {
            logger.warn("[MYSQL-BINLOG] Will not save checkpoint in database, after restart, all checkpoint will lost!");
        }
        binlogSyncWorker.setLogger(this.logger);
        binlogSyncWorker.setConnectorHandler(this);

        if (this.connectorTaskList.isEmpty())
        {
            throw new ConnectorException("Empty task list. Start failed.");
        }

        // 查找同步最慢的那个任务
        String minBinlogFileName = null;
        long minBinlogPosition = 0;
        while (true) {
            try {
                // 获得源数据库的JDBC连接
                Connection sourceConnection = this.newSourceDbConnection();

                // 查找所有的任务信息，依次排查其当前同步位置，取最小值
                for (ConnectorTask connectorTask : this.connectorTaskList) {
                    if (connectorTask.binlogFileName == null) {
                        // 如果没有上次的同步时间点，本次从零开始同步
                        minBinlogFileName = null;
                        minBinlogPosition = 0L;
                        break;
                    }
                    try {
                        this.validateBinlogFile(sourceConnection,
                                connectorTask.binlogFileName, connectorTask.binLogPosition);
                    } catch (ConnectorMysqlBinlogOffsetError ignored) {
                        // 有的task任务已经失效，忽略这个任务
                        minBinlogFileName = null;
                        minBinlogPosition = 0L;
                        break;
                    }

                    if (minBinlogFileName == null) {
                        minBinlogFileName = connectorTask.binlogFileName;
                        minBinlogPosition = connectorTask.binLogPosition;
                    }
                    if (connectorTask.binlogFileName.compareTo(minBinlogFileName) < 0) {
                        minBinlogFileName = connectorTask.binlogFileName;
                        minBinlogPosition = connectorTask.binLogPosition;
                    }
                    if (connectorTask.binlogFileName.equals(minBinlogFileName) &&
                            (connectorTask.binLogPosition < minBinlogPosition)
                    ) {
                        minBinlogPosition = connectorTask.binLogPosition;
                    }
                }
                sourceConnection.close();
                logger.info("[MYSQL-BINLOG] Current binlog sync start position : [{}:{}]", minBinlogFileName, minBinlogPosition);
                break;
            } catch (SQLException sqlException) {
                logger.error(
                        "[MYSQL-BINLOG] Started failed. Source database connected failed . Will retry after 10 seconds. {}",
                        sqlException.getMessage());
                Sleeper.sleep(10*1000L);
            }
        }
        if (minBinlogFileName == null)
        {
            logger.info("[MYSQL-BINLOG] Full sync is required ... ");
            this.fullSync = true;
        }
        // 设置binlog的开始位置
        this.startBinLogClient(minBinlogFileName, minBinlogPosition);

        // 开始进行数据同步
        binlogSyncWorker.start();

        logger.info("[MYSQL-BINLOG] Started successful .");
    }
}
