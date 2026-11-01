package org.slackerdb.dbserver.test;

import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code memory_limit} 的百分比写法必须真正生效。
 *
 * <p>DuckDB 支持 {@code SET memory_limit = '60%'}（系统总内存的百分比）后，服务端不再需要
 * "自动工作负载阈值"来折算默认内存；这里锁定三件事：</p>
 * <ol>
 *   <li>默认值就是 {@code 80%}（与移除阈值参数前的 0.8 档位一致）；</li>
 *   <li>参数文件里的 {@code 60%} 原样进入 DuckDB，并由 DuckDB 解析成约 60% 系统总内存；</li>
 *   <li>旧的 {@code auto_workload_threshold} 键已彻底移除，再出现时按未知参数拒绝。</li>
 * </ol>
 */
class MemoryLimitConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultMemoryLimitIsEightyPercent() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        assertEquals("80%", configuration.getMemory_limit(),
                "默认内存上限应直接使用 DuckDB 支持的百分比写法");
        assertTrue(configuration.getThreads() > 0, "默认线程数仍应基于 80% 内存档位计算");
    }

    @Test
    void percentageMemoryLimitFromConfigFileTakesEffect() throws Exception {
        Path configFile = tempDir.resolve("dbserver.conf");
        Files.writeString(configFile, "memory_limit=60%\n");

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.loadConfigurationFile(configFile.toString());
        assertEquals("60%", configuration.getMemory_limit(), "参数文件里的百分比必须原样保留");

        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setSqlHistory("OFF");
        configuration.setDataServiceHistory("OFF");

        DBInstance instance = new DBInstance(configuration);
        instance.start();
        try {
            long resolved60 = readMemoryLimit(instance.backendSysConnection);
            long total = totalSystemMemory();
            double ratio = (double) resolved60 / total;
            assertTrue(ratio > 0.5 && ratio < 0.7,
                    "60% 应解析为系统总内存的约 60%，实际 " + String.format("%.1f%%", ratio * 100)
                            + "（" + resolved60 + " / " + total + " 字节）");

            // 改成 30%：上限应约为一半，证明百分比是按系统总内存实时解析的
            try (Statement statement = instance.backendSysConnection.createStatement()) {
                statement.execute("SET memory_limit='30%'");
            }
            long resolved30 = readMemoryLimit(instance.backendSysConnection);
            assertEquals(2.0, (double) resolved60 / resolved30, 0.1,
                    "60% 与 30% 的内存上限应约为 2:1");
        } finally {
            instance.stop();
        }
    }

    @Test
    void legacyThresholdKeyIsRejected() throws Exception {
        Path configFile = tempDir.resolve("legacy.conf");
        Files.writeString(configFile, "auto_workload_threshold=0.8\n");

        ServerConfiguration configuration = new ServerConfiguration();
        assertThrows(ServerException.class, () -> configuration.loadConfigurationFile(configFile.toString()),
                "auto_workload_threshold 已移除，参数文件中不应再被接受");
    }

    private static long totalSystemMemory() {
        return ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
    }

    private static long readMemoryLimit(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT current_setting('memory_limit')")) {
            assertTrue(resultSet.next(), "current_setting('memory_limit') 应返回一行");
            return parseSize(resultSet.getString(1));
        }
    }

    /** 解析 DuckDB 的大小显示，例如 {@code 18.7 GiB}。 */
    private static long parseSize(String text) {
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([KMGTPE]?i?B|B)?", Pattern.CASE_INSENSITIVE)
                .matcher(text == null ? "" : text.trim());
        assertTrue(matcher.find(), "无法解析 DuckDB 的内存大小: " + text);

        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2) == null ? "B" : matcher.group(2).toUpperCase();
        long multiplier = switch (unit) {
            case "B" -> 1L;
            case "KIB", "KB" -> 1L << 10;
            case "MIB", "MB" -> 1L << 20;
            case "GIB", "GB" -> 1L << 30;
            case "TIB", "TB" -> 1L << 40;
            case "PIB", "PB" -> 1L << 50;
            default -> throw new AssertionError("未知的内存单位: " + unit);
        };
        return Math.round(value * multiplier);
    }
}
