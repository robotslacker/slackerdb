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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _SET ECHO | FEEDBACK | TERMOUT} 的契约（SQL*Plus 的三个开关）。
 *
 * <ul>
 *   <li><b>ECHO</b>（默认 on）：把脚本里执行的 SQL/命令原文写进日志（{@code --logfile} / {@code --ref}）；
 *       只对**脚本会话**生效（控制台里敲的行不回显），{@code _connect}/{@code _ssh} 照常回显
 *       （日志要能回看"连到哪、跑了什么"），只有 {@code _echo} 文本块不回显（块体本身就是数据）；</li>
 *   <li><b>FEEDBACK</b>（默认 on）：**摘要与错误**（{@code N rows selected.}、{@code Assert successful.}、
 *       {@code ORA-…}）是否打印；错误计数与退出码不受影响。</li>
 *   <li><b>TERMOUT</b>（默认 on）：结果集是否输出；关掉时**什么都不写**——不上屏，也不进
 *       logfile / {@code _SPOOL}（"这批结果不要"，用于结果不稳定、不适合比对的场景）。</li>
 * </ul>
 */
class CliPrintOptionsTest {

    @Test
    @DisplayName("_SET TIMING ON：一条 SQL 只打一行 elapsed（曾经打了两次：执行层与收尾各一次）")
    void timingPrintsOneLinePerStatement() throws Exception {
        // `_set timing on;` 自己也算一条语句（那时 timing 还没生效，因为它是同一行之后才 on），
        // 因此预期正好两行：select 一行 + 它后面的语句各一行。
        Result result = run("_set timing on;\nselect 1;\n");

        long elapsedLines = java.util.Arrays.stream(result.out.split("\n"))
                .filter(line -> line.contains(" ms elapsed.")).count();
        assertEquals(2, elapsedLines, "每条语句只能有一行耗时: " + result.out);
    }

    @TempDir
    Path tempDir;

    private static final class Result {
        final String out;
        final String err;
        final int exitCode;
        final Path log;

        Result(String out, String err, int exitCode, Path log) {
            this.out = out;
            this.err = err;
            this.exitCode = exitCode;
            this.log = log;
        }

        String logText() throws Exception {
            return log == null || !Files.exists(log) ? "" : Files.readString(log, StandardCharsets.UTF_8);
        }
    }

    private Result run(String scriptText, String... extra) throws Exception {
        Path script = tempDir.resolve("print-options.sql");
        Files.writeString(script, scriptText, StandardCharsets.UTF_8);
        Path log = tempDir.resolve("print-options.out");
        String[] args = new String[extra.length + 4];
        args[0] = "--execute";
        args[1] = script.toString();
        args[2] = "--logfile";
        args[3] = log.toString();
        System.arraycopy(extra, 0, args, 4, extra.length);
        return execute(args, null);
    }

    private Result runConsole(String input) {
        return execute(new String[] {"--logfile", tempDir.resolve("console.out").toString()},
                new ScriptedLineSource(java.util.List.of(input.split("\n", -1))));
    }

    private Result execute(String[] args, ScriptedLineSource lines) {
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
        int exitCode = commandLine.execute(args);
        Path log = Path.of(args[java.util.Arrays.asList(args).indexOf("--logfile") + 1]);
        return new Result(out.toString(), err.toString(), exitCode, log);
    }

    @Test
    @DisplayName("ECHO 默认 on：语句原文进日志，但不进 stdout（stdout 契约不变）")
    void echoGoesToTheLogFileOnly() throws Exception {
        Result result = run("_echo t\nhello\necho off\n_assert 1 = 1;\n");

        assertEquals(0, result.exitCode, result.out);
        assertEquals("hello\nAssert successful.\n", result.out, "stdout 里不该出现被执行的语句");
        assertEquals("hello\n_assert 1 = 1;\nAssert successful.\n", result.logText(),
                "日志 = 回显的语句（在它自己的输出之前）+ 数据输出");
    }

    @Test
    @DisplayName("ECHO off：日志里只剩结果输出（连 `_SET ECHO OFF` 这一行自己也不回显）")
    void echoOffLeavesOnlyResultsInTheLog() throws Exception {
        Result result = run("_set echo off;\n_echo t\nhello\necho off\n_assert 1 = 1;\n");

        assertEquals(0, result.exitCode, result.out);
        assertEquals("hello\nAssert successful.\n", result.out);
        assertEquals(result.out, result.logText(), "ECHO OFF 之后日志与 stdout 相同");
        assertFalse(result.logText().contains("_assert"), result.logText());
        assertFalse(result.logText().contains("_set echo off"), result.logText());
    }

    @Test
    @DisplayName("ECHO 也回显 _connect / _ssh：日志要能回看\"连到哪、跑了什么\"")
    void echoCoversConnectAndSsh() throws Exception {
        Result result = run("_connect 127.0.0.1:1/db1\n_ssh execute \"echo hi\"\n");

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.logText().contains("_connect 127.0.0.1:1/db1"),
                "_connect 必须出现在日志里: " + result.logText());
        assertTrue(result.logText().contains("_ssh execute"),
                "_ssh 必须出现在日志里: " + result.logText());
    }

    @Test
    @DisplayName("ECHO 也作用于控制台：敲的语句同样写进它的 --logfile")
    void echoAppliesToTheConsole() {
        Result console = runConsole("_assert 1 = 1;\n_exit");

        assertEquals(0, console.exitCode, console.out);
        try {
            assertEquals("dbcli (SlackerDB command line client)\n"
                            + "dbcli> _assert 1 = 1;\n"
                            + "Assert successful.\n"
                            + "dbcli> _exit\n",
                    console.logText(), "控制台日志必须与屏幕逐行一致");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    @DisplayName("FEEDBACK off：摘要与错误都不打印，但错误照旧计数（退出码 1）")
    void feedbackOffDropsSummariesAndErrors() throws Exception {
        Result result = run("_set feedback off;\n_assert 1 = 2, bad;\n");

        assertEquals(0, result.exitCode, "断言失败仍然算错误（退出码不受 FEEDBACK 影响）");
        assertEquals("", result.err, "摘要（Assert [bad] fail.）不该出现");
        assertEquals("", result.err, "错误消息也归 FEEDBACK 管，关掉就不打印: " + result.err);
        assertFalse(result.logText().contains("Assert [bad] fail."), result.logText());
    }

    @Test
    @DisplayName("TERMOUT off：结果集既不显示、也不进 logfile / _SPOOL（结果不稳定时不参与比对）")
    void termoutOffDropsResultsEverywhere() throws Exception {
        Result result = run("_set termout off;\n_echo t\nhello\necho off\n");

        assertEquals(0, result.exitCode, result.out);
        assertEquals("", result.err, "结果集不该显示");
        assertEquals("_set termout off;\n", result.logText(),
                "logfile 里也不该有结果行（只留回显的语句）: " + result.logText());
    }

    @Test
    @DisplayName("三项叠加 off：终端与日志都空（无回显、无摘要、无结果、连错误也不说），只剩退出码")
    void allThreeOffLeavesNothing() throws Exception {
        Result result = run("_set echo off;\n_set feedback off;\n_set termout off;\n"
                + "_echo t\nhello\necho off\n_assert 1 = 2, bad;\n");

        assertEquals(0, result.exitCode, "错误照旧计数，退出码 1");
        assertEquals("", result.err, "stdout 为空");
        assertEquals("", result.err, "stderr 也为空（错误属于 FEEDBACK）");
        assertEquals("", result.logText(), "logfile 也为空");
    }

    @Test
    @DisplayName("TIMING / TIME 默认 off：什么诊断行都不出")
    void timingAndTimeAreOffByDefault() throws Exception {
        Result result = run("_assert 1 = 1, ok;\n");

        assertEquals(0, result.exitCode, result.out);
        assertFalse(result.out.contains("elapsed."), "默认不该有耗时行: " + result.out);
        assertFalse(result.out.contains("Current time:"), "默认不该有时间行: " + result.out);
    }

    @Test
    @DisplayName("_SET TIMING ON：每条语句后打 'N minutes N seconds N ms elapsed.'（走 stdout，也进日志）")
    void timingOnPrintsElapsedTime() throws Exception {
        Result result = run("_set timing on;\n_assert 1 = 1, ok;\n");

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.matches("(?s).*\\d+ minutes \\d+ seconds \\d+ ms elapsed\\..*"),
                "耗时的格式必须固定，并且走 stdout: " + result.out);
        assertEquals("", result.err.strip(), "不是错误：不写 stderr: " + result.err);
        assertTrue(result.logText().contains("elapsed."), "日志里也要有: " + result.logText());
    }

    @Test
    @DisplayName("_SET TIME ON：每条语句后打 'Current time: yyyy-MM-dd HH:mm:ss.SSS'（走 stdout，也进日志）")
    void timeOnPrintsCurrentTime() throws Exception {
        Result result = run("_set time on;\n_assert 1 = 1, ok;\n");

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.matches("(?s).*Current time: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*"),
                "时间的格式必须固定，并且走 stdout: " + result.out);
        assertEquals("", result.err.strip(), "不是错误：不写 stderr: " + result.err);
        assertTrue(result.logText().contains("Current time:"), "日志里也要有: " + result.logText());
    }

    @Test
    @DisplayName("TIMING / TIME 也支持 off，且取值写错会给可读错误")
    void timingAndTimeValidateTheirValues() throws Exception {
        Result backOff = run("_set timing on;\n_set timing off;\n_assert 1 = 1, ok;\n");
        assertEquals(0, backOff.exitCode, backOff.out);

        Result bad = run("_set time maybe;\n");
        assertEquals(0, bad.exitCode);
        assertTrue(bad.out.contains("requires ON or OFF"), bad.out);
    }

    @Test
    @DisplayName("DIFF_REPORT_HTML：默认 off 不写 .html，on 才另写一份；.dif 两种情况都写")
    void diffReportHtmlIsOptIn() throws Exception {
        Path work = tempDir.resolve("work.txt");
        Path ref = tempDir.resolve("ref.txt");
        Files.writeString(work, "a\nb\n", StandardCharsets.UTF_8);
        Files.writeString(ref, "a\nX\n", StandardCharsets.UTF_8);

        Result off = run("_compare work.txt ref.txt;\n");
        assertEquals(0, off.exitCode, off.out);
        assertTrue(Files.exists(tempDir.resolve("work.dif")), "DIF 恒定写出");
        assertFalse(Files.exists(tempDir.resolve("work.html")), "默认不写 HTML: " + tempDir);

        Files.delete(tempDir.resolve("work.dif"));
        Result on = run("_set diff_report_html on;\n_compare work.txt ref.txt;\n");
        assertEquals(0, on.exitCode, on.out);
        assertTrue(Files.exists(tempDir.resolve("work.dif")), "DIF 恒定写出");
        assertTrue(Files.exists(tempDir.resolve("work.html")), "ON 时才写 HTML");

        Result bad = run("_set diff_report_html maybe;\n");
        assertEquals(0, bad.exitCode);
        assertTrue(bad.out.contains("requires ON or OFF"), bad.out);
    }

    @Test
    @DisplayName("--silent 与三项叠加的区别：终端同样静默，文件则各按 --logfile 的开关走")
    void silentIsComplementaryToTheThreeSwitches() throws Exception {
        // --silent 只关终端；这里文件里之所以也空，是因为三项开关把它们逐类关掉了
        Result result = run("_set echo off;\n_set feedback off;\n_set termout off;\n"
                + "_echo t\nhello\necho off\n_assert 1 = 2, bad;\n", "--silent");

        assertEquals(0, result.exitCode);
        assertEquals("", result.err);
        assertEquals("", result.err, "--silent 连 stderr 也静默");
        assertEquals("", result.logText(), "三项也关着，所以文件里同样没有内容");
    }
}
