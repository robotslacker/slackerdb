package org.slackerdb.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.Main;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Sleeper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TimeZone;

public class PlSqlTest {
    static Thread dbThread = null;
    static int dbPort=4309;

    @BeforeAll
    static void initAll() {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 启动slackerDB的服务
        Thread dbThread = new Thread(() -> {
            try {
                // 修改默认的db启动端口
                ServerConfiguration.LoadDefaultConfiguration();
                ServerConfiguration.setPort(dbPort);
                ServerConfiguration.setData("mem");

                // 启动数据库
                Main.setLogLevel("INFO");
                Main.serverStart();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        dbThread.start();
        while (true)
        {
            if (DBInstance.state.equalsIgnoreCase("RUNNING"))
            {
                break;
            }
            else
            {
                Sleeper.sleep(1000);
            }
        }
        System.out.println("TEST:: Server started successful ...");
    }


    @AfterAll
    static void tearDownAll() throws Exception{
        Main.serverStop();
        if (dbThread != null) {
            dbThread.interrupt();
        }
    }

    @Test
    void simple01() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        stmt.execute("$$Declare\n" +
                "   x  int;\n" +
                "begin\n" +
                "\tpass;\n" +
                "end;$$");
        stmt.close();
    }
}
