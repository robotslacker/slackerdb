package org.slackerdb.logger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

public class AppLogger {
    public static Logger CreateLogger(String loggerName, String pLogLevel, String pLogsStr)
    {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        Level log_level = Level.valueOf(pLogLevel);
        String[] logs = pLogsStr.split(",");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        // 清除默认配置
        rootLogger.detachAndStopAllAppenders();

        // 关闭Netty的日志
        // Retrieve the logger for Netty's internal logging
        Logger nettyLogger = (Logger) LoggerFactory.getLogger("io.netty");
        nettyLogger.setLevel(Level.OFF);

        Iterator<Appender<ILoggingEvent>> iterator = logger.iteratorForAppenders();
        while (iterator.hasNext()) {
            Appender<ILoggingEvent> appender = iterator.next();
            if (appender.getClass().getSimpleName().equals("AsyncAppender")) {
                logger.detachAppender(appender);
            }
        }

        for (String log : logs) {
            // 控制台输出配置
            if (log.trim().equalsIgnoreCase("CONSOLE")) {
                ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
                consoleAppender.setContext(context);
                consoleAppender.setName("CONSOLE");
                PatternLayout consoleLayout = new PatternLayout();
                consoleLayout.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
                consoleLayout.setContext(context);
                consoleLayout.start();
                consoleAppender.setLayout(consoleLayout);
                consoleAppender.start();
                rootLogger.addAppender(consoleAppender);
            }
            else
            {
                // 文件输出配置
                FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
                fileAppender.setContext(context);
                fileAppender.setName("FILE");
                fileAppender.setFile(log.trim()); // 指定输出文件
                PatternLayout fileLayout = new PatternLayout();
                fileLayout.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
                fileLayout.setContext(context);
                fileLayout.start();
                fileAppender.setLayout(fileLayout);
                fileAppender.start();
                rootLogger.addAppender(fileAppender);
            }
        }

        if (log_level != null)
        {
            logger.setLevel(log_level);
        }
        else
        {
            logger.setLevel(Level.valueOf(pLogLevel));
            if (pLogLevel.equalsIgnoreCase("TRACE")) {
                logger.trace("[LOGGER] Logger level has been set to TRACE.");
            }
            if (pLogLevel.equalsIgnoreCase("DEBUG")) {
                logger.trace("[LOGGER] Logger level has been set to DEBUG.");
            }
        }
        return logger;
    }
}
