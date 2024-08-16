package org.slackerdb;

import ch.qos.logback.classic.Level;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.protocol.postgres.server.PostgresServer;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Sleeper;

import java.util.HashMap;
import java.util.Map;

public class Main {
    public static void setLogLevel(String logLevel) {
        AppLogger.setLogLevel(Level.valueOf(logLevel));
    }

    public static void start() throws Exception
    {
        // 加载默认的配置信息，不会覆盖配置文件中的内容或者命令行参数指定的内容
        ServerConfiguration.LoadDefaultConfiguration();

        // 打开日志文件
        AppLogger.CreateLogger();

        // 启动服务器
        PostgresServer protocolServer = new PostgresServer();
        protocolServer.start();

        // 等待服务进程启动
        while (!DBInstance.state.equalsIgnoreCase("RUNNING") &&
                !DBInstance.state.equalsIgnoreCase("STARTUP FAILED")) {
            Sleeper.sleep(1000);
        }
    }

    public static void main(String[] args){
        // 标记数据库正在启动中
        DBInstance.state = "STARTING";

        Map<String, String> appOptions = new HashMap<>();
        String appCommand = null;

        String paramName = null;
        String paramValue = null;
        for (String arg : args) {
            if (arg.startsWith("--")) {
                paramName = arg.substring(2);
            }
            else
            {
                if (paramName == null)
                {
                    appCommand = paramValue;
                    continue;
                }
                paramValue = arg;
                appOptions.put(paramName, paramValue);
                paramName = null;
            }
        }

        try
        {
            // 如果有配置文件，用配置文件中数据进行更新
            if (appOptions.containsKey("conf"))
            {
                ServerConfiguration.LoadConfigurationFile(appOptions.get("conf"));
            }
            if (appOptions.containsKey("log_level"))
            {
                ServerConfiguration.setLog_level(Level.valueOf(appOptions.get("log_level")));
            }
            if (appOptions.containsKey("port"))
            {
                ServerConfiguration.setPort(Integer.parseInt(appOptions.get("port")));
            }


            // 启动应用程序
            if (appCommand != null && appCommand.equalsIgnoreCase("START")) {
                start();
            }
            else
            {
                System.out.println("Usage: java -jar xxx.jar [--parameterName parameterValue] start");
                System.exit(1);
            }
        }
        catch (Exception ex)
        {
            AppLogger.logger.trace("[SERVER] Fatal exception.", ex);
            System.err.println(ex.getMessage());
            System.exit(1);
        }
    }
}