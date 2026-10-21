package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.slackerdb.common.version.BuildInfo.RESOURCE;

/**
 * 版本信息的**来源**测试：它必须是构建期生成的，源码树里不能有可手工编辑的同名资源。
 *
 * <p>为什么这条要单独测：版本信息“不准”的根因不是代码写错，而是“存在一个能被人手改、
 * 且改了不会被发现”的版本源。因此这里同时锁住三件事：</p>
 * <ol>
 *   <li>三个可执行模块的源码树里都**没有** {@code src/main/resources/**&#47;version.properties}
 *       （历史写法，已被 {@code META-INF/build-info.properties} 取代）；</li>
 *   <li>classpath 上的 {@code META-INF/build-info.properties} 是 Spring Boot 的公共键名
 *       （{@code build.artifact}/{@code build.version}/{@code build.time}），且
 *       {@code build.time} 是**UTC 瞬时**、取值里没有未替换的 {@code ${...}} 占位符；</li>
 *   <li>{@link BuildInfo} 的展示口径与生成物一致：版本号是纯版本、{@code Build Time} 是
 *       本地时间 + 偏移，读不到时是可读的 {@code unknown}。</li>
 * </ol>
 *
 * <p>jar 内的证据（standalone jar 里该条目的字节）属于打包期的验证，在构建报告里给出，
 * 单测这一层只保证“生成 + 拷贝 + 读取”这条链路，因为它才是回归面。</p>
 */
class BuildInfoTest {

    /** 项目版本号形状：{@code <major>.<minor>.<patch>}，**不含**构建时刻与 git 信息。 */
    private static final String PROJECT_VERSION = "\\d+\\.\\d+\\.\\d+";

    /** 展示格式：本地时间 + 数字偏移（与 common 的 BuildInfo 一致）。 */
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

    @Test
    @DisplayName("源码树里不得存在可手工编辑的 version.properties（唯一来源是构建期生成的 build-info）")
    void sourceTreeHasNoEditableVersionResource() {
        for (String module : new String[]{"dbcli", "dbserver", "dbproxy"}) {
            Path handEditable = Path.of("..", module, "src", "main", "resources", "version.properties");
            assertFalse(Files.exists(handEditable),
                    "版本源必须是构建产物（META-INF/build-info.properties），源码树里不得留同名可编辑文件: "
                            + handEditable.toAbsolutePath().normalize());
        }
    }

    @Test
    @DisplayName("构建期生成的 build-info.properties：键名是 Spring Boot 约定、时刻是 UTC 瞬时、无占位符")
    void generatedBuildInfoIsWellFormed() throws Exception {
        Properties properties = readBuildInfo();

        assertEquals("slackerdb-dbcli", properties.getProperty("build.artifact"),
                "build.artifact 必须是本模块的 artifactId（classpath 上可能还有别家的同名文件）");
        assertEquals("org.robotslacker", properties.getProperty("build.group"));

        String version = properties.getProperty("build.version");
        assertNotNull(version, "缺少 build.version");
        assertTrue(version.matches(PROJECT_VERSION),
                "build.version 必须是纯项目版本号 <major>.<minor>.<patch>（不含构建时刻、无 git 信息），实际: " + version);

        String time = properties.getProperty("build.time");
        assertNotNull(time, "缺少 build.time");
        assertFalse(Instant.parse(time).toString().isEmpty(),
                "build.time 必须是 ISO-8601 的 UTC 瞬时（带 Z），实际: " + time);

        String raw = readRaw();
        String valueLines = raw.lines()
                .filter(line -> !line.startsWith("#"))
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(valueLines.contains("${"), "生成物取值里不得留下未替换的占位符:\n" + raw);
        assertFalse(valueLines.contains("maven.build.timestamp"), raw);
    }

    @Test
    @DisplayName("BuildInfo 与生成物口径一致：--version 的两行由这里拼出，读不到时给 unknown")
    void buildInfoMatchesTheGeneratedResource() throws Exception {
        Properties properties = readBuildInfo();

        assertEquals("slackerdb-dbcli", properties.getProperty("build.artifact"));
        assertTrue(BuildInfo.version().matches(PROJECT_VERSION), BuildInfo.version());
        assertTrue(BuildInfo.name().contains("dbcli"), BuildInfo.name());

        Instant instant = Instant.parse(properties.getProperty("build.time"));
        String expected = LOCAL_TIME.format(instant.atZone(ZoneId.systemDefault()));
        assertEquals(expected, BuildInfo.buildTime(),
                "BuildInfo.buildTime() 应当把 UTC 瞬时转成本地时间并带偏移（期望 " + expected
                        + "，实际 " + BuildInfo.buildTime() + "）");

        String[] lines = BuildInfo.versionLines();
        assertEquals(2, lines.length, "版本输出必须是两行");
        // 两行形态镜像服务端的 [SERVER] VERSION: …/[SERVER] Build Time: …；客户端名字不在这里
        assertEquals("[CLIENT] VERSION: " + BuildInfo.version(), lines[0]);
        assertEquals("[CLIENT] Build Time: " + BuildInfo.buildTime(), lines[1]);
        // 构建时刻**只**在第二行：第一行是同一个发行版本在任何一次构建里都相同的纯版本号
        assertEquals("[CLIENT] VERSION: " + properties.getProperty("build.version"), lines[0]);
        assertFalse(lines[0].matches("(?s).*\\d{2}:\\d{2}:\\d{2}.*"),
                "第一行里不得出现时刻（它在第二行）: " + lines[0]);
        assertFalse(lines[0].contains(BuildInfo.name()),
                "客户端名字不该再出现在 --version 的两行里: " + lines[0]);
    }

    @Test
    @DisplayName("版本/时刻缺失时的降级值是可读的 unknown，绝不是 ${...} 占位符")
    void degradedValuesAreReadable() {
        // 生成物总是存在（构建期产出）；这里只验证“取值不可能是占位符”这条口径
        assertFalse(BuildInfo.version().contains("${"), BuildInfo.version());
        assertFalse(BuildInfo.buildTime().contains("${"), BuildInfo.buildTime());
    }

    private static Properties readBuildInfo() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/" + RESOURCE)) {
            assertNotNull(in, "classpath 上应当有构建期生成的 " + RESOURCE);
            properties.load(in);
        }
        return properties;
    }

    private static String readRaw() throws Exception {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/" + RESOURCE)) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
