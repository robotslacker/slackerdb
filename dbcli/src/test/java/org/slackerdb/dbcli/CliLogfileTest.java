package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.repl.ScriptedLineSource;
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
 * {@code --logfile} 的命令行级测试：**它写的就是这次运行的数据输出**（与 stdout 相同的内容），
 * 交互式形态下记的是当前会话的结果，并且它是 {@code --reference} 的"实际输出文件"。
 *
 * <p>脚本只用 {@code _echo}/{@code _assert} 这类不碰数据库的命令，所以整个测试没有外部依赖。</p>
 */
class CliLogfileTest {

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
        return run(null, args);
    }

    /** 跑一次命令行；{@code lines} 非 null 时注入控制台输入（无 TTY 也能跑控制台）。 */
    private Result run(ScriptedLineSource lines, String... args) {
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream(new byte[0]));
        if (lines != null) {
            cli.setLineSource(lines);
        }
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true)); // dbcli 从不写 stderr：参数错误也在 stdout
        // 先执行再取字符串：否则拿到的是执行前的空流
        int exitCode = commandLine.execute(args);
        return new Result(out.toString(), err.toString(), exitCode);
    }

    private Path script(String name, String text) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("--logfile：内容 = 被执行语句的原文（默认 ECHO ON）+ stdout（数据 + 状态行）+ 错误行")
    void logFileIsStdoutPlusTheEchoedStatements() throws Exception {
        Path script = script("data.sql", "_echo t\nhello\necho off\n_assert 1 = 2, bad\n");
        Path log = tempDir.resolve("run.out");

        Result plain = run("--execute", script.toString());
        Result logged = run("--execute", script.toString(), "--logfile", log.toString());

        assertEquals(plain.out, logged.out, "stdout 不得因为 --logfile 而变化");
        assertEquals("", logged.err, "dbcli 从不写 stderr");
        assertEquals(plain.exitCode, logged.exitCode);
        assertEquals(0, logged.exitCode, "脚本里有失败断言");
        // 日志 = 被执行的语句原文（ECHO ON，`_echo` 文本块除外）+ 这次运行的数据输出 + 错误行
        String loggedText = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(loggedText.contains("hello"), "日志里有回显与数据: " + loggedText);
        assertTrue(loggedText.contains("Assert [bad] fail."), "日志里有错误行: " + loggedText);
        assertTrue(logged.out.startsWith("hello\nAssert [bad] fail.\n"), "stdout 里没有回显: " + logged.out);
    }

    @Test
    @DisplayName("--logfile 与 --reference 同时给出：日志就是比对对象，一致 → 退出 0 + .suc")
    void logFileIsTheActualOutputForReference() throws Exception {
        Path script = script("match.sql", "_echo t\nalpha\nbeta\necho off\n");
        Path log = tempDir.resolve("actual.out");
        Path reference = tempDir.resolve("match.ref");
        Files.writeString(reference, "alpha\nbeta\n", StandardCharsets.UTF_8);

        Result result = run("--execute", script.toString(),
                "--logfile", log.toString(), "--reference", reference.toString());

        assertEquals(0, result.exitCode, "stderr: " + result.out);
        assertTrue(result.out.startsWith("alpha\nbeta\n"), "脚本输出照常: " + result.out);
        assertEquals(result.out, Files.readString(log, StandardCharsets.UTF_8), "log 与 stdout 同字节");
        // 产物名跟着**工作文件**（这里 log 是 actual.out）走
        assertTrue(Files.exists(tempDir.resolve("actual.suc")), "一致时写 .suc");
    }

    @Test
    @DisplayName("--logfile 与 --reference 不一致：退出 1，日志照旧写全，.dif 产出")
    void logFileStillWrittenWhenTheReferenceDiffers() throws Exception {
        Path script = script("diff.sql", "_echo t\nalpha\nbeta\necho off\n");
        Path log = tempDir.resolve("actual2.out");
        Path reference = tempDir.resolve("diff.ref");
        Files.writeString(reference, "alpha\ngamma\n", StandardCharsets.UTF_8);

        Result result = run("--execute", script.toString(),
                "--logfile", log.toString(), "--reference", reference.toString());

        assertEquals(1, result.exitCode, "golden 不一致 ⇒ 退出 1");
        assertTrue(result.out.startsWith("alpha\nbeta\n"), "脚本输出照常: " + result.out);
        String logText = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(logText.contains("alpha\nbeta\n"), "log 记的是这次运行: " + logText);
        assertTrue(result.out.contains("Compare failed: "), "比对结论在 stdout（tee 已关，不进 log）: " + result.out);
        assertFalse(Files.exists(tempDir.resolve("actual2.suc")), "不一致时不写 .suc");
        // 产物名跟着**工作文件**（这里 log 是 actual2.out）走
        assertEquals("      1 alpha\r\n-     2 beta\r\n+     2 gamma\r\n",
                Files.readString(tempDir.resolve("actual2.dif"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("--logfile 的目录不存在：stderr 报可读错误 + 退出 1，stdout 为空（脚本不跑）")
    void missingLogDirectoryIsReported() throws Exception {
        Path script = script("x.sql", "_assert 1 = 1;\n");

        Result result = run("--execute", script.toString(),
                "--logfile", tempDir.resolve("no-such-dir/run.out").toString());

        assertEquals(1, result.exitCode);
        assertEquals("", result.err);
        assertTrue(result.out.contains("Cannot write the log file ["), result.out);
    }

    @Test
    @DisplayName("交互式形态：--logfile 记录**当前会话**的操作结果（会话里的每一行都写进去）")
    void logFileRecordsTheConsoleSession() throws Exception {
        Path log = tempDir.resolve("console.out");

        Result result = run(new ScriptedLineSource(List.of("_echo t", "from-console", "echo off",
                        "_assert 1 = 1;")),
                "--logfile", log.toString());

        assertEquals(0, result.exitCode, "stderr: " + result.out);
        String written = Files.readString(log, StandardCharsets.UTF_8);
        // 控制台日志是"所见即所得"的转录：横幅、提示符、敲进去的行、输出，逐行都在
        assertEquals("dbcli (SlackerDB command line client)\n"
                        + "dbcli> _echo t\n"
                        + "   > from-console\n"
                        + "   > echo off\n"
                        + "from-console\n"
                        + "dbcli> _assert 1 = 1;\n"
                        + "Assert successful.\n",
                written, "控制台日志必须与屏幕逐行一致");
    }

    @Test
    @DisplayName("脚本失败（退出 1）时 --logfile 照旧写全=回显语句 + 数据输出 + 错误行")
    void logFileIsWrittenEvenWhenTheScriptFails() throws Exception {
        Path script = script("fail.sql", "_echo t\nbefore-failure\necho off\n_assert 1 = 2, bad\n");
        Path log = tempDir.resolve("failed.out");

        Result result = run("--execute", script.toString(), "--logfile", log.toString());

        assertEquals(0, result.exitCode);
        String failedText = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(failedText.contains("before-failure"), "日志里有回显与数据: " + failedText);
        assertTrue(failedText.contains("Assert [bad] fail."), "日志里有错误行: " + failedText);
        assertTrue(result.out.startsWith("before-failure\nAssert [bad] fail.\n"), "stdout 里没有回显: " + result.out);
    }

    @Test
    @DisplayName("--silent 只静默终端：日志里回显与错误行照旧完整")
    void silentStillWritesTheFullLog() throws Exception {
        Path script = script("quiet.sql", "_assert 1 = 2, bad\n");
        Path log = tempDir.resolve("quiet.out");

        Result result = run("--execute", script.toString(), "--logfile", log.toString(), "--silent");

        assertEquals(0, result.exitCode);
        assertEquals("", result.err, "--silent 时 stdout 为空");
        assertEquals("", result.err, "--silent 时 stderr 也为空");
        // 失败行只出现一次：它是"状态行"同时也是"错误"，同一个流（dbcli 的标准输出）上只打一遍
        assertEquals("_assert 1 = 2, bad\nAssert [bad] fail.\n",
                Files.readString(log, StandardCharsets.UTF_8),
                "文件产物不受 --silent 影响：回显 + 状态行都在，且失败行只出现一次");
    }

    @Test
    @DisplayName("失败状态行只打印一次（曾经在 stdout 上出现两遍）")
    void failingStatusIsPrintedOnce() throws Exception {
        Path script = script("once.sql",
                "_assert 1 = 2, bad\n_compare missing-a.txt missing-b.txt\n");

        Result result = run("--execute", script.toString());

        assertEquals(0, result.exitCode);
        assertEquals(1, occurrences(result.out, "Assert [bad] fail."),
                "失败断言只出现一次: " + result.out);
        // 第二条是 compare 的"文件不存在"错误（普通错误，不是状态行），不受影响
        assertTrue(result.out.contains("ERROR: File missing-a.txt does not exist!"), result.out);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
    @Test
    @DisplayName("_SPOOL 与 --logfile 的 log 是同一份内容（数据 / 状态 / 回显 / 错误都在）")
    void spoolContentMatchesTheLog() throws Exception {
        Path script = script("spool-parity.sql",
                "_spool run.txt\n_echo t\nhello\necho off\n_assert 1 = 2, bad\n_spool off\n");
        Path log = tempDir.resolve("spool-parity.out");

        Result result = run("--execute", script.toString(), "--logfile", log.toString());

        assertEquals(0, result.exitCode, result.out);
        String spooled = Files.readString(tempDir.resolve("run.txt"), StandardCharsets.UTF_8);
        assertTrue(spooled.contains("hello"), "数据进 spool: " + spooled);
        assertTrue(spooled.contains("_assert 1 = 2, bad"), "回显的语句进 spool: " + spooled);
        assertTrue(spooled.contains("Assert [bad] fail."), "错误进 spool: " + spooled);
        String logged = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(logged.contains("hello") && logged.contains("_assert 1 = 2, bad")
                        && logged.contains("Assert [bad] fail."),
                "日志里有同样的内容: " + logged);
    }

    @Test
    @DisplayName("--logfile 的相对路径按**进程当前目录**解析（脚本里的路径才跟脚本所在目录）")
    void relativeLogFileIsResolvedAgainstTheProcessDirectory() throws Exception {
        Path script = script("relative.sql", "_echo t\nrelative-log\necho off\n");
        Path processDir = Path.of("").toAbsolutePath();
        Path written = processDir.resolve("dbcli-relative-log-test.out");

        try {
            Result result = run("--execute", script.toString(), "--logfile", written.getFileName().toString());

            assertEquals(0, result.exitCode, "stderr: " + result.out);
            assertTrue(Files.exists(written), "相对 --logfile 应落在进程当前目录: " + written);
            assertEquals(result.out, Files.readString(written, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(written);
        }
    }

    @Test
    @DisplayName("帮助里宣传了 --logfile/--reference/--profile，且不再有 --logfile-timing")
    void helpListsNewOptions() {
        Result result = run("--help");

        assertEquals(0, result.exitCode);
        for (String option : new String[] {"--logfile", "--reference", "--profile"}) {
            assertTrue(result.out.contains(option), "帮助里应有 " + option + ": " + result.out);
        }
        assertFalse(result.out.contains("--logfile-timing"),
                "--logfile-timing 已删除，帮助里不得再出现: " + result.out);
        assertFalse(result.out.contains("TSV"), "语句级 TSV 日志已删除: " + result.out);
        assertFalse(result.out.contains("not implemented yet"),
                "控制台已实现，帮助里不得再写未实现: " + result.out);
    }
}
