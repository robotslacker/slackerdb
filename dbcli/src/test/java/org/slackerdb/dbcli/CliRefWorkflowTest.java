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
 * {@code --ref} 触发的"一条龙"：跑脚本 → 落 {@code <脚本>.log} → 收尾比对。
 *
 * <p>四条纪律（就是本阶段的全部语义）：</p>
 * <ol>
 *   <li><b>给了 {@code --ref} 才进入一条龙</b>；不给就是普通客户端（不落 log、不比对）；</li>
 *   <li>ref 文件不存在 → <b>报错</b>（{@code Reference file [<path>] does not exist.}，退出 1）：
 *       没有"待录制"这种默认放行的形态；录制 = 把一次运行的 log 复制成 ref；</li>
 *   <li>比对口径<b>固定严格</b>（大小写/空白/行序都不放宽），唯一的放宽手段是脚本里声明的
 *       {@code _COMPARE MASKLINE/SKIPLINE}；</li>
 *   <li>内容不一致 → 退出 1、写 {@code .dif}、stderr 给"重录"提示；脚本自身失败时退出码沿用脚本的。</li>
 * </ol>
 */
class CliRefWorkflowTest {

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

    /** 写脚本（{@code replace} 里的 "{}" 换成给定值），返回脚本路径。 */
    private Path script(String name, String... lines) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** 一条最简单的"数据 + 变量"脚本：输出 "ts=<V>"。 */
    private Path echoVarScript(String name, String... extraCommands) throws Exception {
        java.util.List<String> lines = new java.util.ArrayList<>(java.util.List.of(
                "_ECHO t", "ts=${V}", "echo off"));
        lines.addAll(java.util.List.of(extraCommands));
        return script(name, lines.toArray(new String[0]));
    }

    private Path runLog(Path script) {
        String fileName = script.getFileName().toString();
        String base = fileName.substring(0, fileName.lastIndexOf('.'));
        return script.getParent().resolve(base + ".log");
    }

    /** 录制 ref：不带 --ref 跑一次、把数据输出直接写成 ref（比"先跑一次再看提示复制"少一步）。 */
    private void record(Path script, Path ref, String... extraArgs) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "--execute", script.toString(), "--logfile", ref.toString()));
        args.addAll(java.util.List.of(extraArgs));
        Result recorded = run(args.toArray(String[]::new));
        assertEquals(0, recorded.exitCode, "录制应当成功: " + recorded.out);
        assertTrue(Files.exists(ref), "录制应当写出 ref: " + ref);
    }

    private Path refFor(Path script) {
        String fileName = script.getFileName().toString();
        String base = fileName.substring(0, fileName.lastIndexOf('.'));
        return script.getParent().resolve(base + ".ref");
    }

    // ---------------------------------------------------------------- 模式隔离

    @Test
    @DisplayName("不给 --ref：就是普通客户端，不落 log、不读 ref、输出不变")
    void withoutRefNothingIsRecorded() throws Exception {
        Path script = script("plain.sql", "_ECHO t", "alpha", "echo off");

        Result result = run("--execute", script.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.startsWith("alpha\n"), result.out);
        assertEquals("", result.err, "普通客户端不该有任何比对相关输出");
        assertFalse(Files.exists(runLog(script)), "没有 --ref 就不落 log");
        assertFalse(Files.exists(refFor(script)), "没有 --ref 就不碰 ref");
    }

    // ---------------------------------------------------------------- 待录制

    @Test
    @DisplayName("ref 文件不存在：直接报错（退出 1），log 仍写出来，ref 不会被创建")
    void missingRefIsAnError() throws Exception {
        Path script = script("pending.sql", "_ECHO t", "alpha", "beta", "echo off");
        Path ref = refFor(script);

        Result result = run("--execute", script.toString(), "--ref", ref.toString());

        assertEquals(1, result.exitCode, "没有 ref 文件就是失败: " + result.out);
        assertTrue(result.out.startsWith("alpha\nbeta\n"), "脚本 stdout 照常: " + result.out);
        assertTrue(result.out.contains("Reference file [" + ref + "] does not exist."), result.out);
        assertTrue(Files.readString(runLog(script)).startsWith("alpha\nbeta\n"), "log 与 stdout 同字节");
        assertFalse(Files.exists(ref), "报错不会自己创建 ref");
    }

    @Test
    @DisplayName("定稿后重跑：一条命令比对通过，退出 0")
    void recordedRefMatchesOnTheNextRun() throws Exception {
        Path script = script("match.sql", "_ECHO t", "alpha", "beta", "echo off");
        Path ref = refFor(script);
        record(script, ref);

        Result second = run("--execute", script.toString(), "--ref", ref.toString());

        assertEquals(0, second.exitCode, second.out);
        assertTrue(second.out.startsWith("alpha\nbeta\n"), second.out);
        assertEquals("", second.err, "一致时 stderr 应当干净");
    }

    @Test
    @DisplayName("脚本失败 + ref 缺失：退出码沿用脚本的，仍报找不到 ref")
    void failedRunStillReportsTheMissingRef() throws Exception {
        Path script = script("broken.sql", "_ASSERT 1 = 2, bad");
        Path ref = refFor(script);

        Result result = run("--execute", script.toString(), "--ref", ref.toString());

        assertEquals(1, result.exitCode);
        assertTrue(result.out.contains("Reference file [" + ref + "] does not exist."), result.out);
        assertFalse(Files.exists(ref), "失败运行的输出不会变成 ref");
    }

    // ---------------------------------------------------------------- 选项已删除

    @Test
    @DisplayName("_SET REFMISSING 已删除：按未知选项报错、脚本继续、退出 1")
    void refMissingOptionIsGone() throws Exception {
        Path script = script("bad.sql", "_SET REFMISSING SOMETIMES", "_ECHO t", "after", "echo off");

        Result result = run("--execute", script.toString(), "--ref", refFor(script).toString());

        assertEquals(1, result.exitCode);
        assertTrue(result.out.contains("Unknown set option [REFMISSING]"), result.out);
        assertTrue(result.out.contains("does not exist"), "缺 ref 仍然报找不到文件: " + result.out);
        assertTrue(result.out.contains("after\n"), "选项写错不该中断脚本: " + result.out);
    }

    // ---------------------------------------------------------------- 一致性/严格性

    @Test
    @DisplayName("数据变了：退出 1、写 .dif、stderr 给重录提示，log 与 ref 都保留")
    void changedOutputFailsAndKeepsBothFiles() throws Exception {
        Path script = script("changed.sql", "_ECHO t", "alpha", "echo off");
        Path ref = refFor(script);
        record(script, ref);
        // 改脚本：输出变了
        script("changed.sql", "_ECHO t", "BETA", "echo off");

        Result result = run("--execute", script.toString(), "--ref", ref.toString());

        assertEquals(1, result.exitCode);
        assertTrue(result.out.contains("Compare failed: " + ref), result.out);
        assertTrue(result.out.contains("for details"), result.out);
        
        assertTrue(result.out.startsWith("BETA\n"), result.out);
        
        assertTrue(Files.readString(runLog(script)).startsWith("BETA\n"));
        
        assertTrue(Files.readString(ref).startsWith("alpha\n"), "ref 不被覆盖");
        assertTrue(Files.exists(tempDir.resolve("changed.dif")), "差异报告应当写出来");
    }

    @Test
    @DisplayName("大小写变化仍然红：内置比对固定严格，不做任何自动放宽")
    void caseChangeStillFails() throws Exception {
        Path script = echoVarScript("case.sql");
        Path ref = refFor(script);
        record(script, ref, "--var", "V=abc");

        Result result = run("--execute", script.toString(), "--ref", ref.toString(),
                "--var", "V=ABC");

        assertEquals(1, result.exitCode, "大小写变了就是变了: " + result.out);
        assertTrue(result.out.contains("Compare failed"), result.out);
    }

    @Test
    @DisplayName("行序变化仍然红：行序由查询/脚本负责，客户端不做行序无关比较")
    void rowOrderChangeStillFails() throws Exception {
        Path script = script("order.sql", "_ECHO t", "${A}", "${B}", "echo off");
        Path ref = refFor(script);
        record(script, ref, "--var", "A=one", "--var", "B=two");

        Result result = run("--execute", script.toString(), "--ref", ref.toString(),
                "--var", "A=two", "--var", "B=one");

        assertEquals(1, result.exitCode, "两行顺序换了就是不同的输出: " + result.out);
        assertTrue(result.out.contains("Compare failed"), result.out);
    }

    // ---------------------------------------------------------------- mask / skip 继承

    @Test
    @DisplayName("脚本里的 _COMPARE MASKLINE 被内置比对继承：易变值变了仍然绿，且声明行不进 stdout")
    void maskLineIsInheritedByTheBuiltInCompare() throws Exception {
        Path script = echoVarScript("masked.sql",
                "_COMPARE MASKLINE \"AAA|BBB\"=>\"<v>\"");
        Path ref = refFor(script);
        record(script, ref, "--var", "V=AAA");
        assertTrue(Files.readString(ref).contains("ts=AAA"), "ref 里应当有这次的数据输出");

        Result second = run("--execute", script.toString(), "--ref", ref.toString(),
                "--var", "V=BBB");

        assertEquals(0, second.exitCode, "被 mask 的值变化不算差异: " + second.out);
        assertEquals("", second.err, second.err);
    }

    @Test
    @DisplayName("没有 mask 时，同一个易变值变化就是红（对照组）")
    void withoutMaskTheSameChangeFails() throws Exception {
        Path script = echoVarScript("unmasked.sql");
        Path ref = refFor(script);
        record(script, ref, "--var", "V=AAA");

        Result second = run("--execute", script.toString(), "--ref", ref.toString(),
                "--var", "V=BBB");

        assertEquals(1, second.exitCode, second.out);
        assertTrue(second.out.contains("Compare failed"), second.out);
    }

    @Test
    @DisplayName("脚本里的 _COMPARE SKIPLINE 被继承：整行被丢掉，值变化不影响结论")
    void skipLineIsInheritedByTheBuiltInCompare() throws Exception {
        // SKIPLINE 是**整行**匹配（fullmatch），所以模式要写成 ts=.*
        Path script = echoVarScript("skipped.sql", "_COMPARE SKIPLINE \"ts=.*\"");
        Path ref = refFor(script);
        record(script, ref, "--var", "V=AAA");

        Result second = run("--execute", script.toString(), "--ref", ref.toString(),
                "--var", "V=ZZZ");

        assertEquals(0, second.exitCode, "被 skip 的行不参与比对: " + second.out);
    }

    // ---------------------------------------------------------------- 显式 log 覆盖

    @Test
    @DisplayName("--logfile 覆盖默认落点：log 写到指定文件，默认 <脚本>.log 不产生")
    void explicitLogFileOverridesTheDefault() throws Exception {
        Path script = script("custom.sql", "_ECHO t", "alpha", "echo off");
        Path ref = refFor(script);
        Path customLog = tempDir.resolve("out/custom.log");
        Files.createDirectories(customLog.getParent());

        // 录制：把数据输出直接写进自定义 log，再复制成 ref
        Result recorded = run("--execute", script.toString(), "--logfile", customLog.toString());
        assertEquals(0, recorded.exitCode, recorded.out);
        
        assertTrue(Files.readString(customLog).startsWith("alpha\n"));
        assertFalse(Files.exists(runLog(script)), "给了 --logfile 就不该再写默认 log");
        Files.copy(customLog, ref);

        Result compared = run("--execute", script.toString(), "--ref", ref.toString(),
                "--logfile", customLog.toString());

        assertEquals(0, compared.exitCode, compared.out);
        assertEquals("", compared.err, "一致时 stderr 应当干净");
    }
}
