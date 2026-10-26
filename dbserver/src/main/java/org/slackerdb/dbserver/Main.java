package org.slackerdb.dbserver;
/*
    Main

    主程序. 根据命令行参数来完成数据库的启动、停止、状态查看

 */

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.utils.AppDaemon;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.dbserver.client.AdminClient;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.dbserver.server.DBInstance;

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
        System.out.println("  start     Start the Slackerdb server.");
        System.out.println("  stop      Stop the Slackerdb server.");
        System.out.println("  status    Display server status.");
        System.out.println("  help      Display this help message.");
        System.out.println("  version   Display server version information.");
        System.out.println("Parameters:");
        System.out.println("  --conf                      Specify configuration file path.");
        System.out.println("  --daemon                    Run in background daemon mode: true|false (bare --daemon means true)."
                + " Overrides daemon= in the config file. Default: false.");
        System.out.println("  --pid                       Specify process ID file path. Default: none.");
        System.out.println("  --access_mode               Set database access mode: READ_ONLY|READ_WRITE. Default: READ_WRITE.");
        System.out.println("  --locale                    Set default program locale.");
        System.out.println("  --log_level                 Set logging level. Default: INFO.");
        System.out.println("  --log, --logfile            Specify log output file. Default: CONSOLE.");
        System.out.println("  --bind                      Set server binding IP address. Default: 0.0.0.0");
        System.out.println("  --host                      Set remote server address. Default: 127.0.0.1");
        System.out.println("  --advertise_address         Multicast group to announce this service to, as <group>:<port>"
                + " (for example 239.255.66.66:46666). Default: none (service discovery disabled).");
        System.out.println("  --advertise_nic             Local network interface used for the multicast announcement."
                + " Accepts an interface name (eth0, Ethernet) or a local IP. Default: first usable interface.");
        System.out.println("  --port                      Set data service listener port. Default: random");
        System.out.println("  --portX                     Set management service listener port. Default: none");
        System.out.println("  --memory_limit              Set maximum memory allocation. Format: <value>(K|M|G)");
        System.out.println("  --auto_workload_threshold   Set auto workload threshold for memory limit calculation. Default: 0.8.");
        System.out.println("  --max_workers               Set maximum concurrent worker processes.");
        System.out.println("  --threads                   Set maximum thread count for server computation layer.");
        System.out.println("  --data                      Specify database name. Default: none");
        System.out.println("  --data_encrypt              Enable data encryption. Default: false");
        System.out.println("  --data_dir                  Specify database file directory. Default: :memory:");
        System.out.println("  --temp_dir                  Specify temporary file directory. Default: same with data_dir(disk mode), OS-dependent(memory mode).");
        System.out.println("  --plugins_dir               Specify plugins file directory. Default: none.");
        System.out.println("  --extension_dir             Specify extension file directory. Default: $HOME/.duckdb/extensions.");
        System.out.println("  --template                  Specify template datafile for initial setup. Default: none.");
        System.out.println("  --init_script               Specify system initialization script or directory. Default: none.");
        System.out.println("  --startup_script            Specify system startup script or directory. Default: none.");
        System.out.println("  --sql_history               Enable/disable SQL history feature (ON|OFF). Default: OFF.");
        System.out.println("  --query_result_cache_size   Set maximum API query result cache size (bytes).");
        System.out.println("  --data_service_schema       Specify predefined data service schema. Default: none.");
        System.out.println("  --data_service_history      Enable/disable API history feature (ON|OFF). Default: OFF.");
        System.out.println("  --mcp-config                Specify MCP configuration file path. Default: empty.");
        System.out.println("  --mcp-llm-server            Specify MCP LLM server configuration. Format: <service>:<ip>:<port>:<model>. Default: empty.");
        System.out.println("  --socket                    Specify Unix Domain Socket file path for UDS connections. Default: empty (TCP mode).");
        System.out.println("  --quack_addr                Specify Quack remote server address. Format: IP:PORT. Default: none.");
        System.out.println("  --<pluginId>.<param>         Specify plugin-specific parameters. Example: --scheduler.home=someDir");

    }

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
            String name = option.name();
            // 插件参数形如 <pluginId>.<paramName>，**必须保留原样大小写**：
            // 插件内部按 camelCase 取（例如 getPluginProperty("projectHome")），
            // 一律 toLowerCase 会让这类参数永远查不到（实测 plugin-scheduler 因此起不来）。
            String key = name.indexOf('.') > 0 ? name : name.toLowerCase();
            appOptions.put(key, option.value());
        }

        List<AppDaemon.Option> orderedOptions = parsedArguments.options();
        String subCommand = parsedArguments.subCommand();
        Logger logger = null;

        // 必须有一个子命令，start,stop,status等
        if (subCommand == null)
        {
            // 如果没有任何一个子命令，则直接打印帮助后退出
            System.err.println("Error: At least one subcommand is required. ");
            showUsage();
            System.exit(255);
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
            String logTarget = appOptions.containsKey("log")
                    ? appOptions.get("log") : appOptions.get("logfile");
            logger = AppLogger.createLogger(subCommand, "INFO", logTarget);
            try {
                if (AppDaemon.startInBackground(Main.class, subCommand, orderedOptions,
                        logTarget, "log", logger)) {
                    System.exit(0);
                }
            } catch (Exception se) {
                logger.error("[DAEMON] Failed to start daemon process.", se);
                System.exit(255);
            }
            // 返回 false 说明当前是开发模式（不是从 jar 启动），无法脱离当前进程，继续前台运行
        }

        // 版本与构建时刻来自构建期生成的 META-INF/build-info.properties
        // （spring-boot-maven-plugin 的 build-info 目标；读取与格式化见 BuildInfo）
        String version = BuildInfo.version();
        String localBuildDate = BuildInfo.buildTime();

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
            else if (appOptions.containsKey("logfile"))
            {
                serverConfiguration.setLog(appOptions.get("logfile"));
            }
            if (appOptions.containsKey("bind"))
            {
                serverConfiguration.setBindHost(appOptions.get("bind"));
            }
            if (appOptions.containsKey("host"))
            {
                serverConfiguration.setBindHost(appOptions.get("host"));
            }
            if (appOptions.containsKey("advertise_address"))
            {
                serverConfiguration.setAdvertiseAddress(appOptions.get("advertise_address"));
            }
            if (appOptions.containsKey("advertise_nic"))
            {
                serverConfiguration.setAdvertiseNic(appOptions.get("advertise_nic"));
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
            if (appOptions.containsKey("plugins_dir"))
            {
                serverConfiguration.setPlugins_dir(appOptions.get("plugins_dir"));
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
            if (appOptions.containsKey("auto_workload_threshold"))
            {
                serverConfiguration.setAuto_workload_threshold(appOptions.get("auto_workload_threshold"));
            }
            if (appOptions.containsKey("max_workers"))
            {
                serverConfiguration.setMax_workers(appOptions.get("max_workers"));
            }
            if (appOptions.containsKey("query_result_cache_size"))
            {
                serverConfiguration.setQuery_result_cache_size(appOptions.get("query_result_cache_size"));
            }
            if (appOptions.containsKey("data_service_schema"))
            {
                serverConfiguration.setDataServiceSchema(appOptions.get("data_service_schema"));
            }
            if (appOptions.containsKey("data_service_history"))
            {
                serverConfiguration.setDataServiceHistory(appOptions.get("data_service_history"));
            }
            if (appOptions.containsKey("data_encrypt"))
            {
                serverConfiguration.setDataEncrypt(appOptions.get("data_encrypt"));
            }
            if (appOptions.containsKey("mcp-config"))
            {
                serverConfiguration.setMcpConfig(appOptions.get("mcp-config"));
            }
            if (appOptions.containsKey("mcp-llm-server"))
            {
                serverConfiguration.setMcpLlmServer(appOptions.get("mcp-llm-server"));
            }
            if (appOptions.containsKey("socket"))
            {
                serverConfiguration.setSocket(appOptions.get("socket"));
            }
            if (appOptions.containsKey("quack_addr"))
            {
                serverConfiguration.setQuackAddr(appOptions.get("quack_addr"));
            }

            // 处理插件自定义参数（格式为 <pluginId>.<paramName>）
            for (Map.Entry<String, String> entry : appOptions.entrySet()) {
                String key = entry.getKey();
                int dotIndex = key.indexOf('.');
                if (dotIndex > 0 && dotIndex < key.length() - 1) {
                    // 这是一个插件参数，记录到ServerConfiguration的pluginProperties中
                    serverConfiguration.setPluginProperty(key, entry.getValue());
                }
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
                logger.info("[SERVER] VERSION: {}", version);
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
