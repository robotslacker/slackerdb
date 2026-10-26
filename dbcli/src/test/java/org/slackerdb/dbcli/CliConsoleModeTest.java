package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.repl.ScriptedLineSource;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交互式与批处理必须行为一致。
 *
 * <p>做法是把**同一个脚本**用两种方式跑：{@code --execute <file>} 与"把文件的每一行逐行喂给控制台"
 * （通过 {@code Cli.setLineSource} 注入预置输入行，因此不需要 TTY），然后逐字节比较：</p>
 * <ol>
 *   <li>stdout（数据通道）；</li>
 *   <li>{@code --logfile} 内容（它就是这次运行的数据输出，两种跑法因此也必须一致）；</li>
 *   <li>stderr（错误通道）与退出码。</li>
 * </ol>
 *
 * <p>脚本只用不需要数据库的命令，因此这是 hermetic 的；真实 SQL 的行为一致性由 gated 的
 * {@code ScriptEngineIntegrationTest} 覆盖。</p>
 *
 * <p><b>注意</b>：控制台只在 {@code System.console() != null}（或注入了取行来源）时才进入，
 * 没有"强制进控制台"的开关；这里全部走注入路线，因此测试不依赖 TTY。</p>
 */
class CliConsoleModeTest {

    @TempDir
    Path tempDir;

    /** 两种跑法都要能逐字节比对的最小脚本（含数据行、状态行、错误、多行语句与一行多语句）。 */
    private static final String SCRIPT = """
            _set @who world
            _echo title
            hello ${who}
            echo off
            _assert 1 = 1;
            _assert 1 = 2, check
            _assert 2 = 2; _assert 3 = 3;
            """;


    private static final class Result {
        final String out;
        final String err;
        final int exitCode;
        final Path logFile;

        Result(String out, String err, int exitCode, Path logFile) {
            this.out = out;
            this.err = err;
            this.exitCode = exitCode;
            this.logFile = logFile;
        }

        String log() throws Exception {
            return Files.readString(logFile);
        }
    }

    /** 批处理：把脚本写成文件再 {@code --execute}。 */
    private Result runBatch(String script, String... extra) throws Exception {
        Path file = tempDir.resolve("parity.sql");
        Files.writeString(file, script);
        Path log = tempDir.resolve("batch.out");
        List<String> args = new ArrayList<>(List.of("--execute", file.toString(),
                "--logfile", log.toString()));
        args.addAll(List.of(extra));
        return run(args, null);
    }

    /** 交互式：把同一个脚本按行喂给控制台（注入取行来源，不需要 TTY）。 */
    private Result runConsole(String script, String... extra) {
        Path log = tempDir.resolve("console.out");
        List<String> args = new ArrayList<>(List.of("--logfile", log.toString()));
        args.addAll(List.of(extra));
        return run(args, scriptedLines(script));
    }

    /** 脚本文本 → 逐行输入（文本块结尾的换行不算一行，与批处理的切行口径一致）。 */
    private static ScriptedLineSource scriptedLines(String script) {
        List<String> lines = List.of(script.split("\n", -1));
        int end = lines.get(lines.size() - 1).isEmpty() ? lines.size() - 1 : lines.size();
        return new ScriptedLineSource(lines.subList(0, end));
    }

    /**
     * {@link #SCRIPT} 里会被 ECHO 回显的语句（{@code _echo} 文本块不回显：块体本身就是数据）。
     *
     * <p>批处理（脚本会话）默认 {@code _SET ECHO ON}，所以这些行会出现在它的 log 里；
     * 控制台形态不产生它们。</p>
     */
    private static final List<String> ECHOED_STATEMENTS = List.of(
            "_set @who world",
            "_assert 1 = 1;",
            "_assert 1 = 2, check",
            "_assert 2 = 2;",
            "_assert 3 = 3;");

    /** 去掉若干整行（用于把 log 里的"输入回显"剥掉，再与数据输出比对）。 */
    private static String withoutLines(String text, List<String> lines) {
        return Arrays.stream(text.split("\n", -1))
                .filter(line -> !lines.contains(line))
                .collect(Collectors.joining("\n"));
    }

    /** 控制台 stderr 去掉开头那行横幅（横幅总是打印，没有开关）。 */
    private static String stripBanner(String text) {
        String banner = "dbcli (SlackerDB command line client)" + System.lineSeparator();
        return text.startsWith(banner) ? text.substring(banner.length()) : text;
    }

    private Result run(List<String> args, ScriptedLineSource lines) {
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
        int exitCode = commandLine.execute(args.toArray(String[]::new));
        Path logFile = Path.of(args.get(args.indexOf("--logfile") + 1));
        return new Result(out.toString(), err.toString(), exitCode, logFile);
    }

    @Test
    @DisplayName("同一脚本：控制台的 stdout / stderr / 退出码与 --execute 一致（stderr 只差横幅）")
    void consoleMatchesBatchStdoutStderrAndExitCode() throws Exception {
        Result batch = runBatch(SCRIPT);
        Result console = runConsole(SCRIPT);

        // 唯一的差异是控制台开头那行**横幅**（dbcli 从不写 stderr：横幅也在 stdout）
        assertEquals(batch.out, stripBanner(console.out),
                "去掉横幅后，批处理与控制台的消息本体必须逐字节一致");
        assertEquals("", console.err, "dbcli 从不打印 stderr");
        assertFalse(batch.out.contains("parity.sql"),
                "错误行不带文件位置前缀: " + batch.out);
        assertEquals(batch.exitCode, console.exitCode);
        assertEquals(0, batch.exitCode, "脚本里有失败断言，退出码应为 1");
        assertTrue(batch.out.contains("hello world"), batch.out);
        assertTrue(batch.out.contains("Assert [check] fail."), batch.out);
    }

    @Test
    @DisplayName("控制台日志是所见即所得的转录：横幅 + 提示符 + 输入 + 输出，剥掉转录行就是 stdout")
    void consoleLogIsATranscript() throws Exception {
        Result batch = runBatch(SCRIPT);
        Result console = runConsole(SCRIPT);

        assertEquals(stripBanner(console.out), batch.out, "剥掉横幅后 stdout 必须一致（日志的差异只该来自转录行）");

        String transcript = console.log();
        assertTrue(transcript.startsWith("dbcli (SlackerDB command line client)\n"),
                "日志第一行是横幅: " + transcript);
        assertTrue(transcript.contains("dbcli> _set @who world\n"),
                "每行输入都带主提示符: " + transcript);
        assertTrue(transcript.contains("   > hello ${who}\n"),
                "_echo 文本块里的行带续行提示符: " + transcript);
        assertTrue(transcript.endsWith("dbcli> _assert 3 = 3;\nAssert successful.\n")
                        || transcript.contains("Assert successful.\n"),
                "最后一行输入之后是它的输出: " + transcript);

        // 剥掉转录行之后，stdout 的每一行都必须按顺序出现在日志里（屏幕上多出的横幅/提示符只在
        // 日志与终端出现——所以是"子序列"而不是"逐字节相等"）
        String dataOnly = withoutTranscriptLines(transcript);
        assertTrue(isSubsequence(stripBanner(console.out), dataOnly),
                "stdout 的每一行都必须按顺序出现在日志里\nstdout:\n" + console.out
                        + "\n日志(已剥转录行):\n" + dataOnly);
        assertTrue(dataOnly.contains("Assert [check] fail."), "失败断言也在屏幕上，日志要有: " + dataOnly);
    }

    /** 去掉转录行：横幅那一行，以及 `dbcli> ` / `   > ` 开头的输入行。 */
    private static String withoutTranscriptLines(String transcript) {
        StringBuilder data = new StringBuilder();
        for (String line : transcript.split("\n", -1)) {
            if (line.equals("dbcli (SlackerDB command line client)")
                    || line.startsWith("dbcli> ") || line.startsWith("   > ")) {
                continue;
            }
            data.append(line).append('\n');
        }
        return data.toString();
    }

    /** {@code expected} 的每一行是否按顺序（允许中间夹杂别的行）出现在 {@code actual} 里。 */
    private static boolean isSubsequence(String expected, String actual) {
        List<String> remaining = new ArrayList<>(List.of(actual.split("\n", -1)));
        for (String line : expected.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            while (!remaining.isEmpty() && !remaining.remove(0).equals(line)) {
                // 跳过夹杂的行
            }
            if (remaining.isEmpty() && !actual.endsWith(line)) {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("注入取行来源就进控制台（默认分支），不需要 TTY，也不需要任何 --interactive 开关")
    void injectedLineSourceEntersTheConsole() {
        Result console = run(List.of("--logfile", tempDir.resolve("default.out").toString()),
                new ScriptedLineSource(List.of("_assert 1 = 1;")));

        assertEquals(0, console.exitCode, "stdout: " + console.out);
        assertEquals("Assert successful.\n", stripBanner(console.out));
        // 横幅只出现在控制台那条路（脚本形态不会有它）
        assertTrue(console.out.contains("dbcli (SlackerDB command line client)"),
                "注入取行来源就应当进控制台（横幅在 stdout 开头）: " + console.out);
        assertFalse(console.out.contains("Usage: dbcli"), "控制台形态不该打印用法帮助");
    }

    @Test
    @DisplayName("控制台：横幅只有客户端名字一行，没有用法/连接状态那行（且没有 --nologo 可关）")
    void bannerIsJustTheClientName() {
        Result console = run(List.of("--logfile", tempDir.resolve("a.out").toString()),
                new ScriptedLineSource(List.of()));

        assertEquals("dbcli (SlackerDB command line client)" + System.lineSeparator(), console.out,
                "横幅只该有客户端名字一行: " + console.out);
        // 横幅只有名字：不带 project version、不带构建日期（版本信息只从 --version 取）
        assertFalse(console.out.contains("Build Time"), console.out);
        assertFalse(console.out.contains("+"), console.out);
        assertFalse(console.out.contains("0.2.2"), console.out);
        // dbcli 从不写 stderr：横幅与提示符都在 stdout
        assertEquals("", console.err);

        // --nologo 已经删除：它是未知选项，进不了控制台
        Result rejected = run(List.of("--nologo", "--logfile", tempDir.resolve("b.out").toString()),
                new ScriptedLineSource(List.of()));
        assertEquals(2, rejected.exitCode, "removed option must be rejected: " + rejected.out);
        assertTrue(rejected.out.contains("Unknown option"), rejected.out);
    }

    @Test
    @DisplayName("控制台：_EXIT n 结束控制台并以 n 退出（批处理同码），数据输出也一致")
    void consoleHonoursExitCode() throws Exception {
        String script = "_assert 1 = 1;\n_exit 7\n";

        Result batch = runBatch(script);
        Result console = runConsole(script);

        assertEquals(7, batch.exitCode);
        assertEquals(7, console.exitCode);
        assertEquals(batch.out, stripBanner(console.out), "剥掉横幅后数据输出一致");
        // 批处理 log 比控制台多出的正好是被回显的两条语句（`_exit 7` 请求退出后不再有输出）
        assertEquals(List.of("_assert 1 = 1;", "_exit 7"), echoedLinesOf(batch.log(), console.out),
                "被回显的正好是这两条语句: " + batch.log());
        assertEquals(batch.out, withoutLines(batch.log(), List.of("_assert 1 = 1;", "_exit 7")),
                "批处理 log 去掉回显后就是它的数据输出");
        // 控制台的日志是转录：横幅 + 两条提示符行 + 数据输出
        assertEquals("dbcli (SlackerDB command line client)\n"
                        + "dbcli> _assert 1 = 1;\n"
                        + "Assert successful.\n"
                        + "dbcli> _exit 7\n",
                console.log(), "控制台日志应当与屏幕逐行一致");
    }

    /** 一段 log 比"数据输出"多出来的行（= 被回显的语句），按出现顺序。 */
    private static List<String> echoedLinesOf(String log, String dataOutput) {
        List<String> dataLines = List.of(dataOutput.split("\n", -1));
        List<String> extra = new ArrayList<>();
        for (String line : log.split("\n", -1)) {
            if (!dataLines.contains(line)) {
                extra.add(line);
            }
        }
        return extra;
    }

    @Test
    @DisplayName("控制台：_SET 设置的变量在同一会话的后续行里可见（与批处理一致的跨语句状态）")
    void consoleKeepsStateBetweenLines() {
        Result console = run(List.of("--logfile", tempDir.resolve("state.out").toString(),
                        "--var", "K=from-option"),
                new ScriptedLineSource(List.of("_set @K from-script",
                        "_echo t", "K=${K}", "echo off")));

        assertEquals(0, console.exitCode);
        assertTrue(console.out.contains("K=from-script"), console.out);
    }
}
