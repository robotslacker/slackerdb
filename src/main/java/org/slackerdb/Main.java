package org.slackerdb;

import ch.qos.logback.classic.Level;
import org.slackerdb.client.AdminClient;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.server.PostgresServer;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Sleeper;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

public class Main {
    public static void setLogLevel(String logLevel) {
        AppLogger.setLogLevel(Level.valueOf(logLevel));
    }

    public static void serverStart() throws Exception
    {
        // 加载默认的配置信息，不会覆盖配置文件中的内容或者命令行参数指定的内容
        ServerConfiguration.LoadDefaultConfiguration();

        // 打开日志文件
        AppLogger.CreateLogger();

        // 启动服务器
        AppLogger.logger.info("[SERVER] SlackerDB server starting ...");

        // 初始化服务处理程序的后端数据库连接字符串
        DBInstance.init();

        DBInstance.protocolServer = new PostgresServer();
        DBInstance.protocolServer.start();

        // 等待服务进程启动
        while (!DBInstance.state.equalsIgnoreCase("RUNNING") &&
                !DBInstance.state.equalsIgnoreCase("STARTUP FAILED")) {
            Sleeper.sleep(1000);
        }
    }

    public static void serverStop() throws Exception
    {
        // 停止网络服务
        DBInstance.protocolServer.stop(null);

        // 数据库强制进行检查点操作
        DBInstance.forceCheckPoint();

        // 关闭数据库
        for (Connection conn : DBInstance.connectionPool)
        {
            if (!conn.isClosed()) {
                conn.close();
            }
        }
        DBInstance.connectionPool.clear();
        // 关闭BackendSysConnection
        DBInstance.backendSysConnection.close();
    }

    public static void serverAdmin(String appCommand)
    {
        // 加载默认的配置信息，不会覆盖配置文件中的内容或者命令行参数指定的内容
        ServerConfiguration.LoadDefaultConfiguration();

        // 打开日志文件
        AppLogger.CreateLogger();

        // 执行命令请求
        AdminClient.doCommand(appCommand);
    }

    public static void main(String[] args){
        // 标记数据库正在启动中
        DBInstance.state = "STARTING";

        Map<String, String> appOptions = new HashMap<>();
        StringBuilder appCommand = null;

        String paramName = null;
        String paramValue;
        for (String arg : args) {
            if (arg.startsWith("--")) {
                paramName = arg.substring(2);
            }
            else
            {
                if (paramName == null)
                {
                    if (appCommand == null) {
                        appCommand = new StringBuilder(arg);
                    }
                    else
                    {
                        appCommand.append(" ").append(arg);
                    }
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
            if (appOptions.containsKey("log"))
            {
                ServerConfiguration.setLog(appOptions.get("log"));
            }
            if (appOptions.containsKey("port"))
            {
                ServerConfiguration.setPort(Integer.parseInt(appOptions.get("port")));
            }
            if (appOptions.containsKey("data"))
            {
                ServerConfiguration.setData(appOptions.get("data"));
            }
            if (appOptions.containsKey("data_dir"))
            {
                ServerConfiguration.setData_dir(appOptions.get("data_dir"));
            }
            if (appOptions.containsKey("temp_dir"))
            {
                ServerConfiguration.setTemp_dir(appOptions.get("temp_dir"));
            }
            if (appOptions.containsKey("extension_dir"))
            {
                ServerConfiguration.setExtension_dir(appOptions.get("extension_dir"));
            }
            if (appOptions.containsKey("init_schema"))
            {
                ServerConfiguration.setInit_schema(appOptions.get("init_schema"));
            }
            if (appOptions.containsKey("plsql_func_dir"))
            {
                ServerConfiguration.setPlsql_func_dir(appOptions.get("plsql_func_dir"));
            }
            if (appCommand == null)
            {
                AppLogger.logger.error("[SERVER] Invalid command. \n Usage: java -jar slackerdb-xxx.jar [--option value, ]  command.");
                System.exit(1);
            }

            // 启动应用程序
            if (appCommand.toString().toUpperCase().startsWith("START")) {
                serverStart();
            }
            else
            {
                serverAdmin(appCommand.toString());
            }
        }
        catch (Exception ex)
        {
            AppLogger.logger.error("[SERVER] Fatal exception.", ex);
            System.exit(1);
        }
    }
}