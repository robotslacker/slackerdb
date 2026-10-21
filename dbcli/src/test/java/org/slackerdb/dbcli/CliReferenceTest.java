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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --reference} 的命令行级（端到端）测试：stdout 契约不变 + 三份产物 + 退出码。
 *
 * <p>脚本只用 {@code _echo}/{@code _sleep} 之类**不碰数据库**的命令，所以整个测试没有任何外部依赖。
 * 三份产物的格式契约在 {@code report.ReferenceReportTest}；这里管的是"接线"：
 * 选项 → 实际 stdout 的 tee → 参考文件比对 → 退出码。</p>
 */
class CliReferenceTest {

    @TempDir
    Path tempDir;

    private static final class Result {
        final String out;
        final String err;
        final int exitCode;

        Result(String out, String err, int exitCode) {
            this.out = out;
            this.err = err;
            this.exitCode = exitCode;
        }
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

    /** 造一个只回显两行的脚本（输出 = "alpha\nbeta\n"）。 */
    private Path script() throws Exception {
        Path script = tempDir.resolve("case.sql");
        Files.writeString(script, "_echo t\nalpha\nbeta\necho off\n", StandardCharsets.UTF_8);
        return script;
    }

    private Path reference(String name, String content) throws Exception {
        Path reference = tempDir.resolve(name);
        Files.writeString(reference, content, StandardCharsets.UTF_8);
        return reference;
    }

    /** 产物名跟着**工作文件**走（脚本是 {@code case.sql}、log 是 {@code case.log}），所以固定是 {@code case*}。 */
    private Path sibling(String referenceName, String extension) {
        return tempDir.resolve("case" + extension);
    }
    @Test
    @DisplayName("一致：stdout 不变、退出 0、写 .suc、不写 .dif，也不写 .rpt")
    void matchingReferenceWritesSuccess() throws Exception {
        Path script = script();
        Path reference = reference("case1.ref", "alpha\nbeta\n");

        Result result = run("--execute", script.toString(), "--reference", reference.toString());

        assertTrue(result.out.startsWith("alpha\nbeta\n"), "脚本输出照常，消息追加在后面: " + result.out);
        assertEquals("", result.err, "dbcli 从不写 stderr");
        assertEquals(0, result.exitCode);

        assertEquals("SUCCESS\tcase.sql\t" + reference + "\n",
                Files.readString(sibling("case1.ref", ".suc"), StandardCharsets.UTF_8));

        assertFalse(Files.exists(sibling("case1.ref", ".dif")), "一致时不写 .dif");
        assertFalse(Files.exists(sibling("case1.ref", ".rpt")), "没有 .rpt 这回事了");
    }

    @Test
    @DisplayName("不一致：stdout 与一致时逐字节相同、退出 1、写 .dif、stderr 给重录提示")
    void mismatchingReferenceExitsOne() throws Exception {
        Path script = script();
        Path reference = reference("case2.ref", "alpha\ngamma\n");

        Result result = run("--execute", script.toString(), "--reference", reference.toString());

        assertTrue(result.out.startsWith("alpha\nbeta\n"), "脚本输出照常，比对消息追加在后面: " + result.out);
        assertEquals(1, result.exitCode, "golden 不一致 ⇒ 退出 1（与 _COMPARE 失败一致）");
        assertTrue(result.out.contains("Compare failed: " + reference), result.out);
        assertTrue(result.out.contains(".dif") && result.out.contains("for details"),
                "失败只给结论 + 一句“看 .dif”: " + result.out);
        assertFalse(result.out.contains("re-record"), "不再提示重录: " + result.out);
        assertFalse(Files.exists(sibling("case2.ref", ".suc")), "不一致时不写 .suc");

        String diff = Files.readString(sibling("case2.ref", ".dif"), StandardCharsets.UTF_8);
        // 与 _COMPARE 的 .dif 完全同形：完整报告行（相同行也写出来）+ CRLF 行尾
        assertEquals("      1 alpha\r\n-     2 beta\r\n+     2 gamma\r\n", diff,
                ".dif 复用 _COMPARE 的格式");

        assertFalse(Files.exists(sibling("case2.ref", ".rpt")), "没有 .rpt 这回事了");
    }

    @Test
    @DisplayName("--reference 不改变 stdout/退出码：有它与没它的两次运行输出逐字节一致")
    void referenceOptionDoesNotChangeStdout() throws Exception {
        Path script = script();
        Path reference = reference("case3.ref", "alpha\nbeta\n");

        Result plain = run("--execute", script.toString());
        Result withReference = run("--execute", script.toString(), "--reference", reference.toString());

        assertEquals(plain.out, withReference.out);
        assertEquals(plain.out, withReference.out);
        assertEquals(plain.exitCode, withReference.exitCode);
    }

    @Test
    @DisplayName("脚本自身失败的退出码不会被比对结果盖掉：脚本 1 + 不一致仍然是 1")
    void scriptExitCodeIsKept() throws Exception {
        Path script = tempDir.resolve("fail.sql");
        Files.writeString(script, "_assert 1 = 2, bad\n", StandardCharsets.UTF_8);
        Path reference = reference("case4.ref", "anything\n");

        Result result = run("--execute", script.toString(), "--reference", reference.toString());

        assertEquals(1, result.exitCode);
        assertTrue(result.out.contains("Assert [bad] fail."), result.out);
        assertFalse(Files.exists(sibling("case4.ref", ".rpt")), "没有 .rpt 这回事了");
    }

    @Test
    @DisplayName("参考文件不存在：**报错**（退出 1、脚本照跑、log 照写、绝不自动创建 ref、不写产物）")
    void missingReferenceIsAnError() throws Exception {
        Path script = script();
        Path missing = tempDir.resolve("nope.ref");

        Result result = run("--execute", script.toString(), "--reference", missing.toString());

        assertEquals(1, result.exitCode, "没有 ref 文件就是失败: " + result.out);
        assertTrue(result.out.startsWith("alpha\nbeta\n"), "脚本照常执行，消息追加在后面: " + result.out);
        assertTrue(result.out.contains("Reference file [" + missing + "] does not exist."), result.out);
        // --ref 隐含的 log：<脚本名去扩展名>.log，与脚本同目录
        assertTrue(Files.exists(tempDir.resolve("case.log")), "应当写出这次运行的 log");
        
        assertTrue(Files.readString(tempDir.resolve("case.log")).startsWith("alpha\nbeta\n"), "log 与 stdout 同字节");
        assertFalse(Files.exists(tempDir.resolve("nope.suc")), "没比对就不写任何产物");
        assertFalse(Files.exists(missing), "ref 必须已经存在：客户端从不创建它");
        assertFalse(Files.exists(tempDir.resolve("nope.dif")), "不写 .dif");
    }

    @Test
    @DisplayName("参考文件所在目录不存在：报错（路径写错不能被当成'还没定稿'）")
    void missingReferenceDirectoryIsAnError() throws Exception {
        Path script = script();
        Path missing = tempDir.resolve("no-such-dir/deeper.ref");

        Result result = run("--execute", script.toString(), "--reference", missing.toString());

        assertEquals(1, result.exitCode);
        assertTrue(result.out.contains("Reference directory ["), result.out);
        assertTrue(result.out.contains("does not exist."), result.out);
        assertFalse(Files.exists(tempDir.resolve("case.log")), "参数不成立时不该留下 log");
    }

    @Test
    @DisplayName("--report-dir：产物进该目录（自动创建），golden 目录不被写入")
    void reportDirKeepsTheGoldenDirectoryClean() throws Exception {
        Path script = script();
        Path reference = reference("case6.ref", "alpha\nbeta\n");
        Path reportDirectory = tempDir.resolve("reports");

        Result result = run("--execute", script.toString(), "--reference", reference.toString(),
                "--report-dir", reportDirectory.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(Files.exists(reportDirectory.resolve("case.suc")), "产物应在 --report-dir 下");
    }

    @Test
    @DisplayName("--report-dir 没配 --reference：用法错误（退出 2），脚本不跑")
    void reportDirRequiresReference() throws Exception {
        Path script = script();

        Result result = run("--execute", script.toString(),
                "--report-dir", tempDir.resolve("reports").toString());

        assertEquals(2, result.exitCode);
        assertEquals("", result.err, "参数不成立时脚本不应执行");
        assertTrue(result.out.contains("--report-dir requires --reference."), result.out);
    }

    @Test
    @DisplayName("stdin 形态（--execute -）：没有脚本名可派生 log，必须显式 --logfile；给了就照常写产物")
    void stdinFormNeedsAnExplicitLogFile() throws Exception {
        Path reference = reference("case5.ref", "from-stdin\n");
        Path runLog = tempDir.resolve("stdin.log");

        Result withoutLog = runStdin("--execute", "-", "--reference", reference.toString());
        assertEquals(2, withoutLog.exitCode, "没有 --logfile 就无法给 stdin 派生 log: " + withoutLog.out);
        assertTrue(withoutLog.out.contains("--ref needs a script to name the run log"), withoutLog.out);

        Result withLog = runStdin("--execute", "-", "--reference", reference.toString(),
                "--logfile", runLog.toString());
        assertEquals(0, withLog.exitCode, withLog.out);
        assertEquals("from-stdin\n", withLog.out);
        assertEquals("from-stdin\n", Files.readString(runLog), "log 就是这次运行的数据输出");
        // 产物名跟着**工作文件**走：这里 log 是 stdin.log，所以产物是 stdin.suc
        assertEquals("SUCCESS\t<stdin>\t" + reference + "\n",
                Files.readString(tempDir.resolve("stdin.suc"), StandardCharsets.UTF_8));
    }

    /** 跑一次 stdin 形态（脚本来自 stdin）。 */
    private Result runStdin(String... args) {
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream("_echo t\nfrom-stdin\necho off\n"
                .getBytes(StandardCharsets.UTF_8)));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true)); // dbcli 从不写 stderr：参数错误也在 stdout
        int exitCode = commandLine.execute(args);
        return new Result(out.toString(), err.toString(), exitCode);
    }
}
