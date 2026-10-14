package org.slackerdb.common.logger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.Duration;
import ch.qos.logback.core.util.FileSize;
import org.slf4j.LoggerFactory;

/**
 * 应用日志工厂：SlackerDB 各进程（dbserver / dbproxy）唯一的日志落盘配置入口。
 *
 * <p>日志目标通过逗号分隔的字符串描述，例如 {@code CONSOLE,logs/slackerdb.log}。
 * appender 挂在<b>具名 logger</b> 上（{@code additive=false}），不同实例可以各写各的文件，
 * 因此一个 JVM 内跑多个实例时互不干扰。</p>
 *
 * <p>落盘策略由 {@link LogOptions} 描述，共三种：</p>
 * <ul>
 *   <li>{@link LogOptions.Mode#NONE}（默认，等同历史行为）：单文件追加，永不滚动；</li>
 *   <li>{@link LogOptions.Mode#SIZE}：固定窗口按大小滚动，磁盘上界 =
 *       {@code maxFileSize × (maxHistory + 1)}，<b>可预测</b>；</li>
 *   <li>{@link LogOptions.Mode#SIZE_AND_TIME}：按大小 + 日期滚动，按时间段保留，
 *       同一时间段内的文件数不设上界（{@code totalSizeCap} 只清理更早时间段的文件）。</li>
 * </ul>
 *
 * <p>无论是否滚动，appender 启动失败时都会退回单文件 appender —— 宁可"不多留"，不能"一条不落"。
 * 重复调用本类不会叠加 appender：每次都先 {@code detachAndStopAllAppenders()}。</p>
 */
public class AppLogger {

    /** 默认日志行格式（与历史实现逐字符一致，改动会影响所有现网日志解析）。 */
    public static final String DEFAULT_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";

    private static final String CONSOLE_TARGET = "CONSOLE";

    /**
     * 与历史行为完全一致的重载：单文件追加、不滚动。
     *
     * @param loggerName logger 名称（appender 挂在该 logger 上）
     * @param pLogLevel  日志级别，null 时按 INFO 处理；非法值会告警并回退 INFO
     * @param pLogsStr   日志目标，逗号分隔；{@code CONSOLE} 表示标准输出，其余按文件路径处理
     */
    public static Logger createLogger(String loggerName, String pLogLevel, String pLogsStr) {
        return createLogger(loggerName, pLogLevel, pLogsStr, LogOptions.defaults());
    }

    /**
     * 带落盘配置的重载。
     *
     * @param options 落盘策略；传 {@code null} 等价于 {@link LogOptions#defaults()}
     */
    public static Logger createLogger(String loggerName, String pLogLevel, String pLogsStr, LogOptions options) {
        LogOptions logOptions = options == null ? LogOptions.defaults() : options;

        if (pLogsStr == null) {
            // 如果没有提供LOG的位置，则仅输出到屏幕
            pLogsStr = CONSOLE_TARGET;
        }
        if (pLogLevel == null) {
            // 如果没有提供级别，默认为INFO级别
            pLogLevel = "INFO";
        }

        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        String[] logs = pLogsStr.split(",");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        // 清除当前 logger 上已有的所有 appender，避免重复调用 createLogger 时叠加 appender 导致日志重复
        logger.detachAndStopAllAppenders();

        for (String log : logs) {
            String target = log == null ? "" : log.trim();
            if (target.isEmpty()) {
                continue;
            }
            if (target.equalsIgnoreCase(CONSOLE_TARGET)) {
                logger.addAppender(buildConsoleAppender(context, logOptions));
            } else {
                logger.addAppender(buildFileAppender(context, target, logOptions, logger));
            }
        }

        // 阻止当前 logger 的日志向上传播到 root，避免重复输出
        logger.setAdditive(false);

        logger.setLevel(resolveLevel(pLogLevel, logger));

        if (pLogLevel.equalsIgnoreCase("TRACE")) {
            logger.trace("[LOGGER] Logger level has been set to TRACE.");
        }
        if (pLogLevel.equalsIgnoreCase("DEBUG")) {
            logger.trace("[LOGGER] Logger level has been set to DEBUG.");
        }
        return logger;
    }

    private static Appender<ILoggingEvent> buildConsoleAppender(LoggerContext context, LogOptions options) {
        ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
        consoleAppender.setContext(context);
        consoleAppender.setName("CONSOLE");
        consoleAppender.setEncoder(buildEncoder(context, options));
        consoleAppender.start();
        return consoleAppender;
    }

    /** 按 {@link LogOptions#getMode()} 选择落盘方式。 */
    private static Appender<ILoggingEvent> buildFileAppender(LoggerContext context, String path,
                                                            LogOptions options, Logger logger) {
        switch (options.getMode()) {
            case SIZE:
                return buildSizeBoundedAppender(context, path, options, logger);
            case SIZE_AND_TIME:
                return buildSizeAndTimeAppender(context, path, options, logger);
            case NONE:
            default:
                return buildPlainFileAppender(context, path, options);
        }
    }

    /**
     * 固定窗口 + 大小触发：文件数有硬上界，因此磁盘占用可预测。
     * 上界 = {@code maxFileSize × (maxHistory + 1)}（含活动文件）。
     */
    private static Appender<ILoggingEvent> buildSizeBoundedAppender(LoggerContext context, String path,
                                                                   LogOptions options, Logger logger) {
        int windowSize = options.effectiveWindowSize();
        if (options.windowSizeWasClamped()) {
            logger.warn("[LOGGER] maxHistory={} exceeds logback's fixed-window limit {}; clamped to {}. "
                            + "Disk ceiling is now maxFileSize × ({} + 1).",
                    options.getMaxHistory(), LogOptions.maxWindowSize(), windowSize, windowSize);
        }

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(context);
        appender.setName("FILE-" + Integer.toHexString(path.hashCode()));
        appender.setFile(path);
        appender.setAppend(true);
        appender.setImmediateFlush(options.isImmediateFlush());
        appender.setEncoder(buildEncoder(context, options));

        String fileNamePattern = path + ".%i" + (options.isCompress() ? ".gz" : "");

        FixedWindowRollingPolicy policy = new FixedWindowRollingPolicy();
        policy.setContext(context);
        policy.setParent(appender);
        policy.setFileNamePattern(fileNamePattern);
        policy.setMinIndex(1);
        policy.setMaxIndex(windowSize);
        policy.start();

        SizeBasedTriggeringPolicy<ILoggingEvent> triggeringPolicy = new SizeBasedTriggeringPolicy<>();
        triggeringPolicy.setContext(context);
        triggeringPolicy.setMaxFileSize(FileSize.valueOf(options.getMaxFileSize()));
        // 必须显式设置检查间隔：logback 内置的 SimpleInvocationGate 默认 60 秒才检查一次文件大小，
        // 密集写入时文件会涨到远超 maxFileSize 才滚动，"硬上界"形同虚设。
        triggeringPolicy.setCheckIncrement(Duration.buildByMilliseconds(options.getCheckIncrementMillis()));
        triggeringPolicy.start();

        appender.setRollingPolicy(policy);
        appender.setTriggeringPolicy(triggeringPolicy);
        appender.start();

        if (!appender.isStarted()) {
            logger.warn("[LOGGER] Size-bounded rolling appender for [{}] failed to start "
                    + "(maxFileSize={}, fileNamePattern={}); falling back to a single log file.",
                    path, options.getMaxFileSize(), fileNamePattern);
            return buildPlainFileAppender(context, path, options);
        }
        if (options.getTotalSizeCap() != null && !options.getTotalSizeCap().isBlank()) {
            logger.debug("[LOGGER] mode=SIZE ignores totalSizeCap [{}]; the ceiling comes from "
                    + "maxFileSize × (maxHistory + 1).", options.getTotalSizeCap());
        }
        return appender;
    }

    /**
     * 大小 + 日期滚动：按时间段保留（{@code maxHistory} 是天/小时数，不是文件数）。
     *
     * <p>注意其 {@code totalSizeCap} 只能回收"更早时间段"的文件，
     * 同一时间段内高频滚动仍会堆积文件——要硬上界请用 {@link LogOptions.Mode#SIZE}。</p>
     */
    private static Appender<ILoggingEvent> buildSizeAndTimeAppender(LoggerContext context, String path,
                                                                   LogOptions options, Logger logger) {
        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(context);
        appender.setName("FILE-" + Integer.toHexString(path.hashCode()));
        appender.setFile(path);
        appender.setAppend(true);
        appender.setImmediateFlush(options.isImmediateFlush());
        appender.setEncoder(buildEncoder(context, options));

        if (options.getMaxHistory() <= 0) {
            logger.warn("[LOGGER] mode=SIZE_AND_TIME with maxHistory={} (<=0): logback will ignore "
                    + "totalSizeCap and never clean history files, so disk usage has NO upper bound.",
                    options.getMaxHistory());
        }

        String fileNamePattern = path + ".%d{yyyy-MM-dd}.%i" + (options.isCompress() ? ".gz" : "");

        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(context);
        policy.setParent(appender);
        policy.setFileNamePattern(fileNamePattern);
        policy.setMaxFileSize(FileSize.valueOf(options.getMaxFileSize()));
        policy.setMaxHistory(options.getMaxHistory());
        if (options.getTotalSizeCap() != null && !options.getTotalSizeCap().isBlank()) {
            policy.setTotalSizeCap(FileSize.valueOf(options.getTotalSizeCap()));
        }
        policy.setCleanHistoryOnStart(options.isCleanHistoryOnStart());
        policy.start();

        appender.setRollingPolicy(policy);
        appender.start();

        if (!appender.isStarted()) {
            logger.warn("[LOGGER] Size-and-time rolling appender for [{}] failed to start "
                    + "(maxFileSize={}, fileNamePattern={}); falling back to a single log file.",
                    path, options.getMaxFileSize(), fileNamePattern);
            return buildPlainFileAppender(context, path, options);
        }
        return appender;
    }

    /** 单文件追加（历史行为）。 */
    private static Appender<ILoggingEvent> buildPlainFileAppender(LoggerContext context, String path, LogOptions options) {
        FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setContext(context);
        fileAppender.setName("FILE-" + Integer.toHexString(path.hashCode()));
        fileAppender.setFile(path);
        fileAppender.setAppend(true);
        fileAppender.setImmediateFlush(options.isImmediateFlush());
        fileAppender.setEncoder(buildEncoder(context, options));
        fileAppender.start();
        return fileAppender;
    }

    private static Encoder<ILoggingEvent> buildEncoder(LoggerContext context, LogOptions options) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setPattern(options.getPattern() == null || options.getPattern().isBlank()
                ? DEFAULT_PATTERN : options.getPattern());
        encoder.setContext(context);
        encoder.start();
        return encoder;
    }

    /**
     * 解析级别：非法值不再静默变成 DEBUG，而是告警后回退 INFO。
     *
     * <p>logback 的 {@code Level.toLevel(String, Level)} 对未知级别返回传入的默认值，
     * 因此这里传 {@code null} 作为哨兵来区分"合法"与"非法"；
     * 历史实现里的"回退 INFO"分支实际不可达，非法级别会被 logback 变成 DEBUG（噪音很大）。</p>
     */
    private static Level resolveLevel(String pLogLevel, Logger logger) {
        Level level = Level.toLevel(pLogLevel, null);
        if (level == null) {
            logger.warn("[LOGGER] Invalid log level parameter [{}]. Fallback to INFO.", pLogLevel);
            return Level.INFO;
        }
        return level;
    }
}
