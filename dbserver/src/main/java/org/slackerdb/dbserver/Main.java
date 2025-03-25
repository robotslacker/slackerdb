package org.slackerdb.dbserver;


/*
    Main

    主程序. 根据命令行参数来完成数据库的启动、停止、状态查看

 */

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.dbserver.client.AdminClient;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class Main {
    // 打印帮助信息
    public static void showUsage()
    {
        String codeLocation = Main.class.getProtectionDomain().getCodeSource().getLocation().getFile();
        if (codeLocation.split("!").length > 1) {
            codeLocation = codeLocation.split("!")[0];
            codeLocation = codeLocation.split("/")[codeLocation.split("/").length - 1];
        }
        else
        {
            codeLocation = "****.jar";
        }
        System.out.println("Usage: java -jar " + codeLocation + " [COMMAND] [--parameter <parameter value>]");
        System.out.println("Commands:");
        System.out.println("  start     Start slackerdb server.");
        System.out.println("  stop      Stop slackerdb server.");
        System.out.println("  status    print server status.");
        System.out.println("  help      print this message.");
        System.out.println("  version   print server version.");
        System.out.println("Parameters:");
        System.out.println("  --conf              Configuration file.");
        System.out.println("  --pid               process pid file, default is none.");
        System.out.println("  --locale            default language of the program.");
        System.out.println("  --log_level         log level, default is INFO.");
        System.out.println("  --log               log file, default is CONSOLE.");
        System.out.println("  --bind              server bind ip address, default is 0.0.0.0");
        System.out.println("  --host              remote server address,  default is 127.0.0.1");
        System.out.println("  --port              server listener port. default is random");
        System.out.println("  --data              database name. default is slackerdb");
        System.out.println("  --data_dir          database file directory. default is :memory:");
        System.out.println("  --temp_dir          database temporary file directory. default is os dependent.");
        System.out.println("  --extension_dir     extension file directory. default is $HOME/.duckdb/extensions.");
        System.out.println("  --init_script       system init script or script directory. default is none.");
        System.out.println("  --startup_script    system startup script or script directory. default is none.");
        System.out.println("  --sql_history       enable or disable sql history feature(ON|OFF). default is off.");
        System.out.println("  --sql_history_port  sql history database remote service port. default is none.");
        System.out.println("  --sql_history_dir   sql history database file directory. default is none.");
    }

    public static void main(String[] args){
        // 处理应用程序参数
        Map<String, String> appOptions = new HashMap<>();
        StringBuilder subCommand = null;

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
                    if (subCommand == null) {
                        subCommand = new StringBuilder(arg);
                        continue;
                    }
                    else
                    {
                        // 最后只能有一个命令参数存在
                        System.err.println(
                                "Error: Only one subcommand is required. But you have more .. [" + subCommand + ", " + arg + " ...].");
                        showUsage();
                        System.exit(255);
                    }
                }
                paramValue = arg;
                appOptions.put(paramName.toLowerCase(), paramValue);
                paramName = null;
            }
        }
        if (paramName != null)
        {
            appOptions.put(paramName.toLowerCase(), null);
        }

        if (subCommand == null)
        {
            // 如果没有任何一个子命令，则直接打印帮助后退出
            System.err.println("Error: At least one subcommand is required. ");
            showUsage();
            System.exit(255);
        }

        // 从资源信息中读取系统的版本号
        String version, localBuildDate;
        try {
            InputStream inputStream = Main.class.getResourceAsStream("/version.properties");
            Properties properties = new Properties();
            properties.load(inputStream);
            version = properties.getProperty("version", "{project.version}");
            String buildTimestamp = properties.getProperty("build.timestamp", "${build.timestamp}");

            // 转换编译的时间格式
            try {
                ZonedDateTime zdt = ZonedDateTime.parse(buildTimestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
                LocalDateTime localDateTime = LocalDateTime.ofInstant(zdt.toInstant(), ZoneId.systemDefault());
                localBuildDate =
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").format(localDateTime) + " " +
                                TimeZone.getTimeZone(ZoneId.systemDefault()).getID();
            }
            catch (DateTimeParseException ex)
            {
                localBuildDate = buildTimestamp;
            }
        }
        catch (IOException ioe)
        {
            version = "{project.version}";
            localBuildDate = "${build.timestamp}";
        }

        if (subCommand.toString().equalsIgnoreCase("HELP"))
        {
            // 打印帮助信息
            showUsage();
            System.exit(0);
        }
        else if (subCommand.toString().equalsIgnoreCase("VERSION"))
        {
            // 打印版本信息
            System.out.println("[SERVER] VERSION：" + version);
            System.out.println("[SERVER] Build Time: " + localBuildDate );
            System.exit(0);
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
            if (appOptions.containsKey("init_script"))
            {
                serverConfiguration.setInit_script(appOptions.get("init_script"));
            }
            if (appOptions.containsKey("startup_script"))
            {
                serverConfiguration.setStartup_script(appOptions.get("startup_script"));
            }
            if (appOptions.containsKey("sql_history"))
            {
                serverConfiguration.setSqlHistory(appOptions.get("sql_history"));
            }
            if (appOptions.containsKey("pid"))
            {
                serverConfiguration.setPid(appOptions.get("pid"));
            }
            // 初始化日志服务
            Logger logger = AppLogger.createLogger(
                    "SLACKERDB",
                    serverConfiguration.getLog_level().levelStr,
                    serverConfiguration.getLog());

            // 启动应用程序
            if (subCommand.toString().toUpperCase().startsWith("START")) {
                // 启动服务器
                logger.info("[SERVER] SlackerDB server starting (PID:{})...", ProcessHandle.current().pid());
                logger.info("[SERVER] VERSION：{}", version);
                logger.info("[SERVER] Build Time: {}", localBuildDate);

                // 初始化后端的DuckDB数据库
                DBInstance dbInstance = new DBInstance(serverConfiguration);
                // 设置为独占模式，当数据库端口停止，数据库也将停止
                dbInstance.setExclusiveMode(true);
                // 启动数据库
                dbInstance.start();

                // 这里永远等待，不退出
                Sleeper.sleep(Long.MAX_VALUE);
            }
            else
            {
                // 其他命令
                if (!appOptions.containsKey("host") && !appOptions.containsKey("bind") ) {
                    // 客户端运行，如果没有指定远端主机名称，默认127.0.0.1， 而不是0.0.0.0
                    serverConfiguration.setBindHost("127.0.0.1");
                }
                // 执行其他请求
                AdminClient.doCommand(serverConfiguration, subCommand.toString(), appOptions);
            }

            // 退出应用程序
            System.exit(0);
        }
        catch (Exception se)
        {
            System.err.println("Error: unexpected internal error.");
            se.printStackTrace(System.err);
            System.exit(255);
        }
    }
}
