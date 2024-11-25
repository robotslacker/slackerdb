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
                "Declare\n" +
                "   x  int;\n" +
                "begin\n" +
                "\tpass;\n" +
                "end;");
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
                "Declare\n" +
                "   x  int;\n" +
                "   y  text;\n" +
                "   z  double;\n" +
                "   m  bigint;\n" +
                "   n  date;\n" +
                "   o  timestamp;\n" +
                "   p  float;\n" +
                "begin\n" +
                "    let x = 3;\n" +
                "    let y = 'Hello World';\n" +
                "    let z = 1.2;\n" +
                "    let m = 20002;\n" +
                "    let n = '2024-07-08';\n" +
                "    let o = '2024-10-08 23:12:31';\n" +
                "    let p = 3.04;\n" +
                "    insert into testDeclareVariables values(:x,:y, :z, :m, :n, :o, :p);\n" +
                "end;");
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
                "Declare\n" +
                "   x  int;\n" +
                "   y  text;\n" +
                "   z  double;\n" +
                "   m  bigint;\n" +
                "   n  date;\n" +
                "   o  timestamp;\n" +
                "   p  float;\n" +
                "begin\n" +
                "    let x = 3;\n" +
                "    let y = 'Hello World';\n" +
                "    let z = 1.2;\n" +
                "    let m = 20002;\n" +
                "    let n = '2024-07-08';\n" +
                "    let o = '2024-10-08 23:12:31';\n" +
                "    let p = 3.04;\n" +
                "    let x = :x *2;\n" +
                "    let y = :y || :y;\n" +
                "    let z = :z *2;\n" +
                "    let m = :m *2;\n" +
                "    let n = '2024-07-08';\n" +
                "    let o = '2024-10-08 23:12:31';\n" +
                "    let p = :p * 2;\n" +
                "    insert into testVariablesCompute values(:x,:y, :z, :m, :n, :o, :p);\n" +
                "end;");
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
        String plSql = "declare     \n" +
                "    x1 int;    -- xxxx     \n" +
                "    x2 int;     \n" +
                "    i int;     \n" +
                "    cursor c1 is select 400,500;     \n" +
                "begin     \n" +
                "    let x1 = 10;     \n" +
                "    update tab1 set num = :x1;     \n" +
                "    select 3,4 into :x1, :x2;     \n" +
                "    begin     \n" +
                "        let x2 = :x1;     \n" +
                "    exception:     \n" +
                "        let x2 = 20;     \n" +
                "    end;     \n" +
                "    open c1;     \n" +
                "    loop     \n" +
                "        fetch c1 into :x1, :x2;     \n" +
                "        exit when c1%notfound;     \n" +
                "        insert into tab2 values(:x1, :x2);     \n" +
                "        let x1 = 40;     \n" +
                "        let x2 = 50;     \n" +
                "        insert into tab2 values(:x1, :x2);     \n" +
                "    end loop;     \n" +
                "    close c1;     \n" +
                "    for :i in 1 TO 5      \n" +
                "    loop     \n" +
                "        if 3 > 5 then     \n" +
                "            break;     \n" +
                "        end if;     \n" +
                "        pass;     \n" +
                "    end loop;     \n" +
                "    for :i in ['3','4','5']      \n" +
                "    loop     \n" +
                "        if 3 > 5 then     \n" +
                "            break;\n" +
                "        end if;     \n" +
                "        pass;     \n" +
                "    end loop;     \n" +
                "    if 3>5 then     \n" +
                "        pass;     \n" +
                "    else     \n" +
                "        pass;     \n" +
                "    end if;     \n" +
                "end;";
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
                "Begin\n" +
                "   Update testException set num = 5;\n" +
                "Exception:\n" +
                "   pass;\n" +
                "End;";
        PlSqlVisitor.runPlSql(pgConn, sql);
        Statement stmt = pgConn.createStatement();
        ResultSet rs = stmt.executeQuery("select * from testException order by 1");
        while (rs.next())
        {
            assert rs.getInt(1) == 5;
        }
        pgConn.commit();

        // 不会有例外发生，所以数据保持5
        sql = "Begin\n" +
                "   Update testException set num = 5;\n" +
                "Exception:\n" +
                "   Update testException set num = 6;\n" +
                "End;";
        PlSqlVisitor.runPlSql(pgConn, sql);
        stmt = pgConn.createStatement();
        rs = stmt.executeQuery("select * from testException order by 1");
        while (rs.next())
        {
            assert rs.getInt(1) == 5;
        }
        pgConn.commit();

        // 更新字符会出现错误，会导致结果成为6
        sql = "Begin\n" +
                "   Update testException set num = 'bcd';\n" +
                "Exception:\n" +
                "   Rollback;\n" +
                "   Update testException set num = 6;\n" +
                "End;";
        PlSqlVisitor.runPlSql(pgConn, sql);
        stmt = pgConn.createStatement();
        rs = stmt.executeQuery("select * from testException order by 1");
        while (rs.next())
        {
            assert rs.getInt(1) == 6;
        }

        // 测试直接忽略错误的情况, 结果仍然是6
        sql = "Begin\n" +
                "   Update testException set num = 'xxxx';\n" +
                "Exception:\n" +
                "   Pass;\n" +
                "End;";
        PlSqlVisitor.runPlSql(pgConn, sql);
        stmt = pgConn.createStatement();
        rs = stmt.executeQuery("select * from testException order by 1");
        while (rs.next())
        {
            assert rs.getInt(1) == 6;
        }

        // 测试Exception的嵌套，结果保持不变
        sql = "Begin\n" +
                "   Update testException set num = 'xxxx';\n" +
                "Exception:\n" +
                "   Begin\n" +
                "       Rollback;\n" +
                "   Exception:\n" +
                "       Pass;\n" +
                "   End;\n" +
                "End;";
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
                "Declare\n" +
                        "   x  int;\n" +
                        "begin\n" +
                        "    insert into main.tab1 values(10);\n" +
                        "end;");
        pgConn.close();
    }
}
