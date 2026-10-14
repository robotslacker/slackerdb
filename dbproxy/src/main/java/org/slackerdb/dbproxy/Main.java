package org.slackerdb.dbproxy;
/*
    Main

    主程序. 根据命令行参数来完成代理的启动、停止、状态查看

 */

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.utils.AppDaemon;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.dbproxy.client.AdminClient;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;

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
        System.out.println("  start     Start slackerdb cdb server.");
        System.out.println("  stop      Stop slackerdb cdb server.");
        System.out.println("  status    print server status.");
        System.out.println("  help      print this message.");
        System.out.println("  version   print server version.");
        System.out.println("Parameters:");
        System.out.println("  --conf              Configuration file.");
        System.out.println("  --daemon            Run in background daemon mode: true|false (bare --daemon means true)."
                + " Overrides daemon= in the config file. Default is false.");
        System.out.println("  --pid               process pid file, default is none.");
        System.out.println("  --locale            default language of the program.");
        System.out.println("  --log_level         log level, default is INFO.");
        System.out.println("  --log               log file, default is CONSOLE.");
        System.out.println("  --bind              server bind ip address, default is 0.0.0.0");
        System.out.println("  --port              data service listener port. default is random");
        System.out.println("  --portX             management service listener port. default is random");
        System.out.println("  --listener_address  Multicast group to discover backend databases from, as <group>:<port>"
                + " (for example 239.255.66.66:46666)."
                + " Must match the dbserver advertise_address. Default: none (discovery disabled).");
    }

    // 主程序
    public static void main(String[] args){
        // 处理应用程序参数。
        // 参数解析统一交给 AppDaemon.parse，避免"Main 里一套、拉起后台副本时又拼一套"导致行为不一致：
        // 它同时给出 1) 保留原始顺序的选项列表（拉起后台副本时原样搬运）2) 子命令 3) --daemon 意图。
        AppDaemon.ParsedArguments parsedArguments;
        try {
            parsedArguments = AppDaemon.parse(args);
        } catch (IllegalArgumentException iae) {
            // 例如 --daemon maybe、或者给了两个子命令
            System.err.println("Error: " + iae.getMessage());
            showUsage();
            System.exit(255);
            return;
        }

        // 兼容既有代码：仍然按"选项名(小写) -> 取值"的 map 提供参数
        Map<String, String> appOptions = new HashMap<>();
        for (AppDaemon.Option option : parsedArguments.options()) {
            appOptions.put(option.name().toLowerCase(), option.value());
        }

        List<AppDaemon.Option> orderedOptions = parsedArguments.options();
        String subCommand = parsedArguments.subCommand();

        if (subCommand == null)
        {
            // 如果没有任何一个子命令，则直接打印帮助后退出
            System.err.println("Error: At least one subcommand is required. ");
            showUsage();
            System.exit(255);
            return;
        }

        // 检查是否用后台方式启动。
        // 注意这里有两点与历史实现不同（都是"静默做错事"的坑，详见 AppDaemon 类注释）：
        //   1. --daemon 的取值必须合法，写错（如 --daemon maybe）直接报错，而不是静默当前台运行；
        //   2. 命令行里显式给出的 --daemon 优先于配置文件，否则配置文件里写着 daemon=true 时
        //      子进程会重新读到它并再次 fork，形成无限递归。
        AppDaemon.DaemonIntent daemonIntent = parsedArguments.intent();

        // 如果有配置文件，用配置文件中数据进行更新（命令行已经显式给出 --daemon 时以命令行为准）
        if (appOptions.containsKey("conf"))
        {
            ServerConfiguration serverConfiguration = new ServerConfiguration();
            serverConfiguration.loadConfigurationFile(appOptions.get("conf"));
            daemonIntent = AppDaemon.resolve(daemonIntent, serverConfiguration.getDaemonMode());
        }

        // 只有start命令才支持daemon模式，其他情况下不支持
        daemonIntent = new AppDaemon.DaemonIntent(
                AppDaemon.applySubCommandRestriction(daemonIntent, subCommand, null),
                daemonIntent.fromCli());

        if (daemonIntent.requested())
        {
            // 先按命令行/配置文件里的日志设置把日志系统建起来：父进程只多打一条
            // "Daemon started"，但这条恰恰是排查"到底是哪个进程在跑"时唯一需要的线索，
            // 而子进程的启动日志本来就落在同一个文件里。
            String logTarget = appOptions.get("log");
            Logger daemonLogger = AppLogger.createLogger("SLACKER-PROXY", "INFO", logTarget);
            try {
                if (AppDaemon.startInBackground(Main.class, subCommand, orderedOptions,
                        logTarget, "log", daemonLogger)) {
                    System.exit(0);
                }
            } catch (Exception se) {
                daemonLogger.error("[DAEMON] Failed to start daemon process.", se);
                System.exit(255);
            }
            // 返回 false 说明当前是开发模式（不是从 jar 启动），无法脱离当前进程，继续前台运行
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
            if (inputStream != null) {
                inputStream.close();
            }
        }
        catch (IOException ioe)
        {
            version = "{project.version}";
            localBuildDate = "${build.timestamp}";
        }
        if (subCommand.equalsIgnoreCase("HELP"))
        {
            // 打印帮助信息
            showUsage();
            System.exit(0);
        }
        else if (subCommand.equalsIgnoreCase("VERSION"))
        {
            // 打印版本信息
            System.out.println("[PROXY] VERSION: " + version);
            System.out.println("[PROXY] Build Time: " + localBuildDate );
            System.exit(0);
        }

        // 处理应用程序参数
        try {
            ServerConfiguration serverConfiguration = new ServerConfiguration();
            // 如果有配置文件，用配置文件中数据进行更新
            if (appOptions.containsKey("conf"))
            {
                serverConfiguration.loadConfigurationFile(appOptions.get("conf"));
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
            if (appOptions.containsKey("portx"))
            {
                serverConfiguration.setPortX(appOptions.get("portx"));
            }
            if (appOptions.containsKey("listener_address"))
            {
                serverConfiguration.setListenerAddress(appOptions.get("listener_address"));
            }
            if (appOptions.containsKey("pid"))
            {
                serverConfiguration.setPid(appOptions.get("pid"));
            }

            // 初始化日志服务
            Logger logger = AppLogger.createLogger(
                    "SLACKER-PROXY",
                    serverConfiguration.getLog_level().levelStr,
                    serverConfiguration.getLog());
            if (subCommand.equalsIgnoreCase("START"))
            {
                // 如果要求启动，则启动应用程序
                logger.info("[SLACKER-PROXY] SlackerDB CDB starting (PID:{})...", ProcessHandle.current().pid());
                logger.info("[SLACKER-PROXY] VERSION：{}", version);
                logger.info("[SLACKER-PROXY] Build Time: {}", localBuildDate);

                // 初始化程序参数
                ProxyInstance proxyInstance = new ProxyInstance(serverConfiguration);
                // 设置为独占模式，当端口停止，程序也将停止
                proxyInstance.setExclusiveMode(true);
                // 启动代理转发服务
                proxyInstance.start();

                // 循环等待，避免主进程退出
                try {Thread.sleep(Long.MAX_VALUE);} catch (InterruptedException ignored) {
                    logger.info("[SLACKER-PROXY] Caught user interrupt. Quit.");
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
                AdminClient.doCommand(serverConfiguration, subCommand, appOptions);
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
