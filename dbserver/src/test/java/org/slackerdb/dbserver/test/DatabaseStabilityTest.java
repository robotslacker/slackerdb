package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.Test;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.*;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/*
    受限于Windows操作系统的限制，可能会出现客户端端口不够用的情况，非程序问题。可进行如下调整
    netsh int ipv4 set dynamicPort tcp start=1024 num=64511


    目前测试结果：
        笔记本(16GMem, 8Core, 10thread.  i5 2.5GHz, Windows11)下： 持续运行2170秒，完成事务100万次
        服务器1(256GMem, 36Core, 40thread.  i9 3.0GHz, DebianLinux)下： 持续运行200秒，完成事务100万次
        服务器2(256GMem, 48Core, 40thread.  Xeon 2.9GHz, CentOS8)下： 持续运行220秒，完成事务100万次

* */

public class DatabaseStabilityTest {
    private int dbPort = 4309;

    @Test
    public void testDatabaseStability() {
        int THREAD_COUNT = 10;
        long taskCount = 1000;

        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();

        // 初始化数据库
        DBInstance dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        // 运行前打印当前系统资源
        printSystemStatus();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        AtomicLong totalFinishedTest = new AtomicLong(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            int threadId = i;
            executor.submit(() -> {
                long  startTime = System.currentTimeMillis();
                long  bootTime = System.currentTimeMillis();
                String tableName = "test_table_" + threadId;
                boolean tableCreated = false;
                while (totalFinishedTest.get() < taskCount) {
                    if (System.currentTimeMillis() - bootTime > 10000)
                    {
                        System.out.println("Thread " + threadId + ": Test has passed " + totalFinishedTest.get() + ". Time elapsed " + (System.currentTimeMillis() - startTime));
                        bootTime = System.currentTimeMillis();
                    }

                    totalFinishedTest.incrementAndGet();
                    try {
                        Connection conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/mem?socketTimeout=60", "", "");
                        conn.setAutoCommit(false);
                        if (!tableCreated)
                        {
                            Statement stmt = conn.createStatement();
                            String createTableSQL = String.format(
                                    "CREATE TABLE IF NOT EXISTS %s (" +
                                            "name VARCHAR(255), " +
                                            "value INT)",
                                    tableName);
                            stmt.execute(createTableSQL);
                            stmt.close();
                            conn.commit();
                            tableCreated = true;
                        }
                        // Insert operation
                        try (PreparedStatement insertStmt = conn.prepareStatement("INSERT INTO " + tableName + " (name, value) VALUES (?, ?)")) {
                            insertStmt.setString(1, "TestName");
                            insertStmt.setInt(2, (int) (Math.random() * 1000));
                            insertStmt.executeUpdate();
                        }

                        // Update operation
                        try (PreparedStatement updateStmt = conn.prepareStatement("UPDATE " + tableName + " SET value = ? WHERE name = ?")) {
                            updateStmt.setInt(1, (int) (Math.random() * 1000));
                            updateStmt.setString(2, "TestName");
                            updateStmt.executeUpdate();
                        }

                        // Select operation
                        try (PreparedStatement selectStmt = conn.prepareStatement("SELECT * FROM " + tableName + " WHERE name = ?")) {
                            selectStmt.setString(1, "TestName");
                            try (ResultSet rs = selectStmt.executeQuery()) {
                                //noinspection ALL
                                while (rs.next()) {
//                                    System.out.println("Thread " + threadId + " - Name: " + rs.getString("name") + ", Value: " + rs.getInt("value"));
                                }
                            }
                        }

                        // Delete operation
                        try (PreparedStatement deleteStmt = conn.prepareStatement("DELETE FROM " + tableName + " WHERE name = ?")) {
                            deleteStmt.setString(1, "TestName");
                            deleteStmt.executeUpdate();
                        }
                        // 关闭数据库连接
                        conn.commit();
                        conn.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.exit(0);
                    }
                }
            });
        }
        executor.shutdown();
        while (!executor.isTerminated()) {
            // 等待所有任务完成
            try {
                Sleeper.sleep(3 * 1000);
            }
            catch (InterruptedException ignored) {}
        }
        // 运行后再次打印当前系统资源
        printSystemStatus();

        // 关闭数据库
        System.out.println("Database will shutting down ...");
        dbInstance.stop();

        // 运行后再次打印当前系统资源
        printSystemStatus();
    }

    @Test
    public void testDatabaseStability2() {
        int THREAD_COUNT = 10;
        long taskCount = 100;

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
        DBInstance dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        // 运行前打印当前系统资源
        printSystemStatus();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        AtomicLong totalFinishedTest = new AtomicLong(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            int threadId = i;
            executor.submit(() -> {
                long  startTime = System.currentTimeMillis();
                long  bootTime = System.currentTimeMillis();
                String tableName = "test_table_" + threadId;
                boolean tableCreated = false;
                while (totalFinishedTest.get() < taskCount) {
                    if (System.currentTimeMillis() - bootTime > 10000)
                    {
                        System.out.println("Thread " + threadId + ": Test has passed " + totalFinishedTest.get() + ". Time elapsed " + (System.currentTimeMillis() - startTime));
                        bootTime = System.currentTimeMillis();
                    }

                    totalFinishedTest.incrementAndGet();
                    try {
                        Connection conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/mem?socketTimeout=60", "", "");
                        conn.setAutoCommit(false);
                        if (!tableCreated)
                        {
                            Statement stmt = conn.createStatement();
                            String createTableSQL = String.format(
                                    "CREATE TABLE IF NOT EXISTS %s (" +
                                            "name VARCHAR(255), " +
                                            "value INT)",
                                    tableName);
                            stmt.execute(createTableSQL);
                            stmt.close();
                            conn.commit();
                            tableCreated = true;
                        }
                        // Insert operation
                        try (PreparedStatement insertStmt = conn.prepareStatement("INSERT INTO " + tableName + " (name, value) VALUES (?, ?)")) {
                            insertStmt.setString(1, "TestName");
                            insertStmt.setInt(2, (int) (Math.random() * 1000));
                            insertStmt.executeUpdate();
                        }

                        // Update operation
                        try (PreparedStatement updateStmt = conn.prepareStatement("UPDATE " + tableName + " SET value = ? WHERE name = ?")) {
                            updateStmt.setInt(1, (int) (Math.random() * 1000));
                            updateStmt.setString(2, "TestName");
                            updateStmt.executeUpdate();
                        }

                        // Select operation
                        try (PreparedStatement selectStmt = conn.prepareStatement("SELECT * FROM " + tableName + " WHERE name = ?")) {
                            selectStmt.setString(1, "TestName");
                            try (ResultSet rs = selectStmt.executeQuery()) {
                                //noinspection ALL
                                while (rs.next()) {
//                                    System.out.println("Thread " + threadId + " - Name: " + rs.getString("name") + ", Value: " + rs.getInt("value"));
                                }
                            }
                        }

                        // Delete operation
                        try (PreparedStatement deleteStmt = conn.prepareStatement("DELETE FROM " + tableName + " WHERE name = ?")) {
                            deleteStmt.setString(1, "TestName");
                            deleteStmt.executeUpdate();
                        }
                        // 关闭数据库连接
                        conn.commit();
                        conn.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.exit(0);
                    }
                }
            });
        }
        executor.shutdown();
        while (!executor.isTerminated()) {
            // 等待所有任务完成
            try {
                Sleeper.sleep(3 * 1000);
            }
            catch (InterruptedException ignored) {}
        }
        // 运行后再次打印当前系统资源
        printSystemStatus();

        // 关闭数据库
        System.out.println("Database will shutting down ...");
        dbInstance.stop();

        // 运行后再次打印当前系统资源
        printSystemStatus();
    }

    private static void printSystemStatus() {
        Runtime runtime = Runtime.getRuntime();
        long memoryUsed = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        int threadCount = Thread.activeCount();
        System.out.printf("Memory used: %d bytes, Max memory: %d bytes. Active threads: %d%n",
                memoryUsed, maxMemory, threadCount);
    }
}

