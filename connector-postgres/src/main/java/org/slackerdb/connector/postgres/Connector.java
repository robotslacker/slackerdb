package org.slackerdb.connector.postgres;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.apache.activemq.broker.BrokerService;
import org.duckdb.DuckDBConnection;
import org.slackerdb.common.utils.DBUtil;
import org.slackerdb.connector.postgres.exception.ConnectorException;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class Connector {
    public  String      connectorName;
    private String      connectorURL;
    private String      status;
    private Connection  targetDBConnection;
    public final ConcurrentHashMap<String, ConnectorTask> connectorTasks
            = new ConcurrentHashMap<>();

    public static BrokerService embeddedBrokerService = null;

    // 只能使用newConnector, 或者load的方式来加载
    private Connector() throws Exception {
        synchronized (this) {
            if (embeddedBrokerService == null) {
                embeddedBrokerService = EmbeddedActiveMQ.startBroker();
            }
        }
    }

    private Logger logger;

    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    public Logger getLogger()
    {
        return this.logger;
    }

    public Connection getTargetDBConnection()
    {
        return targetDBConnection;
    }

    public String getStatus()
    {
        return this.status;
    }

    public void setTargetDBConnection(Connection targetDBConnection)
    {
        this.targetDBConnection = targetDBConnection;
    }

    public String getConnectorURL()
    {
        return connectorURL;
    }

    // 创建同步进程需要的数据字典
    private static void createCatalogIfNotExist(Connection catalogConnection) throws SQLException
    {
        Statement stmt = catalogConnection.createStatement();

        // 创建数据字典，如果不存在的话
        stmt.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
        stmt.execute(
                """
                        CREATE TABLE IF NOT EXISTS sysaux.connector_postgres
                        (
                        	connectorName  			text primary key,
                        	connectorURL            text,
                        	status     				text
                        )
                        """
        );
        stmt.execute(
                """
                        CREATE TABLE IF NOT EXISTS sysaux.connector_postgres_task
                        (
                        	connectorName			text,
                        	taskName   				text,
                        	sourceSchemaRule        text,
                        	sourceTableRule         text,
                        	targetSchemaRule        text,
                        	targetTableRule         text,
                        	pullInterval            int,
                        	latestLSN               text,
                        	status     				text,
                        	errorMsg   				text,
                        	updateTime              datetime,
                        	primary key (connectorName, taskName)
                        )
                        """
        );
        stmt.close();
        catalogConnection.commit();

    }
    public static Connector newConnector(Connection targetDBConnection, String connectorName, String connectorURL) throws Exception {
        Connector connector = new Connector();

        // 创建数据字典，如果不存在
        connector.createCatalogIfNotExist(targetDBConnection);

        // 数据保存到数据字典中
        String sql = """
                Insert or replace into sysaux.connector_postgres (connectorName, connectorURL, status) values(?, ?, ?)
                """;
        PreparedStatement pStmt = targetDBConnection.prepareStatement(sql);
        pStmt.setString(1, connectorName);
        pStmt.setString(2, connectorURL);
        pStmt.setString(3, "CREATED");

        pStmt.execute();
        pStmt.close();

        // 保存提交
        targetDBConnection.commit();

        connector.connectorName = connectorName;
        connector.targetDBConnection = targetDBConnection;
        connector.connectorURL = connectorURL;
        connector.status = "CREATED";
        return connector;
    }

    public void addTask(ConnectorTask connectorTask)
    {
        connectorTasks.put(connectorTask.taskName, connectorTask);
    }

    public static List<String> list(Connection targetDBConnection) throws SQLException
    {
        List<String> connectorLists = new ArrayList<>();

        if (!DuckdbUtil.isTableExists(targetDBConnection, "sysaux", "connector_postgres"))
        {
            return connectorLists;
        }

        String sql = """
                SELECT  connectorName
                FROM    sysaux.connector_postgres
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

    // 从数据库中加载之前的持久化connector
    public static ConcurrentHashMap<String, Connector> load(Connection targetDBConnection, Logger logger) throws Exception
    {
        ConcurrentHashMap<String, Connector> connectors = new ConcurrentHashMap<>();

        // 创建数据字典
        Connector.createCatalogIfNotExist(targetDBConnection);
        // 从数据字典中加载连接器
        String sql = """
                SELECT  *
                FROM    sysaux.connector_postgres
                """;
        PreparedStatement preparedStatement = targetDBConnection.prepareStatement(sql);
        ResultSet rs = preparedStatement.executeQuery();
        while (rs.next())
        {
            Connector connector = new Connector();

            // 创建数据字典，如果不存在
            connector.setTargetDBConnection(targetDBConnection);

            connector.connectorURL = rs.getString("connectorURL");
            connector.connectorName = rs.getString("connectorName");
            connector.status = rs.getString("status");
            connector.logger = logger;

            // 从数据字典中加载任务配置
            sql = """
                SELECT  *
                FROM    sysaux.connector_postgres_task
                WHERE   ConnectorName = ?
                """;
            preparedStatement = targetDBConnection.prepareStatement(sql);
            preparedStatement.setString(1, connector.connectorName);
            ResultSet taskRs = preparedStatement.executeQuery();
            while (taskRs.next())
            {
                ConnectorTask connectorTask = ConnectorTask.loadTask(connector, taskRs.getString("taskName"));
                connector.addTask(connectorTask);
            }
            taskRs.close();

            connectors.put(connector.connectorName, connector);
        }
        rs.close();
        return connectors;
    }

    public void start() throws ConnectorException
    {
        if (this.connectorTasks.isEmpty())
        {
            throw new ConnectorException("[POSTGRES-WAL] Empty task list. Connector start failed.");
        }

        // 启动同步作业
        for (ConnectorTask connectorTask : this.connectorTasks.values())
        {
            connectorTask.start();
        }
        logger.info("[POSTGRES-WAL] Started successful .");
    }
}
