package org.slackerdb.dbcli.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作业管理器测试（线程模型）：用**真实作业线程**验证启动/等待/超时/中止/定时点/收尾。
 *
 * <p>作业体是 {@link #execute}（测试自己的 {@link JobExecutor}），因此不依赖脚本引擎、不依赖数据库、
 * 不起子进程——这是"作业调度"这一层的 hermetic 测试。脚本级的屏障/并发行为由
 * {@code engine.JobBarrierTest} 用真引擎覆盖。</p>
 *
 * <p>作业体认四个参数：{@code sleep}（毫秒，分段睡，可被停止请求唤醒）、{@code exit}（退出码）、
 * {@code boom}（非空时抛异常，验证"作业线程异常 → FAILED + .out"）、{@code gate}（非空时先等这个文件
 * 出现再继续——把"作业此刻确实在跑"变成确定的事，而不是靠 sleep 抢时间）。</p>
 */
class JobManagerTest {

    @TempDir
    Path tempDir;

    private JobManager manager;

    @BeforeEach
    void setUp() {
        manager = new JobManager(this::spec, this::execute, tempDir.resolve("logs"), tempDir);
        manager.start();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    // ------------------------------------------------------------------ 测试用作业

    /** 定义：所有参数都当变量（作业体自己解释 {@code sleep}/{@code exit}/{@code boom}）。 */
    private JobSpec spec(String jobName, Map<String, String> parameters) {
        return new JobSpec(jobName, parameters.getOrDefault("script", jobName + ".sql"),
                parameters, Map.of(), null);
    }

    /** 作业体：打印 start/end、按需睡一会儿、按需失败。 */
    private int execute(JobContext job) throws Exception {
        Map<String, String> parameters = job.spec().variables();
        String name = job.spec().name();
        PrintWriter out = job.output().stdout();
        out.print("start " + name + "\n");
        out.flush();
        if (parameters.containsKey("boom")) {
            throw new IllegalStateException("boom " + parameters.get("boom"));
        }
        if (parameters.containsKey("gate")) {
            awaitGate(parameters.get("gate"), job.control());
        }
        sleepQuietly(Long.parseLong(parameters.getOrDefault("sleep", "0")), job.control());
        out.print("end " + name + "\n");
        out.flush();
        job.output().stderr().print("stderr " + name + "\n");
        job.output().stderr().flush();
        if (job.control().stopRequested()) {
            // 与引擎同一口径：被请求停止的作业给一个非 0 退出码，终态由请求方落地
            return 1;
        }
        return Integer.parseInt(parameters.getOrDefault("exit", "0"));
    }

    /** 分段睡：停止请求一到就醒（真引擎的 {@code _SLEEP} 就是这么做的）。 */
    private static void sleepQuietly(long millis, JobControl control) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (control.stopRequested()) {
                return;
            }
            Thread.sleep(10L);
        }
    }

    /**
     * 闸门：等 {@code tempDir} 下那个文件出现才继续（停止请求一到就醒）。
     *
     * <p>让"start 之后作业确实还在跑"变成<b>确定</b>的事：只靠 {@code sleep=0} 的作业，
     * 线程可能在断言之前就跑完了（实测红过：expected RUNNING but was DONE）。</p>
     */
    private void awaitGate(String gate, JobControl control) throws InterruptedException {
        Path gateFile = tempDir.resolve(gate);
        while (!Files.exists(gateFile)) {
            if (control.stopRequested()) {
                return;
            }
            Thread.sleep(10L);
        }
    }

    private static Map<String, String> params(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** 轮询等一个文件出现某段文本（作业体在别的线程里写文件，读之前要等它落盘）。 */
    private static String awaitFileText(Path file, String needle, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        String content = "";
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                content = Files.readString(file);
                if (content.contains(needle)) {
                    return content;
                }
            }
            Thread.sleep(20L);
        }
        return content;
    }

    // ------------------------------------------------------------------ 测试

    @Test
    @DisplayName("正常作业：create → start → wait，退出码 0，输出落到 <作业名>.out")
    void happyPath() throws Exception {
        // 用闸门而不是 sleep：作业体在放行之前一定还活着，于是"start 之后是 RUNNING"是确定的
        manager.create("JOB1", params("sleep", "0", "exit", "0", "gate", "JOB1.gate"));
        Job started = manager.start("JOB1");
        assertEquals(JobState.RUNNING, started.state());
        assertNotNull(started.thread(), "start 之后必须有作业线程");
        assertEquals("dbcli-job-JOB1", started.threadName());
        assertTrue(started.thread().isDaemon(), "作业线程必须一律 daemon（否则会拖住 JVM 退出）");

        // 放行：作业体继续跑完（sleep=0、exit=0）
        Files.writeString(tempDir.resolve("JOB1.gate"), "go");

        JobManager.WaitResult result = manager.waitFor("JOB1", 30_000L);
        assertEquals(JobState.DONE, result.job().state());
        assertEquals(0, result.job().exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.job().elapsedMillis() >= 0);

        String stdout = Files.readString(tempDir.resolve("logs").resolve("JOB1.out"));
        assertTrue(stdout.contains("start JOB1") && stdout.contains("end JOB1"),
                "作业 stdout 应当落到 <作业名>.out，实际: " + stdout);
        // stderr 也写进同一个文件
        assertTrue(stdout.contains("stderr JOB1"), "作业 stderr 也应当落到 <作业名>.out，实际: " + stdout);
    }

    @Test
    @DisplayName("_JOB ABORT 不会把已经结束的作业改写成 ABORTED（DONE/TIMEOUT 是事实）")
    void abortDoesNotRewriteATerminalState() {
        manager.create("DONE1", params("sleep", "0", "exit", "0"));
        manager.start("DONE1");
        manager.waitFor("DONE1", 30_000L);

        Job aborted = manager.abort("DONE1");

        assertEquals(JobState.DONE, aborted.state(),
                "对已经跑完的作业再 abort 只是表达意图，不能改写结局");
        assertEquals(0, aborted.exitCode());
    }

    @Test
    @DisplayName("非 0 退出码 → FAILED，且退出码可读回")
    void failingJob() {
        manager.create("BAD", params("exit", "7"));
        manager.start("BAD");
        JobManager.WaitResult result = manager.waitFor("BAD", 30_000L);
        assertEquals(JobState.FAILED, result.job().state());
        assertEquals(7, result.job().exitCode());
    }

    @Test
    @DisplayName("作业线程抛异常 → FAILED + 可读原因写进 <作业名>.err（异常绝不冒到调用线程）")
    void threadFailure() throws Exception {
        manager.create("BOOM", params("boom", "bad-thing"));
        manager.start("BOOM");
        JobManager.WaitResult result = manager.waitFor("BOOM", 30_000L);
        assertEquals(JobState.FAILED, result.job().state());
        assertTrue(result.job().failureMessage().contains("boom bad-thing"),
                "失败原因应当可读，实际: " + result.job().failureMessage());
        String jobOut = Files.readString(tempDir.resolve("logs").resolve("BOOM.out"));
        assertTrue(jobOut.contains("ERROR: boom bad-thing"), "异常必须写进 .out，实际: " + jobOut);
    }

    @Test
    @DisplayName("wait 超时 → timedOut=true 但**作业状态不变**（仍是 RUNNING），abort 后 → ABORTED")
    void waitTimeoutThenAbort() throws Exception {
        manager.create("SLOW", params("sleep", "30000"));
        Job started = manager.start("SLOW");

        JobManager.WaitResult result = manager.waitFor("SLOW", 300L);
        assertTrue(result.timedOut(), "超时应当如实报告");
        assertEquals(JobState.RUNNING, result.job().state(),
                "超时是等待结果，不是作业状态：作业还在跑，状态必须还是 RUNNING");
        assertEquals(JobState.RUNNING, manager.show("SLOW").state(), "show 也应显示 RUNNING");

        Job aborted = manager.abort("SLOW");
        assertEquals(JobState.ABORTED, aborted.state());
        waitUntilStopped(started.thread());
        assertFalse(started.thread().isAlive(), "abort 之后作业线程必须已经结束");
        assertEquals(JobState.ABORTED, manager.show("SLOW").state(), "状态查询仍然是 ABORTED");
    }

    @Test
    @DisplayName("shutdown → 优雅停止并进入 SHUTDOWN 状态")
    void shutdownJob() {
        manager.create("SLOW2", params("sleep", "30000"));
        Job started = manager.start("SLOW2");
        Job stopped = manager.shutdown("SLOW2");
        assertEquals(JobState.SHUTDOWN, stopped.state());
        assertFalse(started.thread().isAlive(), "shutdown 之后作业线程必须已经结束");
    }

    @Test
    @DisplayName("定时点：先放行后等待立即返回；等待中放行会被唤醒；未放行则超时")
    void timerPoints() throws Exception {
        // 1) 先放行，后等待 → 立即返回
        manager.releaseTimer("TP1");
        assertTrue(manager.waitFor("TIMER:TP1", 1_000L).timerReleased());

        // 2) 等待中放行 → 被唤醒
        AtomicReference<JobManager.WaitResult> captured = new AtomicReference<>();
        Thread waiter = new Thread(() -> captured.set(manager.waitFor("TIMER:TP2", 10_000L)));
        waiter.start();
        Thread.sleep(200);                       // 让等待方先进入 await
        manager.releaseTimer("TP2");
        waiter.join(10_000);
        assertFalse(waiter.isAlive(), "放行后等待线程应当已经返回");
        assertTrue(captured.get().timerReleased(), "定时点应当被放行");

        // 3) 一直不放行 → 超时（不是异常）
        assertFalse(manager.waitFor("TIMER:TP3", 200L).timerReleased());
    }

    @Test
    @DisplayName("屏障：作业线程等定时点，主线程放行 → 作业继续并成功结束（共享同一个管理器）")
    void jobThreadWaitsAtTimerPoint() throws Exception {
        // 作业体先打印 waiting，再等 TIMER:JOB_GATE（用的是**同一个** manager）
        JobExecutor barrierBody = job -> {
            job.output().stdout().print("waiting\n");
            job.output().stdout().flush();
            JobManager.WaitResult wait = job.manager().waitFor("TIMER:JOB_GATE", 30_000L);
            job.output().stdout().print(wait.timerReleased() ? "released\n" : "not-released\n");
            job.output().stdout().flush();
            return wait.timerReleased() ? 0 : 1;
        };
        JobManager barrierManager = new JobManager(this::spec, barrierBody, tempDir.resolve("logs3"), tempDir);
        try {
            barrierManager.start();
            barrierManager.create("WAITER", params());
            barrierManager.start("WAITER");

            Path out = tempDir.resolve("logs3").resolve("WAITER.out");
            assertTrue(awaitFileText(out, "waiting", 10_000).contains("waiting"),
                    "作业线程应当先到屏障前");
            assertEquals(JobState.RUNNING, barrierManager.show("WAITER").state(),
                    "作业应当还卡在屏障上（没有被唤醒之前不能结束）");

            barrierManager.releaseTimer("JOB_GATE");
            JobManager.WaitResult result = barrierManager.waitFor("WAITER", 10_000L);
            assertEquals(JobState.DONE, result.job().state(), "放行之后作业应当成功结束");
            assertTrue(Files.readString(out).contains("released"), Files.readString(out));
        } finally {
            barrierManager.close();
        }
    }

    @Test
    @DisplayName("任意深度：作业线程里再 create/start 一个孙作业（同一个管理器）")
    void nestedJobUsesTheSameManager() throws Exception {
        AtomicReference<String> grandState = new AtomicReference<>();
        JobExecutor spawningBody = job -> {
            if (!"CHILD".equals(job.spec().name())) {
                // 孙作业只负责跑完（否则它会无限地再起一个同名孙作业）
                return 0;
            }
            JobManager shared = job.manager();
            shared.create("GRAND", params("exit", "0"));
            shared.start("GRAND");
            shared.waitFor("GRAND", 30_000L);
            grandState.set(shared.show("GRAND").state().name());
            return shared.show("GRAND").exitCode();
        };
        JobManager nested = new JobManager(this::spec, spawningBody, tempDir.resolve("logs4"), tempDir);
        try {
            nested.start();
            nested.create("CHILD", params());
            nested.start("CHILD");
            JobManager.WaitResult result = nested.waitFor("CHILD", 30_000L);
            assertEquals(JobState.DONE, result.job().state());
            assertEquals("DONE", grandState.get(), "孙作业应当登记在同一个管理器上");
            assertEquals(JobState.DONE, nested.show("GRAND").state());
        } finally {
            nested.close();
        }
    }

    @Test
    @DisplayName("名字解析：作业优先；都不存在时报错（不静默挂住）")
    void nameResolution() {
        manager.create("JOBX", params());
        manager.start("JOBX");
        assertEquals("JOBX", manager.waitFor("JOBX", 30_000L).job().name());
        JobException error = assertThrows(JobException.class, () -> manager.waitFor("NOSUCH", 100L));
        assertTrue(error.getMessage().contains("not found"), error.getMessage());
    }

    @Test
    @DisplayName("管理器未启动 / 重名 / 运行中改参数 / 缺 script 都要报可读错误")
    void errorPaths() {
        JobManager stopped = new JobManager(this::spec, this::execute, tempDir.resolve("logs2"), tempDir);
        JobException notStarted = assertThrows(JobException.class, () -> stopped.create("X", params()));
        assertTrue(notStarted.getMessage().contains("not started"), notStarted.getMessage());

        manager.create("DUP", params());
        JobException duplicate = assertThrows(JobException.class, () -> manager.create("DUP", params()));
        assertTrue(duplicate.getMessage().contains("already exists"), duplicate.getMessage());

        manager.create("RUN", params("sleep", "30000"));
        manager.start("RUN");
        JobException running = assertThrows(JobException.class, () -> manager.setParameters("RUN", params()));
        assertTrue(running.getMessage().contains("is running"), running.getMessage());

        JobException unknown = assertThrows(JobException.class, () -> manager.show("NOPE"));
        assertTrue(unknown.getMessage().contains("not found"), unknown.getMessage());

        // 生产工厂（DbcliJobSpecFactory）缺 script 时的错误由它自己给（见 DbcliJobSpecFactoryTest）；
        // 这里验证"工厂给出了没有脚本的定义"时管理器也报可读错误
        JobManager strict = new JobManager((name, parameters) -> new JobSpec(name, name + ".sql",
                Map.of(), Map.of(), null), this::execute, tempDir.resolve("logs5"), tempDir);
        strict.start();
        assertEquals(JobState.CREATED, strict.create("OK", params()).state());
        strict.close();
    }

    @Test
    @DisplayName("未启动的作业不能等待；start 过的作业不能重复 start")
    void lifecycleGuards() {
        manager.create("ONLY_CREATED", params());
        // 没启动的作业永远不会"结束"：等它必须当场报错（否则漏写 _JOB START 会得到一个假成功）
        JobException notStartedYet = assertThrows(JobException.class,
                () -> manager.waitFor("ONLY_CREATED", 1_000L));
        assertTrue(notStartedYet.getMessage().contains("never started"), notStartedYet.getMessage());

        manager.start("ONLY_CREATED");
        manager.waitFor("ONLY_CREATED", 30_000L);
        JobException twice = assertThrows(JobException.class, () -> manager.start("ONLY_CREATED"));
        assertTrue(twice.getMessage().contains("already"), twice.getMessage());
    }

    @Test
    @DisplayName("close() 停掉仍在运行的作业：限时内线程结束、状态 SHUTDOWN（绝不拖住 JVM）")
    void closeStopsRunningJobs() throws Exception {
        manager.create("ORPHAN", params("sleep", "30000"));
        Job job = manager.start("ORPHAN");
        Thread thread = job.thread();
        manager.close();
        assertFalse(thread.isAlive(), "close 之后不得留下还活着的作业线程");
        assertEquals(JobState.SHUTDOWN, job.state(), "管理器关闭收掉的作业落到 SHUTDOWN");
        assertTrue(manager.jobs().stream().noneMatch(Job::isRunning), "不应还有 RUNNING 的作业");
        for (Thread alive : Thread.getAllStackTraces().keySet()) {
            assertFalse(alive.getName().startsWith(JobManager.THREAD_NAME_PREFIX) && alive.isAlive(),
                    "不应留下作业线程: " + alive.getName());
        }
    }

    @Test
    @DisplayName("SHOW 的行摘要：name/state/thread/elapsed/exitCode")
    void describeRow() {
        manager.create("SHOWME", params());
        List<String> row = manager.show("SHOWME").describe();
        assertEquals("SHOWME", row.get(0));
        assertEquals("CREATED", row.get(1));
        assertEquals("", row.get(2), "未启动时没有作业线程");
        assertEquals("0ms", row.get(3));
        assertEquals("", row.get(4), "未结束时退出码为空");
    }

    @Test
    @DisplayName("wait 传 null（不写 timeout=）：一直等到放行为止，不会用默认上限自己放弃")
    void waitWithoutTimeoutNeverGivesUpByItself() throws Exception {
        // 作业体先报"到了"，再卡在 GATE 上（**不写 timeout**）——父线程放行之前它必须一直在跑
        JobExecutor barrierBody = job -> {
            job.output().stdout().print("at-gate\n");
            job.output().stdout().flush();
            JobManager.WaitResult wait = job.manager().waitFor("TIMER:GATE", null);
            job.output().stdout().print(wait.timerReleased() ? "released\n" : "not-released\n");
            job.output().stdout().flush();
            return wait.timerReleased() ? 0 : 1;
        };
        JobManager unlimited = new JobManager(this::spec, barrierBody, tempDir.resolve("logs6"), tempDir);
        try {
            unlimited.start();
            unlimited.create("UNLIMITED", params());
            unlimited.start("UNLIMITED");

            Path out = tempDir.resolve("logs6").resolve("UNLIMITED.out");
            assertTrue(awaitFileText(out, "at-gate", 10_000).contains("at-gate"),
                    "作业应当已经到达没有时限的等待点");
            // 等一段明显超过"任何默认超时哨兵"的时间：若实现还留着 300 秒之类的默认上限，
            // 这里不会立刻失败，但下面的状态断言与放行后的结果断言会失败（作业已经超时收尾）
            Thread.sleep(500L);
            assertEquals(JobState.RUNNING, unlimited.show("UNLIMITED").state(),
                    "没有时限时作业必须仍然卡在等待点上");

            unlimited.releaseTimer("GATE");
            JobManager.WaitResult result = unlimited.waitFor("UNLIMITED", 30_000L);
            assertEquals(JobState.DONE, result.job().state(), "放行之后作业应当正常结束");
            assertTrue(Files.readString(out).contains("released"), Files.readString(out));
        } finally {
            unlimited.close();
        }
    }

    /** 等线程结束（最多 5 秒）；abort/shutdown 是"尽力"，给线程一点收尾时间再断言。 */
    private static void waitUntilStopped(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000L * 1_000_000L;
        while (thread.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
    }
}
