package org.slackerdb.dbserver.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.server.DBDataSourcePool;
import org.slackerdb.dbserver.server.DBDataSourcePoolConfig;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连接池单元测试。
 * 测试重点：并发获取/释放连接、超配（over-subscription）、超时、连接生命周期等场景。
 * <p>
 * 注意：这些测试直接使用 DBDataSourcePool，不依赖完整的 DBInstance 启动。
 * 使用 DuckDB 原生 JDBC 驱动（org.duckdb.DuckDBDriver），不走 DriverManager SPI。
 */
public class DBDataSourcePoolTest {

    private static final Logger logger = (Logger) LoggerFactory.getLogger(DBDataSourcePoolTest.class);

    // DuckDB 驱动实例，供所有测试共享
    private static Driver duckdbDriver;

    @BeforeAll
    static void initAll() throws Exception {
        // 关闭连接池的调试日志，避免刷屏
        Logger poolLogger = (Logger) LoggerFactory.getLogger("org.slackerdb.dbserver.server.DBDataSourcePool");
        poolLogger.setLevel(Level.WARN);

        // 创建 DuckDB 驱动实例（不依赖 DriverManager SPI 注册）
        duckdbDriver = (Driver) Class.forName("org.duckdb.DuckDBDriver").getDeclaredConstructor().newInstance();
    }

    @AfterAll
    static void tearDownAll() {
        // 恢复日志级别
        Logger poolLogger = (Logger) LoggerFactory.getLogger("org.slackerdb.dbserver.server.DBDataSourcePool");
        poolLogger.setLevel(Level.DEBUG);
    }

    /**
     * 创建连接池配置（使用 DuckDB 内存数据库）
     * 注意：DuckDB 1.5.x 中 jdbc:duckdb:mem: 会被解析为加载 mem 扩展，
     * 正确的内存数据库 URL 是 jdbc:duckdb:（不带 mem）
     */
    private static DBDataSourcePoolConfig createPoolConfig(int maxPoolSize, int minIdle, int maxIdle) {
        DBDataSourcePoolConfig config = new DBDataSourcePoolConfig();
        config.setJdbcURL("jdbc:duckdb:");
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setMaximumIdle(maxIdle);
        config.setAutoCommit(true);
        config.setConnectionAcquireTimeoutMs(30_000);
        config.setDriver(duckdbDriver);
        return config;
    }

    // ============================================================
    // 基础功能测试
    // ============================================================

    @Test
    void testBasicGetAndRelease() throws Exception {
        // minimumIdle 设为 0，避免监控线程在此测试中补充空闲连接导致竞态
        DBDataSourcePoolConfig config = createPoolConfig(5, 0, 3);
        DBDataSourcePool pool = new DBDataSourcePool("testBasic", config, logger);

        // 初始无空闲连接
        assertEquals(0, pool.getIdleConnectionPoolSize());
        assertEquals(0, pool.getUsedConnectionPoolSize());

        // 获取一个连接
        Connection conn = pool.getConnection();
        assertNotNull(conn);
        assertEquals(0, pool.getIdleConnectionPoolSize());
        assertEquals(1, pool.getUsedConnectionPoolSize());

        // 验证连接可用
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }

        // 释放连接
        pool.releaseConnection(conn);
        assertEquals(1, pool.getIdleConnectionPoolSize());
        assertEquals(0, pool.getUsedConnectionPoolSize());

        pool.shutdown();
    }

    @Test
    void testGetMultipleConnections() throws Exception {
        // minimumIdle 设为 0，避免监控线程在测试期间补充空闲连接，
        // 导致空闲连接数不为 0 的竞态问题（连接数断言可精确确定）。
        DBDataSourcePoolConfig config = createPoolConfig(10, 0, 5);
        DBDataSourcePool pool = new DBDataSourcePool("testMulti", config, logger);

        // minimumIdle 为 0，初始无空闲连接
        assertEquals(0, pool.getIdleConnectionPoolSize());
        assertEquals(0, pool.getUsedConnectionPoolSize());

        // 获取 5 个连接
        List<Connection> connections = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Connection conn = pool.getConnection();
            assertNotNull(conn);
            connections.add(conn);
        }

        assertEquals(0, pool.getIdleConnectionPoolSize());
        assertEquals(5, pool.getUsedConnectionPoolSize());

        // 释放所有连接
        for (Connection conn : connections) {
            pool.releaseConnection(conn);
        }

        // After releasing 5 connections, all 5 go back to idle pool
        assertEquals(5, pool.getIdleConnectionPoolSize());
        assertEquals(0, pool.getUsedConnectionPoolSize());

        pool.shutdown();
    }

    @Test
    void testReleaseNullConnection() throws Exception {
        DBDataSourcePoolConfig config = createPoolConfig(5, 0, 5);
        DBDataSourcePool pool = new DBDataSourcePool("testNullRelease", config, logger);
        // 释放 null 不应抛异常
        pool.releaseConnection(null);
        pool.shutdown();
    }

    // ============================================================
    // 并发测试
    // ============================================================

    @Test
    void testConcurrentGetAndRelease() throws Exception {
        int poolSize = 10;
        int threadCount = 50;
        int iterationsPerThread = 20;

        DBDataSourcePoolConfig config = createPoolConfig(poolSize, 2, poolSize);
        config.setConnectionAcquireTimeoutMs(30_000);
        DBDataSourcePool pool = new DBDataSourcePool("testConcurrent", config, logger);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // 使用 CountDownLatch 让所有线程同时开始
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            Thread thread = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        Connection conn = null;
                        try {
                            conn = pool.getConnection();
                            // 模拟短时间使用
                            try (Statement stmt = conn.createStatement();
                                 ResultSet rs = stmt.executeQuery("SELECT " + threadId + " + " + i)) {
                                if (rs.next()) {
                                    int expected = threadId + i;
                                    assertEquals(expected, rs.getInt(1));
                                }
                            }
                            successCount.incrementAndGet();
                        } catch (SQLException e) {
                            failureCount.incrementAndGet();
                            logger.warn("Thread {} iteration {} failed: {}", threadId, i, e.getMessage());
                        } finally {
                            if (conn != null) {
                                pool.releaseConnection(conn);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
            thread.setName("PoolTest-" + t);
            thread.start();
        }

        startLatch.countDown();
        doneLatch.await();

        int totalExpected = threadCount * iterationsPerThread;
        assertEquals(totalExpected, successCount.get(),
                "All " + totalExpected + " operations should succeed");
        assertEquals(0, failureCount.get(),
                "No operations should fail");

        pool.shutdown();
    }

    @Test
    void testConcurrentOverSubscription() throws Exception {
        // 测试超配场景：线程数 > 连接池大小，验证不会死锁
        int poolSize = 5;
        int threadCount = 20;
        int iterationsPerThread = 10;

        DBDataSourcePoolConfig config = createPoolConfig(poolSize, 0, poolSize);
        config.setConnectionAcquireTimeoutMs(60_000);
        DBDataSourcePool pool = new DBDataSourcePool("testOverSub", config, logger);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            Thread thread = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        Connection conn = null;
                        try {
                            conn = pool.getConnection();
                            // 模拟较长的使用时间，让其他线程等待
                            Thread.sleep(10);
                            try (Statement stmt = conn.createStatement();
                                 ResultSet rs = stmt.executeQuery("SELECT 42")) {
                                assertTrue(rs.next());
                                assertEquals(42, rs.getInt(1));
                            }
                            successCount.incrementAndGet();
                        } catch (SQLException e) {
                            failureCount.incrementAndGet();
                            logger.warn("OverSub iteration failed: {}", e.getMessage());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            if (conn != null) {
                                pool.releaseConnection(conn);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
            thread.start();
        }

        startLatch.countDown();
        doneLatch.await();

        int totalExpected = threadCount * iterationsPerThread;
        assertEquals(totalExpected, successCount.get(),
                "All " + totalExpected + " operations should succeed despite over-subscription");
        assertEquals(0, failureCount.get(),
                "No operations should fail");

        pool.shutdown();
    }

    @Test
    void testConcurrentPeakLoad() throws Exception {
        // 测试峰值负载：大量线程同时获取连接，模拟突发流量
        int poolSize = 20;
        int threadCount = 100;

        DBDataSourcePoolConfig config = createPoolConfig(poolSize, poolSize, poolSize);
        config.setConnectionAcquireTimeoutMs(30_000);
        DBDataSourcePool pool = new DBDataSourcePool("testPeakLoad", config, logger);

        // 等待监控线程填充连接
        Thread.sleep(500);

        // 初始应该有 poolSize 个空闲连接（监控线程可能已补充更多，所以用 >=）
        assertTrue(pool.getIdleConnectionPoolSize() >= poolSize,
                "Should have at least " + poolSize + " idle connections, got " + pool.getIdleConnectionPoolSize());

        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        long startTime = System.nanoTime();

        for (int t = 0; t < threadCount; t++) {
            Thread thread = new Thread(() -> {
                try {
                    startLatch.await();
                    Connection conn = pool.getConnection();
                    try {
                        // 每个线程只做一次查询，模拟突发请求
                        try (Statement stmt = conn.createStatement();
                             ResultSet rs = stmt.executeQuery("SELECT 100")) {
                            assertTrue(rs.next());
                            assertEquals(100, rs.getInt(1));
                        }
                        successCount.incrementAndGet();
                    } finally {
                        pool.releaseConnection(conn);
                    }
                } catch (Exception e) {
                    logger.error("Peak load thread failed: {}", e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
            thread.start();
        }

        startLatch.countDown();
        doneLatch.await();

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        assertEquals(threadCount, successCount.get(),
                "All " + threadCount + " threads should succeed");
        assertTrue(elapsedMs < 30_000,
                "Peak load test should complete within timeout, took " + elapsedMs + "ms");

        logger.info("Peak load test completed: {} threads in {}ms", threadCount, elapsedMs);

        pool.shutdown();
    }

    // ============================================================
    // 超时测试
    // ============================================================

    @Test
    void testConnectionAcquireTimeout() throws Exception {
        int poolSize = 2;
        long timeoutMs = 1000;

        DBDataSourcePoolConfig config = createPoolConfig(poolSize, 0, poolSize);
        config.setConnectionAcquireTimeoutMs(timeoutMs);
        DBDataSourcePool pool = new DBDataSourcePool("testTimeout", config, logger);

        // 占满所有连接
        List<Connection> connections = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            connections.add(pool.getConnection());
        }

        // 此时再获取连接应该超时
        long startTime = System.currentTimeMillis();
        try {
            pool.getConnection();
            fail("Should have thrown SQLException due to timeout");
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            assertTrue(e.getMessage().contains("Timeout"),
                    "Error message should mention timeout: " + e.getMessage());
            // 超时时间应该在合理范围内（允许一些误差）
            assertTrue(elapsed >= timeoutMs - 200,
                    "Timeout should not fire too early: " + elapsed + "ms < " + timeoutMs + "ms");
            assertTrue(elapsed < timeoutMs + 2000,
                    "Timeout should not take too long: " + elapsed + "ms");
        }

        // 释放一个连接后，应该能获取到
        pool.releaseConnection(connections.remove(0));
        Connection conn = pool.getConnection();
        assertNotNull(conn);
        pool.releaseConnection(conn);

        // 清理剩余连接
        for (Connection c : connections) {
            pool.releaseConnection(c);
        }

        pool.shutdown();
    }

    @Test
    void testZeroTimeout() throws Exception {
        // timeoutMs = 0 表示无限等待
        int poolSize = 1;

        DBDataSourcePoolConfig config = createPoolConfig(poolSize, 0, poolSize);
        config.setConnectionAcquireTimeoutMs(0); // 无限等待
        DBDataSourcePool pool = new DBDataSourcePool("testZeroTimeout", config, logger);

        // 占满连接
        Connection conn1 = pool.getConnection();

        // 在另一个线程中获取连接（会阻塞等待）
        AtomicInteger result = new AtomicInteger(-1);
        Thread worker = new Thread(() -> {
            try {
                Connection c = pool.getConnection();
                try (Statement stmt = c.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT 999")) {
                    if (rs.next()) {
                        result.set(rs.getInt(1));
                    }
                } finally {
                    pool.releaseConnection(c);
                }
            } catch (SQLException e) {
                result.set(-2);
            }
        });
        worker.start();

        // 等待一会儿，确认 worker 在等待
        Thread.sleep(500);
        assertEquals(-1, result.get(), "Worker should be waiting");

        // 释放连接，worker 应该能获取到
        pool.releaseConnection(conn1);
        worker.join(5000);

        assertEquals(999, result.get(), "Worker should have succeeded");

        pool.shutdown();
    }

    // ============================================================
    // 连接生命周期测试
    // ============================================================

    @Test
    void testConnectionLifecycle() throws Exception {
        // 设置很短的连接生命周期，验证连接会被回收
        int maxLifeMs = 500;

        DBDataSourcePoolConfig config = createPoolConfig(5, 1, 3);
        config.setMaximumLifeCycleTime(maxLifeMs);
        DBDataSourcePool pool = new DBDataSourcePool("testLifecycle", config, logger);

        // 获取一个连接
        Connection conn = pool.getConnection();
        assertNotNull(conn);

        // 等待连接过期
        Thread.sleep(maxLifeMs + 500);

        // 释放连接，应该被回收（因为已过期）
        pool.releaseConnection(conn);

        // 获取新连接
        Connection conn2 = pool.getConnection();
        assertNotNull(conn2);
        assertNotSame(conn, conn2, "Should get a new connection, not the expired one");

        pool.releaseConnection(conn2);
        pool.shutdown();
    }

    // ============================================================
    // 连接池监控线程测试
    // ============================================================

    @Test
    void testMonitorMaintainsMinimumIdle() throws Exception {
        int minimumIdle = 3;
        int maximumPoolSize = 10;

        DBDataSourcePoolConfig config = createPoolConfig(maximumPoolSize, minimumIdle, 5);
        DBDataSourcePool pool = new DBDataSourcePool("testMinIdle", config, logger);

        // 初始至少有 minimumIdle 个连接（由构造器及监控线程共同保证）
        int initialIdle = pool.getIdleConnectionPoolSize();
        assertTrue(initialIdle >= minimumIdle,
                "Should have at least minimumIdle (" + minimumIdle + ") idle connections, got " + initialIdle);

        // 获取一个连接再释放，验证连接始终可用且释放后能回到空闲池。
        // 注意：监控线程可能在此期间补充空闲连接，因此不做精确的空闲数量断言。
        Connection conn = pool.getConnection();
        assertNotNull(conn);
        pool.releaseConnection(conn);

        assertTrue(pool.getIdleConnectionPoolSize() >= minimumIdle,
                "Idle connections should still be at least minimumIdle");

        pool.shutdown();
    }

    @Test
    void testReleaseReturnsConnectionsToIdlePool() throws Exception {
        // maximumIdle 设为 0 禁用监控线程的修剪，避免监控线程在释放过程中
        // 修剪多余空闲连接导致空闲连接数断言出现竞态。本测试只验证释放逻辑：
        // 使用中的连接释放后应回到空闲池。
        int maximumIdle = 0;
        int maximumPoolSize = 10;

        DBDataSourcePoolConfig config = createPoolConfig(maximumPoolSize, 0, maximumIdle);
        DBDataSourcePool pool = new DBDataSourcePool("testMaxIdle", config, logger);

        // 手动创建多个连接并释放到空闲池
        List<Connection> connections = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            connections.add(pool.getConnection());
        }
        for (Connection conn : connections) {
            pool.releaseConnection(conn);
        }

        // 禁用修剪后，释放的空闲连接应全部保留在空闲池
        assertEquals(5, pool.getIdleConnectionPoolSize());
        assertEquals(0, pool.getUsedConnectionPoolSize());

        pool.shutdown();
    }

    // ============================================================
    // 连接池关闭测试
    // ============================================================

    @Test
    void testShutdownWithActiveConnections() throws Exception {
        DBDataSourcePoolConfig config = createPoolConfig(5, 1, 5);
        DBDataSourcePool pool = new DBDataSourcePool("testShutdown", config, logger);

        // 获取一些连接但不释放
        List<Connection> connections = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            connections.add(pool.getConnection());
        }
        assertEquals(3, connections.size());
        assertEquals(3, pool.getUsedConnectionPoolSize());

        // 直接关闭池（应该能正常关闭，不会抛异常）
        pool.shutdown();

        assertEquals(0, pool.getIdleConnectionPoolSize());
        assertEquals(0, pool.getUsedConnectionPoolSize());
    }

    @Test
    void testShutdownIdempotent() throws Exception {
        DBDataSourcePoolConfig config = createPoolConfig(5, 0, 5);
        DBDataSourcePool pool = new DBDataSourcePool("testShutdownIdem", config, logger);

        // 多次 shutdown 不应抛异常
        pool.shutdown();
        pool.shutdown();
        pool.shutdown();
    }

    // ============================================================
    // 高水位标记测试
    // ============================================================

    @Test
    void testHighWaterMark() throws Exception {
        DBDataSourcePoolConfig config = createPoolConfig(10, 0, 10);
        DBDataSourcePool pool = new DBDataSourcePool("testHWM", config, logger);

        assertEquals(0, pool.getHighWaterMark());

        // 获取 3 个连接
        List<Connection> conns = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            conns.add(pool.getConnection());
        }
        assertEquals(3, pool.getHighWaterMark());

        // 获取更多连接
        for (int i = 0; i < 4; i++) {
            conns.add(pool.getConnection());
        }
        assertEquals(7, pool.getHighWaterMark());

        // 释放后高水位不应下降
        for (Connection c : conns) {
            pool.releaseConnection(c);
        }
        assertEquals(7, pool.getHighWaterMark());

        pool.shutdown();
    }

    // ============================================================
    // 压力测试：长时间运行
    // ============================================================

    @Test
    void testSustainedLoad() throws Exception {
        // 持续负载测试：多个线程反复获取/释放连接
        int poolSize = 8;
        int threadCount = 16;
        int operationsPerThread = 50;

        DBDataSourcePoolConfig config = createPoolConfig(poolSize, 2, poolSize);
        config.setConnectionAcquireTimeoutMs(30_000);
        DBDataSourcePool pool = new DBDataSourcePool("testSustained", config, logger);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        Connection conn = null;
                        try {
                            conn = pool.getConnection();
                            // 模拟混合操作
                            try (Statement stmt = conn.createStatement()) {
                                // 查询
                                ResultSet rs = stmt.executeQuery("SELECT " + i);
                                assertTrue(rs.next());
                                assertEquals(i, rs.getInt(1));
                                rs.close();

                                // DDL
                                stmt.execute("CREATE OR REPLACE TABLE pool_test_sustained (id INT)");
                                // DML
                                stmt.execute("INSERT INTO pool_test_sustained VALUES (" + i + ")");
                                // 查询验证
                                rs = stmt.executeQuery("SELECT COUNT(*), SUM(id) FROM pool_test_sustained");
                                assertTrue(rs.next());
                                rs.close();
                            }
                            successCount.incrementAndGet();
                        } catch (SQLException e) {
                            failureCount.incrementAndGet();
                            logger.error("Sustained load error: {}", e.getMessage());
                        } finally {
                            if (conn != null) {
                                pool.releaseConnection(conn);
                            }
                        }
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
            thread.start();
        }

        doneLatch.await();

        int totalExpected = threadCount * operationsPerThread;
        assertEquals(totalExpected, successCount.get(),
                "All " + totalExpected + " operations should succeed");
        assertEquals(0, failureCount.get(),
                "No operations should fail");

        pool.shutdown();
    }

    // ============================================================
    // 边界条件测试
    // ============================================================

    @Test
    void testPoolSizeOne() throws Exception {
        // 连接池大小为 1，多个线程竞争
        DBDataSourcePoolConfig config = createPoolConfig(1, 0, 1);
        config.setConnectionAcquireTimeoutMs(10_000);
        DBDataSourcePool pool = new DBDataSourcePool("testSizeOne", config, logger);

        int threadCount = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            Thread thread = new Thread(() -> {
                try {
                    Connection conn = pool.getConnection();
                    try {
                        Thread.sleep(20);
                        try (Statement stmt = conn.createStatement();
                             ResultSet rs = stmt.executeQuery("SELECT 1")) {
                            assertTrue(rs.next());
                        }
                        successCount.incrementAndGet();
                    } finally {
                        pool.releaseConnection(conn);
                    }
                } catch (Exception e) {
                    logger.error("Pool size 1 test error: {}", e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
            thread.start();
        }

        doneLatch.await();
        assertEquals(threadCount, successCount.get(),
                "All " + threadCount + " threads should succeed with pool size 1");

        pool.shutdown();
    }

    @Test
    void testRapidAcquireRelease() throws Exception {
        // 快速获取/释放，验证没有资源泄漏
        DBDataSourcePoolConfig config = createPoolConfig(5, 0, 5);
        DBDataSourcePool pool = new DBDataSourcePool("testRapid", config, logger);

        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            Connection conn = pool.getConnection();
            assertNotNull(conn);
            // 快速使用
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT " + i)) {
                assertTrue(rs.next());
                assertEquals(i, rs.getInt(1));
            }
            pool.releaseConnection(conn);
        }

        // 验证池状态正常
        assertEquals(0, pool.getUsedConnectionPoolSize());
        assertTrue(pool.getIdleConnectionPoolSize() <= 5);

        pool.shutdown();
    }

    @Test
    void testInterleavedGetRelease() throws Exception {
        // 交错获取/释放，验证内部状态一致性
        DBDataSourcePoolConfig config = createPoolConfig(3, 0, 3);
        DBDataSourcePool pool = new DBDataSourcePool("testInterleaved", config, logger);

        Connection c1 = pool.getConnection();
        Connection c2 = pool.getConnection();
        assertEquals(2, pool.getUsedConnectionPoolSize());
        assertEquals(0, pool.getIdleConnectionPoolSize());

        pool.releaseConnection(c1);
        assertEquals(1, pool.getUsedConnectionPoolSize());
        assertEquals(1, pool.getIdleConnectionPoolSize());

        Connection c3 = pool.getConnection(); // 重用 c1
        assertEquals(2, pool.getUsedConnectionPoolSize());
        assertEquals(0, pool.getIdleConnectionPoolSize());

        pool.releaseConnection(c2);
        pool.releaseConnection(c3);
        assertEquals(0, pool.getUsedConnectionPoolSize());
        assertEquals(2, pool.getIdleConnectionPoolSize());

        pool.shutdown();
    }
}
