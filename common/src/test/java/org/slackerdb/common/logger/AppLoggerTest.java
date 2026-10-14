package org.slackerdb.common.logger;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AppLogger} 落盘行为测试。
 *
 * <p>三个重点：</p>
 * <ol>
 *   <li><b>默认行为不变</b>：三参重载仍是"单文件追加、不滚动"，引入本能力不改变现网日志形态；</li>
 *   <li><b>SIZE 模式有硬上界</b>：文件数受窗口约束，磁盘占用可预测；</li>
 *   <li><b>SIZE_AND_TIME 只按时间段保留</b>：同一时间段内的文件数不设上界（语义与 SIZE 不同）。</li>
 * </ol>
 */
class AppLoggerTest {

    private final List<Logger> createdLoggers = new ArrayList<>();

    /** Windows 上文件句柄会阻止临时目录删除，测试结束必须摘掉 appender。 */
    @AfterEach
    void detachAllAppenders() {
        for (Logger logger : createdLoggers) {
            logger.detachAndStopAllAppenders();
        }
        createdLoggers.clear();
    }

    private Logger newLogger(String name, String level, String target) {
        Logger logger = AppLogger.createLogger(name, level, target);
        createdLoggers.add(logger);
        return logger;
    }

    private Logger newLogger(String name, String level, String target, LogOptions options) {
        Logger logger = AppLogger.createLogger(name, level, target, options);
        createdLoggers.add(logger);
        return logger;
    }

    /**
     * 统计已滚动的历史文件。
     *
     * <p>只统计 {@code .gz}：logback 压缩历史文件时会先写 {@code *.tmp}，
     * 若压缩任务尚未完成（异步压缩）会短暂留下临时文件，那不是"保留过多历史"。</p>
     */
    private static List<Path> rotatedFilesOf(Path logFile) throws IOException {
        Path dir = logFile.getParent();
        String name = logFile.getFileName().toString();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                        String fileName = p.getFileName().toString();
                        return fileName.startsWith(name + ".") && fileName.endsWith(".gz");
                    })
                    .collect(Collectors.toList());
        }
    }

    private static void logMany(Logger logger, int lines, String marker) {
        for (int i = 0; i < lines; i++) {
            logger.info("{} line {} padding-padding-padding-padding-padding-padding-padding", marker, i);
        }
    }

    @Test
    void defaultOptionsKeepSingleFileWithoutRotation(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("default.log");
        Logger logger = newLogger("test-default", "INFO", logFile.toString());
        assertEquals("INFO", logger.getLevel().toString(), "默认级别应为 INFO");

        logMany(logger, 500, "DEFAULT");
        assertTrue(rotatedFilesOf(logFile).isEmpty(), "默认（Mode.NONE）不应产生滚动文件");
        assertTrue(Files.exists(logFile), "默认应生成单一日志文件");
        assertTrue(Files.readString(logFile).contains("DEFAULT line 0"), "日志内容应写入文件");
    }

    @Test
    void sizeBoundedModeKeepsHardUpperBound(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("bounded.log");
        Logger logger = newLogger("test-bounded", "INFO", logFile.toString(),
                LogOptions.sizeBoundedDefaults()
                        .maxFileSize("4KB")
                        .maxHistory(3));

        // 每行约 110 字节，3000 行 ≈ 330KB，足以滚动几十次
        logMany(logger, 3000, "BOUNDED");

        List<Path> rotated = rotatedFilesOf(logFile);
        assertFalse(rotated.isEmpty(), "应产生滚动文件");
        assertTrue(rotated.size() <= 3, "SIZE 模式历史文件数不得超过 maxHistory，实际=" + rotated.size());
        assertTrue(rotated.stream().allMatch(p -> p.getFileName().toString().endsWith(".gz")),
                "默认应压缩历史文件：" + rotated);
        assertTrue(Files.exists(logFile), "活动日志文件必须仍然存在（/logfile 等端点依赖该路径）");
        assertTrue(Files.readString(logFile).contains("BOUNDED line 2999"), "活动文件应包含最新日志");
    }

    @Test
    void sizeBoundedModeClampsOversizedWindow(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("clamped.log");
        // logback 的固定窗口上限是 20，超出必须被收敛而不是让 appender 启动失败
        Logger logger = newLogger("test-clamped", "INFO", logFile.toString(),
                LogOptions.sizeBoundedDefaults().maxFileSize("1KB").maxHistory(200));

        logMany(logger, 2000, "CLAMPED");
        List<Path> rotated = rotatedFilesOf(logFile);
        assertFalse(rotated.isEmpty(), "收敛窗口后仍应正常滚动");
        assertTrue(rotated.size() <= 20, "窗口应被收敛到 logback 上限 20，实际=" + rotated.size());
    }

    /**
     * 固定窗口 + 大小触发：文件数有硬上界，因此磁盘占用可预测。
     *
     * <p>这条用例同时锁住一个 logback 陷阱：{@code SizeBasedTriggeringPolicy} 内置
     * {@code SimpleInvocationGate}，默认 60 秒才检查一次文件大小，密集写入时根本不滚动。
     * 因此 {@link LogOptions#checkIncrementMillis(long)} 必须默认 0（每条日志都检查）。</p>
     */
    @Test
    void sizeAndTimeModeRotatesButOnlyRetainsPeriods(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("daily.log");
        Logger logger = newLogger("test-daily", "INFO", logFile.toString(),
                LogOptions.dailyDefaults().maxFileSize("4KB").maxHistory(2).totalSizeCap("64KB"));

        logMany(logger, 3000, "DAILY");

        List<Path> rotated = rotatedFilesOf(logFile);
        assertFalse(rotated.isEmpty(), "应产生滚动文件");
        assertTrue(Files.exists(logFile), "活动日志文件必须仍然存在");
        // 刻意不断言文件个数：SIZE_AND_TIME 的 maxHistory 是"时间段个数"，
        // 同一时间段内高频滚动不会被 totalSizeCap 回收——要硬上界请用 Mode.SIZE。
    }

    /** 把检查间隔放大到 60 秒（logback 默认值）时，密集写入不会触发滚动——反证上面的修复是必要的。 */
    @Test
    void largeCheckIncrementDefersSizeChecks(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("gated.log");
        Logger logger = newLogger("test-gated", "INFO", logFile.toString(),
                LogOptions.sizeBoundedDefaults()
                        .maxFileSize("1KB")
                        .maxHistory(3)
                        .checkIncrementMillis(60_000));

        logMany(logger, 2000, "GATED");
        assertTrue(rotatedFilesOf(logFile).isEmpty(),
                "检查间隔 60s 时，一次密集写入不应触发滚动（这正是必须显式设置 checkIncrement 的原因）");
        assertTrue(Files.size(logFile) > 100_000, "活动文件会因此远超 maxFileSize");
    }

    @Test
    void rotationCanBeDisabledExplicitly(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("noroll.log");
        Logger logger = newLogger("test-noroll", "INFO", logFile.toString(),
                LogOptions.sizeBoundedDefaults().rotation(false).maxFileSize("1KB"));

        logMany(logger, 500, "NOROLL");
        assertTrue(rotatedFilesOf(logFile).isEmpty(), "关闭滚动后不应产生历史文件");
        assertTrue(Files.size(logFile) > 1024, "关闭滚动后文件应继续增长");
    }

    @Test
    void repeatedCreateLoggerDoesNotDuplicateAppenders(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("dup.log");
        Logger first = newLogger("test-dup", "INFO", logFile.toString());
        Logger second = newLogger("test-dup", "INFO", logFile.toString());
        assertEquals(first, second, "同名 logger 应返回同一实例");

        second.info("ONLY-ONCE");
        String content = Files.readString(logFile);
        int occurrences = content.split("ONLY-ONCE", -1).length - 1;
        assertEquals(1, occurrences, "重复 createLogger 不应叠加 appender（否则日志重复），实际=" + occurrences);
    }

    @Test
    void consoleOnlyTargetCreatesNoFile(@TempDir Path tempDir) throws Exception {
        Logger logger = newLogger("test-console", "INFO", "CONSOLE");
        logger.info("console only");
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(0, files.count(), "CONSOLE 模式不应创建任何文件");
        }
    }

    @Test
    void nullTargetsFallBackToConsoleAndInfo(@TempDir Path tempDir) {
        Logger logger = newLogger("test-null", null, null);
        assertEquals("INFO", logger.getLevel().toString());
        assertTrue(logger.iteratorForAppenders().hasNext(), "应至少挂一个 appender");
    }

    @Test
    void invalidLevelFallsBackToInfoInsteadOfDebugOrThrow() {
        Logger logger = newLogger("test-badlevel", "NOT_A_LEVEL", "CONSOLE");
        assertEquals("INFO", logger.getLevel().toString(),
                "非法级别应回退 INFO（历史行为会被 logback 静默变成 DEBUG）");
    }

    @Test
    void customPatternIsApplied(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("pattern.log");
        Logger logger = newLogger("test-pattern", "INFO", logFile.toString(),
                LogOptions.defaults().pattern("%msg%n"));
        logger.info("PLAIN-MESSAGE");
        assertEquals("PLAIN-MESSAGE", Files.readString(logFile).trim(), "自定义 pattern 应生效");
    }

    @Test
    void multipleFileTargetsAreAllWritten(@TempDir Path tempDir) throws Exception {
        Path first = tempDir.resolve("multi-a.log");
        Path second = tempDir.resolve("multi-b.log");
        Logger logger = newLogger("test-multi", "INFO", "CONSOLE," + first + "," + second);

        logger.info("MULTI-TARGET");
        assertTrue(Files.readString(first).contains("MULTI-TARGET"), "第一个文件应写入");
        assertTrue(Files.readString(second).contains("MULTI-TARGET"), "第二个文件应写入");
    }
}
