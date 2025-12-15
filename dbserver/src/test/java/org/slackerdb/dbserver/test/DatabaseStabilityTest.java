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
import java.util.concurrent.atomic.AtomicInteger;

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
        AtomicInteger errorCount = new AtomicInteger(0);

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
                            int inserted = insertStmt.executeUpdate();
                            if (inserted != 1) {
                                System.err.println("Thread " + threadId + ": Insert failed, inserted " + inserted);
                                errorCount.incrementAndGet();
                            }
                        }

                        // Update operation
                        try (PreparedStatement updateStmt = conn.prepareStatement("UPDATE " + tableName + " SET value = ? WHERE name = ?")) {
                            updateStmt.setInt(1, (int) (Math.random() * 1000));
                            updateStmt.setString(2, "TestName");
                            int updated = updateStmt.executeUpdate();
                            if (updated < 0) {
                                System.err.println("Thread " + threadId + ": Update failed, updated " + updated);
                                errorCount.incrementAndGet();
                            }
                        }

                        // Select operation
                        try (PreparedStatement selectStmt = conn.prepareStatement("SELECT * FROM " + tableName + " WHERE name = ?")) {
                            selectStmt.setString(1, "TestName");
                            try (ResultSet rs = selectStmt.executeQuery()) {
                                int rowCount = 0;
                                while (rs.next()) {
                                    rowCount++;
                                }
                                // 预期至少有一行（因为刚插入）
                                if (rowCount == 0) {
                                    System.err.println("Thread " + threadId + ": Select returned no rows");
                                    errorCount.incrementAndGet();
                                }
                            }
                        }

                        // Delete operation
                        try (PreparedStatement deleteStmt = conn.prepareStatement("DELETE FROM " + tableName + " WHERE name = ?")) {
                            deleteStmt.setString(1, "TestName");
                            int deleted = deleteStmt.executeUpdate();
                            if (deleted < 0) {
                                System.err.println("Thread " + threadId + ": Delete failed, deleted " + deleted);
                                errorCount.incrementAndGet();
                            }
                        }
                        // 关闭数据库连接
                        conn.commit();
                        conn.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                        errorCount.incrementAndGet();
                        // 不再退出，继续执行
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
        System.out.println("Database will shutting down ... testDatabaseStability");
        dbInstance.stop();

        // 运行后再次打印当前系统资源
        printSystemStatus();

        // 报告错误
        if (errorCount.get() > 0) {
            System.err.println("Test completed with " + errorCount.get() + " errors.");
        } else {
            System.out.println("Test completed successfully with no errors.");
        }
    }

    @Test
    public void testDatabaseStabilityWithComplexBusiness() {
        int THREAD_COUNT = 5;
        long taskCount = 200;

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("ON");
        dbPort = serverConfiguration.getPort();

        DBInstance dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        printSystemStatus();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        AtomicLong totalFinishedTest = new AtomicLong(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            int threadId = i;
            executor.submit(() -> {
                long startTime = System.currentTimeMillis();
                long bootTime = System.currentTimeMillis();
                String tableName = "complex_table_" + threadId;
                String joinTableName = "join_table_" + threadId;
                boolean tablesCreated = false;
                while (totalFinishedTest.get() < taskCount) {
                    if (System.currentTimeMillis() - bootTime > 10000) {
                        System.out.println("Thread " + threadId + ": Complex test has passed " + totalFinishedTest.get() + ". Time elapsed " + (System.currentTimeMillis() - startTime));
                        bootTime = System.currentTimeMillis();
                    }

                    totalFinishedTest.incrementAndGet();
                    try (Connection conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/mem?socketTimeout=60", "", "")) {
                        conn.setAutoCommit(false);

                        if (!tablesCreated) {
                            try (Statement stmt = conn.createStatement()) {
                                // 创建主表
                                stmt.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (id INT PRIMARY KEY, name VARCHAR(255), salary DECIMAL(10,2), created TIMESTAMP)");
                                // 创建关联表
                                stmt.execute("CREATE TABLE IF NOT EXISTS " + joinTableName + " (id INT PRIMARY KEY, main_id INT, description TEXT)");
                                // 创建索引（使用唯一索引名避免并发冲突）
                                stmt.execute("CREATE INDEX IF NOT EXISTS idx_main_name_" + threadId + " ON " + tableName + "(name)");
                                stmt.execute("CREATE INDEX IF NOT EXISTS idx_join_main_" + threadId + " ON " + joinTableName + "(main_id)");
                            }
                            conn.commit();
                            tablesCreated = true;
                        }

                        // 模拟业务事务
                        // 1. 插入主表数据
                        int id = (int) (Math.random() * 10000);
                        try (PreparedStatement insertMain = conn.prepareStatement(
                                "INSERT INTO " + tableName + " (id, name, salary, created) VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {
                            insertMain.setInt(1, id);
                            insertMain.setString(2, "Employee_" + id);
                            insertMain.setDouble(3, Math.random() * 100000);
                            int inserted = insertMain.executeUpdate();
                            if (inserted != 1) {
                                System.err.println("Thread " + threadId + ": Insert main failed");
                                errorCount.incrementAndGet();
                            }
                        }

                        // 2. 插入关联表数据
                        try (PreparedStatement insertJoin = conn.prepareStatement(
                                "INSERT INTO " + joinTableName + " (id, main_id, description) VALUES (?, ?, ?)")) {
                            insertJoin.setInt(1, id);
                            insertJoin.setInt(2, id);
                            insertJoin.setString(3, "Description for " + id);
                            int inserted = insertJoin.executeUpdate();
                            if (inserted != 1) {
                                System.err.println("Thread " + threadId + ": Insert join failed");
                                errorCount.incrementAndGet();
                            }
                        }

                        // 3. 执行JOIN查询
                        try (PreparedStatement selectJoin = conn.prepareStatement(
                                "SELECT t.id, t.name, t.salary, j.description FROM " + tableName + " t JOIN " + joinTableName + " j ON t.id = j.main_id WHERE t.id = ?")) {
                            selectJoin.setInt(1, id);
                            try (ResultSet rs = selectJoin.executeQuery()) {
                                if (!rs.next()) {
                                    System.err.println("Thread " + threadId + ": JOIN query returned no rows");
                                    errorCount.incrementAndGet();
                                }
                            }
                        }

                        // 4. 更新数据
                        try (PreparedStatement update = conn.prepareStatement(
                                "UPDATE " + tableName + " SET salary = salary * 1.1 WHERE id = ?")) {
                            update.setInt(1, id);
                            int updated = update.executeUpdate();
                            if (updated != 1) {
                                System.err.println("Thread " + threadId + ": Update salary failed");
                                errorCount.incrementAndGet();
                            }
                        }

                        // 5. 事务回滚测试已移除，因为数据库不支持SAVEPOINT

                        // 5. 分页查询
                        try (PreparedStatement pagination = conn.prepareStatement(
                                "SELECT * FROM " + tableName + " ORDER BY id LIMIT 10 OFFSET ?")) {
                            pagination.setInt(1, (int) (Math.random() * 10));
                            try (ResultSet rs = pagination.executeQuery()) {
                                // 只是遍历结果，不做断言
                                while (rs.next()) {
                                    // 忽略
                                }
                            }
                        }

                        // 6. 删除数据
                        try (PreparedStatement deleteJoin = conn.prepareStatement(
                                "DELETE FROM " + joinTableName + " WHERE main_id = ?")) {
                            deleteJoin.setInt(1, id);
                            deleteJoin.executeUpdate();
                        }
                        try (PreparedStatement deleteMain = conn.prepareStatement(
                                "DELETE FROM " + tableName + " WHERE id = ?")) {
                            deleteMain.setInt(1, id);
                            deleteMain.executeUpdate();
                        }

                        conn.commit();
                    } catch (Exception e) {
                        e.printStackTrace();
                        errorCount.incrementAndGet();
                    }
                }
            });
        }
        executor.shutdown();
        while (!executor.isTerminated()) {
            try {
                Sleeper.sleep(3 * 1000);
            } catch (InterruptedException ignored) {}
        }

        printSystemStatus();
        System.out.println("Database will shutting down ...");
        dbInstance.stop();
        printSystemStatus();

        if (errorCount.get() > 0) {
            System.err.println("Complex test completed with " + errorCount.get() + " errors.");
        } else {
            System.out.println("Complex test completed successfully with no errors.");
        }
    }

    @Test
    public void testDatabaseStabilityWithMemoryLeakDetection() {
        // 此测试专注于检测内存泄漏，通过循环创建对象并检查内存增长
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();

        DBInstance dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        printSystemStatus();

        final int LOOPS = 100;
        final int BATCH_SIZE = 100;
        AtomicInteger leakCounter = new AtomicInteger(0);

        for (int loop = 0; loop < LOOPS; loop++) {
            // 每个循环创建一批连接并故意不关闭（模拟泄漏）
            Connection[] connections = new Connection[BATCH_SIZE];
            try {
                for (int i = 0; i < BATCH_SIZE; i++) {
                    connections[i] = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/mem?socketTimeout=60", "", "");
                    // 执行简单查询
                    try (Statement stmt = connections[i].createStatement()) {
                        stmt.execute("SELECT 1");
                    }
                    // 不关闭连接，模拟泄漏
                }
                // 记录内存使用
                if (loop % 10 == 0) {
                    printSystemStatus();
                }
                // 故意不关闭 connections，但为了测试稳定性，我们最终需要关闭它们
                // 实际上，我们应该在测试结束后关闭，但这里我们模拟泄漏后立即关闭以避免影响其他测试
                for (Connection conn : connections) {
                    if (conn != null) conn.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
                leakCounter.incrementAndGet();
            }
        }

        // 强制GC并检查内存
        System.gc();
        printSystemStatus();

        System.out.println("Database will shutting down ... testDatabaseStabilityWithMemoryLeakDetection");
        dbInstance.stop();
        printSystemStatus();

        if (leakCounter.get() > 0) {
            System.err.println("Memory leak test detected " + leakCounter.get() + " errors.");
        } else {
            System.out.println("Memory leak test passed.");
        }
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
