package org.slackerdb.dbserver;
/*
    Main

    主程序. 根据命令行参数来完成数据库的启动、停止、状态查看

 */

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.utils.OSUtil;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.dbserver.client.AdminClient;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.File;
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
        System.out.println("  start     Start slackerdb server.");
        System.out.println("  stop      Stop slackerdb server.");
        System.out.println("  status    print server status.");
        System.out.println("  help      print this message.");
        System.out.println("  version   print server version.");
        System.out.println("Parameters:");
        System.out.println("  --conf                      Configuration file.");
        System.out.println("  --daemon                    Run server in background. default is false.");
        System.out.println("  --pid                       process pid file, default is none.");
        System.out.println("  --access_mode               database mode, READ_ONLY|READ_WRITE, default is READ_WRITE.");
        System.out.println("  --locale                    default language of the program.");
        System.out.println("  --log_level                 log level, default is INFO.");
        System.out.println("  --log                       log file, default is CONSOLE.");
        System.out.println("  --bind                      server bind ip address, default is 0.0.0.0");
        System.out.println("  --host                      remote server address,  default is 127.0.0.1");
        System.out.println("  --remote_listener           external remote listener address, default is none.");
        System.out.println("  --port                      data service listener port. default is random");
        System.out.println("  --portX                     management service listener port. default is random");
        System.out.println("  --memory_limit              maximum memory size used. Format:  ....(K|M|G)");
        System.out.println("  --max_workers               maximum concurrent quantity.");
        System.out.println("  --threads                   maximum number of threads used on the server compute layer.");
        System.out.println("  --data                      database name. default is slackerdb");
        System.out.println("  --data_dir                  database file directory. default is :memory:");
        System.out.println("  --temp_dir                  database temporary file directory. default is os dependent.");
        System.out.println("  --extension_dir             extension file directory. default is $HOME/.duckdb/extensions.");
        System.out.println("  --template                  template datafile file for first open. default is none.");
        System.out.println("  --init_script               system init script or script directory. default is none.");
        System.out.println("  --startup_script            system startup script or script directory. default is none.");
        System.out.println("  --sql_history               enable or disable sql history feature(ON|OFF). default is off.");
        System.out.println("  --query_result_cache_size   maximum size (bytes) of api query result cache.");
        System.out.println("  --autoload                  enable or disable automatically attach new datafile under data_dir. (ON|OFF). default is off.");
    }

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
        else
        {
            // jarPath需要进行转换, 转为操作系统下的路径方式
            try {
                jarPath = new File(jarPath).getCanonicalPath();
            }
            catch (IOException ignored) {}
        }

        // 处理应用程序参数
        Map<String, String> appOptions = new HashMap<>();
        String subCommand = null;
        Logger logger = null;

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
        // 必须有一个子命令，start,stop,status等
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
            System.out.println("[SERVER] VERSION: " + version);
            System.out.println("[SERVER] Build Time: " + localBuildDate );
            System.exit(0);
        }

        try
        {
            ServerConfiguration serverConfiguration = new ServerConfiguration();

            // 如果有配置文件，用配置文件中数据进行更新
            if (appOptions.containsKey("conf"))
            {
                serverConfiguration.loadConfigurationFile(appOptions.get("conf"));
            }
            // 如果有其他的指定，以指定的内容为准
            if (appOptions.containsKey("access_mode"))
            {
                serverConfiguration.setAccess_mode(appOptions.get("access_mode"));
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
            if (appOptions.containsKey("remote_listener"))
            {
                serverConfiguration.setRemoteListener(appOptions.get("remote_listener"));
            }
            if (appOptions.containsKey("port"))
            {
                serverConfiguration.setPort(appOptions.get("port"));
            }
            if (appOptions.containsKey("portx"))
            {
                serverConfiguration.setPortX(appOptions.get("portx"));
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
            if (appOptions.containsKey("autoload"))
            {
                serverConfiguration.setAutoload(appOptions.get("autoload"));
            }
            if (appOptions.containsKey("pid"))
            {
                serverConfiguration.setPid(appOptions.get("pid"));
            }
            if (appOptions.containsKey("template"))
            {
                serverConfiguration.setTemplate(appOptions.get("template"));
            }
            if (appOptions.containsKey("threads"))
            {
                serverConfiguration.setThreads(appOptions.get("threads"));
            }
            if (appOptions.containsKey("memory_limit"))
            {
                serverConfiguration.setMemory_limit(appOptions.get("memory_limit"));
            }
            if (appOptions.containsKey("max_workers"))
            {
                serverConfiguration.setMax_workers(appOptions.get("max_workers"));
            }
            if (appOptions.containsKey("query_result_cache_size"))
            {
                serverConfiguration.setQuery_result_cache_size(appOptions.get("query_result_cache_size"));
            }

            // 初始化日志服务
            logger = AppLogger.createLogger(
                    serverConfiguration.getData(),
                    serverConfiguration.getLog_level().levelStr,
                    serverConfiguration.getLog());

            // 启动应用程序
            if (subCommand.toUpperCase().startsWith("START")) {
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
                AdminClient.doCommand(serverConfiguration, subCommand, appOptions);
            }

            // 退出应用程序
            System.exit(0);
        }
        catch (Exception se)
        {
            if (logger != null)
            {
                logger.error("[SERVER] ServerException:", se);
            }
            else
            {
                se.printStackTrace(System.err);
            }
            System.exit(255);
        }
    }
}
