package org.slackerdb.dbserver.test;

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
    netsh int ipv4 set dynamicport tcp start=1024 num=64511


    目前测试结果：
        笔记本下： 基于20线程，持续运行1分钟，可以正常完成
* */

public class DatabaseStabilityTest {
    private static final int THREAD_COUNT = 20;
    private static final long TEST_DURATION_MS = 1 * 60 * 1000;
    public static void main(String[] args) {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(4309);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");

        // 初始化数据库
        DBInstance dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        // 运行前打印当前系统资源
        printSystemStatus();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        long endTime = System.currentTimeMillis() + TEST_DURATION_MS;

        AtomicLong totalFinishedTest = new AtomicLong(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            int threadId = i;
            executor.submit(() -> {
                long  startTime = System.currentTimeMillis();
                long  bootTime = System.currentTimeMillis();
                String tableName = "test_table_" + threadId;
                boolean tableCreated = false;
                while (System.currentTimeMillis() < endTime) {
                    if (System.currentTimeMillis() - bootTime > 10000)
                    {
                        System.out.println("Test has passed " + totalFinishedTest.get() + ". Time elapsed " + (System.currentTimeMillis() - startTime));
                        bootTime = System.currentTimeMillis();
                    }

                    totalFinishedTest.incrementAndGet();
                    try {
                        Connection conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:4309/mem", "", "");
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
            Sleeper.sleep(10*1000);
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

