package org.slackerdb.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.Main;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Sleeper;

import java.io.File;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;


public class TPCDSTest {
    static Thread dbThread = null;
    static int dbPort=4309;
    // 一共需要运行几轮
    static int round = 1000;
    // 需要几个线程并发测试
    static int parallel = 8;
    // 数据集的规模， 1代表1G
    static int scale = 1;
    // 服务端工作线程数量
    static int threads = 4;
    // 工作最大内存限制
    static String memory_limit = "4G";

    static int max_nio_workers = 2;

    static Map<String, String> tpcdsSQLMap = new HashMap<>();
    static List<String> tpcdsTestTaskList = new ArrayList<>();

    static int roundSuccessfulCount = 0;
    static int roundFailedCount = 0;

    @BeforeAll
    static void initAll() throws SQLException {
        tpcdsSQLMap = TpcdsSQL.loadTPCDSSQLMap();

        // 启动slackerDB的服务
        Thread dbThread = new Thread(() -> {
            try {
                // 修改默认的db启动端口
                ServerConfiguration.LoadDefaultConfiguration();
                ServerConfiguration.setPort(dbPort);
                ServerConfiguration.setData("tpcdstest");
                ServerConfiguration.setData_dir(System.getProperty("java.io.tmpdir"));
                ServerConfiguration.setThreads(threads);
                ServerConfiguration.setMemory_limit(memory_limit);
                ServerConfiguration.setMax_workers(max_nio_workers);

                File dbFile = new File(String.valueOf(Path.of(ServerConfiguration.getData_Dir(), ServerConfiguration.getData() + ".db")));
                if (dbFile.exists())
                {
                    var ignored = dbFile.delete();
                }

                // 启动数据库
                Main.setLogLevel("INFO");
                Main.serverStart();

                // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
                TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        dbThread.start();
        while (true)
        {
            if (DBInstance.state.equalsIgnoreCase("RUNNING"))
            {
                break;
            }
            else
            {
                Sleeper.sleep(1000);
            }
        }
        System.out.println("TEST:: Server started successful ...");

        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/tpcdstest";
        Connection pgConn = DriverManager.getConnection(
                connectURL, "", "");
        pgConn.setAutoCommit(false);
        System.out.println("TEST:: Server connected successful ...");

        // 生成数据
        pgConn.createStatement().execute("CALL dsdgen(sf = " + scale + ")");
        pgConn.commit();
        pgConn.close();

        System.out.println("Test data generated successful.");

    }

    @AfterAll
    static void tearDownAll() throws Exception {
        Main.serverStop();
        if (dbThread != null) {
            dbThread.interrupt();
        }
        File dbFile = new File(String.valueOf(Path.of(ServerConfiguration.getData_Dir(), ServerConfiguration.getData() + ".db")));
        if (dbFile.exists())
        {
            var ignored = dbFile.delete();
        }
    }

    void runSQL(String name, String sql)  {
        Thread.currentThread().setName("RUN-" + name + "-START..." );
        String  connectURL = "jdbc:postgresql://127.0.0.1:" + dbPort + "/tpcdstest";

        Timestamp start = new Timestamp(System.currentTimeMillis());
        int rowsReturned = 0;
        int ret = 0;

        Connection pgConn = null;

        Thread.currentThread().setName("RUN-" + name + "-CONNECTING..." );
        // 偶发的网络连接Timeout问题
        for (int i=0;i<3;i++) {
            try {
                Properties props = new Properties();
                props.setProperty("user", "");
                props.setProperty("password", "");
                props.setProperty("connectTimeout", "10");      // 设置连接超时为10秒
                props.setProperty("socketTimeout", "300");      // 设置socket读取超时为300秒
                pgConn = DriverManager.getConnection(connectURL, props);
                break;
            } catch (SQLException ignored) {}
            Sleeper.sleep(2*1000);
        }
        Thread.currentThread().setName("RUN-" + name + "-CONNECTED." );

        if (pgConn == null)
        {
            System.out.println("Connection to " + connectURL + " failed.");
            Thread.currentThread().setName("RUN-" + name + "-CONNECT FAILED." );
            synchronized (this) {
                roundFailedCount++;
            }
            return;
        }
        try {
            pgConn.setAutoCommit(false);
            // 结果做只读存储
            Statement stmt = pgConn.createStatement();
            Thread.currentThread().setName("RUN-" + name + "-EXECUTE ..." );
            ResultSet rs = stmt.executeQuery(sql);
            Thread.currentThread().setName("RUN-" + name + "-FETCHING ..." );
            while (rs.next()) {
                rowsReturned = rowsReturned + 1;
            }
            Thread.currentThread().setName("RUN-" + name + "-FETCHED." );
            rs.close();
            stmt.close();
            pgConn.close();
            Thread.currentThread().setName("RUN-" + name + "-CLOSED." );
            synchronized (this) {
                roundSuccessfulCount++;
            }
        }
        catch (SQLException sqlException)
        {
            synchronized (this) {
                roundFailedCount++;
            }
            ret = -1;
            System.out.println("NAME:" + name);
            System.out.println("SQL :" + sql);
            sqlException.printStackTrace();
            Thread.currentThread().setName("RUN-" + name + "-EXECUTE FAILED." );
        }
        finally {
            Timestamp end = new Timestamp(System.currentTimeMillis());
            if (ret == -1) {
                System.out.println("Test " + name + " failed. " +
                        "Cost [" + (end.getTime() - start.getTime()) + "]ms. " +
                        "Got [" + rowsReturned + "] rows.");
            }
            else
            {
                System.out.println("Test " + name + " successful. " +
                        "Cost [" + (end.getTime() - start.getTime()) + "]ms. " +
                        "Got [" + rowsReturned + "] rows.");
            }
            try {
                pgConn.close();
            } catch (SQLException ignored){}
        }
    }

    void runTPCDSTest() throws SQLException {
        String  taskName;
        while (true) {
            synchronized (TPCDSTest.class) {
                if (tpcdsTestTaskList.isEmpty()) {
                    // 已经没有任务需要完成
                    return;
                }
                taskName = tpcdsTestTaskList.get(0);
                tpcdsTestTaskList.remove(0);
            }
            runSQL(taskName, tpcdsSQLMap.get(taskName));
        }
    }

    @Test
    void testTPCDS()
    {
        for (int i=0; i<round; i++) {
            // 打乱测试列表的顺序
            tpcdsTestTaskList.addAll(tpcdsSQLMap.keySet());
            Collections.shuffle(tpcdsTestTaskList);

            roundSuccessfulCount = 0;
            roundFailedCount = 0;
            Timestamp roundStartTime = new Timestamp(System.currentTimeMillis());
            // 多线程完成测试
            Thread[] threads = new Thread[parallel];
            for (int j = 0; j < threads.length; j++) {
                // Listener thread
                threads[j] = new Thread(() -> {
                    try {
                        runTPCDSTest();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
                threads[j].start();
            }

            // 循环等待进程运行结束
            while (true) {
                boolean allThreadsDone = true;
                for (Thread thread : threads) {
                    if (thread.isAlive()) {
                        // 还有测试线程没有结束
                        Sleeper.sleep(1000);
                        allThreadsDone = false;
                        break;
                    }
                }
                if (allThreadsDone) {
                    break;
                }
            }
            Timestamp roundEndTime = new Timestamp(System.currentTimeMillis());
            System.out.println("Round #" + i + " finished with [" + (roundEndTime.getTime() - roundStartTime.getTime()) + "]ms." +
                    "[" + roundSuccessfulCount + "]/[" + (roundSuccessfulCount + roundFailedCount) + "]");
        }
        System.out.println("TEST:: testTPCDS");
    }

}

