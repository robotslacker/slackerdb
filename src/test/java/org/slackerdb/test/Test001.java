package org.slackerdb.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.Main;
import org.slackerdb.server.ServerConfiguration;
import org.slackerdb.utils.Sleeper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

public class Test001 {
    static Thread dbThread = null;

    @BeforeAll
    static void initAll() {
        // 启动slackerDB的服务
        Thread dbThread = new Thread(() -> {
            try {
                ServerConfiguration.LoadDefaultConfiguration();

                Main.setLogLevel("TRACE");
                Main.start();

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        dbThread.start();
        while (true)
        {
            if (Main.isRunning())
            {
                break;
            }
            else
            {
                Sleeper.sleep(3*1000);
            }
        }
        System.out.println("TEST:: Server started successful ...");
    }

    @Test
    void connectDB() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:4309/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);

        System.out.println("TEST:: DB connect successful.");
    }

    @Test
    void simpleQuery() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:4309/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);

        ResultSet rs = pgConn.createStatement().executeQuery("SELECT 3+4");
        while (rs.next()) {
            assert rs.getInt(1) == 7;
        }
        pgConn.close();
    }

    @Test
    void simpleDDL() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:4309/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);

        pgConn.createStatement().execute("Create TABLE aaa (id int)");
        pgConn.createStatement().execute("insert into aaa values(3)");

        ResultSet rs = pgConn.createStatement().executeQuery("SELECT * from aaa");
        while (rs.next()) {
            assert rs.getInt(1) == 3;
        }
        pgConn.close();
    }

    @Test
    void multiConnectionWithOneInstance() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:4309/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);
        Connection pgConn2 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn2.setAutoCommit(false);

        pgConn1.createStatement().execute("Create TABLE multiConnectionWithOneInstance (id int)");
        pgConn1.createStatement().execute("insert into multiConnectionWithOneInstance values(3)");

        ResultSet rs = pgConn2.createStatement().executeQuery("SELECT * from multiConnectionWithOneInstance");
        while (rs.next()) {
            assert rs.getInt(1) == 3;
        }
        pgConn1.close();
        pgConn2.close();
    }

    @AfterAll
    static void tearDownAll() {
        if (dbThread != null) {
            dbThread.interrupt();
        }
    }
}
