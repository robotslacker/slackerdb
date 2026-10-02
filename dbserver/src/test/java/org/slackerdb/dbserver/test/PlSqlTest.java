package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;

import java.sql.*;
import java.util.TimeZone;

/**
 * PL/SQL 扩展协议（Parse/Bind/Describe/Execute）集成测试。
 *
 * <p>历史问题（本类用例专门锁死）：</p>
 * <ul>
 *   <li>旧实现靠正则 {@code (.*)(DO)?\s*\$\$(.*)\$\$.*} 识别 PL/SQL，正则的贪婪匹配会把
 *       {@code $$} <b>之前</b>的语句静默丢弃 —— 旧的 {@code testCombinePlsqlAndSimpleSql}
 *       因为断言的表是块内创建的，所以"丢弃"也能通过；这里改为断言被丢弃的语句确实生效；</li>
 *   <li>{@code $$} <b>之后</b>的语句同样会丢；这里也加了断言；</li>
 *   <li>不带 {@code $$} 包装的匿名块旧实现完全识别不了。</li>
 * </ul>
 */
public class PlSqlTest {
    static final int dbPort=4309;
    static DBInstance dbInstance ;

    @BeforeAll
    static void initAll() throws ServerException {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(dbPort);
        serverConfiguration.setData("mem");

        // 启动数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        System.out.println("TEST:: Server started successful ...");
    }


    @AfterAll
    static void tearDownAll(){
        dbInstance.stop();
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem", "", "");
        connection.setAutoCommit(false);
        return connection;
    }

    @Test
    void doWrapperBlock() throws SQLException {
        try (Connection pgConn = connect(); Statement stmt = pgConn.createStatement()) {
            stmt.execute("""
                    DO $$
                    declare
                       x int;
                    begin
                       create or replace table do_wrapper(i int);
                       let x = 5;
                       insert into do_wrapper values(:x);
                    end;
                    $$
                    """);
            try (ResultSet rs = stmt.executeQuery("select i from do_wrapper")) {
                assert rs.next();
                assert rs.getInt(1) == 5;
            }
        }
    }

    @Test
    void bareDollarBlock() throws SQLException {
        try (Connection pgConn = connect(); Statement stmt = pgConn.createStatement()) {
            stmt.execute("""
                    $$
                    begin
                        create or replace table bare_block(i int);
                        insert into bare_block values(3);
                    end;
                    $$
                    """);
            try (ResultSet rs = stmt.executeQuery("select i from bare_block")) {
                assert rs.next();
                assert rs.getInt(1) == 3;
            }
        }
    }

    /**
     * 注意：<b>不带</b> {@code $$} 包装的匿名块<b>不能</b>在扩展协议下这样测试。
     *
     * <p>SlackerDB 自带的 JDBC 驱动（pgjdbc fork）在扩展协议下会按顶层分号把 SQL
     * {@code splitStatements} 成多条语句，于是未包装块会被拆成
     * {@code declare x int} / {@code begin ...} / {@code end} 等碎片分别发送，
     * 服务端再也拼不回来。要点：</p>
     * <ul>
     *   <li>扩展协议下请使用 {@code DO $$ ... $$}（驱动认得美元引用，不会拆）；</li>
     *   <li>无包装块在简单查询协议（psql / {@code preferQueryMode=simple}）下可用，
     *       见 {@link PlSqlSimpleQueryTest#unwrappedBlockOverSimpleQuery()}。</li>
     * </ul>
     */

    /**
     * 回归：{@code $$} 之前的语句不能被丢弃。
     *
     * <p>先建一张<b>两列</b>的表，再跑"drop + 块内重建一列"的脚本：
     * 如果 drop 被丢弃，最终表仍是两列，断言失败。</p>
     */
    @Test
    void statementsBeforeBlockAreNotDropped() throws SQLException {
        try (Connection pgConn = connect(); Statement stmt = pgConn.createStatement()) {
            stmt.execute("create or replace table before_block(num int, extra text)");
            stmt.execute("insert into before_block values (999, 'stale')");

            stmt.execute("""
                    drop table if exists before_block;
                    $$
                    begin
                        create table before_block(num int);
                        insert into before_block values(10);
                    end;
                    $$
                    """);

            try (ResultSet rs = stmt.executeQuery(
                    "select count(*) from information_schema.columns "
                            + "where table_name = 'before_block'")) {
                assert rs.next();
                assert rs.getInt(1) == 1 : "drop 语句被丢弃了：表仍然是旧结构";
            }
            try (ResultSet rs = stmt.executeQuery("select num from before_block")) {
                assert rs.next();
                assert rs.getInt(1) == 10;
                assert !rs.next();
            }
        }
    }

    /** 回归：{@code $$} 之后的语句同样不能被丢弃。 */
    @Test
    void statementsAfterBlockAreNotDropped() throws SQLException {
        try (Connection pgConn = connect(); Statement stmt = pgConn.createStatement()) {
            stmt.execute("""
                    create or replace table after_block(num int);
                    $$
                    begin
                        insert into after_block values(1);
                    end;
                    $$;
                    insert into after_block values(2)
                    """);

            try (ResultSet rs = stmt.executeQuery("select count(*) from after_block")) {
                assert rs.next();
                assert rs.getInt(1) == 2 : "块之前（或之后）的语句被丢弃了";
            }
        }
    }

    /** 脚本中途失败：报错后不再执行后续语句。 */
    @Test
    void scriptStopsAtFirstError() throws SQLException {
        try (Connection pgConn = connect(); Statement stmt = pgConn.createStatement()) {
            // 建表并提交：DuckDB 的 DDL 也是事务性的，若留在未提交事务里，
            // 后续 rollback 会把表一起回滚，导致断言失真
            stmt.execute("create or replace table script_err(num int)");
            pgConn.commit();

            SQLException error = null;
            try {
                stmt.execute("""
                        insert into no_such_table values(1);
                        insert into script_err values(9)
                        """);
            } catch (SQLException e) {
                error = e;
            }
            assert error != null : "脚本中的失败语句必须报错";
            pgConn.rollback();

            try (Statement check = pgConn.createStatement();
                 ResultSet rs = check.executeQuery("select count(*) from script_err")) {
                assert rs.next();
                assert rs.getInt(1) == 0 : "失败之后的语句不应执行";
            }
        }
    }

    /** 动态 SQL：{@code EXECUTE IMMEDIATE}（DDL/DML、USING、INTO）经扩展协议端到端可用。 */
    @Test
    void executeImmediateRunsDynamicSql() throws SQLException {
        try (Connection pgConn = connect(); Statement stmt = pgConn.createStatement()) {
            stmt.execute("create or replace table dyn_t(i int, s text)");
            pgConn.commit();

            stmt.execute("""
                    DO $$
                    declare
                      n int;
                      who text := 'bob';
                    begin
                      execute immediate 'insert into dyn_t values (?, ?)' using 1, who;
                      execute immediate 'select count(*) from dyn_t' into n;
                      execute immediate 'insert into dyn_t values (:1, ''x'')' using n + 1;
                    end;
                    $$
                    """);

            try (ResultSet rs = stmt.executeQuery("select i, s from dyn_t order by i")) {
                assert rs.next();
                assert rs.getInt(1) == 1 && "bob".equals(rs.getString(2));
                assert rs.next();
                assert rs.getInt(1) == 2 && "x".equals(rs.getString(2));
                assert !rs.next();
            }
        }
    }

    /** 动态 SQL：占位符个数不匹配可被 EXCEPTION 捕获；未捕获的后端错误照常回给客户端。 */
    @Test
    void executeImmediateErrorsAreCatchable() throws SQLException {
        try (Connection pgConn = connect(); Statement stmt = pgConn.createStatement()) {
            stmt.execute("create or replace table dyn_err(i int)");
            pgConn.commit();

            stmt.execute("""
                    DO $$
                    declare
                      m int := 0;
                    begin
                      begin
                        execute immediate 'insert into dyn_err values (?)';
                      exception
                        when others then
                          m := 7;
                      end;
                      insert into dyn_err values(:m);
                    end;
                    $$
                    """);

            try (ResultSet rs = stmt.executeQuery("select i from dyn_err")) {
                assert rs.next();
                assert rs.getInt(1) == 7 : "USING 个数不匹配必须可被 EXCEPTION 捕获";
                assert !rs.next();
            }

            SQLException error = null;
            try {
                stmt.execute("""
                        DO $$
                        begin
                          execute immediate 'select * from no_such_table_dyn';
                        end;
                        $$;
                        """);
            } catch (SQLException e) {
                error = e;
            }
            assert error != null : "未捕获的动态 SQL 错误必须返回给客户端";
        }
    }
}
