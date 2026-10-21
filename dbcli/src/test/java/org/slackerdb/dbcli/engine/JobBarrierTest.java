package org.slackerdb.dbcli.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.job.Job;
import org.slackerdb.dbcli.job.JobManager;
import org.slackerdb.dbcli.job.JobState;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _JOB} 的**线程模型与同步屏障**（脚本级、hermetic）：作业脚本文本 → 真引擎 → 真作业线程。
 *
 * <p>本类覆盖作业闸门的关键语义：</p>
 * <ul>
 *   <li>{@code _job wait TIMER:<点>} 在作业脚本里真的会**阻塞**，父脚本 {@code _job timer <点>} 放行后继续；</li>
 *   <li>反过来（父先放行、作业后等待）立即通过；</li>
 *   <li>作业里再起孙作业也**共享同一个管理器**（任意深度都能互相会合）；</li>
 *   <li>超时是正常结果且不改作业状态；{@code abort}/{@code shutdown} 能停掉正在 {@code _sleep} 的作业；</li>
 *   <li>并发作业各写各的 {@code <作业名>.out}，互不交错，父脚本 stdout 只有父脚本的产出；</li>
 *   <li>{@code close()} 之后不留活着的作业线程（线程卫生）。</li>
 * </ul>
 *
 * <p>脚本里只有 {@code _job}/{@code _echo}/{@code _sleep}/{@code _show} 这类命令，**没有 SQL**，
 * 因此不需要数据库；作业体走的是生产路径 {@code ScriptJobExecutor}（每个作业一份独立
 * {@code ScriptContext}）。</p>
 */
class JobBarrierTest {

    @TempDir
    Path tempDir;

    private record Result(int exitCode, String stdout, String stderr) {
    }

    /** 跑一段父脚本文本（生产路径：{@code CommandDispatcher} 自建共享的作业管理器）。 */
    private Result runParent(String script) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode;
        try (FakeSqlExecutor executor = new FakeSqlExecutor();
             ScriptContext context = new ScriptContext(executor,
                     new ScriptOutput(new PrintWriter(out, true), new PrintWriter(err, true)),
                     new VariableResolver(Map.of()), tempDir)) {
            exitCode = new ScriptRunner(context).runText(script, "<job-parent>", List.of());
        }
        return new Result(exitCode, out.toString(), err.toString());
    }

    private void writeScript(String name, String... lines) throws Exception {
        Files.writeString(tempDir.resolve(name), String.join("\n", lines) + "\n");
    }

    private String readJobOutput(String jobName, String suffix) throws Exception {
        return Files.readString(tempDir.resolve(jobName + suffix));
    }

    // ------------------------------------------------------------------ 屏障

    @Test
    @DisplayName("屏障：作业在 _job wait timer 上阻塞，父脚本 _job timer 放行后作业继续并成功结束")
    void jobWaitsAtTheBarrierAndTheParentReleasesIt() throws Exception {
        writeScript("child.sql",
                "_echo t", "child-entered", "echo off",
                "_job timer CHILD_READY",
                "_job wait TIMER:GO timeout=30",
                "_echo t", "child-released", "echo off");
        Result result = runParent(String.join("\n",
                "_job jobmanager on",
                "_job create CHILD script=child.sql",
                "_job start CHILD",
                "_job wait TIMER:CHILD_READY timeout=30",
                "_job show CHILD",
                "_job timer GO",
                "_job wait CHILD timeout=30"));

        assertEquals(0, result.exitCode(), "stderr: " + result.stderr());
        assertTrue(result.stdout().contains("Job [CHILD] started."), result.stdout());
        // 父脚本在放行之前看到的作业状态是 RUNNING：它真的卡在屏障上，而不是已经跑完
        assertTrue(result.stdout().contains("RUNNING"), result.stdout());
        assertTrue(result.stdout().contains("Timer point [CHILD_READY] released."), result.stdout());
        assertTrue(result.stdout().contains("Job [CHILD] finished with exit code 0."), result.stdout());

        String childOut = readJobOutput("CHILD", ".out");
        assertTrue(childOut.contains("child-entered"), childOut);
        assertTrue(childOut.contains("child-released"), "放行之后作业应当继续跑: " + childOut);
        assertTrue(childOut.contains("Timer point [GO] released."),
                "作业侧应当看到定时点被放行: " + childOut);
        assertFalse(result.stdout().contains("child-entered"),
                "作业的输出绝不能混进父脚本的 stdout: " + result.stdout());
    }

    @Test
    @DisplayName("反向：父脚本先放行定时点，作业随后等待 → 立即通过（退出码 0，不是超时）")
    void parentReleasesBeforeTheJobWaits() throws Exception {
        writeScript("late.sql",
                "_job wait TIMER:EARLY timeout=5",
                "_echo t", "late-went-through", "echo off");
        Result result = runParent(String.join("\n",
                "_job jobmanager on",
                "_job timer EARLY",
                "_job create LATE script=late.sql",
                "_job start LATE",
                "_job wait LATE timeout=30"));

        assertEquals(0, result.exitCode(), "stderr: " + result.stderr());
        assertTrue(result.stdout().contains("Job [LATE] finished with exit code 0."), result.stdout());
        assertTrue(readJobOutput("LATE", ".out").contains("late-went-through"), "作业应当直接跑过去");
    }

    @Test
    @DisplayName("任意深度：作业里再起孙作业并与之会合（同一个管理器），父脚本也能看到孙作业")
    void grandChildJobSharesTheSameManager() throws Exception {
        writeScript("grand.sql",
                "_echo t", "grand-ran", "echo off");
        writeScript("child.sql",
                "_job create GRAND script=grand.sql",
                "_job start GRAND",
                "_job wait GRAND timeout=30",
                "_echo t", "child-saw-grand", "echo off",
                "_job timer CHILD_READY",
                "_job wait TIMER:GATE timeout=30",
                "_echo t", "child-released", "echo off");
        Result result = runParent(String.join("\n",
                "_job jobmanager on",
                "_job create CHILD script=child.sql",
                "_job start CHILD",
                "_job wait TIMER:CHILD_READY timeout=30",
                "_job show GRAND",
                "_job timer GATE",
                "_job wait CHILD timeout=30"));

        assertEquals(0, result.exitCode(), "stderr: " + result.stderr());
        assertTrue(result.stdout().contains("Job [CHILD] finished with exit code 0."), result.stdout());
        // 孙作业登记在同一个管理器上：父脚本可以 show 它，而且是 DONE
        assertTrue(result.stdout().contains("GRAND"), "父脚本应当看得到孙作业: " + result.stdout());
        assertTrue(result.stdout().contains("DONE"), "孙作业应当已经结束: " + result.stdout());
        String childOut = readJobOutput("CHILD", ".out");
        assertTrue(childOut.contains("child-saw-grand"), childOut);
        assertTrue(childOut.contains("child-released"), "父脚本放行后子作业应当继续: " + childOut);
        assertTrue(readJobOutput("GRAND", ".out").contains("grand-ran"), "孙作业应当真的跑过");
    }

    // ------------------------------------------------------------------ 超时

    @Test
    @DisplayName("超时：作业 wait 超时返回 'Job [x] wait timed out.'，作业状态仍是 RUNNING；定时点超时同理")
    void waitTimeoutIsReportedAndKeepsTheJobRunning() throws Exception {
        writeScript("slow.sql", "_sleep 30");
        Result result = runParent(String.join("\n",
                "_job jobmanager on",
                "_job create SLOWJOB script=slow.sql",
                "_job start SLOWJOB",
                "_job wait SLOWJOB timeout=0.2",
                "_job show SLOWJOB",
                "_job wait TIMER:NOBODY timeout=0.2"));

        assertTrue(result.stdout().contains("Job [SLOWJOB] wait timed out."), result.stdout());
        assertTrue(result.stdout().contains("RUNNING"), "超时不改作业状态: " + result.stdout());
        assertTrue(result.stdout().contains("Timer point [NOBODY] wait timed out."), result.stdout());
    }

    // ------------------------------------------------------------------ 中止

    @Test
    @DisplayName("abort：正在 _sleep 的作业尽快变 ABORTED，父脚本照常往下跑")
    void abortStopsASleepingJob() throws Exception {
        writeScript("sleepy.sql",
                "_echo t", "sleepy-entered", "echo off",
                "_job timer SLEEPY_READY",
                "_sleep 30",
                "_echo t", "never-printed", "echo off");
        long startedAt = System.nanoTime();
        Result result = runParent(String.join("\n",
                "_job jobmanager on",
                "_job create SLEEPY script=sleepy.sql",
                "_job start SLEEPY",
                "_job wait TIMER:SLEEPY_READY timeout=30",
                "_job abort SLEEPY",
                "_job show SLEEPY",
                "_echo t", "parent-continues-after-abort", "echo off"));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals(0, result.exitCode(), "stderr: " + result.stderr());
        assertTrue(result.stdout().contains("Job [SLEEPY] aborted."), result.stdout());
        assertTrue(result.stdout().contains("ABORTED"), result.stdout());
        assertTrue(result.stdout().contains("parent-continues-after-abort"),
                "中止作业不得影响父脚本: " + result.stdout());
        assertTrue(readJobOutput("SLEEPY", ".out").contains("sleepy-entered"),
                "作业应当真的跑起来过: " + readJobOutput("SLEEPY", ".out"));
        assertFalse(readJobOutput("SLEEPY", ".out").contains("never-printed"),
                "被中止的作业不该继续执行后面的语句");
        assertTrue(elapsedMillis < 10_000L,
                "abort 必须让 30 秒的作业尽快结束，实际用了 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("shutdown：优雅停止（只请求，不打断睡眠）→ SHUTDOWN，父脚本照常往下跑")
    void shutdownStopsASleepingJobGracefully() throws Exception {
        writeScript("sleepy2.sql",
                "_echo t", "sleepy2-entered", "echo off",
                "_job timer SLEEPY2_READY",
                "_sleep 30",
                "_echo t", "never-printed", "echo off");
        Result result = runParent(String.join("\n",
                "_job jobmanager on",
                "_job create SLEEPY2 script=sleepy2.sql",
                "_job start SLEEPY2",
                "_job wait TIMER:SLEEPY2_READY timeout=30",
                "_job shutdown SLEEPY2",
                "_job show SLEEPY2",
                "_echo t", "parent-continues-after-shutdown", "echo off"));

        assertEquals(0, result.exitCode(), "stderr: " + result.stderr());
        assertTrue(result.stdout().contains("Job [SLEEPY2] shutdown."), result.stdout());
        assertTrue(result.stdout().contains("SHUTDOWN"), result.stdout());
        assertTrue(result.stdout().contains("parent-continues-after-shutdown"), result.stdout());
        assertTrue(readJobOutput("SLEEPY2", ".out").contains("sleepy2-entered"),
                "作业应当真的跑起来过: " + readJobOutput("SLEEPY2", ".out"));
        assertFalse(readJobOutput("SLEEPY2", ".out").contains("never-printed"),
                "被停掉的作业不该继续执行后面的语句");
        assertFalse(readJobOutput("SLEEPY2", ".out").contains("Sleep interrupted."),
                "优雅停止不该用中断：作业应当自己看见停止请求后收尾");
    }

    // ------------------------------------------------------------------ 并发与输出隔离

    @Test
    @DisplayName("并发：3 个作业各自写 <作业名>.out，内容独立完整、无交错；父 stdout 只有父脚本的产出")
    void concurrentJobsWriteTheirOwnFiles() throws Exception {
        int linesPerJob = 200;
        for (int job = 1; job <= 3; job++) {
            List<String> script = new ArrayList<>();
            script.add("_echo t");
            script.add("C" + job + "-before");
            script.add("echo off");
            script.add("_job wait TIMER:GO timeout=30");
            script.add("_echo t");
            for (int line = 1; line <= linesPerJob; line++) {
                script.add("C" + job + "-" + line);
            }
            script.add("echo off");
            writeScript("c" + job + ".sql", script.toArray(new String[0]));
        }
        Result result = runParent(String.join("\n",
                "_job jobmanager on",
                "_job create C1 script=c1.sql",
                "_job create C2 script=c2.sql",
                "_job create C3 script=c3.sql",
                "_job start C1",
                "_job start C2",
                "_job start C3",
                "_job timer GO",
                "_job wait C1 timeout=30",
                "_job wait C2 timeout=30",
                "_job wait C3 timeout=30",
                "_echo t", "parent-only-line", "echo off"));

        assertEquals(0, result.exitCode(), "stderr: " + result.stderr());
        assertTrue(result.stdout().contains("parent-only-line"), result.stdout());
        assertTrue(result.stdout().contains("Job [C1] finished with exit code 0."), result.stdout());
        assertTrue(result.stdout().contains("Job [C2] finished with exit code 0."), result.stdout());
        assertTrue(result.stdout().contains("Job [C3] finished with exit code 0."), result.stdout());

        for (int job = 1; job <= 3; job++) {
            String prefix = "C" + job;
            List<String> dataLines = Files.readAllLines(tempDir.resolve(prefix + ".out")).stream()
                    .filter(line -> line.startsWith(prefix))
                    .toList();
            List<String> expected = new ArrayList<>();
            // 作业脚本第一行数据是 "C<job>-before"（见本测试上面构造脚本处），这里必须与它一致
            expected.add(prefix + "-before");
            for (int line = 1; line <= linesPerJob; line++) {
                expected.add(prefix + "-" + line);
            }
            assertEquals(expected, dataLines,
                    "作业 C" + job + " 的输出必须完整且保持顺序（不能交错、不能丢行）");
        }

        // 父脚本的 stdout 里不能出现任何作业的数据行
        for (int job = 1; job <= 3; job++) {
            assertFalse(result.stdout().contains("C" + job + "-before"),
                    "作业数据混进了父 stdout: " + result.stdout());
            assertFalse(result.stdout().contains("C" + job + "-1\n"),
                    "作业数据混进了父 stdout: " + result.stdout());
        }
    }

    // ------------------------------------------------------------------ 线程卫生

    @Test
    @DisplayName("线程卫生：作业线程是 daemon；引擎 close() 之后相关线程在限时内结束")
    void closingTheEngineLeavesNoJobThreads() throws Exception {
        writeScript("long.sql", "_sleep 30");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        FakeSqlExecutor executor = new FakeSqlExecutor();
        ScriptContext context = new ScriptContext(executor,
                new ScriptOutput(new PrintWriter(out, true), new PrintWriter(err, true)),
                new VariableResolver(Map.of()), tempDir);
        try {
            int exitCode = new ScriptRunner(context).runText(String.join("\n",
                    "_job jobmanager on",
                    "_job create LONGRUN script=long.sql",
                    "_job start LONGRUN"), "<job-parent>", List.of());
            assertEquals(0, exitCode, "stderr: " + err);

            JobManager manager = context.dispatcher().jobManager();
            Job job = manager.show("LONGRUN");
            Thread thread = job.thread();
            assertTrue(thread.isAlive(), "作业线程应当在跑");
            assertTrue(thread.isDaemon(), "作业线程必须一律 daemon");
            assertEquals(JobManager.THREAD_NAME_PREFIX + "LONGRUN", thread.getName());

            context.close();
            assertFalse(thread.isAlive(), "close() 之后作业线程必须已经结束");
            assertEquals(JobState.SHUTDOWN, job.state());
            for (Thread alive : Thread.getAllStackTraces().keySet()) {
                assertFalse(alive.isAlive() && alive.getName().startsWith(JobManager.THREAD_NAME_PREFIX),
                        "不应留下作业线程: " + alive.getName());
            }
        } finally {
            context.close();
            executor.close();
        }
    }
}
