package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;

import java.math.BigDecimal;
import java.sql.*;
import java.util.TimeZone;

public class PlSqlTest {
    static int dbPort=4309;
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
    static void tearDownAll() throws Exception{
        dbInstance.stop();
    }

    @Test
    void simple01() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        stmt.execute("DO $$\nDeclare\n" +
                "   x  int;\n" +
                "begin\n" +
                "\tpass;\n" +
                "end;$$");
        stmt.close();
        pgConn.close();
    }

    @Test
    void testDeclareVariables() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        stmt.execute("Create or replace Table testDeclareVariables(" +
                "col1 int, col2 text, col3 double, col4 bigint," +
                "col5 date, col6 timestamp, col7 float)");
        stmt.execute("DO $$\nDeclare\n" +
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
                "end;$$");
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        Statement stmt = pgConn.createStatement();
        stmt.execute("Create or replace Table testVariablesCompute(" +
                "col1 int, col2 text, col3 double, col4 bigint," +
                "col5 date, col6 timestamp, col7 float)");
        stmt.execute("DO $$\nDeclare\n" +
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
                "end;$$");
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
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
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
        Statement stmt = pgConn.createStatement();
        stmt.execute("DO $$\n" + plSql + "\n$$");

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
}
