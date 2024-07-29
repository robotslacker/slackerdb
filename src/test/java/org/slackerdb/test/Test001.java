package org.slackerdb.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.Main;
import org.slackerdb.server.ServerConfiguration;
import org.slackerdb.utils.Sleeper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Properties;

public class Test001 {
    static Thread dbThread = null;
    static int dbPort=4309;

    @BeforeAll
    static void initAll() {
        // 启动slackerDB的服务
        Thread dbThread = new Thread(() -> {
            try {
                // 修改默认的db启动端口
                ServerConfiguration.LoadDefaultConfiguration();
                ServerConfiguration.setPort(dbPort);

                // 启动数据库
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
                Sleeper.sleep(1*1000);
            }
        }
        System.out.println("TEST:: Server started successful ...");
    }

    @Test
    void connectDB() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
    }

    @Test
    void simpleQuery() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);
        Connection pgConn2 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn2.setAutoCommit(false);

        pgConn1.createStatement().execute("Create TABLE multiConnectionWithOneInstance (id int)");
        pgConn1.createStatement().execute("insert into multiConnectionWithOneInstance values(3)");
        pgConn1.commit();

        ResultSet rs = pgConn2.createStatement().executeQuery("SELECT * from multiConnectionWithOneInstance");
        while (rs.next()) {
            assert rs.getInt(1) == 3;
        }
        pgConn1.close();
        pgConn2.close();
    }

    @Test
    void commitAndRollback() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("Create TABLE commitAndRollback (id int)");
        pgConn1.commit();

        pgConn1.createStatement().execute("insert into commitAndRollback values(3)");

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from commitAndRollback");

        while (rs.next()) {
            assert rs.getInt(1) == 3;
        }
        rs.close();
        pgConn1.rollback();

        rs = pgConn1.createStatement().executeQuery("SELECT COUNT(*) from commitAndRollback");
        while (rs.next()) {
            assert rs.getInt(1) == 0;
        }
        rs.close();

        pgConn1.createStatement().execute("insert into commitAndRollback values(5)");

        rs = pgConn1.createStatement().executeQuery("SELECT * from commitAndRollback");
        while (rs.next()) {
            assert rs.getInt(1) == 5;
        }
        rs.close();

        pgConn1.commit();
        pgConn1.close();
    }

    @Test
    void lotsOfConnection() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        int  MAX_THREADS = 100;

        // 创建一个包含100个线程的数组
        Thread[] threads = new Thread[MAX_THREADS];

        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);
        pgConn1.createStatement().execute("DROP TABLE IF EXISTS lotsOfConnection");

        pgConn1.createStatement().execute("Create TABLE lotsOfConnection (id int)");
        pgConn1.commit();

        for (int i = 0; i < threads.length; i++) {
            int finalI = i;
            threads[i] = new Thread(() -> {
                try {
                    Connection pgConnX = DriverManager.getConnection(
                            connectURL, "", "");
                    pgConnX.setAutoCommit(false);
                    pgConnX.createStatement().execute("insert into lotsOfConnection values(" + finalI + ")");
                    pgConnX.commit();
                    pgConnX.close();
                } catch (SQLException se) {
                    se.printStackTrace();
                }
            });
        }

        // 启动所有线程
        for (Thread thread : threads) {
            thread.start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        int expectedSumValue = 0;
        for (int i = 0; i < threads.length; i++) {
            expectedSumValue = expectedSumValue + i;
        }
        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT COUNT(*),SUM(id) from lotsOfConnection");
        while (rs.next()) {
            assert rs.getInt(1) == MAX_THREADS;
            assert rs.getInt(2) == expectedSumValue;
        }
        rs.close();
        pgConn1.commit();
        pgConn1.close();
    }

    @Test
    void testFailedHybridSQL() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        pgConn1.createStatement().execute("Create TABLE testFailedHybridSQL (id int)");
        pgConn1.commit();

        pgConn1.createStatement().execute("insert into testFailedHybridSQL values(1)");

        try {
            pgConn1.createStatement().execute("insert into testFailedHybridSQLFake values(2)");
        }
        catch (SQLException se)
        {
            assert se.getClass().getSimpleName().equalsIgnoreCase("PSQLException");
        }
        pgConn1.createStatement().execute("insert into testFailedHybridSQL values(3)");

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT Count(*),Sum(id) from testFailedHybridSQL");

        int resultCount = 0;
        while (rs.next()) {
            resultCount++;
            assert rs.getInt(1) == 2;
            assert rs.getInt(2) == 4;
        }
        rs.close();
        pgConn1.close();
        assert resultCount == 1;
    }

//    @Test
    void variousDataTypeSelect() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = "CREATE TABLE variousDataTypeSelect ( " +
                "id INTEGER PRIMARY KEY," +
                "name VARCHAR(100)," +
                "birth_date DATE," +
                "is_active BOOLEAN," +
                "salary DECIMAL(10, 2)," +
                "binary_data BLOB," +
                "float_value FLOAT," +
                "double_value DOUBLE," +
                "numeric_value NUMERIC," +
                "timestamp_value TIMESTAMP" +
                ")";
        pgConn1.createStatement().execute(sql);

        sql = "INSERT INTO variousDataTypeSelect (id, name, birth_date, is_active, salary, binary_data, float_value, double_value, numeric_value, timestamp_value) " +
                "VALUES " +
                "(1, 'John Doe', '1990-01-01', TRUE, 50000.00, X'48454C4C', 3.14, 3.14159, 12345, '2022-01-01 00:00:00')," +
                "(2, 'Jane Smith', '1995-05-15', FALSE, 60000.00, X'4A616E65', 2.71, 2.71828, 98765, '2023-06-30 12:00:00')";
        pgConn1.createStatement().execute(sql);

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from variousDataTypeSelect Where id = 2");
        while (rs.next()) {
            assert rs.getInt("id") == 2;
            assert rs.getString("name").equals("Jane Smith");
            assert rs.getDate("birth_date").toString().equals("1995-05-15");
            assert !rs.getBoolean("is_active");
            assert rs.getBigDecimal("salary").longValue() == 60000;
//            Blob blob = rs.getBlob("binary_data");
            assert Math.abs(rs.getFloat("float_value") - 2.71) < 0.001;
            assert rs.getDouble("double_value") == 2.71828;
            assert rs.getDouble("numeric_value") == 98765;
            assert rs.getTimestamp("timestamp_value").toInstant().getEpochSecond() == 1688097600;
        }
        rs.close();
        pgConn1.close();
    }

//    @Test
    void testBindInsert() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:4309/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = "CREATE TABLE testBindInsert ( " +
                "id INTEGER PRIMARY KEY," +
                "name VARCHAR(100)," +
                "birth_date DATE," +
                "is_active BOOLEAN," +
                "salary DECIMAL(10, 2)," +
                "binary_data BLOB," +
                "float_value FLOAT," +
                "double_value DOUBLE," +
                "numeric_value NUMERIC," +
                "timestamp_value TIMESTAMP" +
                ")";
        pgConn1.createStatement().execute(sql);

        sql = "INSERT INTO testBindInsert (id, name, birth_date, is_active, salary, binary_data, float_value, double_value, numeric_value, timestamp_value) " +
                "VALUES " +
                "(?, 'John Doe', '1990-01-01', TRUE, 50000.00, X'48454C4C', 3.14, 3.14159, 12345, '2023-06-30 12:00:00')";
        PreparedStatement pStmt = pgConn1.prepareStatement(sql);
        pStmt.setInt(1, 99);
        pStmt.executeUpdate();

        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from testBindInsert Where id = 1");
        while (rs.next()) {
            assert rs.getInt("id") == 99;
            assert rs.getString("name").equals("John Doe");
            assert rs.getDate("birth_date").toString().equals("1990-01-01");
            assert !rs.getBoolean("is_active");
            assert rs.getBigDecimal("salary").longValue() == 50000;
//            Blob blob = rs.getBlob("binary_data");
            assert Math.abs(rs.getFloat("float_value") - 3.14) < 0.001;
            assert rs.getDouble("double_value") == 3.14159;
            assert rs.getDouble("numeric_value") == 12345;
            assert rs.getTimestamp("timestamp_value").toInstant().getEpochSecond() == 1688097600;
        }
        rs.close();
        pStmt.close();
        pgConn1.close();
    }

//    @Test
    void BatchInsert() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:4309/mem";
        Connection pgConn1 = DriverManager.getConnection(
                connectURL, "", "");
        pgConn1.setAutoCommit(false);

        String sql = "CREATE TABLE testBatchInsert ( " +
                "id INTEGER PRIMARY KEY," +
                "name VARCHAR(100)," +
                "birth_date DATE," +
                "is_active BOOLEAN," +
                "salary DECIMAL(10, 2)," +
                "binary_data BLOB," +
                "float_value FLOAT," +
                "double_value DOUBLE," +
                "numeric_value NUMERIC," +
                "timestamp_value TIMESTAMP" +
                ")";
        pgConn1.createStatement().execute(sql);

        sql = "INSERT INTO testBindInsert (id, name, birth_date, is_active, salary, binary_data, float_value, double_value, numeric_value, timestamp_value) " +
                "VALUES " +
                "(?, 'John Doe', '1990-01-01', TRUE, 50000.00, X'48454C4C', 3.14, 3.14159, 12345, '2023-06-30 12:00:00')";
        PreparedStatement pStmt = pgConn1.prepareStatement(sql);
        int expectedResult = 0;
        for (int i=1; i<=100; i++) {
            pStmt.setInt(1, i);
            pStmt.addBatch();
            expectedResult = expectedResult + i;
        }
        pStmt.executeBatch();

        int actualResult = 0;
        ResultSet rs = pgConn1.createStatement().executeQuery("SELECT * from testBatchInsert Where id = 1");
        while (rs.next()) {
            actualResult = actualResult + rs.getInt("id");
        }
        rs.close();
        pStmt.close();
        pgConn1.close();

        assert expectedResult == actualResult;
    }

    @AfterAll
    static void tearDownAll() {
        if (dbThread != null) {
            dbThread.interrupt();
        }
    }
}
