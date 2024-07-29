package org.slackerdb;

import ch.qos.logback.classic.Level;
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.postgres.PostgresProtocol;
import org.slackerdb.server.ServerConfiguration;
import org.slackerdb.server.TcpServer;
import org.slackerdb.server.TcpServerHandler;
import org.slackerdb.sql.jdbc.JdbcProxy;
import org.slackerdb.utils.Sleeper;
import org.apache.commons.cli.*;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Pattern;

public class Main {
    private static TcpServer protocolServer;

    public static boolean isRunning() {
        if (protocolServer == null) return false;
        return protocolServer.isRunning();
    }

    private static String checkAndGetConnectString() throws ServerException {
        String connectString = "jdbc:duckdb:";

        String instanceName = ServerConfiguration.getData().trim();
        // 检查是否包含路径分隔符
        if (instanceName.contains("/") || instanceName.contains("\\")) {
            throw new ServerException(999,
                    "Invalid instance name [" + instanceName + "]");
        }
        // 检查是否包含不合法字符
        if (Pattern.compile("[\\\\/:*?\"<>|]").matcher(instanceName).find()) {
            throw new ServerException(999,
                    "Invalid instance name [" + instanceName + "]");
        }
        // 检查文件名长度（假设文件系统限制为255字符）
        if (instanceName.isEmpty() || instanceName.length() > 255) {
            throw new ServerException(999,
                    "Invalid instance name [" + instanceName + "]");
        }
        if (ServerConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:"))
        {
            connectString = connectString + ":memory:" + instanceName;
        }
        else
        {
            if (!new File(ServerConfiguration.getData_Dir()).isDirectory())
            {
                throw new ServerException(999,
                        "Data directory [" + ServerConfiguration.getData_Dir() + "] does not exist!");
            }
            File dataFile = new File(ServerConfiguration.getData_Dir(), instanceName + ".db");
            if (!dataFile.canRead() && ServerConfiguration.getAccess_mode().equalsIgnoreCase("READ_ONLY"))
            {
                throw new ServerException(999,
                        "Data [" + dataFile.getAbsolutePath() + "] can't be read!!");
            }
            if (!dataFile.canRead() && ServerConfiguration.getAccess_mode().equalsIgnoreCase("READ_WRITE"))
            {
                throw new ServerException(999,
                        "Data [" + dataFile.getAbsolutePath() + "] can't be write!!");
            }
        }
        return connectString;
    }

    public static void setLogLevel(String logLevel) {
        AppLogger.setLogLevel(Level.valueOf(logLevel));
    }

    public static void start() throws Exception
    {
        // 如果连初始化配置都没有，则首先加载默认配置
        if (!ServerConfiguration.isLoaded())
        {
            ServerConfiguration.LoadDefaultConfiguration();
        }

        // 打开日志文件
        AppLogger.CreateLogger();

        // 获取数据库的连接字符串
        String connectString = checkAndGetConnectString();

        PostgresProtocol baseProtocol = new PostgresProtocol(ServerConfiguration.getPort());
        JdbcProxy proxy = new JdbcProxy("org.duckdb.DuckDBDriver", connectString);
        baseProtocol.setProxy(proxy);
        baseProtocol.initialize();

        // 指定访问的协议
        TcpServerHandler.protoDescriptor = baseProtocol;

        protocolServer = new TcpServer(baseProtocol);
        protocolServer.start();

        while (!protocolServer.isRunning()) {
            Sleeper.sleep(1000);
        }
    }


    public static void stop() {
        protocolServer.stop();
    }

    public static void clientRemoteStop()
    {
        try {
            // 如果连初始化配置都没有，则首先加载默认配置
            if (!ServerConfiguration.isLoaded())
            {
                ServerConfiguration.LoadDefaultConfiguration();
            }

            // 打开日志文件
            AppLogger.CreateLogger();

            AppLogger.logger.info("Will stop instance on port [{}]...", ServerConfiguration.getPort());

            // 只能停止本地的服务进程
            String connectURL = "jdbc:postgresql://";
            connectURL = connectURL + "127.0.0.1" + ":";
            connectURL = connectURL + ServerConfiguration.getPort() + "/";
            Connection pgConn1 = null;
            try {
                pgConn1 = DriverManager.getConnection(connectURL, "", "");
            }
            catch (SQLException sqlException)
            {
                AppLogger.logger.error("Connect to server fail. Instance maybe does not exist!");
                System.exit(1);
            }
            AppLogger.logger.info("Connect to server successful. Send shutdown request ...");
            pgConn1.createStatement().execute("ALTER DATABASE SHUTDOWN");
        }
        catch (Exception ex)
        {
            if (ex.getCause().getClass().getName().equals("java.net.SocketException"))
            {
                AppLogger.logger.info("Server has shutdown successful.");
                System.exit(0);
            }
            else {
                AppLogger.logger.error("Server shutdown failed.", ex);
                System.exit(1);
            }
        }
    }

    private static void printHelp(HelpFormatter formatter, Options options) {
        String header = "SlackerDB - A duckdb proxy \n\n";
        String footer = "\nExamples:\n" +
                "  java -jar slackerdb-<version>.jar start\n" +
                "  java -jar slackerdb-<version>.jar stop\n";
        formatter.printHelp("SlackerDB [OPTIONS] <operation>", header, options, footer, true);
    }

    public static void main(String[] args){
        // 处理应用程序参数
        // 配置文件信息
        Options options = new Options();
        Option confOption = new Option("c", "conf", true, "Configuration File");
        confOption.setRequired(false);
        options.addOption(confOption);

        // 数据库实例名称
        Option opOption1 = new Option("i", "instance", true, "Instance Name");
        opOption1.setRequired(false);
        options.addOption(opOption1);

        // 数据库端口信息
        Option opOption2 = new Option("p", "port", true, "Server Port");
        opOption2.setRequired(false);
        options.addOption(opOption2);

        // 数据库实例名称
        Option opOption3 = new Option("l", "log_level", true, "Log Level");
        opOption1.setRequired(false);
        options.addOption(opOption3);

        // 解析命令行
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try
        {
            CommandLine cmd = parser.parse(options, args);

            // 获取未命名的参数
            String[] remainingArgs = cmd.getArgs();
            if (remainingArgs.length != 1) {
                printHelp(formatter, options);
                System.exit(1);
            }
            String op = remainingArgs[0];

            // 配置文件
            if (cmd.hasOption("conf")) {
                ServerConfiguration.LoadConfiguration(cmd.getOptionValue("conf"));
            }
            else
            {
                ServerConfiguration.LoadDefaultConfiguration();
            }

            // 根据参数覆盖默认的配置
            if (cmd.hasOption("instance")) {
                ServerConfiguration.setData(cmd.getOptionValue("instance"));
            }
            if (cmd.hasOption("port")) {
                ServerConfiguration.setPort(Integer.parseInt(cmd.getOptionValue("port")));
            }
            if (cmd.hasOption("log_level")) {
                ServerConfiguration.setLog_level(Level.valueOf(cmd.getOptionValue("log_level")));
            }
            // 启动应用程序
            if (op.trim().equalsIgnoreCase("START")) {
                start();
            }
            else if (op.trim().equalsIgnoreCase("STOP")) {
                clientRemoteStop();
            }
            else
            {
                printHelp(formatter, options);
                System.exit(1);
            }
        }
        catch (ParseException e)
        {
            System.out.println(e.getMessage());
            printHelp(formatter, options);
            System.exit(1);
        }
        catch (Exception ex)
        {
            AppLogger.logger.trace("[SERVER] Fatal exception.", ex);
            System.err.println(ex.getMessage());
            System.exit(1);
        }
    }
}