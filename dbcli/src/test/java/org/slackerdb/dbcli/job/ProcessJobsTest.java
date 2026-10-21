package org.slackerdb.dbcli.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbcli.engine.ScriptJobExecutor;
import org.slackerdb.dbcli.rendezvous.SqliteRendezvous;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mode=process} 的真实生命周期测试：**真的起子进程**（跑的是当前 dbcli 主程序）。
 *
 * <p>覆盖 create/start/wait/show/abort/shutdown/硬超时/收尾，并逐条断言**没有残留进程**。
 * 线程模式的行为与文案由 {@link JobManagerTest} 与 {@code engine.JobBarrierTest} 锁住，
 * 这里只关心"作业真的跑在另一个进程里"这件事带来的差异。</p>
 *
 * <h2>为什么工作目录在 {@code target/} 而不是 {@code @TempDir}</h2>
 * <p>本类会**强杀**子进程（abort / 硬超时）。Windows 上被杀的进程可能短暂攥着
 * {@code <作业名>.out/.err} 的句柄，JUnit 的 {@code @TempDir} 清理因此偶发失败
 * （报 {@code Failed to close extension context}，与被测行为无关，纯粹是测试设施的问题）。
 * 与 {@code CliRealProcessTest} 同一条口径：这类目录放在构建目录下，由 {@code mvn clean} 回收。</p>
 */
class ProcessJobsTest {

    /** 本类的固定工作目录（`mvn clean` 负责最终清理）。 */
    private static final Path WORK_DIR = Path.of("target", "dbcli-process-jobs-test");

    private Path tempDir;

    private JobManager manager;

    @BeforeEach
    void setUp() throws IOException {
        // 每次从干净目录开始：旧输出文件会污染断言。
        // 删不掉的文件（上一轮被强杀的子进程短暂攥着）不影响本轮的断言——每个用例用不同的作业名，
        // 所以这里 best-effort 即可，不能让清理本身变成测试失败的原因。
        Files.createDirectories(WORK_DIR);
        try (Stream<Path> entries = Files.list(WORK_DIR)) {
            for (Path entry : entries.toList()) {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException stillHeld) {
                    // 忽略：详见类注释（被杀的进程与文件句柄）
                }
            }
        }
        tempDir = WORK_DIR;
        // 生产形态：默认线程执行体 + 进程模式子进程执行体
        manager = new JobManager(new DbcliJobSpecFactory(),
                new ModeJobExecutor(new ScriptJobExecutor(), new ProcessJobExecutor()),
                tempDir, tempDir);
        manager.start();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    // ------------------------------------------------------------------ 测试

    @Test
    @DisplayName("进程模式：create/start/wait → DONE，输出落在 <作业名>.out，SHOW 的 thread 列是 pid")
    void processJobRunsAndReportsDone() throws Exception {
        writeScript("ok.sql", "_echo t", "hello-from-child", "echo off");
        manager.create("P1", params("script", "ok.sql", "mode", "process"));

        Job job = manager.start("P1");
        JobManager.WaitResult result = manager.waitFor("P1", 60_000L);
        assertEquals(JobState.DONE, result.job().state(),
                "进程模式作业应当正常跑完，stderr: " + readIfExists("P1.err"));
        assertEquals(0, result.job().exitCode());
        assertFalse(result.timedOut());

        String stdout = readIfExists("P1.out");
        assertTrue(stdout.contains("hello-from-child"),
                "子进程的输出应当落在 <作业名>.out，实际: [" + stdout + "]");
        assertFalse(readIfExists("P1.err").contains("Exception"),
                "不该有异常: " + readIfExists("P1.err"));

        // 线程模式没有 pid；进程模式必须报 pid，而且它已经不在了
        assertTrue(job.processId() > 0L, "进程模式应当登记子进程 pid");
        assertEquals("pid " + job.processId(), job.runtimeIdentity(), "SHOW 的 thread 列应当是 pid");
        assertTrue(job.describe().get(2).startsWith("pid "), "行摘要: " + job.describe());
        assertFalse(isAlive(job.processId()), "作业结束后子进程必须已经消失");
    }

    @Test
    @DisplayName("进程模式：子进程退出码非 0 → FAILED，退出码可读回")
    void nonZeroExitCodeIsFailed() throws Exception {
        writeScript("bad.sql", "_exit 7");
        manager.create("P2", params("script", "bad.sql", "mode", "process"));
        manager.start("P2");
        JobManager.WaitResult result = manager.waitFor("P2", 60_000L);
        assertEquals(JobState.FAILED, result.job().state());
        assertEquals(7, result.job().exitCode());
    }

    @Test
    @DisplayName("进程模式：ABORT 强杀整棵进程树，作业尽快变 ABORTED，父脚本照常往下跑")
    void abortKillsTheProcess() throws Exception {
        writeScript("sleepy.sql", "_sleep 60");
        manager.create("P3", params("script", "sleepy.sql", "mode", "process"));
        Job job = manager.start("P3");
        long pid = awaitProcessId(job);

        long startedAt = System.nanoTime();
        Job aborted = manager.abort("P3");
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals(JobState.ABORTED, aborted.state());
        assertTrue(elapsedMillis < 15_000L, "ABORT 必须让 60 秒的作业尽快结束，实际 " + elapsedMillis + "ms");
        assertFalse(isAlive(pid), "ABORT 之后子进程必须已经消失");
        assertEquals(JobState.ABORTED, manager.show("P3").state());
    }

    @Test
    @DisplayName("进程模式：SHUTDOWN 先请求再宽限后强杀 → SHUTDOWN，没有残留进程")
    void shutdownStopsTheProcess() throws Exception {
        writeScript("sleepy2.sql", "_sleep 60");
        manager.create("P4", params("script", "sleepy2.sql", "mode", "process"));
        Job job = manager.start("P4");
        long pid = awaitProcessId(job);

        Job stopped = manager.shutdown("P4");

        assertEquals(JobState.SHUTDOWN, stopped.state());
        assertFalse(isAlive(pid), "SHUTDOWN 之后子进程必须已经消失");
    }

    @Test
    @DisplayName("进程模式：timeout=<秒> 到点强杀 → TIMEOUT，且作业没有跑满它的睡眠")
    void hardTimeoutKillsTheProcess() throws Exception {
        writeScript("forever.sql", "_sleep 60");
        manager.create("P5", params("script", "forever.sql", "mode", "process", "timeout", "1"));
        Job job = manager.start("P5");
        long pid = awaitProcessId(job);

        long startedAt = System.nanoTime();
        JobManager.WaitResult result = manager.waitFor("P5", 60_000L);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals(JobState.TIMEOUT, result.job().state(),
                "到点被停掉的作业终态是 TIMEOUT，实际 " + result.job().state());
        assertTrue(elapsedMillis < 30_000L, "硬超时必须真的把它停掉，实际等了 " + elapsedMillis + "ms");
        assertFalse(isAlive(pid), "硬超时之后子进程必须已经消失");
    }

    @Test
    @DisplayName("进程模式：管理器 close() 收掉仍在跑的作业，并清理自己建的同步库")
    void closeStopsTheProcessAndCleansTheSyncDatabase() throws Exception {
        writeScript("sleepy3.sql", "_sleep 60");
        manager.create("P6", params("script", "sleepy3.sql", "mode", "process"));
        Job job = manager.start("P6");
        long pid = awaitProcessId(job);

        Path syncDatabase = manager.syncDatabase();
        assertNotNull(syncDatabase, "出现 mode=process 作业之后应当有同步库");
        assertTrue(Files.isRegularFile(syncDatabase), "同步库应当已经建出来: " + syncDatabase);

        manager.close();

        assertEquals(JobState.SHUTDOWN, job.state());
        assertFalse(isAlive(pid), "close() 之后不得留下子进程");
        assertFalse(Files.exists(syncDatabase), "建库者收尾时应当清理同步库");
    }

    @Test
    @DisplayName("线程模式不受影响：同一管理器里 thread 作业仍然显示线程名，且不建同步库")
    void threadModeIsUnchanged() throws Exception {
        writeScript("thread.sql", "_echo t", "from-a-thread", "echo off");
        manager.create("T1", params("script", "thread.sql"));
        manager.start("T1");
        JobManager.WaitResult result = manager.waitFor("T1", 30_000L);

        assertEquals(JobState.DONE, result.job().state());
        assertEquals("dbcli-job-T1", result.job().runtimeIdentity(), "线程模式仍显示线程名");
        assertTrue(manager.syncDatabase() == null, "没有进程模式作业时不该建同步库");
        assertFalse(Files.exists(tempDir.resolve(SqliteRendezvous.SYNC_DB_FILE_NAME)),
                "工作目录里不该出现同步库");
    }

    @Test
    @DisplayName("编排参数校验：mode 只认 thread/process，timeout 必须是正数")
    void orchestrationParametersAreValidated() {
        JobException badMode = assertThrows(JobException.class,
                () -> manager.create("X1", params("script", "ok.sql", "mode", "threads")));
        assertTrue(badMode.getMessage().contains("mode must be 'thread' or 'process'"), badMode.getMessage());
        assertTrue(badMode.getMessage().contains("Job [X1]"), badMode.getMessage());

        JobException badTimeout = assertThrows(JobException.class,
                () -> manager.create("X2", params("script", "ok.sql", "timeout", "soon")));
        assertTrue(badTimeout.getMessage().contains("Invalid job [X2] timeout [soon]"),
                badTimeout.getMessage());

        JobException zeroTimeout = assertThrows(JobException.class,
                () -> manager.create("X3", params("script", "ok.sql", "timeout", "0")));
        assertTrue(zeroTimeout.getMessage().contains("Invalid job [X3] timeout [0]"),
                zeroTimeout.getMessage());

        // mode 的**取值**大小写不敏感（参数名与变量名一样区分大小写），且编排参数不会变成脚本变量
        JobSpec spec = new DbcliJobSpecFactory().specFor("X4",
                params("script", "ok.sql", "mode", "PROCESS", "timeout", "2.5", "K", "V"));
        assertEquals(JobMode.PROCESS, spec.mode());
        assertEquals(2.5, spec.timeoutSeconds(), 0.0001);
        assertEquals(Map.of("K", "V"), spec.variables(), "编排参数不该变成变量: " + spec.variables());
    }

    // ------------------------------------------------------------------ 小工具

    private void writeScript(String name, String... lines) throws Exception {
        Files.writeString(tempDir.resolve(name), String.join("\n", lines) + "\n");
    }

    private String readIfExists(String name) throws Exception {
        Path file = tempDir.resolve(name);
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    private static Map<String, String> params(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** 等执行体登记子进程 pid（子进程 JVM 启动需要一点时间）。 */
    private static long awaitProcessId(Job job) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000L * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (job.processId() > 0L) {
                return job.processId();
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("子进程 pid 一直没有登记（作业状态 " + job.state() + "）");
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
