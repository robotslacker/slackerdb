package org.slackerdb.dbproxy;
/*
    Main

    主程序. 根据命令行参数来完成代理的启动、停止、状态查看

 */

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.utils.OSUtil;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.dbproxy.client.AdminClient;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        System.out.println("  --daemon            Run server in background. default is false.");
        System.out.println("  --pid               process pid file, default is none.");
        System.out.println("  --locale            default language of the program.");
        System.out.println("  --log_level         log level, default is INFO.");
        System.out.println("  --log               log file, default is CONSOLE.");
        System.out.println("  --bind              server bind ip address, default is 0.0.0.0");
        System.out.println("  --port              data service listener port. default is random");
        System.out.println("  --port_x            management service listener port. default is random");
    }

    // 主程序
    public static void main(String[] args){
        // 获得当前程序启动的JAVA_HOME
        String javaHome = System.getProperty("java.home");

        // 获取当前JAR包所在的目录
        URL url = Main.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();
        String jarPath;
        jarPath = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8);
        if (jarPath.contains("/!")) {
            jarPath = jarPath.substring(0, jarPath.indexOf("/!"));
        }
        jarPath = jarPath.replace("nested:", "");
        if (!jarPath.endsWith(".jar"))
        {
            jarPath = null;
        }

        // 处理应用程序参数
        Map<String, String> appOptions = new HashMap<>();
        // 参数-子命令
        String subCommand = null;
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
                        subCommand = arg;
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

        // 检查是否用后台方式启动
        boolean daemonMode = appOptions.containsKey("daemon") && appOptions.get("daemon").equalsIgnoreCase("true");

        // 如果有配置文件，用配置文件中数据进行更新
        if (appOptions.containsKey("conf"))
        {
            ServerConfiguration serverConfiguration = new ServerConfiguration();
            serverConfiguration.loadConfigurationFile(appOptions.get("conf"));
            if (serverConfiguration.getDaemonMode())
            {
                daemonMode = true;
            }
        }

        // 只有start命令才支持daemon模式，其他情况下不支持
        if (daemonMode && !subCommand.equalsIgnoreCase("start"))
        {
            System.out.println("Warn: [" + subCommand + "] does not support daemon mode, ignore it.");
            daemonMode = false;
        }

        if (daemonMode)
        {
            if (jarPath != null) {
                String javaCommand;
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    javaCommand = Path.of(javaHome, "bin", "java.exe").toString();
                } else {
                    javaCommand = Path.of(javaHome, "bin", "java").toString();
                }
                List<String> daemonCommand = new ArrayList<>();
                // JAVA_BIN
                daemonCommand.add(javaCommand);
                // JAR包
                daemonCommand.add("-jar");
                daemonCommand.add(jarPath);
                // 复制其他参数
                for (String paramKey : appOptions.keySet()) {
                    if (paramKey.equalsIgnoreCase("daemon")) {
                        continue;
                    }
                    daemonCommand.add("--" + paramKey);
                    if (appOptions.get(paramKey) != null) {
                        daemonCommand.add(appOptions.get(paramKey));
                    }
                }
                // 子进程不能带有daemon参数，避免循环
                daemonCommand.add("--daemon");
                daemonCommand.add("false");

                // 复制命令
                daemonCommand.add(subCommand);

                // 非阻塞执行
                try {
                    long pid = OSUtil.launchDaemon(daemonCommand.toArray(new String[0]));
                    System.out.println("Daemon pid [" + pid + "] has successful started in background.");
                    System.exit(0);
                } catch (Exception se) {
                    se.printStackTrace(System.err);
                    System.exit(255);
                }
            }
            else
            {
                System.err.println("Error: Daemon is not supported in dev mode. Ignored.");
            }
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
            if (appOptions.containsKey("port_x"))
            {
                serverConfiguration.setPortX(appOptions.get("port_x"));
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
