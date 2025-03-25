package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.*;
import java.util.TimeZone;

public class PlSqlTest {
    static final int dbPort=4309;
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
    static void tearDownAll(){
        dbInstance.stop();
    }

    @Test
    void simple01() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        stmt.execute("""
                DO $$
                Declare
                   x  int;
                begin
                    pass;
                end;
                $$
                """);
        stmt.close();
        pgConn.close();
    }

    @Test
    void simple02() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        stmt.execute(
                """
                        $$
                        Declare
                           x  int;
                        begin
                            pass;
                        end;
                        $$
                        """);
        stmt.close();
        pgConn.close();
    }

    @Test
    void testCombinePlsqlAndSimpleSql() throws SQLException
    {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        stmt.execute(
                """
                        Drop table if exists aaa;
                        $$
                        begin
                            create table aaa(num int);
                            insert into aaa values(10);
                        end;
                        $$
                        """);
        ResultSet rs = stmt.executeQuery("select * from aaa");
        while (rs.next())
        {
            assert rs.getInt(1) == 10;
        }
        rs.close();
        stmt.close();
        pgConn.close();
    }
}
