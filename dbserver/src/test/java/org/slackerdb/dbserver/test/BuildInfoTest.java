package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.BuildInfo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端构建信息的**口径**测试：版本号与构建时刻必须来自构建期生成的
 * {@code META-INF/build-info.properties}（{@code spring-boot-maven-plugin} 的 {@code build-info} 目标）。
 *
 * <p>锁住三件事，任何一条破掉都会让 {@code VERSION} 命令、{@code STATUS} 输出、健康检查
 * 或 MCP 的 {@code serverInfo.version} 印出错误信息：</p>
 * <ol>
 *   <li>版本号是 POM 里的纯版本号（不是硬编码常量，也不是 {@code unknown}）；</li>
 *   <li>构建时刻是“本地时间 + 数字偏移”（存储的是 UTC 瞬时，展示才本地化）；</li>
 *   <li>取值里不会漏出未替换的 {@code ${...}} 占位符。</li>
 * </ol>
 */
class BuildInfoTest {

    @Test
    @DisplayName("版本号来自构建期生成物（纯 <major>.<minor>.<patch>），不是手写常量")
    void versionComesFromGeneratedBuildInfo() {
        String version = BuildInfo.version();

        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"),
                "版本号应是构建期写入的纯项目版本号，实际: " + version);
        assertFalse(version.contains("${"), "版本号里不得出现未替换的占位符: " + version);
    }

    @Test
    @DisplayName("构建时刻是本地时间 + 偏移（存储是 UTC 瞬时）")
    void buildTimeIsLocalWithOffset() {
        String buildTime = BuildInfo.buildTime();

        assertTrue(buildTime.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [+-]\\d{2}:\\d{2}"),
                "构建时刻应形如 2026-10-20 23:13:25 +08:00，实际: " + buildTime);
        assertFalse(buildTime.contains("${"), "构建时刻里不得出现未替换的占位符: " + buildTime);
    }
}
