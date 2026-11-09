package org.slackerdb.dbserver.scheduler;

import io.javalin.Javalin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度能力的"在场判定 / 配置校验"契约。
 *
 * <p>这里锁的是三条容易写错的规则：</p>
 * <ol>
 *   <li><b>缺配置 = 没有这个能力</b>（不抛异常，返回原因）；</li>
 *   <li><b>配置错 = 拒绝启动</b>（{@link SchedulerConfigException}，信息里要能直接看到键名与值）；</li>
 *   <li><b>HOP 不是启用前提</b>（不配照常启用，只是 HOP 任务不可执行）。</li>
 * </ol>
 */
class SchedulerActivationTest {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(SchedulerActivationTest.class);

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- 在场判定

    @Test
    @DisplayName("只读实例：没有这个能力，且优先于配置判断")
    void readOnlyInstanceHasNoCapability() {
        Map<String, String> props = new HashMap<>();
        props.put("plugin-scheduler.home", tempDir.toString());
        props.put("plugin-scheduler.projectHome", tempDir.toString());

        String reason = SchedulerModule.disabledReason(props, true);
        assertNotNull(reason, "read-only instances must not expose the scheduler capability");
        assertTrue(reason.contains("read-only"), reason);
    }

    @Test
    @DisplayName("没配 home / projectHome：没有这个能力（不报错）")
    void missingHomeOrProjectHomeDisablesCapability() {
        assertNotNull(SchedulerModule.disabledReason(Map.of(), false));
        assertTrue(SchedulerModule.disabledReason(Map.of(), false).contains("home"));

        Map<String, String> onlyHome = new HashMap<>();
        onlyHome.put("plugin-scheduler.home", tempDir.toString());
        String reason = SchedulerModule.disabledReason(onlyHome, false);
        assertNotNull(reason);
        assertTrue(reason.contains("projectHome"), reason);
    }

    @Test
    @DisplayName("home 与 projectHome 都在：启用（hop 不影响判定）")
    void configuredHomeAndProjectHomeActivates() {
        Map<String, String> props = new HashMap<>();
        props.put("plugin-scheduler.home", tempDir.toString());
        props.put("plugin-scheduler.projectHome", tempDir.toString());

        assertNull(SchedulerModule.disabledReason(props, false));
    }

    // ---------------------------------------------------------------- 配置校验

    @Test
    @DisplayName("home 指向不存在的目录：拒绝启动，异常里带键名与值")
    void nonExistingHomeIsRejected() {
        Map<String, String> props = new HashMap<>();
        props.put("plugin-scheduler.home", tempDir.resolve("does-not-exist").toString());
        props.put("plugin-scheduler.projectHome", tempDir.toString());

        SchedulerConfigException e = assertThrows(SchedulerConfigException.class, () -> newModule(props));
        assertEquals("plugin-scheduler.home", e.getKey());
        assertTrue(e.getMessage().contains("does-not-exist"), e.getMessage());
    }

    @Test
    @DisplayName("hop.runScript 为空：照常启用，只是 HOP 未就绪")
    void blankHopRunScriptStillActivates() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put("plugin-scheduler.home", tempDir.toString());
        props.put("plugin-scheduler.projectHome", tempDir.toString());

        SchedulerModule module = assertDoesNotThrow(() -> newModule(props));
        assertFalse(module.isHopReady(), "without hop.runScript the HOP task type is not available");
    }

    @Test
    @DisplayName("hop.runScript 有值但文件不存在：拒绝启动")
    void nonExistingHopRunScriptIsRejected() {
        Map<String, String> props = new HashMap<>();
        props.put("plugin-scheduler.home", tempDir.toString());
        props.put("plugin-scheduler.projectHome", tempDir.toString());
        props.put("plugin-scheduler.hop.runScript", tempDir.resolve("hop-run.cmd").toString());

        SchedulerConfigException e = assertThrows(SchedulerConfigException.class, () -> newModule(props));
        assertEquals("plugin-scheduler.hop.runScript", e.getKey());
    }

    @Test
    @DisplayName("hop.runScript 指向真实文件：启用且 HOP 就绪")
    void existingHopRunScriptIsReady() throws Exception {
        Path hopRun = tempDir.resolve("hop-run.cmd");
        Files.writeString(hopRun, "@echo off\n");

        Map<String, String> props = new HashMap<>();
        props.put("plugin-scheduler.home", tempDir.toString());
        props.put("plugin-scheduler.projectHome", tempDir.toString());
        props.put("plugin-scheduler.hop.runScript", hopRun.toString());

        SchedulerModule module = newModule(props);
        assertTrue(module.isHopReady());
    }

    @Test
    @DisplayName("retention 天数不是非负整数：拒绝启动")
    void invalidRetentionIsRejected() {
        Map<String, String> props = new HashMap<>();
        props.put("plugin-scheduler.home", tempDir.toString());
        props.put("plugin-scheduler.projectHome", tempDir.toString());
        props.put("plugin-scheduler.retention.historyDays", "-1");

        SchedulerConfigException e = assertThrows(SchedulerConfigException.class, () -> newModule(props));
        assertEquals("plugin-scheduler.retention.historyDays", e.getKey());
    }

    /** 构造一个只做校验、不启动的模块实例（Javalin 未 start，不占端口）。 */
    private SchedulerModule newModule(Map<String, String> props) throws SchedulerConfigException {
        return new SchedulerModule(Javalin.create(), LOGGER, null, props);
    }
}
