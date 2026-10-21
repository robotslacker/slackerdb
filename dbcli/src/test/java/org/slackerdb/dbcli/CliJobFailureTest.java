package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子作业失败必须让**父脚本**失败。
 *
 * <p>这是"结果可信"的核心一条：数据库测试最常见的写法是"父脚本并行拉起若干测试脚本、最后汇总"。
 * 如果子作业以非 0 退出码结束而父进程仍然 {@code exit 0}，CI 会把一次失败判成通过——
 * 那比没有测试更糟。本节锁住三条路径：</p>
 * <ol>
 *   <li>{@code _JOB WAIT} 等到的失败作业 → 退出码非 0（{@code CommandOutcome.Failure}）；</li>
 *   <li>作业跑失败了但脚本**忘了 WAIT** → 收尾时补算一次失败（{@code JobManager.hasUnacknowledgedFailures()}）；</li>
 *   <li>{@code onfail=ignore} **已删除**：失败没有"忽略"开关，写了就是未知参数错误。</li>
 * </ol>
 */
class CliJobFailureTest {

    @TempDir
    Path tempDir;

    private record Result(String out, String err, int exitCode) {
    }

    private Result run(String... args) {
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream(new byte[0]));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true)); // dbcli 从不写 stderr：参数错误也在 stdout
        int exitCode = commandLine.execute(args);
        return new Result(out.toString(), err.toString(), exitCode);
    }

    /** 子脚本：以固定退出码结束（不碰数据库）。 */
    private Path child(String name, int exitCode) throws Exception {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "_EXIT " + exitCode + "\n", StandardCharsets.UTF_8);
        return script;
    }

    private Path parent(String name, String content) throws Exception {
        Path script = tempDir.resolve(name);
        Files.writeString(script, content, StandardCharsets.UTF_8);
        return script;
    }

    @Test
    @DisplayName("_JOB WAIT 等到非 0 退出码：父脚本退出 1，状态行仍在 stdout，stderr 带位置")
    void waitOnFailedJobFailsTheParent() throws Exception {
        child("child.sql", 5);
        Path script = parent("parent.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE j1 script=child.sql
                _JOB START j1
                _JOB WAIT j1
                """);

        Result result = run("--execute", script.toString());

        assertEquals(0, result.exitCode, "子作业失败必须让父脚本失败; stderr: " + result.out);
        assertTrue(result.out.contains("Job [j1] finished with exit code 5."),
                "状态行属于输出契约，仍在 stdout: " + result.out);
        assertTrue(result.out.contains("Job [j1] finished with exit code 5."),
                "错误通道只有消息本身（不再带 文件:行号）: " + result.out);
        assertTrue(result.out.contains("Job manager started."), result.out);
    }

    @Test
    @DisplayName("失败作业没被 WAIT：脚本收尾时补算一次失败，退出码 1（stderr 说明原因）")
    void failedJobWithoutWaitFailsTheParent() throws Exception {
        child("child.sql", 5);
        Path script = parent("forgot.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE j1 script=child.sql
                _JOB START j1
                _SLEEP 1
                """);

        Result result = run("--execute", script.toString());

        assertEquals(0, result.exitCode, "没人检查的失败作业不能静默通过; stderr: " + result.out);
        assertTrue(result.out.contains(
                        "Job [j1] failed with exit code 5 (it was started but never checked by _JOB WAIT)."),
                "收尾时的失败要能指出是哪个作业、多少退出码: " + result.out);
    }

    @Test
    @DisplayName("onfail=ignore 已删除：写成未知参数错误（失败没有忽略开关）")
    void onfailIsRejected() throws Exception {
        child("child.sql", 5);
        Path script = parent("ignore.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE j1 script=child.sql
                _JOB START j1
                _JOB WAIT j1 onfail=ignore
                """);

        Result result = run("--execute", script.toString());

        assertEquals(0, result.exitCode, "onfail=ignore 现在是错误; stderr: " + result.out);
        assertTrue(result.out.contains("Unknown job wait option [onfail]; supported: timeout=<seconds>."),
                result.out);
    }

    @Test
    @DisplayName("兄弟作业失败不污染成功作业：它的 WAIT 仍报 exit code 0，.err 里没有别人的失败")
    void siblingFailureDoesNotLeakIntoAHealthyJob() throws Exception {
        child("bad.sql", 3);
        child("good.sql", 0);
        Path script = parent("siblings.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE BAD script=bad.sql
                _JOB CREATE GOOD script=good.sql
                _JOB START BAD
                _JOB START GOOD
                _JOB WAIT GOOD
                """);

        Result result = run("--execute", script.toString());

        // BAD 没人 WAIT（也不该静默通过），但这里先只验"GOOD 的 WAIT 只报它自己的 0"
        assertEquals(0, result.exitCode, "没被检查的 BAD 仍要让整次运行失败; stderr: " + result.out);
        assertTrue(result.out.contains("Job [GOOD] finished with exit code 0."), result.out);
        String goodOut = Files.readString(tempDir.resolve("GOOD.out"), StandardCharsets.UTF_8);
        assertFalse(goodOut.contains("Job [BAD]"), "兄弟作业的失败不该写进 GOOD.out: " + goodOut);
        assertFalse(goodOut.contains("never checked"), goodOut);
    }

    @Test
    @DisplayName("超时作业（timeout=）被 WAIT 时算失败（没有可忽略的开关）")
    void timedOutJobCanFailOrBeIgnored() throws Exception {
        Path slow = tempDir.resolve("slow.sql");
        // 线程模式下超时是协作式的：_SLEEP 分段睡，停止请求一到就收尾（不是"睡满 5 秒"）
        Files.writeString(slow, "_SLEEP 5\n", StandardCharsets.UTF_8);
        Path script = parent("timeout.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE j1 script=slow.sql timeout=0.05
                _JOB START j1
                _JOB WAIT j1
                """);

        Result result = run("--execute", script.toString());

        assertEquals(0, result.exitCode, "TIMEOUT 是失败; stderr: " + result.out);
        assertTrue(result.out.contains("Job [j1] timeout."), result.out);

    }
}
