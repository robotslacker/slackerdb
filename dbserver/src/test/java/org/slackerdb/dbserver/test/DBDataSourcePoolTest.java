package org.slackerdb.dbserver.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.server.DBDataSourcePool;
import org.slackerdb.dbserver.server.DBDataSourcePoolConfig;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
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

    // ============================================================
    // 句柄语义："close() = 归还" 必须同时满足四件事
    //   ① 槽位回池  ② 语句关闭  ③ 未提交事务回滚  ④ autoCommit 复位
    // 池化之后物理连接不再真的关闭，这四条就是"close() 等于销毁"的替代品；
    // 少任何一条，上一个借用者的状态都会泄漏给下一个借用者。
    // ============================================================

    /**
     * 句柄 {@code close()} 必须把槽位还给池。
     *
     * <p>历史缺陷：{@code getConnection()} 直接发放物理连接，调用方按 JDBC 习惯
     * {@code try (Connection c = ...)} 关闭的是物理连接，池却仍认为它在使用中——
     * {@code connectionMetaDataMap} 只增不减，撑到上限后整台服务器再也建不起连接
     * （MCP 的 {@code list_tables} 每调用一次泄漏一个槽位）。</p>
     */
    @Test
    void testHandleCloseReturnsSlotToPool() throws Exception {
        DBDataSourcePoolConfig config = createPoolConfig(2, 0, 2);
        DBDataSourcePool pool = new DBDataSourcePool("testHandleClose", config, logger);

        Connection conn = pool.getConnection();
        assertEquals(1, pool.getUsedConnectionPoolSize());
        assertEquals(0, pool.getIdleConnectionPoolSize());

        conn.close();

        assertEquals(0, pool.getUsedConnectionPoolSize(),
                "the slot must return to the pool after close(), otherwise every call leaks one");
        assertEquals(1, pool.getIdleConnectionPoolSize());

        // close() 可重复调用（JDBC 规定），且不得重复归还
        conn.close();
        assertEquals(0, pool.getUsedConnectionPoolSize());
        assertEquals(1, pool.getIdleConnectionPoolSize());

        pool.shutdown();
    }

    /**
     * 归还连接必须连带关闭它产生的语句（JDBC 契约）。
     *
     * <p>物理连接不再真的关闭，如果语句不跟着关，调用方"只关连接、不关语句"时语句会残留在
     * 连接上，而这条连接随后会被别的会话借走——DuckDB 单条连接不是线程安全的，
     * 两个会话于是在同一批语句上互相踩。</p>
     */
    @Test
    void testHandleCloseClosesItsStatements() throws Exception {
        DBDataSourcePoolConfig config = createPoolConfig(2, 0, 2);
        DBDataSourcePool pool = new DBDataSourcePool("testCloseStatements", config, logger);

        Connection conn = pool.getConnection();
        Statement plainStatement = conn.createStatement();
        PreparedStatement preparedStatement = conn.prepareStatement("SELECT 1");
        assertFalse(plainStatement.isClosed(), "a freshly created statement must not be closed");
        assertFalse(preparedStatement.isClosed(), "a freshly created statement must not be closed");

        conn.close();

        assertTrue(plainStatement.isClosed(), "close() must also close plain statements (JDBC contract)");
        assertTrue(preparedStatement.isClosed(), "close() must also close PreparedStatements (JDBC contract)");

        // 语句自己先关掉，同样不能影响连接归还
        Connection conn2 = pool.getConnection();
        Statement statement2 = conn2.createStatement();
        statement2.close();
        conn2.close();
        assertEquals(0, pool.getUsedConnectionPoolSize());

        pool.shutdown();
    }

    /**
     * 归还连接必须回滚"显式事务"。
     *
     * <p>关键点：DuckDB 的显式 {@code BEGIN} <b>不会</b>反映到 {@code getAutoCommit()}（仍是 true），
     * 所以"只在 autoCommit=false 时才回滚"的实现会漏掉这种事务——而它恰恰是会话最常用的写法。
     * 不清理就放回池里，下一个借用者会看到上一个会话未提交的数据，
     * 甚至在自己 COMMIT 时把别人的改动一起提交。</p>
     */
    @Test
    void testReleasedConnectionRollsBackExplicitTransaction() throws Exception {
        // 池里只留一条连接：第二次借到的必然是同一条物理连接，才验证得了"状态清没清"
        DBDataSourcePoolConfig config = createPoolConfig(1, 0, 1);
        DBDataSourcePool pool = new DBDataSourcePool("testRollbackExplicit", config, logger);

        Connection first = pool.getConnection();
        try (Statement stmt = first.createStatement()) {
            stmt.execute("CREATE TABLE txn_probe(a INTEGER)");
            stmt.execute("BEGIN");
            stmt.execute("INSERT INTO txn_probe VALUES (1), (2), (3)");
        }
        assertTrue(first.getAutoCommit(),
                "precondition: an explicit BEGIN in DuckDB does not change getAutoCommit(), which is exactly what this case covers");

        // 不提交就归还
        pool.releaseConnection(first);

        Connection second = pool.getConnection();
        try (Statement stmt = second.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM txn_probe")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1),
                    "the 3 uncommitted rows of the previous borrower must be rolled back, otherwise the next borrower sees dirty data");
        }

        pool.releaseConnection(second);
        pool.shutdown();
    }

    /** 归还连接必须回滚 {@code autoCommit=false} 的事务，并把 autoCommit 复位到池配置值。 */
    @Test
    void testReleasedConnectionRestoresAutoCommit() throws Exception {
        DBDataSourcePoolConfig config = createPoolConfig(1, 0, 1);
        DBDataSourcePool pool = new DBDataSourcePool("testRestoreAutoCommit", config, logger);

        Connection first = pool.getConnection();
        // 建表放在关闭 autoCommit 之前：否则 CREATE TABLE 也会被回滚掉，后面的断言就没意义了
        try (Statement stmt = first.createStatement()) {
            stmt.execute("CREATE TABLE ac_probe(a INTEGER)");
        }
        first.setAutoCommit(false);
        try (Statement stmt = first.createStatement()) {
            stmt.execute("INSERT INTO ac_probe VALUES (1)");
        }
        assertFalse(first.getAutoCommit(), "precondition: the borrower turned autoCommit off");

        pool.releaseConnection(first);

        Connection second = pool.getConnection();
        assertTrue(second.getAutoCommit(),
                "release must restore autoCommit to the configured value (true), otherwise the next borrower silently starts inside a transaction");
        try (Statement stmt = second.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM ac_probe")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "uncommitted data must be rolled back when autoCommit=false");
        }

        pool.releaseConnection(second);
        pool.shutdown();
    }

    // ============================================================
    // 归还时"状态不可信"必须退役，而不是放回空闲池
    //   JDBC 的 close() 语义是"归还"，但调用方把语句留在连接上（或还有线程在语句里）时，
    //   "调用方已经不再碰这条连接"这个前提就不成立：复用等于把一条仍在被使用的连接
    //   交给下一个借用者，而 DuckDB 的单条连接不是线程安全的。
    // ============================================================

    /**
     * 归还连接时仍有未关闭的语句 → 语句由池关掉，**探活通过则复用**，探活失败才退役。
     *
     * <p>历史行为是把这类连接直接放回空闲池：调用方少写一个 {@code stmt.close()}
     * （或依赖"关连接时顺带关语句"）就会把一条还挂着语句的连接交给下一个借用者。</p>
     *
     * <p>2026-10-22 调整：只在"语句没关"时一律退役的代价太大 —— 长跑实例上每次失败批处理
     * 都会销毁一条 DuckDB 连接（实测 40 次失败批处理 = 40 次退役），连接反复创建/销毁，
     * 池也长期处于抖动状态。现在改为"先关语句，再跑一条 SELECT 1 探活"：
     * 探活证明没有残留的未消费结果，就可以安全复用；探活失败仍然退役。</p>
     */
    @Test
    void testHandleCloseWithOutstandingStatementRecoversConnection() throws Exception {
        DBDataSourcePoolConfig config = createPoolConfig(1, 0, 1);
        DBDataSourcePool pool = new DBDataSourcePool("testRecoverOutstanding", config, logger);

        Connection first = pool.getConnection();
        Statement leaked = first.createStatement();
        leaked.execute("CREATE TABLE leak_probe(a INTEGER)");
        assertFalse(leaked.isClosed(), "precondition: the statement is still open");
        // 刻意不关 leaked：调用方没有把语句收拾干净

        first.close();

        assertEquals(1, pool.getIdleConnectionPoolSize(),
                "the pool closed the leftover statement and the probe passed, so the connection must be reused instead of retired");
        assertEquals(0, pool.getUsedConnectionPoolSize());

        // 退役不能吃掉池的容量：下一次借到的必须是一条可用连接
        Connection second = pool.getConnection();
        try (Statement stmt = second.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertTrue(rs.next(), "the pool must still hand out a usable connection after retiring one");
            assertEquals(1, rs.getInt(1));
        }
        second.close();
        assertEquals(1, pool.getIdleConnectionPoolSize(),
                "there is no leftover statement this time, so the connection must go back to the idle pool");

        pool.shutdown();
    }

    /**
     * 归还连接时仍有语句<b>正在执行</b> → 必须退役。
     *
     * <p>用假驱动把"调用卡在语句方法里"做成确定状态，而不是靠真实查询抢时间：
     * 靠 sleep 抢窗口的用例要么偶发失败、要么慢到没人愿意跑。</p>
     *
     * <p>注意这条与上一条覆盖的是不同的窗口：语句已经 {@code close()} 过就会从登记表里摘掉，
     * 但另一个线程仍可能卡在它的方法里——只看"未关闭语句"会漏掉这种最危险的情况。</p>
     */
    @Test
    void testHandleCloseWhileStatementIsExecutingRetiresConnection() throws Exception {
        BlockingStatementDriver driver = new BlockingStatementDriver();
        DBDataSourcePoolConfig config = createPoolConfig(1, 0, 1);
        config.setDriver(driver);
        DBDataSourcePool pool = new DBDataSourcePool("testRetireInFlight", config, logger);

        Connection handle = pool.getConnection();
        PreparedStatement statement = handle.prepareStatement("SELECT 1");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> executing = executor.submit(() -> {
                try {
                    statement.executeQuery();
                } catch (SQLException ignored) {
                    // 放行前连接已被退役，底层语句会报错；本用例只关心池的处置
                }
                return null;
            });
            assertTrue(driver.awaitStatementEntered(10, TimeUnit.SECONDS),
                    "the fake statement never entered execution, the test precondition does not hold");

            // 另一个线程还卡在这条语句里，连接绝不能被放回池里给下一个借用者
            handle.close();

            assertEquals(0, pool.getIdleConnectionPoolSize(),
                    "returning a connection while a statement is still executing must retire it, not put it back in the idle pool");
            assertEquals(0, pool.getUsedConnectionPoolSize());
            assertEquals(1, driver.closedConnections(),
                    "retiring means a physical close: the underlying connection must receive close()");

            driver.releaseStatement();
            executing.get(10, TimeUnit.SECONDS);
        } finally {
            driver.releaseStatement();
            executor.shutdownNow();
            pool.shutdown();
        }
    }

    /**
     * 归还连接的同时、另一个线程正在同一条连接上建语句 → 那条语句必须被当场拒掉，
     * 绝不能留在物理连接上（否则它会跟着连接一起被下一个借用者拿到）。
     *
     * <p>覆盖的是 {@code requireOpen()} 与 {@code physical.createStatement()} 之间的窗口：
     * 归还时这条连接还没有任何语句（走的是正常归还路径），但"正在创建"的那条语句会在
     * {@code released} 置位之后才登记。改造前这种连接一律被退役，窗口被顺手兜住；现在要复用，
     * 就得由 {@code track()} 自己拒绝。</p>
     */
    @Test
    void testStatementCreatedWhileClosingIsRefused() throws Exception {
        SlowCreateStatementDriver driver = new SlowCreateStatementDriver();
        DBDataSourcePoolConfig config = createPoolConfig(1, 0, 1);
        config.setDriver(driver);
        DBDataSourcePool pool = new DBDataSourcePool("testCreateRace", config, logger);

        Connection handle = pool.getConnection();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<SQLException> racing = executor.submit(() -> {
                try {
                    handle.createStatement();
                    return null;
                } catch (SQLException expected) {
                    return expected;
                }
            });
            assertTrue(driver.awaitCreateEntered(10, TimeUnit.SECONDS),
                    "the fake connection never entered statement creation, the test precondition does not hold");

            // 另一个线程正卡在 createStatement 里时归还连接：此时连接上没有残留语句，
            // 走的是正常归还路径，连接会回到空闲池
            handle.close();
            assertEquals(1, pool.getIdleConnectionPoolSize(),
                    "with only one statement being created, the connection itself should be returned normally");

            driver.releaseCreate();
            SQLException failure = racing.get(10, TimeUnit.SECONDS);
            assertNotNull(failure, "a statement created after the connection was returned must be refused, otherwise it stays on the physical connection");
            assertEquals(1, driver.closedStatements(),
                    "the refused statement must be closed on the spot and never left on the physical connection");
        } finally {
            driver.releaseCreate();
            executor.shutdownNow();
            pool.shutdown();
        }
    }

    /**
     * 假驱动：连接上的语句会"卡住"直到测试放行。
     *
     * <p>动态代理的 {@code equals/hashCode} 也会转到处理器上，而池把连接当作
     * {@code ConcurrentHashMap} / {@code LinkedHashSet} 的键——这里必须给出恒等语义，
     * 否则 {@code usedConnectionPool.remove(connection)} 会找不到自己。
     * （生产代码里的语句代理出于同样的原因自己实现了这两个方法。）</p>
     */
    private static class BlockingStatementDriver implements Driver {

        private final CountDownLatch statementEntered = new CountDownLatch(1);
        private final CountDownLatch statementMayReturn = new CountDownLatch(1);
        private final AtomicInteger closedConnections = new AtomicInteger();

        boolean awaitStatementEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return statementEntered.await(timeout, unit);
        }

        void releaseStatement() {
            statementMayReturn.countDown();
        }

        int closedConnections() {
            return closedConnections.get();
        }

        @Override
        public Connection connect(String url, Properties info) {
            return proxy(Connection.class, (self, method, args) -> {
                switch (method.getName()) {
                    case "createStatement":
                        return fakeStatement(false);
                    case "prepareStatement":
                        return fakeStatement(true);
                    case "isClosed":
                        return false;
                    case "close":
                        closedConnections.incrementAndGet();
                        return null;
                    case "rollback":
                        // 与 DuckDB 一致：没有活动事务时的固定报错，池按"正常情况"忽略
                        throw new SQLException("cannot rollback - no transaction is active");
                    case "getAutoCommit":
                        return true;
                    case "setAutoCommit":
                        return null;
                    case "isValid":
                        return true;
                    case "isWrapperFor":
                        return false;
                    case "unwrap":
                        throw new SQLException("Not a wrapper for " + args[0]);
                    case "equals":
                        return self == args[0];
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "toString":
                        return "BlockingFakeConnection";
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        }

        private Object fakeStatement(boolean prepared) {
            Class<?> statementInterface = prepared ? PreparedStatement.class : Statement.class;
            return proxy(statementInterface, (self, method, args) -> {
                switch (method.getName()) {
                    case "execute":
                    case "executeQuery":
                    case "executeUpdate":
                        statementEntered.countDown();
                        statementMayReturn.await();
                        return defaultValue(method.getReturnType());
                    case "isClosed":
                        return false;
                    case "close":
                    case "cancel":
                        return null;
                    case "equals":
                        return self == args[0];
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "toString":
                        return "BlockingFakeStatement";
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        }

        static <T> T proxy(Class<T> iface, InvocationHandler handler) {
            return iface.cast(Proxy.newProxyInstance(BlockingStatementDriver.class.getClassLoader(),
                    new Class<?>[]{iface}, handler));
        }

        static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive() || returnType == void.class) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == char.class) {
                return (char) 0;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0f;
            }
            if (returnType == double.class) {
                return 0d;
            }
            return null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return true;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }
    }

    /**
     * 假驱动：{@code createStatement()} 会"卡住"直到测试放行。
     *
     * <p>用来复现一个真实的竞态窗口：线程 A 已通过 {@code requireOpen()} 检查、正卡在
     * {@code physical.createStatement()} 里；此时线程 B 把连接归还了。A 拿到的那条语句
     * 是"归还之后才登记"的——改造前一概退役（物理关闭）能顺手兜住它，现在连接要复用，
     * 就必须由 {@code PooledConnection.track()} 当场拒掉，否则它会跟着连接一起被下一个
     * 借用者拿到（DuckDB 单连接非线程安全）。</p>
     */
    private static final class SlowCreateStatementDriver extends BlockingStatementDriver {

        private final CountDownLatch createEntered = new CountDownLatch(1);
        private final CountDownLatch createMayReturn = new CountDownLatch(1);
        private final AtomicInteger closedStatements = new AtomicInteger();

        boolean awaitCreateEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return createEntered.await(timeout, unit);
        }

        void releaseCreate() {
            createMayReturn.countDown();
        }

        int closedStatements() {
            return closedStatements.get();
        }

        @Override
        public Connection connect(String url, Properties info) {
            return proxy(Connection.class, (self, method, args) -> {
                switch (method.getName()) {
                    case "createStatement":
                        createEntered.countDown();
                        createMayReturn.await();
                        return fakeStatement();
                    case "isClosed":
                        return false;
                    case "close":
                        return null;
                    case "rollback":
                        // 与 DuckDB 一致：没有活动事务时的固定报错，池按"正常情况"忽略
                        throw new SQLException("cannot rollback - no transaction is active");
                    case "getAutoCommit":
                        return true;
                    case "setAutoCommit":
                        return null;
                    case "isValid":
                        return true;
                    case "equals":
                        return self == args[0];
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "toString":
                        return "SlowCreateFakeConnection";
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        }

        private Object fakeStatement() {
            return proxy(Statement.class, (self, method, args) -> {
                switch (method.getName()) {
                    case "close":
                        closedStatements.incrementAndGet();
                        return null;
                    case "isClosed":
                        return false;
                    case "equals":
                        return self == args[0];
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "toString":
                        return "SlowCreateFakeStatement";
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        }
    }
}
