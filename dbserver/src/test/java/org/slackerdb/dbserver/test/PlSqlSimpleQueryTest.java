package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PL/SQL 简单查询协议（{@code Q} 消息 / {@code preferQueryMode=simple}）集成测试。
 *
 * <p>回归的历史缺陷：{@code QueryRequest} 里<b>完全没有</b> PL/SQL 分支，
 * 于是 {@code psql}、{@code Statement.execute} 发来的 {@code DO $$...$$} 会被
 * 原样丢给 DuckDB（报 "DO" 语法错误）；多语句脚本也会被整串丢给 prepare 而失败。</p>
 */
public class PlSqlSimpleQueryTest {

    static final int dbPort = 4313;
    static DBInstance dbInstance;

    @BeforeAll
    static void initAll() throws ServerException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(dbPort);
        serverConfiguration.setData("mem");
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
    }

    /** 简单查询协议连接（psql / libpq 的行为）。 */
    private Connection connectSimple() throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem?preferQueryMode=simple", "", "");
        connection.setAutoCommit(false);
        return connection;
    }

    /**
     * 用<b>官方 pgjdbc</b>（而非 SlackerDB 自带的 fork）建立简单查询连接。
     *
     * <p>为什么需要第二个客户端：SlackerDB 的驱动是 pgjdbc 的 fork，扩展协议下会按分号
     * 拆分语句；用官方驱动可以独立验证服务端在简单查询协议下"逐条返回结果"的行为，
     * 避免把客户端特性误当成服务端行为。</p>
     */
    private Connection connectSimpleWithOfficialDriver() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "");
        properties.setProperty("password", "");
        properties.setProperty("preferQueryMode", "simple");
        Connection connection = new org.postgresql.Driver()
                .connect("jdbc:postgresql://127.0.0.1:" + dbPort + "/mem", properties);
        connection.setAutoCommit(false);
        return connection;
    }

    @Test
    void doBlockOverSimpleQuery() throws SQLException {
        try (Connection conn = connectSimple(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    DO $$
                    begin
                        create or replace table sq_block(i int);
                        insert into sq_block values(7);
                    end;
                    $$
                    """);
            try (ResultSet rs = stmt.executeQuery("select i from sq_block")) {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
            }
        }
    }

    @Test
    void unwrappedBlockOverSimpleQuery() throws SQLException {
        try (Connection conn = connectSimple(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    declare
                        x int;
                    begin
                        create or replace table sq_unwrapped(i int);
                        let x = 3;
                        insert into sq_unwrapped values(:x);
                    end;
                    """);
            try (ResultSet rs = stmt.executeQuery("select i from sq_unwrapped")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
        }
    }

    /** 多语句脚本：DDL + DML + 块 + DML，每条都必须真正执行。 */
    @Test
    void scriptRunsEveryStatement() throws SQLException {
        try (Connection conn = connectSimpleWithOfficialDriver(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    create or replace table sq_script(i int);
                    insert into sq_script values(1);
                    $$
                    begin
                        insert into sq_script values(2);
                    end;
                    $$;
                    insert into sq_script values(3)
                    """);

            try (Statement check = conn.createStatement();
                 ResultSet rs = check.executeQuery("select count(*) from sq_script")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1), "脚本内所有语句（含匿名块）都必须真正执行");
            }
        }
    }

    /** 简单查询协议下脚本中的查询要能把结果集发回来。 */
    @Test
    void scriptReturnsResultSet() throws SQLException {
        try (Connection conn = connectSimpleWithOfficialDriver(); Statement stmt = conn.createStatement()) {
            boolean hasResult = stmt.execute("select 42 as answer; select 43 as other");
            assertTrue(hasResult, "脚本里的第一条查询应当返回结果集");
            try (ResultSet rs = stmt.getResultSet()) {
                assertNotNull(rs);
                assertTrue(rs.next());
                assertEquals(42, rs.getInt("answer"));
            }
        }
    }

    /** 脚本中途失败：后续语句不再执行。 */
    @Test
    void scriptStopsAtFirstErrorOverSimpleQuery() throws SQLException {
        try (Connection conn = connectSimple(); Statement stmt = conn.createStatement()) {
            // 建表并提交（DuckDB 的 DDL 也是事务性的）
            stmt.execute("create or replace table sq_err(i int)");
            conn.commit();

            SQLException error = null;
            try {
                stmt.execute("""
                        insert into no_such_table values(1);
                        insert into sq_err values(9)
                        """);
            } catch (SQLException e) {
                error = e;
            }
            assertNotNull(error, "脚本中的失败语句必须报错");
            conn.rollback();

            try (Statement check = conn.createStatement();
                 ResultSet rs = check.executeQuery("select count(*) from sq_err")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "失败之后的语句不应执行");
            }
        }
    }

    /** 只有注释的查询不应被丢给数据库（旧实现会 prepare 纯注释文本）。 */
    @Test
    void commentOnlyQueryIsNoOp() throws SQLException {
        try (Connection conn = connectSimple(); Statement stmt = conn.createStatement()) {
            assertFalse(stmt.execute("-- 只有注释\n/* 什么都没有 */"));
        }
    }
}
