package org.slackerdb.dbcli.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.common.CommandOutcome;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _JOB} 动作分派与文案的契约测试。
 *
 * <p>分派器产出的文案与 {@code _JOB SHOW} 的列就是脚本里能看到的东西，因此逐字断言；
 * 作业体是测试自己的 {@link JobExecutor}（与 {@link JobManagerTest} 同一套 hermetic 手法），
 * 不起子进程、不碰数据库。</p>
 */
class JobCommandDispatcherTest {

    @Test
    @DisplayName("WAIT 的 timeout 必须是正的有限秒数：负数 / NaN / Infinity 都给可读错误")
    void waitTimeoutMustBePositiveAndFinite() {
        dispatcher.execute(ir("action", "startJobmanager"));
        dispatcher.execute(ir("action", "create", "jobName", "J", "param", params("script", "j.sql")));
        dispatcher.execute(ir("action", "start", "jobName", "J"));

        assertEquals("Invalid job wait timeout [-1]: expected a positive number of seconds.",
                firstError(dispatcher.execute(ir("action", "wait", "jobName", "J",
                        "param", params("timeout", "-1")))));
        assertEquals("Invalid job wait timeout [NaN]: expected a positive number of seconds.",
                firstError(dispatcher.execute(ir("action", "wait", "jobName", "J",
                        "param", params("timeout", "NaN")))));
        assertEquals("Invalid job wait timeout [Infinity]: expected a positive number of seconds.",
                firstError(dispatcher.execute(ir("action", "wait", "jobName", "J",
                        "param", params("timeout", "Infinity")))));
    }

    @Test
    @DisplayName("_JOB WAIT TIMER: 缺点名：可读错误，不静默挂住")
    void timerWaitNeedsAPointName() {
        assertEquals("Timer point name is missing: write _JOB WAIT TIMER:<point>.",
                firstError(dispatcher.execute(ir("action", "wait", "jobName", "TIMER:"))));
    }

    @TempDir
    Path tempDir;

    private JobManager manager;
    private JobCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        manager = new JobManager(this::spec, this::execute, tempDir.resolve("logs"), tempDir);
        dispatcher = new JobCommandDispatcher(manager);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private JobSpec spec(String jobName, Map<String, String> parameters) {
        return new JobSpec(jobName, parameters.getOrDefault("script", jobName + ".sql"),
                parameters, Map.of(), null);
    }

    private int execute(JobContext job) throws Exception {
        Map<String, String> parameters = job.spec().variables();
        PrintWriter out = job.output().stdout();
        out.print("start " + job.spec().name() + "\n");
        out.flush();
        long sleep = Long.parseLong(parameters.getOrDefault("sleep", "0"));
        long deadline = System.nanoTime() + sleep * 1_000_000L;
        while (System.nanoTime() < deadline && !job.control().stopRequested()) {
            Thread.sleep(10L);
        }
        out.print("end " + job.spec().name() + "\n");
        out.flush();
        return Integer.parseInt(parameters.getOrDefault("exit", "0"));
    }

    private static Map<String, Object> ir(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, String> params(Object... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], String.valueOf(keyValues[i + 1]));
        }
        return map;
    }

    private static String firstError(List<CommandOutcome> outcomes) {
        for (CommandOutcome outcome : outcomes) {
            if (outcome instanceof CommandOutcome.Error error) {
                return error.message();
            }
        }
        return null;
    }

    private static String firstStatus(List<CommandOutcome> outcomes) {
        for (CommandOutcome outcome : outcomes) {
            if (outcome instanceof CommandOutcome.Status status) {
                return status.text();
            }
        }
        return null;
    }

    /** {@code Failure} 是"状态行 + 失败"，文案与 Status 同形，但引擎会额外计一次错误。 */
    private static String firstFailure(List<CommandOutcome> outcomes) {
        for (CommandOutcome outcome : outcomes) {
            if (outcome instanceof CommandOutcome.Failure failure) {
                return failure.text();
            }
        }
        return null;
    }

    @Test
    @DisplayName("JOBMANAGER ON/OFF 的文案")
    void jobManagerSwitch() {
        assertEquals("Job manager started.", firstStatus(dispatcher.execute(ir("action", "startJobmanager"))));
        assertEquals("Job manager stopped.", firstStatus(dispatcher.execute(ir("action", "stopJobmanager"))));
    }

    @Test
    @DisplayName("create → start → wait 全链路文案；未启动管理器时创建要报错")
    void createStartWait() {
        assertEquals("Job manager is not started (use _JOB JOBMANAGER ON).",
                firstError(dispatcher.execute(ir("action", "create", "jobName", "J1", "param", params()))));

        dispatcher.execute(ir("action", "startJobmanager"));
        assertEquals("Job [J1] created.",
                firstStatus(dispatcher.execute(ir("action", "create", "jobName", "J1", "param", params()))));

        assertEquals("Job [J1] started.",
                firstStatus(dispatcher.execute(ir("action", "start", "jobName", "J1"))));

        assertEquals("Job [J1] finished with exit code 0.",
                firstStatus(dispatcher.execute(ir("action", "wait", "jobName", "J1"))));
    }

    @Test
    @DisplayName("SHOW 产出结果集：表头 name/state/thread/elapsed/exitCode")
    void showProducesTable() {
        dispatcher.execute(ir("action", "startJobmanager"));
        dispatcher.execute(ir("action", "create", "jobName", "J2", "param", params()));
        List<CommandOutcome> outcomes = dispatcher.execute(ir("action", "show", "jobName", "J2"));

        assertEquals(1, outcomes.size());
        CommandOutcome.Table table = (CommandOutcome.Table) outcomes.get(0);
        assertEquals(List.of("name", "state", "thread", "elapsed", "exitCode"), table.headers());
        assertEquals(1, table.rows().size());
        List<Object> row = new ArrayList<>(table.rows().get(0));
        assertEquals("J2", row.get(0));
        assertEquals("CREATED", row.get(1));
        assertEquals("", row.get(2), "未启动时没有作业线程");
    }

    @Test
    @DisplayName("SHOW 在作业跑起来之后给出线程名与状态")
    void showRunningJobReportsThread() {
        dispatcher.execute(ir("action", "startJobmanager"));
        dispatcher.execute(ir("action", "create", "jobName", "J9",
                "param", params("sleep", 30000)));
        dispatcher.execute(ir("action", "start", "jobName", "J9"));
        CommandOutcome.Table table = (CommandOutcome.Table)
                dispatcher.execute(ir("action", "show", "jobName", "J9")).get(0);
        List<Object> row = new ArrayList<>(table.rows().get(0));
        assertEquals("J9", row.get(0));
        assertEquals("RUNNING", row.get(1));
        assertEquals("dbcli-job-J9", row.get(2));
        dispatcher.execute(ir("action", "abort", "jobName", "J9"));
    }

    @Test
    @DisplayName("非 0 退出码、wait 超时、abort、shutdown 的文案")
    void failureAndStopTexts() {
        dispatcher.execute(ir("action", "startJobmanager"));
        dispatcher.execute(ir("action", "create", "jobName", "BAD", "param", params("exit", 5)));
        dispatcher.execute(ir("action", "start", "jobName", "BAD"));
        // 等到的作业以非 0 退出码结束 = **失败**（不是普通状态行）：文案不变，但产出 Failure
        assertEquals("Job [BAD] finished with exit code 5.",
                firstFailure(dispatcher.execute(ir("action", "wait", "jobName", "BAD"))));

        dispatcher.execute(ir("action", "create", "jobName", "SLOW", "param", params("sleep", 30000)));
        dispatcher.execute(ir("action", "start", "jobName", "SLOW"));
        // timeout 单位是秒，0.2 秒；等待超时同样默认是失败（屏障/作业没按时到就是编排错了）
        assertEquals("Job [SLOW] wait timed out.",
                firstFailure(dispatcher.execute(ir("action", "wait", "jobName", "SLOW",
                        "param", params("timeout", "0.2")))));
        assertEquals("Job [SLOW] aborted.",
                firstStatus(dispatcher.execute(ir("action", "abort", "jobName", "SLOW"))));

        dispatcher.execute(ir("action", "create", "jobName", "SLOW2", "param", params("sleep", 30000)));
        dispatcher.execute(ir("action", "start", "jobName", "SLOW2"));
        assertEquals("Job [SLOW2] shutdown.",
                firstStatus(dispatcher.execute(ir("action", "shutdown", "jobName", "SLOW2"))));
    }

    @Test
    @DisplayName("onfail 已删除：WAIT 只认 timeout，别的参数当场报可读错误")
    void onfailIsRejected() {
        dispatcher.execute(ir("action", "startJobmanager"));
        dispatcher.execute(ir("action", "create", "jobName", "W", "param", params("exit", 0)));
        dispatcher.execute(ir("action", "start", "jobName", "W"));

        assertEquals("Unknown job wait option [onfail]; supported: timeout=<seconds>.",
                firstError(dispatcher.execute(ir("action", "wait", "jobName", "W",
                        "param", params("onfail", "ignore")))),
                "静默忽略会让使用者以为它生效了");
    }

    @Test
    @DisplayName("失败作业被 WAIT 检查过之后，收尾时不再算一次失败；没检查过的仍要算")
    void unacknowledgedFailuresAreReportedAtTheEnd() throws Exception {
        dispatcher.execute(ir("action", "startJobmanager"));

        // 没 WAIT 过：作业自己失败 = 收尾时必须被发现
        Job unwatched = manager.create("UNWATCHED", params("exit", 3, "script", "u.sql"));
        manager.start("UNWATCHED");
        awaitNotRunning(unwatched);
        assertTrue(manager.hasUnacknowledgedFailures(), "没人检查的失败作业必须被发现");

        // WAIT 过：已经检查过，收尾时不再算
        dispatcher.execute(ir("action", "create", "jobName", "WATCHED",
                "param", params("exit", 3, "script", "w.sql")));
        dispatcher.execute(ir("action", "start", "jobName", "WATCHED"));
        dispatcher.execute(ir("action", "wait", "jobName", "WATCHED"));
        manager.acknowledge("UNWATCHED");
        assertFalse(manager.hasUnacknowledgedFailures(), "都认领过之后不该再报");
    }

    @Test
    @DisplayName("作业到点被超时停掉（TIMEOUT）：WAIT 也按失败处理")
    void timedOutJobIsAFailure() {
        JobManager timed = new JobManager(
                (name, parameters) -> new JobSpec(name, "t.sql", parameters,
                        Map.of(JobSpec.OPTION_TIMEOUT, "0.05"), null),
                this::execute, tempDir.resolve("logs-timed"), tempDir);
        try {
            JobCommandDispatcher timedDispatcher = new JobCommandDispatcher(timed);
            timedDispatcher.execute(ir("action", "startJobmanager"));
            timedDispatcher.execute(ir("action", "create", "jobName", "T",
                    "param", params("sleep", 5000)));
            timedDispatcher.execute(ir("action", "start", "jobName", "T"));

            List<CommandOutcome> outcomes = timedDispatcher.execute(ir("action", "wait", "jobName", "T"));

            assertEquals("Job [T] timeout.", firstFailure(outcomes));
        } finally {
            timed.close();
        }
    }

    private static void awaitNotRunning(Job job) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (job.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertFalse(job.isRunning(), "作业没有在 5 秒内结束");
    }

    @Test
    @DisplayName("定时点的文案：TIMER 放行后同一个点的 WAIT 立即返回")
    void timerPointTexts() {
        assertEquals("Timer point [TP1] released.",
                firstStatus(dispatcher.execute(ir("action", "timer", "timerPoint", "TP1"))));
        assertEquals("Timer point [TP1] released.",
                firstStatus(dispatcher.execute(ir("action", "wait", "jobName", "TIMER:TP1"))));
    }

    @Test
    @DisplayName("wait 不写 timeout：一直等，直到另一个线程放行该定时点（不是默认 300 秒）")
    void waitWithoutTimeoutWaitsUntilReleased() throws Exception {
        java.util.concurrent.atomic.AtomicReference<List<CommandOutcome>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread waiter = new Thread(() ->
                captured.set(dispatcher.execute(ir("action", "wait", "jobName", "TIMER:GATE"))));
        waiter.setDaemon(true);
        waiter.start();
        // 让它先进入等待：这里只睡一小会儿，若实现"默认超时"是 300 秒，下面的断言会拖到超时后失败
        Thread.sleep(300L);
        assertTrue(waiter.isAlive(), "不写 timeout 时应当一直等着，而不是立刻返回");
        dispatcher.execute(ir("action", "timer", "timerPoint", "GATE"));
        waiter.join(10_000L);
        assertFalse(waiter.isAlive(), "放行之后等待线程应当已经返回");
        assertEquals("Timer point [GATE] released.", firstStatus(captured.get()));
    }

    @Test
    @DisplayName("已删除的 register/deregister 动作不再被认识（明确报未知动作，不静默）")
    void removedWorkerActionsAreUnknown() {
        assertEquals("Unknown job action [register].",
                firstError(dispatcher.execute(ir("action", "register", "jobName", "W1"))));
        assertEquals("Unknown job action [deregister].",
                firstError(dispatcher.execute(ir("action", "deregister"))));
    }

    @Test
    @DisplayName("set 更新参数（未运行时）并回报文案")
    void setParameters() {
        dispatcher.execute(ir("action", "startJobmanager"));
        dispatcher.execute(ir("action", "create", "jobName", "J3", "param", params("exit", 0)));
        assertEquals("Job [J3] parameters updated.",
                firstStatus(dispatcher.execute(ir("action", "set", "jobName", "J3", "param", params("exit", 3)))));
        dispatcher.execute(ir("action", "start", "jobName", "J3"));
        assertEquals("Job [J3] finished with exit code 3.",
                firstFailure(dispatcher.execute(ir("action", "wait", "jobName", "J3"))));
    }

    @Test
    @DisplayName("错误路径：未知动作、缺 jobName、非法 timeout、找不到名字")
    void errorPaths() {
        assertEquals("Unknown job action [nosuch].",
                firstError(dispatcher.execute(ir("action", "nosuch"))));
        assertEquals("Job command needs a 'jobName' value.",
                firstError(dispatcher.execute(ir("action", "show"))));
        assertEquals("Job command needs a 'timerPoint' value.",
                firstError(dispatcher.execute(ir("action", "timer"))));

        dispatcher.execute(ir("action", "startJobmanager"));
        assertEquals("Invalid job wait timeout [abc]: expected a positive number of seconds.",
                firstError(dispatcher.execute(ir("action", "wait", "jobName", "X",
                        "param", params("timeout", "abc")))));
        assertEquals("Job or timer point [NOPE] not found.",
                firstError(dispatcher.execute(ir("action", "wait", "jobName", "NOPE"))));
    }
}
