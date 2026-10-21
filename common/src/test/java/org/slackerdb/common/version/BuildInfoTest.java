package org.slackerdb.common.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link BuildInfo} 的口径测试：classpath 上有**多份** {@code META-INF/build-info.properties}
 * 是常态（Spring Boot 的 fat jar 里每个依赖都可能自带一份），因此这里锁住三条：
 *
 * <ol>
 *   <li>按 {@code build.artifact} <b>精确匹配</b>，不会被排在前面的别人的文件抢走；</li>
 *   <li>匹配不上时退化成可读的 {@code unknown}，绝不把别人的版本号或 {@code ${...}} 占位符印出来；</li>
 *   <li>{@code build.time} 是 UTC 瞬时，展示时转本地时间并带上偏移（存储与展示分开）。</li>
 * </ol>
 */
class BuildInfoTest {

    /** 与生成物一致的 UTC 瞬时（{@code spring-boot-maven-plugin:build-info} 写的就是 Instant）。 */
    private static final String BUILD_TIME = "2026-10-20T15:13:25.123Z";

    @Test
    @DisplayName("多份 build-info.properties 时按 build.artifact 精确匹配，不取第一份")
    void picksTheEntryOfTheExpectedArtifact(@TempDir Path tempDir) throws IOException {
        Path other = writeCandidate(tempDir.resolve("other"), "slackerdb-aserver", "0.1.0");
        Path mine = writeCandidate(tempDir.resolve("mine"), "slackerdb-dbserver", "0.2.1");

        try (URLClassLoader classLoader = loader(other, mine)) {
            BuildInfo info = BuildInfo.load("slackerdb-dbserver", classLoader);

            assertEquals("slackerdb-dbserver", info.artifact());
            assertEquals("0.2.1", info.version());
            assertNotNull(info.time(), "build.time 应当被解析成 Instant");
        }
    }

    @Test
    @DisplayName("匹配不上构件名时退化成 unknown，绝不印别人的版本")
    void fallsBackToUnknownWhenNoArtifactMatches(@TempDir Path tempDir) throws IOException {
        Path other = writeCandidate(tempDir.resolve("other"), "slackerdb-aserver", "9.9.9");

        try (URLClassLoader classLoader = loader(other)) {
            BuildInfo info = BuildInfo.load("slackerdb-dbserver", classLoader);

            assertEquals("unknown", info.artifact());
            assertEquals("unknown", info.version());
            assertEquals("unknown", info.buildTime());
            assertNull(info.time());
        }
    }

    @Test
    @DisplayName("classpath 上没有任何 build-info.properties 时是 unknown，不抛异常")
    void degradesQuietlyWithoutAnyResource(@TempDir Path tempDir) throws IOException {
        try (URLClassLoader classLoader = loader(tempDir.resolve("empty"))) {
            BuildInfo info = BuildInfo.load("slackerdb-dbserver", classLoader);

            assertEquals("unknown", info.version());
            assertEquals("unknown", info.buildTime());
        }
    }

    @Test
    @DisplayName("残留 ${...} 占位符一律当作没有：退回 unknown，而不是把占位符印给用户")
    void neverEchoesUnresolvedPlaceholders(@TempDir Path tempDir) throws IOException {
        Path broken = tempDir.resolve("broken");
        Files.createDirectories(broken.resolve("META-INF"));
        Files.writeString(broken.resolve(BuildInfo.RESOURCE), String.join(System.lineSeparator(),
                "build.artifact=slackerdb-dbserver",
                "build.version=${project.version}",
                "build.time=${maven.build.timestamp}"), StandardCharsets.UTF_8);

        try (URLClassLoader classLoader = loader(broken)) {
            BuildInfo info = BuildInfo.load("slackerdb-dbserver", classLoader);

            assertEquals("slackerdb-dbserver", info.artifact(), "构件名本身不是占位符，应当保留");
            assertEquals("unknown", info.version());
            assertEquals("unknown", info.buildTime());
        }
    }

    @Test
    @DisplayName("build.time 存 UTC、展示本地时间并带偏移")
    void buildTimeIsStoredAsInstantAndShownLocallyWithOffset(@TempDir Path tempDir) throws IOException {
        Path mine = writeCandidate(tempDir.resolve("mine"), "slackerdb-dbserver", "0.2.1");
        try (URLClassLoader classLoader = loader(mine)) {
            BuildInfo info = BuildInfo.load("slackerdb-dbserver", classLoader);

            assertEquals(Instant.parse(BUILD_TIME), info.time());
            String expected = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
                    .format(Instant.parse(BUILD_TIME).atZone(ZoneId.systemDefault()));
            assertEquals(expected, info.buildTime(),
                    "展示串必须是本地时间 + 偏移，实际: " + info.buildTime());
        }
    }

    @Test
    @DisplayName("additionalProperties 声明的 build.product 优先作为展示名")
    void productWinsOverArtifactName(@TempDir Path tempDir) throws IOException {
        Path mine = tempDir.resolve("mine");
        Files.createDirectories(mine.resolve("META-INF"));
        Files.writeString(mine.resolve(BuildInfo.RESOURCE), String.join(System.lineSeparator(),
                "build.artifact=slackerdb-dbcli",
                "build.name=slackerdb-dbcli",
                "build.product=dbcli (SlackerDB command line client)",
                "build.version=0.2.1",
                "build.time=" + BUILD_TIME), StandardCharsets.UTF_8);

        try (URLClassLoader classLoader = loader(mine)) {
            BuildInfo info = BuildInfo.load("slackerdb-dbcli", classLoader);

            assertEquals("dbcli (SlackerDB command line client)", info.name());
        }
    }

    /** 造一份候选：{@code <dir>/META-INF/build-info.properties}。 */
    private static Path writeCandidate(Path dir, String artifact, String version) throws IOException {
        Files.createDirectories(dir.resolve("META-INF"));
        Files.writeString(dir.resolve(BuildInfo.RESOURCE), String.join(System.lineSeparator(),
                "build.artifact=" + artifact,
                "build.group=org.robotslacker",
                "build.name=" + artifact,
                "build.version=" + version,
                "build.time=" + BUILD_TIME), StandardCharsets.UTF_8);
        return dir;
    }

    /** 按给定顺序拼一个 classloader：顺序即 {@code getResources} 的返回顺序。 */
    private static URLClassLoader loader(Path... dirs) throws IOException {
        URL[] urls = new URL[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            Files.createDirectories(dirs[i]);
            urls[i] = dirs[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }
}
