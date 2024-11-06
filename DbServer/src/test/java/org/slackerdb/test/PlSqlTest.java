package org.slackerdb.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.server.DBInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TimeZone;

public class PlSqlTest {
    static int dbPort=4309;
    static DBInstance dbInstance ;

    @BeforeAll
    static void initAll() throws ServerException {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(dbPort);
        serverConfiguration.setData("mem");

        // 启动数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        System.out.println("TEST:: Server started successful ...");
    }


    @AfterAll
    static void tearDownAll() throws Exception{
        dbInstance.stop();
    }

    @Test
    void simple01() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        stmt.execute("DO $$\nDeclare\n" +
                "   x  int;\n" +
                "begin\n" +
                "\tpass;\n" +
                "end;$$");
        stmt.close();
    }
}
