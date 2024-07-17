package org.slackerdb;

import ch.qos.logback.classic.Level;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.postgres.PostgresProtocol;
import org.slackerdb.protocol.context.ProtoContext;
import org.slackerdb.server.ServerConfiguration;
import org.slackerdb.server.TcpServer;
import org.slackerdb.sql.jdbc.JdbcProxy;
import org.slackerdb.utils.Sleeper;
import org.apache.commons.cli.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class Main {
    private static TcpServer protocolServer;

    public static boolean isRunning() {
        if (protocolServer == null) return false;
        return protocolServer.isRunning();
    }

    public static void execute() throws Exception {
        String connectString = "jdbc:duckdb:";
        if (ServerConfiguration.getData().isEmpty())
        {
            connectString = connectString + ":memory:";
        }
        else
        {
            connectString = connectString + ServerConfiguration.getData();
        }
        ProtoContext.setTimeout(30);

        var baseProtocol = new PostgresProtocol(ServerConfiguration.getPort());
        var proxy = new JdbcProxy("org.duckdb.DuckDBDriver", connectString);
        baseProtocol.setProxy(proxy);
        baseProtocol.initialize();
        protocolServer = new TcpServer(baseProtocol);
        protocolServer.useCallDurationTimes(false);
        protocolServer.start();

        while (!protocolServer.isRunning()) {
            Sleeper.sleep(100);
        }
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

        // 主程序
        execute();
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
            System.err.println(ex.getMessage());
            System.exit(1);
        }
    }
}