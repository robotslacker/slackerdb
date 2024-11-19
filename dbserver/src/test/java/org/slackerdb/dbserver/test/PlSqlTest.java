package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.plsql.PlSqlVisitor;
import org.slackerdb.dbserver.server.DBInstance;

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
    }

    @Test
    void simple02() throws SQLException {
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);

        // 准备测试表
        pgConn.createStatement().execute("Create Table tab1(num int)");
        pgConn.createStatement().execute("Create Table tab2(col1 int, col2 int)");
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
        assert rs.getInt(1) == 10;
        assert rs.getInt(2) == 0;
        rs.next();
        assert rs.getInt(1) == 40;
        assert rs.getInt(2) == 50;

        rs.close();
        stmt.close();
    }
}
