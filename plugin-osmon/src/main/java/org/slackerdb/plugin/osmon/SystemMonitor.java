package org.slackerdb.plugin.osmon;

import io.javalin.Javalin;
import org.duckdb.DuckDBConnection;
import org.pf4j.Plugin;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.slackerdb.plugin.DBPlugin;
import org.slackerdb.plugin.DBPluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class SystemMonitor extends DBPlugin {
    private Connection dbConn = null;
    private Javalin    app = null;
    private Logger     logger = null;

    private MonitorThread monitorThread = null;

    public SystemMonitor(PluginWrapper wrapper)
    {
        super(wrapper);

        // 禁用OSHI的日志信息
        ch.qos.logback.classic.Logger oshiLogger;
        oshiLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("oshi.software.os.windows.WindowsOperatingSystem");
        oshiLogger.setLevel(ch.qos.logback.classic.Level.OFF);

        oshiLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("oshi.util.platform.windows.PerfCounterQuery");
        oshiLogger.setLevel(ch.qos.logback.classic.Level.OFF);

    }

    class MonitorThread extends Thread {
        @Override
        public void run()
        {
            try {
                logger.error("DDDDD");
                // 获取数据库连接
                dbConn = getDbConnection();
                // 处理Web端口注册
                while (true) {
                    try {
                        Thread.sleep(1 * 1000);
                        System.err.println("Hello world!");
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
            catch (SQLException sqlException)
            {
            }
            finally {
                if (dbConn != null)
                {
                    try
                    {
                        dbConn.close();
                    }
                    catch (SQLException ignored) {}
                }
            }
        }
    }

    @Override
    public void onStart()  {
        this.logger = getLogger();
        if (monitorThread == null)
        {
            this.monitorThread = new MonitorThread();
            this.monitorThread.start();

        }
    }

    public void afterStart() {
        System.err.println("after start");
//        if (this.conn != null)
//        {
//            try {
//                this.conn.close();
//            }
//            catch (SQLException ignored) {}
//        }
//        System.out.println("[PluginExample] Stopped.");
    }

}
