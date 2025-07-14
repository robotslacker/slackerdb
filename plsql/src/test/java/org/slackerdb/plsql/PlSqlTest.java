package org.slackerdb.plsql;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.*;

public class PlSqlTest {

    @Test
    void simple01() throws SQLException {
        String  connectURL = "jdbc:duckdb::memory:";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        PlSqlVisitor.runPlSql(pgConn,
                """
                        Declare
                           x  int;
                        begin
                            pass;
                        end;""");
        pgConn.close();
    }

    @Test
    void testDeclareVariables() throws SQLException {
        String  connectURL = "jdbc:duckdb::memory:";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        stmt.execute("Create or replace Table testDeclareVariables(" +
                "col1 int, col2 text, col3 double, col4 bigint," +
                "col5 date, col6 timestamp, col7 float)");
        PlSqlVisitor.runPlSql(pgConn,
                """
                        Declare
                           x  int;
                           y  text;
                           z  double;
                           m  bigint;
                           n  date;
                           o  timestamp;
                           p  float;
                        begin
                            let x = 3;
                            let y = 'Hello World';
                            let z = 1.2;
                            let m = 20002;
                            let n = '2024-07-08';
                            let o = '2024-10-08 23:12:31';
                            let p = 3.04;
                            insert into testDeclareVariables values(:x,:y, :z, :m, :n, :o, :p);
                        end;""");
        ResultSet rs = stmt.executeQuery("select * from testDeclareVariables order by 1");
        if (rs.next())
        {
            assert rs.getInt(1) == 3;
            assert rs.getString(2).equals("Hello World");
            assert Math.abs(rs.getDouble(3) - 1.2) < 0.0001;
            assert rs.getBigDecimal(4).equals(new BigDecimal(20002));
            assert rs.getDate(5).toString().equals("2024-07-08");
            assert rs.getTimestamp(6).toString().equals("2024-10-08 23:12:31.0");
            assert Math.abs(rs.getFloat(7) - 3.04) < 0.0001;
        }
        rs.close();
        stmt.close();
        pgConn.close();
    }

    @Test
    void testVariablesCompute() throws SQLException {
        String  connectURL = "jdbc:duckdb::memory:";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        stmt.execute("Create or replace Table testVariablesCompute(" +
                "col1 int, col2 text, col3 double, col4 bigint," +
                "col5 date, col6 timestamp, col7 float)");
        PlSqlVisitor.runPlSql(pgConn,
                """
                        Declare
                           x  int;
                           y  text;
                           z  double;
                           m  bigint;
                           n  date;
                           o  timestamp;
                           p  float;
                        begin
                            let x = 3;
                            let y = 'Hello World';
                            let z = 1.2;
                            let m = 20002;
                            let n = '2024-07-08';
                            let o = '2024-10-08 23:12:31';
                            let p = 3.04;
                            let x = :x *2;
                            let y = :y || :y;
                            let z = :z *2;
                            let m = :m *2;
                            let n = '2024-07-08';
                            let o = '2024-10-08 23:12:31';
                            let p = :p * 2;
                            insert into testVariablesCompute values(:x,:y, :z, :m, :n, :o, :p);
                        end;""");
        ResultSet rs = stmt.executeQuery("select * from testVariablesCompute order by 1");
        if (rs.next())
        {
            assert rs.getInt(1) == 6;
            assert rs.getString(2).equals("Hello WorldHello World");
            assert Math.abs(rs.getDouble(3) - 2.4) < 0.0001;
            assert rs.getBigDecimal(4).equals(new BigDecimal(40004));
            assert rs.getDate(5).toString().equals("2024-07-08");
            assert rs.getTimestamp(6).toString().equals("2024-10-08 23:12:31.0");
            assert Math.abs(rs.getFloat(7) - 6.08) < 0.0001;
        }
        rs.close();
        stmt.close();
        pgConn.close();
    }

    @Test
    void simple02() throws SQLException {
        String  connectURL = "jdbc:duckdb::memory:";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);

        // 准备测试表
        pgConn.createStatement().execute("Create or replace Table tab1(num int)");
        pgConn.createStatement().execute("Create or replace Table tab2(col1 int, col2 int)");
        pgConn.createStatement().execute("Insert into tab1(num) values(3)");

        // 执行PLSQL
        String plSql = """
                declare
                    x1 int;    -- xxxx
                    x2 int;
                    i int;
                    cursor c1 is select 400,500;
                begin
                    let x1 = 10;
                    update tab1 set num = :x1;
                    select 3,4 into :x1, :x2;
                    begin
                        let x2 = :x1;
                    exception:
                        let x2 = 20;
                    end;
                    open c1;
                    loop
                        fetch c1 into :x1, :x2;
                        exit when c1%notfound;
                        insert into tab2 values(:x1, :x2);
                        let x1 = 40;
                        let x2 = 50;
                        insert into tab2 values(:x1, :x2);
                    end loop;
                    close c1;
                    for :i in 1 TO 5
                    loop
                        if 3 > 5 then
                            break;
                        end if;
                        pass;
                    end loop;
                    for :i in ['3','4','5']
                    loop
                        if 3 > 5 then
                            break;
                        end if;
                        pass;
                    end loop;
                    if 3>5 then
                        pass;
                    else
                        pass;
                    end if;
                end;
                """;
        PlSqlVisitor.runPlSql(pgConn, plSql);

        Statement stmt = pgConn.createStatement();
        ResultSet rs = stmt.executeQuery("select * from tab1 order by 1");
        while (rs.next())
        {
            assert rs.getInt(1) == 10;
        }
        rs = stmt.executeQuery("select * from tab2 order by 1");
        rs.next();
        assert rs.getInt(1) == 40;
        assert rs.getInt(2) == 50;
        rs.next();
        assert rs.getInt(1) == 400;
        assert rs.getInt(2) == 500;

        rs.close();
        stmt.close();
    }

    @Test
    void testException() throws SQLException {
        String  connectURL = "jdbc:duckdb::memory:";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);

        // 准备测试表
        pgConn.createStatement().execute("Create or replace Table testException(num int)");
        pgConn.createStatement().execute("Insert into testException(num) values(3)");
        pgConn.commit();

        // 正向，正常测试，测试后数据会被更新到5
        String sql =
                """
                        Begin
                           Update testException set num = 5;
                        Exception:
                           pass;
                        End;""";
        PlSqlVisitor.runPlSql(pgConn, sql);
        Statement stmt = pgConn.createStatement();
        ResultSet rs = stmt.executeQuery("select * from testException order by 1");
        while (rs.next())
        {
            assert rs.getInt(1) == 5;
        }
        pgConn.commit();

        // 不会有例外发生，所以数据保持5
        sql = """
                Begin
                   Update testException set num = 5;
                Exception:
                   Update testException set num = 6;
                End;""";
        PlSqlVisitor.runPlSql(pgConn, sql);
        stmt = pgConn.createStatement();
        rs = stmt.executeQuery("select * from testException order by 1");
        while (rs.next())
        {
            assert rs.getInt(1) == 5;
        }
        pgConn.commit();

        // 更新字符会出现错误，会导致结果成为6
        sql = """
                Begin
                   Update testException set num = 'bcd';
                Exception:
                   Rollback;
                   Update testException set num = 6;
                End;""";
        PlSqlVisitor.runPlSql(pgConn, sql);
        stmt = pgConn.createStatement();
        rs = stmt.executeQuery("select * from testException order by 1");
        while (rs.next())
        {
            assert rs.getInt(1) == 6;
        }

        // 测试直接忽略错误的情况, 结果仍然是6
        sql = """
                Begin
                   Update testException set num = 'xxxx';
                Exception:
                   Pass;
                End;""";
        PlSqlVisitor.runPlSql(pgConn, sql);
        stmt = pgConn.createStatement();
        rs = stmt.executeQuery("select * from testException order by 1");
        while (rs.next())
        {
            assert rs.getInt(1) == 6;
        }

        // 测试Exception的嵌套，结果保持不变
        sql = """
                Begin
                   Update testException set num = 'xxxx';
                Exception:
                   Begin
                       Rollback;
                   Exception:
                       Pass;
                   End;
                End;""";
        PlSqlVisitor.runPlSql(pgConn, sql);
        stmt = pgConn.createStatement();
        rs = stmt.executeQuery("select * from testException order by 1");
        while (rs.next())
        {
            assert rs.getInt(1) == 6;
        }

        pgConn.close();
    }

    @Test
    void testIdentifierWithDot() throws SQLException {
        String  connectURL = "jdbc:duckdb::memory:";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        pgConn.createStatement().execute("create table main.tab1 (id int)");
        PlSqlVisitor.runPlSql(pgConn,
                """
                        Declare
                           x  int;
                        begin
                            insert into main.tab1 values(10);
                        end;""");
        pgConn.close();
    }

    @Test
    void testEnvIdentifier() throws SQLException {
        String  connectURL = "jdbc:duckdb::memory:";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        pgConn.createStatement().execute("create table main.testEnvIdentifier (id int, col1 text)");
        PlSqlVisitor.runPlSql(pgConn,
                """
                        Declare
                            x   int;
                            y   text;
                        Begin
                            let x = 10;
                            let y = 'Hello World';
                            insert into main.testEnvIdentifier values(:x, ':y');
                            insert into main.testEnvIdentifier values(:x + 1, ':y Me');
                        End;
                        """);
        ResultSet rs = pgConn.createStatement().executeQuery(
                "Select id, col1 " +
                        " FROM main.testEnvIdentifier order by id");
        rs.next();
        assert rs.getInt(1) == 10;
        assert rs.getString(2).equals("Hello World");
        rs.next();
        assert rs.getInt(1) == 11;
        assert rs.getString(2).equals("Hello World Me");
        rs.close();
        pgConn.close();
    }

    @Test
    void testLoopInsert() throws SQLException {
        String  connectURL = "jdbc:duckdb::memory:";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        PlSqlVisitor.runPlSql(pgConn,
                """
                        Declare
                            Cursor mCur Is select unnest(generate_series(DATE '2020-01-01', DATE '2021-01-01', INTERVAL 1 HOUR));
                            xx TimeStamp;
                        Begin
                            Create Or Replace Table testLoopInsert(ID int, time TimeStamp);
                            Open mCur;
                            Loop
                                Fetch mCur into :xx;
                                Insert into testLoopInsert(time) values(:xx);
                                Exit When mCur%NotFound;
                            End Loop;
                            Close mCur;
                        End;
                        """);
        ResultSet rs = pgConn.createStatement().executeQuery(
                "Select count(*) recount, min(time) mintime, max(time) maxtime" +
                " FROM main.testLoopInsert order by 1");
        if (rs.next())
        {
            assert rs.getInt(1) == 8786;
            assert rs.getString(2).equals("2020-01-01 00:00:00.0");
            assert rs.getString(3).equals("2021-01-01 00:00:00.0");
        }
        rs.close();
        pgConn.close();
    }

    @Test
    void testVariableInSql() throws SQLException
    {
        String  connectURL = "jdbc:duckdb::memory:";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        PlSqlVisitor.runPlSql(pgConn,
                """
                        declare
                            current int;
                        begin
                            let current = 10;
                            select ':current';
                        end;
                        """);
        pgConn.close();
    }
}
