package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Sleeper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.TimeZone;

public class ProxyTest {
    private static DBInstance dbInstance;
    private static ProxyInstance proxyInstance;
    private static final int dbPort = 4309;

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

        org.slackerdb.dbproxy.configuration.ServerConfiguration proxyConfiguration =
                new org.slackerdb.dbproxy.configuration.ServerConfiguration();
        proxyConfiguration.setPort(dbPort);
        proxyInstance = new ProxyInstance(proxyConfiguration);
        proxyInstance.start();

        // 等待Netty进程就绪
        while (!proxyInstance.instanceState.equalsIgnoreCase("RUNNING")) {
            Sleeper.sleep(1000);
        }

        // 添加代理规则
        proxyInstance.createAlias("mem", false);
        proxyInstance.addAliasTarget("mem", "127.0.0.1", dbInstance.serverConfiguration.getPort(), 200);

        System.out.println("TEST:: Server started successful ...");
    }


    @AfterAll
    static void tearDownAll() throws Exception{
        proxyInstance.stop();
        dbInstance.stop();
    }

    @Test
    void connectProxy() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        pgConn.close();
    }

}
