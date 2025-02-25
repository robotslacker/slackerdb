package org.slackerdb.connector.postgres;

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.connector.postgres.command.PostgresConnectorCommandVisitor;
import org.slackerdb.connector.postgres.exception.ConnectorException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

public class Main {
    public static void main(String[] args) throws SQLException {
        Logger logger = AppLogger.createLogger("testCDC", "TRACE", "CONSOLE");

        // 创建目标数据库
//        String  connectURL = "jdbc:duckdb::memory:";
        String connectURL = "jdbc:duckdb:aa.db";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        pgConn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS wal_test");
        pgConn.commit();

//        List<String> connectorList;
//        try {
//            connectorList = Connector.list(pgConn);
//        }
//        catch (ConnectorException connectorException)
//        {
//            logger.error("[POSTGRES-WAL] {}", connectorException.getErrorMessage());
//            logger.error("[POSTGRES-WAL] App will quit now.");
//            System.exit(255);
//        }
//        Connector connector = Connector.load(pgConn, "testCDC");
//        connector.setLogger(logger);
//
//        System.out.println("OK");
//        System.exit(0);

        // 正则表达式匹配 JDBC URL
        String regex = "((?<user>[^?]+(?=/|$))?/?(?<pass>[^?]+)?@)?jdbc:(?<protocol>[^:]+)://(?<host>[^:/]+)(:(?<port>\\d+))?/?(?<database>[^?]+)?(\\?(?<params>.*))?";

        PostgresConnectorCommandVisitor.runConnectorCommand(
                pgConn, "Create connector if not exists testCDC connect to 'postgres/postgres@jdbc:postgresql://192.168.40.129:5432/jtls_db'", logger);
        PostgresConnectorCommandVisitor.runConnectorCommand(
                pgConn, "alter connector testCDC add task if not exists ddd 'sourceSchema=jtls_sdc   sourceTable=^(.*)$ targetTable=$1'", logger);
        PostgresConnectorCommandVisitor.runConnectorCommand(
                pgConn, "Start connector testCDC", logger);

//        // 创建连接器
//        Connector connector = Connector.newConnector("testCDC");
//        connector.setLogger(logger);
//
//        // 设置连接数据源和目的地
//        connector.setTargetDBConnection(pgConn);
//        connector.setHostName("192.168.40.132");
//        connector.setPort(3406);
//        connector.setUserName("root");
//        connector.setPassword("123456");
//        connector.setDatabase("jtls_sdc");
//
//        // 添加任务
//        ConnectorTask connectorTask = ConnectorTask.newTask("task1");
//        connectorTask.setSourceSchemaRule("jtls_sdc");
//        connectorTask.setSourceTableRule("^(.*)$");
//        connectorTask.setTargetSchemaRule("jtls_sdc");
//        connectorTask.setTargetTableRule("$1");
//        connectorTask.setCheckpointInterval(0);
//        connector.addTask(connectorTask);
//
//        // 保存数据到数据字典中
//        connector.save();
//
//        // 启动任务
//        connector.start();
//        try {
//            Thread.sleep(100000 * 1000);
//        }
//        catch (InterruptedException interruptedException)
//        {
//            Thread.currentThread().interrupt();
//        }
        }

    }