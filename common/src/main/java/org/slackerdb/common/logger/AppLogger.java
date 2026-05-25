package org.slackerdb.common.logger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;

public class AppLogger {
    public static Logger createLogger(String loggerName, String pLogLevel, String pLogsStr)
    {
        if (pLogsStr == null)
        {
            // 如果没有提供LOG的位置，则仅输出到屏幕
            pLogsStr = "CONSOLE";
        }
        if (pLogLevel == null)
        {
            // 如果没有提供级别，默认为INFO级别
            pLogLevel = "INFO";
        }

        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        Level log_level = Level.valueOf(pLogLevel);
        String[] logs = pLogsStr.split(",");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        // 清除当前 logger 上已有的所有 appender，避免重复调用 createLogger 时叠加 appender 导致日志重复
        logger.detachAndStopAllAppenders();

        // 将 appender 添加到当前 logger 而非 root，避免影响上层应用
        for (String log : logs) {
            // 控制台输出配置
            if (log.trim().equalsIgnoreCase("CONSOLE")) {
                ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
                consoleAppender.setContext(context);
                consoleAppender.setName("CONSOLE");
                PatternLayoutEncoder consoleEncoder = new PatternLayoutEncoder();
                consoleEncoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
                consoleEncoder.setContext(context);
                consoleEncoder.start();
                consoleAppender.setEncoder(consoleEncoder);
                consoleAppender.start();
                logger.addAppender(consoleAppender);
            }
            else
            {
                // 文件输出配置
                FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
                fileAppender.setContext(context);
                fileAppender.setName("FILE");
                fileAppender.setFile(log.trim()); // 指定输出文件
                fileAppender.setAppend(true);
                PatternLayoutEncoder fileEncoder = new PatternLayoutEncoder();
                fileEncoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
                fileEncoder.setContext(context);
                fileEncoder.start();
                fileAppender.setEncoder(fileEncoder);
                fileAppender.start();
                logger.addAppender(fileAppender);
            }
        }

        // 阻止当前 logger 的日志向上传播到 root，避免重复输出
        logger.setAdditive(false);

        if (log_level != null)
        {
            logger.setLevel(log_level);
        }
        else {
            logger.warn("[LOGGER] Invalid log level parameter. Fallback to INFO.");
            logger.setLevel(Level.INFO);
        }

        if (pLogLevel.equalsIgnoreCase("TRACE")) {
            logger.trace("[LOGGER] Logger level has been set to TRACE.");
        }
        if (pLogLevel.equalsIgnoreCase("DEBUG")) {
            logger.trace("[LOGGER] Logger level has been set to DEBUG.");
        }
        return logger;
    }
}
