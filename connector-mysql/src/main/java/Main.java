import ch.qos.logback.classic.Logger;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.connector.mysql.Connector;
import org.slackerdb.connector.mysql.ConnectorTask;
import org.slackerdb.connector.mysql.exception.ConnectorException;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class Main {
    public void start(Properties properties)
    {

    }

    public static void main(String[] args) throws SQLException {
        Logger logger = AppLogger.createLogger("testCDC", "TRACE", "CONSOLE");

        // 创建目标数据库
        String  connectURL = "jdbc:duckdb:sql-connector-test.db";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        pgConn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS jtls_sdc");
        pgConn.commit();

        List<String> connectorList;
        try {
            connectorList = Connector.list(pgConn);
        }
        catch (ConnectorException connectorException)
        {
            logger.error("[MYSQL-BINLOG] {}", connectorException.getErrorMessage());
            logger.error("[MYSQL-BINLOG] App will quit now.");
            System.exit(255);
        }
        Connector connector = Connector.load(pgConn, "testCDC");
        connector.setLogger(logger);

//
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
        // 启动任务
        connector.start();
        try {
            Thread.sleep(100000 * 1000);
        }
        catch (InterruptedException interruptedException)
        {
            Thread.currentThread().interrupt();
        }
    }
}
