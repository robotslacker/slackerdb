package org.slackerdb;

import ch.qos.logback.classic.Level;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.protocol.postgres.server.PostgresServer;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Sleeper;
import org.apache.commons.cli.*;

public class Main {
    public static void setLogLevel(String logLevel) {
        AppLogger.setLogLevel(Level.valueOf(logLevel));
    }

    public static void start() throws Exception
    {
        // 加载默认的配置信息，不会覆盖配置文件中的内容或者命令行参数指定的内容
        ServerConfiguration.LoadDefaultConfiguration();

        // 打开日志文件
        AppLogger.CreateLogger();

        // 启动服务器
        PostgresServer protocolServer = new PostgresServer();
        protocolServer.start();

        // 等待服务进程启动
        while (!DBInstance.state.equalsIgnoreCase("RUNNING") &&
                !DBInstance.state.equalsIgnoreCase("STARTUP FAILED")) {
            Sleeper.sleep(1000);
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
        // 标记数据库正在启动中
        DBInstance.state = "STARTING";

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

            // 如果有配置文件，用配置文件中数据进行更新
            if (cmd.hasOption("conf")) {
                ServerConfiguration.LoadConfigurationFile(cmd.getOptionValue("conf"));
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