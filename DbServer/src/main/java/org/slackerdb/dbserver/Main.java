package org.slackerdb.dbserver;


/*
    Main

    主程序. 根据命令行参数来完成数据库的启动、停止、状态查看

 */

import ch.qos.logback.classic.Logger;
import org.slackerdb.dbserver.client.AdminClient;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.dbserver.server.DBInstance;

import java.util.HashMap;
import java.util.Map;

public class Main {
    public static void serverStart(Logger logger, ServerConfiguration serverConfiguration) throws Exception
    {
        // 启动服务器
        logger.info("[SERVER] SlackerDB server starting ...");

        // 初始化后端的DuckDB数据库
        DBInstance dbInstance = new DBInstance(serverConfiguration);
        // 设置为独占模式，当数据库端口停止，数据库也将停止
        dbInstance.setExclusiveMode(true);
        // 启动数据库
        dbInstance.start();
    }

    public static void serverAdmin(Logger logger, ServerConfiguration serverConfiguration, String appCommand)
    {
        // 执行命令请求
        AdminClient.doCommand(logger, serverConfiguration, appCommand);
    }

    public static void main(String[] args){
        // 打开日志文件
        Logger logger = AppLogger.createLogger("SLACKERDB", "INFO", "CONSOLE");

        // 处理应用程序参数
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
            ServerConfiguration serverConfiguration = new ServerConfiguration();
            // 如果有配置文件，用配置文件中数据进行更新
            if (appOptions.containsKey("conf"))
            {
                serverConfiguration.LoadConfigurationFile(appOptions.get("conf"));
            }
            if (appOptions.containsKey("locale"))
            {
                serverConfiguration.setLocale(appOptions.get("locale"));
            }
            if (appOptions.containsKey("log_level"))
            {
                serverConfiguration.setLog_level(appOptions.get("log_level"));
            }
            if (appOptions.containsKey("log"))
            {
                serverConfiguration.setLog(appOptions.get("log"));
            }
            if (appOptions.containsKey("bind"))
            {
                serverConfiguration.setBindHost(appOptions.get("bind"));
            }
            if (appOptions.containsKey("host"))
            {
                serverConfiguration.setBindHost(appOptions.get("host"));
            }
            if (appOptions.containsKey("port"))
            {
                serverConfiguration.setPort(appOptions.get("port"));
            }
            if (appOptions.containsKey("data"))
            {
                serverConfiguration.setData(appOptions.get("data"));
            }
            if (appOptions.containsKey("data_dir"))
            {
                serverConfiguration.setData_dir(appOptions.get("data_dir"));
            }
            if (appOptions.containsKey("temp_dir"))
            {
                serverConfiguration.setTemp_dir(appOptions.get("temp_dir"));
            }
            if (appOptions.containsKey("extension_dir"))
            {
                serverConfiguration.setExtension_dir(appOptions.get("extension_dir"));
            }
            if (appOptions.containsKey("init_schema"))
            {
                serverConfiguration.setInit_schema(appOptions.get("init_schema"));
            }
            if (appOptions.containsKey("plsql_func_dir"))
            {
                serverConfiguration.setPlsql_func_dir(appOptions.get("plsql_func_dir"));
            }
            if (appOptions.containsKey("sql_history"))
            {
                serverConfiguration.setSqlHistory(appOptions.get("sql_history"));
            }
            if (appOptions.containsKey("sql_history_port"))
            {
                serverConfiguration.setSqlHistoryPort(appOptions.get("sql_history_port"));
            }
            if (appOptions.containsKey("sql_history_dir"))
            {
                serverConfiguration.setSqlHistoryDir(appOptions.get("sql_history_dir"));
            }
            if (appOptions.containsKey("help") || (appCommand != null && appCommand.toString().equalsIgnoreCase("HELP")))
            {
                System.out.println("Usage:  java -jar slackerdb-xxx-standalone.jar [--option value, ]  command.");
                System.out.println();
                System.out.println("        COMMAND:   START");
                System.out.println("        COMMAND:   STOP");
                System.out.println("        COMMAND:   STATUS");
                System.out.println("        COMMAND:   KILL <sessionId>");
                System.out.println();
                System.exit(0);
            }
            if (appCommand == null)
            {
                logger.error("[SERVER] Invalid command [null]. \n Usage: java -jar slackerdb-xxx-standalone.jar [--option value, ]  command.");
                System.exit(1);
            }

            // 启动应用程序
            if (appCommand.toString().toUpperCase().startsWith("START")) {
                serverStart(logger, serverConfiguration);
                // 这里永远等待，不退出
                try {Thread.sleep(Long.MAX_VALUE);} catch (InterruptedException ignored) {}
            }
            else
            {
                serverAdmin(logger, serverConfiguration, appCommand.toString());
                System.exit(0);
            }
        }
        catch (Exception ex)
        {
            logger.error("[SERVER] Fatal exception.", ex);
            System.exit(1);
        }
    }
}
