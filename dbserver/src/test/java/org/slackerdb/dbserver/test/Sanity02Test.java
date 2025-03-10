package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;
import java.sql.*;
import java.util.TimeZone;

public class Sanity02Test {
    static int dbPort = 4309;
    static DBInstance dbInstance;

    @BeforeAll
    static void initAll() throws ServerException {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("ON");
        dbPort = serverConfiguration.getPort();

        // 初始化数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
        System.out.println("TEST:: Server started successful ...");
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("TEST:: Will shutdown server ...");
        System.out.println("TEST:: Active sessions : " + dbInstance.activeSessions);
        dbInstance.stop();
        System.out.println("TEST:: Server stopped successful.");
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    @Test
    void testSQLHistory() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("create table testSQLHistory(id int)");
        for (int i=0; i<200;i++)
        {
            pgConn1.createStatement().execute("Insert into testSQLHistory values(" + i + ")");
        }
        pgConn1.commit();
        pgConn1.close();

        pgConn1 = DriverManager.getConnection(connectURL, "", "");
        ResultSet rs = pgConn1.createStatement().executeQuery("Select Count(*) From sysaux.SQL_HISTORY");
        if (rs.next())
        {
            assert rs.getInt(1) > 200;
        }
        else
        {
            assert false;
        }
        rs.close();
        pgConn1.close();
    }
}

