package org.slackerdb.dbproxy;
/*
    Main

    主程序. 根据命令行参数来完成代理的启动、停止、状态查看

 */

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.dbproxy.client.AdminClient;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.server.ProxyInstance;

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
        String version;
        try {
            InputStream inputStream = Main.class.getResourceAsStream("/version.properties");
            Properties properties = new Properties();
            properties.load(inputStream);
            version = properties.getProperty("version", "{project.version}");
        }
        catch (IOException ioe)
        {
            version = "{project.version}";
        }

        System.out.println("Usage: java -jar slackerdb-dbproxy-" + version + "-standalone.jar [COMMAND] [--parameter <parameter value>]");
        System.out.println("Commands:");
        System.out.println("  start     Start proxy server.");
        System.out.println("  stop      Stop proxy server.");
        System.out.println("  status    print server status.");
        System.out.println("  register  register a proxy target in.");
        System.out.println("  help      print this message.");
        System.out.println("  version   print server version.");
        System.out.println("Parameters:");
        System.out.println("  --conf         Configuration file.");
        System.out.println("  --locale       default language of the program.");
        System.out.println("  --log_level    log level, default is INFO.");
        System.out.println("  --log          log file, default is CONSOLE.");
        System.out.println("  --bind         server bind ip address, default is 0.0.0.0");
        System.out.println("  --host         remote server address,  default is 127.0.0.1");
        System.out.println("  --port         server listener port. default is random");
        System.out.println("  --database     proxy database name.");
        System.out.println("  --target       proxy target url. Example: 127.0.0.1:5432/db1");
    }

    // 主程序
    public static void main(String[] args){
        // 处理应用程序参数
        Map<String, String> appOptions = new HashMap<>();
        // 参数-子命令
        StringBuilder subCommand = null;
        String paramName = null;
        String paramValue;
        for (String arg : args) {
            if (arg.startsWith("--")) {
                // --开头的内容被认为是参数名称
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
                appOptions.put(paramName, paramValue);
                paramName = null;
            }
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
            System.out.println("[PROXY] VERSION：" + version);
            System.out.println("[PROXY] Build Time: " + localBuildDate );
            System.exit(0);
        }

        // 处理应用程序参数
        try {
            ServerConfiguration serverConfiguration = new ServerConfiguration();
            if (appOptions.containsKey("conf")) {
                serverConfiguration.LoadConfigurationFile(appOptions.get("conf"));
            }
            if (appOptions.containsKey("locale")) {
                serverConfiguration.setLocale(appOptions.get("locale"));
            }
            if (appOptions.containsKey("log_level")) {
                serverConfiguration.setLog_level(appOptions.get("log_level"));
            }
            if (appOptions.containsKey("log")) {
                serverConfiguration.setLog(appOptions.get("log"));
            }
            if (appOptions.containsKey("bind")) {
                serverConfiguration.setBindHost(appOptions.get("bind"));
            }
            if (appOptions.containsKey("host")) {
                serverConfiguration.setBindHost(appOptions.get("host"));
            }
            if (appOptions.containsKey("port")) {
                serverConfiguration.setPort(appOptions.get("port"));
            }

            // 初始化日志服务
            Logger logger = AppLogger.createLogger(
                    "PROXY",
                    serverConfiguration.getLog_level().levelStr,
                    serverConfiguration.getLog());

            if (subCommand.toString().equalsIgnoreCase("START"))
            {
                // 如果要求启动，则启动应用程序
                logger.info("[PROXY] SlackerDB proxy starting (PID:{})...", ProcessHandle.current().pid());
                logger.info("[PROXY] VERSION：{}", version);
                logger.info("[PROXY] Build Time: {}", localBuildDate);

                // 初始化程序参数
                ProxyInstance proxyInstance = new ProxyInstance(serverConfiguration);
                // 设置为独占模式，当端口停止，程序也将停止
                proxyInstance.setExclusiveMode(true);
                // 启动代理转发服务
                proxyInstance.start();

                // 循环等待，避免主进程退出
                try {Thread.sleep(Long.MAX_VALUE);} catch (InterruptedException ignored) {
                    logger.info("[PROXY] Caught user interrupt. Quit.");
                    System.exit(255);
                }
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
        catch (ServerException se)
        {
            System.err.println("Error: unexpected internal error.");
            se.printStackTrace(System.err);
            System.exit(255);
        }
    }
}
