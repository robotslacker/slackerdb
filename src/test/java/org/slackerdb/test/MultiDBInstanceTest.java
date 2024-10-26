package org.slackerdb.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.server.DBInstance;

import java.util.TimeZone;

public class MultiDBInstanceTest {
    @BeforeAll
    static void initAll() {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterAll
    static void tearDownAll() {
    }

    @Test
    void testMultiDBInstance() throws ServerException
    {
        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration1 = new ServerConfiguration();
        serverConfiguration1.setPort(4309);
        serverConfiguration1.setData("data1");

        ServerConfiguration serverConfiguration2 = new ServerConfiguration();
        serverConfiguration2.setPort(4310);
        serverConfiguration2.setData("data2");

        // 初始化数据库
        DBInstance dbInstance1 = new DBInstance(serverConfiguration1);
        DBInstance dbInstance2 = new DBInstance(serverConfiguration2);

        // 启动数据库
        dbInstance1.start();
        dbInstance2.start();

        assert dbInstance1.instanceState.equalsIgnoreCase("RUNNING");
        assert dbInstance2.instanceState.equalsIgnoreCase("RUNNING");

        // 关闭数据库
        dbInstance1.stop();
        dbInstance2.stop();

        assert dbInstance1.instanceState.equalsIgnoreCase("IDLE");
        assert dbInstance2.instanceState.equalsIgnoreCase("IDLE");
    }

    @Test
    void testDuplicateStart() throws ServerException
    {
        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration1 = new ServerConfiguration();
        serverConfiguration1.setPort(4309);
        serverConfiguration1.setData("data1");

        // 初始化数据库
        DBInstance dbInstance1 = new DBInstance(serverConfiguration1);

        // 启动数据库
        dbInstance1.start();

        boolean gotException = false;
        try {
            dbInstance1.start();
        }
        catch (ServerException se)
        {
            gotException = true;
            assert se.getErrorCode().equalsIgnoreCase("SLACKERDB-00006");
        }
        assert gotException;

        // 关闭数据库
        dbInstance1.stop();
    }

    @Test
    void testStartStopStartAgain() throws ServerException
    {
        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration1 = new ServerConfiguration();
        serverConfiguration1.setPort(4309);
        serverConfiguration1.setData("data1");

        // 初始化数据库
        DBInstance dbInstance1 = new DBInstance(serverConfiguration1);

        // 启动数据库
        dbInstance1.start();
        // 关闭数据库
        dbInstance1.stop();

        // 再次启动数据库
        dbInstance1.start();
        // 再次关闭数据库
        dbInstance1.stop();
    }
}