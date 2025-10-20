package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Sleeper;

import java.sql.*;
import java.util.TimeZone;

public class ProxyTest {
    private static ProxyInstance proxyInstance;
    private static DBInstance dbInstance1;
    private static DBInstance dbInstance2;

    private static final int proxyPort = 4310;

    @BeforeAll
    static void initAll() throws ServerException {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 启动代理服务
        org.slackerdb.dbproxy.configuration.ServerConfiguration proxyConfiguration =
                new org.slackerdb.dbproxy.configuration.ServerConfiguration();
        proxyConfiguration.setPort(proxyPort);
        proxyInstance = new ProxyInstance(proxyConfiguration);
        proxyInstance.start();

        // 等待Netty进程就绪
        while (!proxyInstance.instanceState.equalsIgnoreCase("RUNNING")) {
            try {
                Sleeper.sleep(1000);
            }
            catch (InterruptedException ignored) {}
        }
        System.out.println("TEST:: Server started successful ...");

        // 启动两个数据库, 启动在随机端口上
        org.slackerdb.dbserver.configuration.ServerConfiguration serverConfiguration =
                new org.slackerdb.dbserver.configuration.ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setRemoteListener("127.0.0.1:" + proxyPort);
        serverConfiguration.setData("mem1");
        dbInstance1 = new DBInstance(serverConfiguration);
        dbInstance1.start();

        org.slackerdb.dbserver.configuration.ServerConfiguration serverConfiguration2 =
                new org.slackerdb.dbserver.configuration.ServerConfiguration();
        serverConfiguration2.setPort(0);
        serverConfiguration2.setRemoteListener("127.0.0.1:" + proxyPort);
        serverConfiguration2.setData("mem2");
        dbInstance2 = new DBInstance(serverConfiguration2);
        dbInstance2.start();

        // 需要等待远程注册成功
        try {
            Sleeper.sleep(3 * 1000);
        } catch (InterruptedException ignored) {}
    }


    @AfterAll
    static void tearDownAll(){
        proxyInstance.stop();
        dbInstance1.stop();
        dbInstance2.stop();
    }

    @Test
    void connectProxy() throws SQLException {
        {
            String connectURL = "jdbc:postgresql://127.0.0.1:" + proxyPort + "/mem1";

            Connection pgConn = DriverManager.getConnection(connectURL, "", "");
            pgConn.setAutoCommit(false);
            Statement stmt = pgConn.createStatement();
            ResultSet rs = stmt.executeQuery("select current_catalog();");
            rs.next();
            assert rs.getString(1).equals("mem1");
            rs.close();
            rs = stmt.executeQuery("select 42");
            rs.next();
            assert rs.getString(1).equals("42");
            rs.close();
            stmt.close();
            pgConn.close();
        }
        {
            String connectURL = "jdbc:postgresql://127.0.0.1:" + proxyPort + "/mem2";

            Connection pgConn = DriverManager.getConnection(connectURL, "", "");
            pgConn.setAutoCommit(false);
            Statement stmt = pgConn.createStatement();
            ResultSet rs = stmt.executeQuery("select current_catalog();");
            rs.next();
            assert rs.getString(1).equals("mem2");
            rs.close();
            rs = stmt.executeQuery("select 42");
            rs.next();
            assert rs.getString(1).equals("42");
            rs.close();
            stmt.close();
            pgConn.close();
        }
    }
}
