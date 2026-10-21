package org.slackerdb.dbcli.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作业定义工厂的参数映射测试：{@code _JOB CREATE} 的参数怎么变成"脚本 + 变量 + 运行选项"。
 *
 * <p>这里不启动任何作业线程，只锁"映射规则"；作业行为由 {@link JobManagerTest} 与
 * {@code engine.JobBarrierTest} 覆盖。</p>
 */
class DbcliJobSpecFactoryTest {

    private final JobSpecFactory factory = new DbcliJobSpecFactory();

    @Test
    @DisplayName("script → 脚本；logon/profile/logfile/reference → 运行选项；其余 k=v → 变量")
    void mapsParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("script", "child.sql");
        parameters.put("logon", "main@127.0.0.1:3175/db1");
        parameters.put("logfile", "out.out");
        parameters.put("TABA", "TAB1");

        JobSpec spec = factory.specFor("J1", parameters);

        assertEquals("child.sql", spec.script(), "script 就是作业要跑的脚本");
        assertEquals("main@127.0.0.1:3175/db1", spec.options().get("logon"));
        assertEquals("out.out", spec.options().get("logfile"));
        assertEquals(Map.of("TABA", "TAB1"), spec.variables(),
                "其余参数变成子脚本可见的变量");
        assertEquals(2, spec.options().size(), "只透传声明过的运行选项: " + spec.options());
    }

    @Test
    @DisplayName("运行选项不会同时变成变量（与旧的命令行模型一致）")
    void runOptionsAreNotVariables() {
        JobSpec spec = factory.specFor("J2", new LinkedHashMap<>(Map.of(
                "script", "child.sql",
                "profile", "setup.sql",
                "reference", "golden.out")));
        assertTrue(spec.variables().isEmpty(), "不该有变量: " + spec.variables());
        assertEquals("setup.sql", spec.options().get("profile"));
        assertEquals("golden.out", spec.options().get("reference"));
    }

    @Test
    @DisplayName("参数顺序被保留（给人看的可预期顺序）")
    void keepsParameterOrder() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("script", "child.sql");
        parameters.put("B", "2");
        parameters.put("A", "1");
        parameters.put("C", "3");
        assertEquals(java.util.List.of("B", "A", "C"),
                java.util.List.copyOf(factory.specFor("J3", parameters).variables().keySet()));
    }

    @Test
    @DisplayName("缺少 script 参数 → 可读错误（带示例）")
    void requiresScript() {
        JobException error = assertThrows(JobException.class,
                () -> factory.specFor("J4", Map.of("logon", "x")));
        assertTrue(error.getMessage().contains("needs a 'script' parameter"), error.getMessage());
        assertTrue(error.getMessage().contains("_JOB CREATE J4 script=child.sql"),
                "错误信息里应当给出可直接照抄的写法，实际: " + error.getMessage());
    }

    @Test
    @DisplayName("换工作目录只动基准目录，脚本/变量/选项不变")
    void withWorkingDirectoryKeepsTheRest() {
        JobSpec spec = factory.specFor("J5", new LinkedHashMap<>(Map.of("script", "child.sql", "K", "V")));
        Path directory = Path.of("some", "where");
        JobSpec moved = spec.withWorkingDirectory(directory);
        assertEquals(directory, moved.workingDirectory());
        assertEquals("child.sql", moved.script());
        assertEquals(Map.of("K", "V"), moved.variables());
    }

    @Test
    @DisplayName("参数名大小写不敏感：SCRIPT= / MODE=process / TIMEOUT=30 都生效")
    void parameterNamesAreCaseInsensitive() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("SCRIPT", "child.sql");
        parameters.put("MODE", "Process");
        parameters.put("TIMEOUT", "30");
        parameters.put("LOGON", "main@127.0.0.1:3175/db1");
        parameters.put("PROFILE", "setup.sql");

        JobSpec spec = factory.specFor("J6", parameters);

        assertEquals("child.sql", spec.script(), "SCRIPT= 就是 script=");
        assertEquals(JobMode.PROCESS, spec.mode(), "MODE=Process 就是 mode=process（取值也不区分大小写）");
        assertEquals(30.0, spec.timeoutSeconds(), "TIMEOUT=30 就是 timeout=30");
        assertEquals("main@127.0.0.1:3175/db1", spec.options().get("logon"));
        assertEquals("setup.sql", spec.options().get("profile"));
        // 规范名进 options：子进程命令行（SelfJobProcessLauncher）只认这些规范名
        assertEquals(java.util.List.of("mode", "timeout", "logon", "profile"),
                java.util.List.copyOf(spec.options().keySet()), spec.options().toString());
    }

    @Test
    @DisplayName("参数名不再能当同名变量用；非编排参数的大小写原样保留")
    void orchestrationNamesAreConsumedButOtherNamesKeepTheirCase() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("script", "child.sql");
        parameters.put("MODE", "process");
        parameters.put("Timeout", "5");
        parameters.put("Mode", "thread");
        parameters.put("mode", "process");
        parameters.put("TABA", "TAB1");
        parameters.put("taba", "TAB2");
        parameters.put("K", "V");

        JobSpec spec = factory.specFor("J7", parameters);

        assertEquals(JobMode.PROCESS, spec.mode(), "同名参数的第一次写法胜出（MODE=process）");
        assertEquals(5.0, spec.timeoutSeconds());
        assertEquals(Map.of("TABA", "TAB1", "taba", "TAB2", "K", "V"), spec.variables(),
                "其余 k=v 仍是变量，且**变量名区分大小写**（TABA 与 taba 是两个变量）");
        assertFalse(spec.variables().containsKey("MODE"), "MODE 被编排参数吃掉，不再是变量");
        assertFalse(spec.variables().containsKey("Mode"));
        assertFalse(spec.variables().containsKey("Timeout"));
    }
}
