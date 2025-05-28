package org.slackerdb.cdb.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.cdb.server.CDBInstance;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Sleeper;

import java.sql.*;
import java.util.TimeZone;

public class CDBTest {
    private static DBInstance dbInstance;
    private static CDBInstance CDBInstance;
    private static int dbPort = 0;

    @BeforeAll
    static void initAll() throws ServerException {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 启动一个数据库, 启动在随机端口上
        org.slackerdb.dbserver.configuration.ServerConfiguration serverConfiguration =
                new org.slackerdb.dbserver.configuration.ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        org.slackerdb.cdb.configuration.ServerConfiguration proxyConfiguration =
                new org.slackerdb.cdb.configuration.ServerConfiguration();
        proxyConfiguration.setPort(0);
        CDBInstance = new CDBInstance(proxyConfiguration);
        CDBInstance.start();
        dbPort = proxyConfiguration.getPort();

        // 等待Netty进程就绪
        while (!CDBInstance.instanceState.equalsIgnoreCase("RUNNING")) {
            try {
                Sleeper.sleep(1000);
            }
            catch (InterruptedException ignored) {}
        }

        // 添加代理规则
        CDBInstance.addAlias("mem1",
                "127.0.0.1:" +
                dbInstance.serverConfiguration.getPort() + "/" +
                dbInstance.serverConfiguration.getData(), dbInstance);

        System.out.println("TEST:: Server started successful ...");
    }


    @AfterAll
    static void tearDownAll(){
        CDBInstance.stop();
        dbInstance.stop();
    }

    @Test
    void connectProxy() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem1";
        Connection pgConn = DriverManager.getConnection(connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        ResultSet rs = stmt.executeQuery("select current_database();");
        rs.next();
        assert rs.getString(1).equals("memory");
        rs.close();
        stmt.close();
        pgConn.close();
    }
}
