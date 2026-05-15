/*
 * Copyright (c) 2025, SlackerDB
 * Unix Domain Socket 连接测试
 * 仅在 Linux/macOS 上运行（Windows 不支持 UDS）
 */

package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.OSUtil;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unix Domain Socket 连接测试。
 * <p>
 * 测试步骤：
 * <ol>
 *   <li>启动数据库实例，配置 UDS socket 路径</li>
 *   <li>通过 UDS 连接，执行 SELECT 查询</li>
 *   <li>通过 UDS 连接，执行 UPDATE 操作</li>
 *   <li>通过 UDS 连接，再次 SELECT 验证更新结果</li>
 *   <li>清理资源</li>
 * </ol>
 * <p>
 * 注意：此测试仅在 Linux/macOS 上运行，Windows 下直接返回成功（避免 mvn test skipped 告警）。
 */
public class UdsTest {

    private static final Logger logger = LoggerFactory.getLogger(UdsTest.class);

    /** UDS socket 文件路径 */
    private static final String SOCKET_PATH = System.getProperty("user.dir")
            + "/target/uds_test.sock";

    /** 数据库实例 */
    private static DBInstance dbInstance;

    @BeforeAll
    static void initAll() throws ServerException {
        // Windows 不支持 UDS，直接跳过
        assumeTrue(!OSUtil.isWindows(), "UDS is not supported on Windows");

        // 确保之前的 socket 文件已清理
        File socketFile = new File(SOCKET_PATH);
        if (socketFile.exists()) {
            assertTrue(socketFile.delete(), "无法删除已存在的 socket 文件: " + SOCKET_PATH);
        }

        // 确保父目录存在
        File socketDir = socketFile.getParentFile();
        if (socketDir != null && !socketDir.exists()) {
            assertTrue(socketDir.mkdirs(), "无法创建 socket 目录: " + socketDir);
        }

        // 配置数据库实例
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);          // 随机端口
        serverConfiguration.setData("mem");       // 内存模式
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        serverConfiguration.setSocket(SOCKET_PATH); // 设置 UDS socket 路径

        logger.info("UDS Test - socket path: {}", SOCKET_PATH);

        // 初始化数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        assertTrue(dbInstance.instanceState.equalsIgnoreCase("RUNNING"),
                "数据库实例未成功启动，状态: " + dbInstance.instanceState);
        logger.info("UDS Test - Server started successfully");
    }

    @AfterAll
    static void tearDownAll() {
        // 如果 Windows 下未初始化，直接返回
        if (dbInstance == null) {
            return;
        }

        logger.info("UDS Test - Shutting down server ...");
        if (dbInstance != null) {
            dbInstance.stop();
            assertTrue(dbInstance.instanceState.equalsIgnoreCase("IDLE"),
                    "数据库实例未成功停止，状态: " + dbInstance.instanceState);
        }

        // 清理 socket 文件
        File socketFile = new File(SOCKET_PATH);
        if (socketFile.exists()) {
            boolean deleted = socketFile.delete();
            logger.info("UDS Test - Socket file deleted: {}", deleted);
        }
        logger.info("UDS Test - Server stopped successfully");
    }

    /**
     * 通过 UDS 建立 JDBC 连接。
     * <p>
     * 使用 jdbc:slackerdb:// 协议，通过 DriverManager 获取连接。
     * slackerdb 驱动只接受 jdbc:slackerdb:// 协议，不会与接受
     * jdbc:postgresql:// 协议的 PostgreSQL 驱动冲突。
     */
    private static Connection createUdsConnection() throws SQLException {
        // 使用 slackerdb 驱动，通过 socketFactory 参数指定 UDS 连接
        String url = "jdbc:slackerdb:///mem";
        Properties props = new Properties();
        props.setProperty("user", "");
        props.setProperty("password", "");
        props.setProperty("socketFactoryArg", SOCKET_PATH);

        Connection conn = DriverManager.getConnection(url, props);
        if (conn == null) {
            throw new SQLException("无法通过 slackerdb 驱动建立 UDS 连接");
        }
        return conn;
    }

    /**
     * 测试1: 通过 UDS 连接，执行 SELECT 查询
     */
    @Test
    void udsSelectTest() throws SQLException {
        logger.info("UDS Test - Running SELECT test via UDS ...");

        try (Connection conn = createUdsConnection()) {
            conn.setAutoCommit(false);

            try (ResultSet rs = conn.createStatement().executeQuery("SELECT 3+4")) {
                assertTrue(rs.next(), "SELECT 应返回一行数据");
                int result = rs.getInt(1);
                assertEquals(7, result, "3+4 应等于 7");
                logger.info("UDS Test - SELECT 3+4 = {}", result);
            }

            conn.commit();
        }

        logger.info("UDS Test - SELECT test passed");
    }

    /**
     * 测试2: 通过 UDS 连接，执行 CREATE TABLE + INSERT (UPDATE) + SELECT 验证
     */
    @Test
    void udsUpdateAndSelectTest() throws SQLException {
        logger.info("UDS Test - Running UPDATE/SELECT test via UDS ...");

        try (Connection conn = createUdsConnection()) {
            conn.setAutoCommit(false);

            // 创建表
            conn.createStatement().execute(
                    "CREATE TABLE uds_test_table (id INT, value VARCHAR(100))");
            logger.info("UDS Test - Table created");

            // INSERT 数据（UPDATE 操作）
            conn.createStatement().execute(
                    "INSERT INTO uds_test_table VALUES (1, 'hello')");
            conn.createStatement().execute(
                    "INSERT INTO uds_test_table VALUES (2, 'world')");
            logger.info("UDS Test - Data inserted");

            // UPDATE 数据
            int updated = conn.createStatement().executeUpdate(
                    "UPDATE uds_test_table SET value = 'updated' WHERE id = 1");
            assertEquals(1, updated, "应更新 1 行");
            logger.info("UDS Test - Data updated, affected rows: {}", updated);

            conn.commit();

            // SELECT 验证更新结果
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT id, value FROM uds_test_table ORDER BY id")) {
                assertTrue(rs.next(), "应返回第一行数据");
                assertEquals(1, rs.getInt("id"));
                assertEquals("updated", rs.getString("value"),
                        "id=1 的 value 应已被更新为 'updated'");

                assertTrue(rs.next(), "应返回第二行数据");
                assertEquals(2, rs.getInt("id"));
                assertEquals("world", rs.getString("value"),
                        "id=2 的 value 应保持为 'world'");
            }

            logger.info("UDS Test - SELECT verification passed");
        }

        logger.info("UDS Test - UPDATE/SELECT test passed");
    }
}
