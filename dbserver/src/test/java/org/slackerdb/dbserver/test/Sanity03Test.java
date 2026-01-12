package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.*;
import java.util.Arrays;

public class Sanity03Test {
    @Test
    void testDataEncrypt01() throws Exception {
        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setDataEncrypt(true);
        int dbPort = serverConfiguration.getPort();

        // 初始化数据库
        DBInstance dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
        System.out.println("TEST:: Server started successful ...");

        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");

        Statement stmt = pgConn1.createStatement();
        ResultSet rs = stmt.executeQuery("select group_concat(database_name)::TEXT from duckdb_databases() order by 1");
        if (rs.next())
        {
            assert rs.getString(1).equalsIgnoreCase("memory,system,temp");
        }
        else
        {
            assert false;
        }
        rs.close();
        stmt.close();

        stmt = pgConn1.createStatement();
        stmt.execute("alter database mem set encrypt key yyy");
        stmt.close();

        stmt = pgConn1.createStatement();
        rs = stmt.executeQuery("select group_concat(database_name)::TEXT from duckdb_databases() order by 1");
        if (rs.next())
        {
            String[] parts = rs.getString(1).split(",");
            Arrays.sort(parts);
            String target = String.join(",", parts);
            assert target.equalsIgnoreCase("mem,memory,system,temp");
        }
        else
        {
            assert false;
        }
        rs.close();
        stmt.close();

        pgConn1.close();

        dbInstance.stop();
    }

    @Test
    void testDataEncrypt02() throws Exception {
        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setDataEncrypt(true);
        int dbPort = serverConfiguration.getPort();

        // 设置数据库密钥
        System.setProperty("SLACKERDB_MEM_KEY", "abcdefg");

        // 初始化数据库
        DBInstance dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
        System.out.println("TEST:: Server started successful ...");

        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");

        Statement stmt = pgConn1.createStatement();
        ResultSet rs = stmt.executeQuery("select group_concat(database_name)::TEXT from duckdb_databases() order by 1");
        if (rs.next())
        {
            String[] parts = rs.getString(1).split(",");
            Arrays.sort(parts);
            String target = String.join(",", parts);
            assert target.equalsIgnoreCase("mem,memory,system,temp");
        }
        else
        {
            assert false;
        }
        rs.close();
        stmt.close();

        pgConn1.close();
        dbInstance.stop();
    }

}

