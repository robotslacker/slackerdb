package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbproxy.BuildInfo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代理构建信息的**口径**测试：版本号与构建时刻必须来自构建期生成的
 * {@code META-INF/build-info.properties}（{@code spring-boot-maven-plugin} 的 {@code build-info} 目标）。
 *
 * <p>与 dbserver 的同类测试一一对应：三个模块共用同一套生成与读取口径，
 * 任何一处退回“手写版本 / 手改资源 / 漏占位符”都会在这里失败。</p>
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
