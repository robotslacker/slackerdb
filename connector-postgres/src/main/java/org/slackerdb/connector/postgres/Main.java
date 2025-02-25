package org.slackerdb.connector.postgres;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.connector.postgres.command.PostgresConnectorCommandVisitor;
import org.slackerdb.connector.postgres.exception.ConnectorException;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

public class Main {
    public static void main(String[] args) throws SQLException {
        Logger logger = AppLogger.createLogger("testCDC", "TRACE", "CONSOLE");
        Logger nettyLogger = (Logger) LoggerFactory.getLogger("org.apache.activemq");
//        if (!logger.getLevel().equals(Level.TRACE)) {
            nettyLogger.setLevel(Level.OFF);
//        }

        // 创建目标数据库
//        String  connectURL = "jdbc:duckdb::memory:";
        String connectURL = "jdbc:duckdb:xx.db";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);

        PostgresConnectorCommandVisitor.runConnectorCommand(
                pgConn, "Create connector if not exists testCDC connect to 'postgres/postgres@jdbc:postgresql://192.168.40.129:5432/jtls_db'", logger);
        PostgresConnectorCommandVisitor.runConnectorCommand(
                pgConn, "alter connector testCDC add task if not exists ddd 'sourceSchema=aa   sourceTable=^(.*)$ targetTable=$1'", logger);
        PostgresConnectorCommandVisitor.runConnectorCommand(
                pgConn, "alter connector testCDC add task if not exists eee 'sourceSchema=bb   sourceTable=^(.*)$ targetTable=$1'", logger);
        PostgresConnectorCommandVisitor.runConnectorCommand(
                pgConn, "Start connector testCDC", logger);

        try {
            Thread.sleep(100000 * 1000);
        }
        catch (InterruptedException interruptedException)
        {
            Thread.currentThread().interrupt();
        }
        }

    }