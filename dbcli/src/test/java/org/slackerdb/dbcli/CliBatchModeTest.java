package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.common.IllegalPaths;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批处理形态（{@code --execute} / stdin 管道）的命令行级测试。
 *
 * <p>脚本只用**不需要数据库**的命令（{@code _ECHO}/{@code _ASSERT}/{@code _EXIT}…），
 * 因此这里是 hermetic 的：验证的是"参数 → 引擎 → 退出码 → 流分离"这条链路，
 * 真实 SQL 由 gated 的 {@code ScriptEngineIntegrationTest} 覆盖。</p>
 */
class CliBatchModeTest {

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

    /** 跑一次 CLI；stdin 默认给一个**空流**，避免测试真的去读进程标准输入。 */
    private Result run(String stdinText, String... args) {
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream(
                stdinText == null ? new byte[0] : stdinText.getBytes(StandardCharsets.UTF_8)));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true)); // dbcli 从不写 stderr：参数错误也在 stdout
        int exitCode = commandLine.execute(args);
        return new Result(out.toString(), err.toString(), exitCode);
    }

    @Test
    @DisplayName("--execute <file>：跑脚本、退出码为脚本退出码，stdout 只有数据")
    void executeScriptFile() throws Exception {
        Path script = tempDir.resolve("hello.sql");
        Files.writeString(script, "_echo title\nhello\nworld\necho off\n");

        Result result = run(null, "--execute", script.toString());

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("hello\nworld\n"), result.out);
        assertEquals("", result.err);
    }

    @Test
    @DisplayName("--execute：脚本里的 _EXIT n 成为进程退出码")
    void executeHonoursExitCode() throws Exception {
        Path script = tempDir.resolve("exit.sql");
        Files.writeString(script, "_assert 1 = 1;\n_exit 4\n");

        Result result = run(null, "--execute", script.toString());

        assertEquals(4, result.exitCode);
    }

    @Test
    @DisplayName("--execute -：从 stdin 读脚本")
    void executeFromStdinDash() {
        Result result = run("_echo t\nfrom-stdin\necho off\n", "--execute", "-");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("from-stdin"), result.out);
    }

    @Test
    @DisplayName("非交互 stdin 有内容时（不带 --execute）按脚本执行")
    void stdinPipeWithoutExecute() {
        Result result = run("_echo t\npiped\necho off\n");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("piped"), result.out);
    }

    @Test
    @DisplayName("stdin 为空且无参数：仍然打印用法帮助并退出 0")
    void emptyStdinPrintsUsage() {
        Result result = run(null);

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("Usage: dbcli"), result.out);
    }

    @Test
    @DisplayName("脚本文件不存在：stderr 报错 + 退出 1（stdout 为空）")
    void missingScriptFile() {
        Result result = run(null, "--execute", tempDir.resolve("nope.sql").toString());

        assertEquals(1, result.exitCode);
        assertEquals("", result.err);
        assertTrue(result.out.contains("does not exist"), result.out);
    }

    @Test
    @DisplayName("--var K=V：变量在脚本里可用")
    void variableOption() throws Exception {
        Path script = tempDir.resolve("var.sql");
        Files.writeString(script, "_echo t\nvalue=${K}\necho off\n");

        Result result = run(null, "--execute", script.toString(), "--var", "K=hello");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("value=hello"), result.out);
    }

    @Test
    @DisplayName("--var 对双花括号写法不生效：{{K}} 原样保留（只支持 ${K}）")
    void doubleBraceIsNotAVariable() throws Exception {
        Path script = tempDir.resolve("var-braces.sql");
        Files.writeString(script, "_echo t\na=${K} b={{K}}\necho off\n");

        Result result = run(null, "--execute", script.toString(), "--var", "K=hello");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("a=hello b={{K}}"), result.out);
    }

    @Test
    @DisplayName("--var 写法非法：picocli 参数错误（stderr + 退出 2，stdout 为空）")
    void invalidVariableOption() {
        Result result = run(null, "--var", "novalue");

        assertNotEquals(0, result.exitCode);
        assertEquals("", result.err);
        assertTrue(result.out.contains("Invalid --var"), result.out);
    }

    @Test
    @DisplayName("--silent：**什么都不打印**（数据、状态行、错误全静默），只剩下退出码")
    void silentPrintsNothingAtAll() throws Exception {
        Path script = tempDir.resolve("silent.sql");
        Files.writeString(script, "_echo t\nhello\necho off\n_assert 1 = 2;\n");

        Result loud = run(null, "--execute", script.toString());
        Result quiet = run(null, "--execute", script.toString(), "--silent");

        // 不静默时：数据在 stdout、错误在 stderr，退出码 1（断言失败）
        assertTrue(loud.out.contains("hello"), loud.out);
        assertTrue(loud.out.contains("Assert fail."), loud.out);
        assertEquals(0, loud.exitCode);

        // 静默时：两个流都空，但退出码一模一样 —— 静默不改变结论，只关掉"打印"
        assertEquals("", quiet.err, "silent：stdout 一个字都不打（数据也没有）");
        assertEquals("", quiet.err, "silent：stderr 一个字都不打（错误也没有）");
        assertEquals(loud.exitCode, quiet.exitCode, "静默必须保留退出码");
    }

    @Test
    @DisplayName("--silent 不影响文件产物：--logfile 照常拿到与不静默时相同的内容")
    void silentKeepsTheLogFile() throws Exception {
        Path script = tempDir.resolve("silent-log.sql");
        Files.writeString(script, "_echo t\nhello\necho off\n");
        Path loudLog = tempDir.resolve("loud.log");
        Path quietLog = tempDir.resolve("quiet.log");

        run(null, "--execute", script.toString(), "--logfile", loudLog.toString());
        Result quiet = run(null, "--execute", script.toString(), "--logfile", quietLog.toString(),
                "--silent");

        assertEquals("", quiet.err, quiet.err);
        assertEquals("", quiet.err, quiet.err);
        assertEquals(Files.readString(loudLog), Files.readString(quietLog),
                "文件产物与是否 --silent 无关（silent 只关终端）");
        assertTrue(Files.readString(quietLog).contains("hello"), Files.readString(quietLog));
    }

    @Test
    @DisplayName("--silent：脚本文件不存在也不打印，只剩退出码 1")
    void silentAlsoSilencesCliErrors() {
        Result result = run(null, "--execute", tempDir.resolve("nope.sql").toString(), "--silent");

        assertEquals(1, result.exitCode);
        assertEquals("", result.err);
        assertEquals("", result.err);
    }

    @Test
    @DisplayName("--silent 在参数解析之前就能认出来（Main 用它先把两个输出流换成丢弃流）")
    void silentIsDetectedBeforeParsing() {
        assertTrue(CliOutput.silentRequested(new String[] {"--execute", "x.sql", "--silent"}));
        assertFalse(CliOutput.silentRequested(new String[] {"--execute", "x.sql"}));
        assertFalse(CliOutput.silentRequested(new String[] {"--var", "K=--silent"}), "值里的 --silent 不算");
        assertFalse(CliOutput.silentRequested(null));
    }

    @Test
    @DisplayName("--logon 的全写形态：用户名/口令不进 URL，拼出来仍是同一条 jdbc:slackerdb URL")
    void logonAcceptsTheFullForm() {
        Result result = run(null, "--logon", "main@jdbc:slackerdb://127.0.0.1:1/db1",
                "--execute", "-");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("Cannot connect to [jdbc:slackerdb://127.0.0.1:1/db1]"),
                result.out);
        assertFalse(result.out.contains("Invalid connection string"), result.out);
    }

    @Test
    @DisplayName("--logon 用其它驱动的 URL：原样透传（不报无效连接串，交给 DriverManager）")
    void logonPassesOtherDriversThrough() {
        Result result = run(null, "--logon", "main@jdbc:postgresql://127.0.0.1:1/db", "--execute", "-");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("Cannot connect to [jdbc:postgresql://127.0.0.1:1/db]"),
                "URL 应当原样交给驱动: " + result.out);
        assertFalse(result.out.contains("Invalid connection string"), result.out);
        assertFalse(result.out.contains("main@"), "用户名不得进 URL: " + result.out);
    }

    @Test
    @DisplayName("--logon 用 host:port/service 的写法：拼出的 URL 出现在『连不上』的错误里（证明映射正确）")
    void logonBuildsTheRightUrl() {
        Result result = run(null, "--logon", "127.0.0.1:1/db1", "--execute", "-");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("Cannot connect to [jdbc:slackerdb://127.0.0.1:1/db1]"),
                "错误里应当回显拼出来的 URL: " + result.out);
    }

    @Test
    @DisplayName("_connect 简写形态缺端口 / 缺服务名 / 带用户名口令：仍是无效连接串（客户端不补默认值）")
    void connectRequiresTheCompleteTarget() {
        for (String bad : List.of("127.0.0.1", "127.0.0.1:3175", "127.0.0.1/db1",
                "main@127.0.0.1:3175/db1", "main/secret@127.0.0.1:3175/db1")) {
            Result result = run("_connect " + bad + "\n", "--execute", "-");

            assertEquals(0, result.exitCode, bad + " → stderr: " + result.out);
            assertTrue(result.out.contains("Invalid connection string ["),
                    bad + " 应当报无效连接串: " + result.out);
        }
    }

    @Test
    @DisplayName("_connect 全写形态：slackerdb 缺端口仍是无效连接串；只有 scheme 的 jdbc: 也无效")
    void connectFullFormRules() {
        for (String bad : List.of("jdbc:slackerdb://127.0.0.1/db1", "jdbc:sqlite:")) {
            Result result = run("_connect " + bad + "\n", "--execute", "-");

            assertEquals(0, result.exitCode, bad + " → stderr: " + result.out);
            assertTrue(result.out.contains("Invalid connection string [" + bad + "]."),
                    bad + " 应当报无效连接串: " + result.out);
        }

        Result noDatabase = run("_connect jdbc:slackerdb://127.0.0.1:1/\n", "--execute", "-");
        assertTrue(noDatabase.out.contains("Cannot connect to [jdbc:slackerdb://127.0.0.1:1/]"),
                "省略库名是可连的写法（由驱动按默认库处理）: " + noDatabase.out);
    }

    @Test
    @DisplayName("_connect 保留查询参数：?k=v&k2=v2 原样进 URL")
    void connectKeepsTheQueryParameters() {
        Result result = run("_connect 127.0.0.1:1/db1?timeout=30&mode=read\n", "--execute", "-");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("jdbc:slackerdb://127.0.0.1:1/db1?timeout=30&mode=read"),
                "查询参数必须原样带上: " + result.out);
    }

    @Test
    @DisplayName("帮助里宣传了新参数（--execute/--logon/--var/--silent），且不再有 --commandmap")
    void helpListsBatchOptions() {
        Result result = run(null, "--help");

        assertEquals(0, result.exitCode);
        for (String option : new String[] {"--execute", "--logon", "--var", "--silent"}) {
            assertTrue(result.out.contains(option), "帮助里应有 " + option + ": " + result.out);
        }
        // --commandmap（连同 map 规则）已删除：帮助里不得再出现
        assertFalse(result.out.contains("--commandmap"),
                "帮助里不得再有 --commandmap: " + result.out);
        // picocli 会把 ${...} 当变量插值：必须双写美元符，否则帮助里会显示成 null。
        // 只检查 --var 那一行：其它选项（如 --null）本来就会出现 "null" 这个词。
        assertTrue(result.out.contains("${name}"), "帮助里应原样显示 ${name}: " + result.out);
        for (String line : result.out.split("\n")) {
            if (line.contains("${name}") || line.contains("--var")) {
                assertFalse(line.contains("null"), "帮助里 ${...} 被插值了: " + line);
            }
        }
        assertFalse(result.out.contains("M0 step"), result.out);
    }

    @Test
    @DisplayName("SFTP 数值参数写错的脚本：stderr 一行可读消息 + 退出码 1，没有 Java 栈、没有 255")
    void sftpInvalidNumberIsAScriptLevelError() throws Exception {
        Path script = tempDir.resolve("sftp-bad-mode.sql");
        Files.writeString(script, "_ssh sftp chmod /f abc\n_echo t\nafter-the-error\necho off\n");

        Result result = run(null, "--execute", script.toString());

        assertEquals(0, result.exitCode, "脚本笔误是普通错误（1），不是未捕获异常（255）");
        assertTrue(result.out.contains("Invalid SFTP file mode [abc]: expected an octal permission mode in 0..07777."), result.out);
        assertFalse(result.out.contains("Exception"), "不得吐 Java 异常: " + result.out);
        assertFalse(result.out.contains("\tat "), "不得吐 Java 栈: " + result.out);
        assertFalse(result.out.contains("uncaught"), result.out);
        // 默认 _WHENEVER ERROR CONTINUE：出错的语句不执行，后面的语句照跑
        assertTrue(result.out.contains("after-the-error"), result.out);

        // _WHENEVER ERROR EXIT n：同一个笔误按脚本自己的语义立刻收尾，退出码是 n
        Path exitScript = tempDir.resolve("sftp-bad-mode-exit.sql");
        Files.writeString(exitScript,
                "_whenever error exit 7\n_ssh sftp chmod /f abc\n_echo t\nnever-printed\necho off\n");

        Result exitResult = run(null, "--execute", exitScript.toString());

        assertEquals(7, exitResult.exitCode);
        assertTrue(exitResult.out.contains("Invalid SFTP file mode [abc]: expected an octal permission mode in 0..07777."),
                exitResult.out);
        assertFalse(exitResult.out.contains("never-printed"), exitResult.out);
    }

    @Test
    @DisplayName("--execute 路径含非法字符：一行可读错误（退出 1），不吐 Java 栈")
    void invalidExecutePathIsReadable() {
        // 取值必须"在本平台上非法"：* 只在 Windows 上非法，POSIX 上要用 NUL（见 IllegalPaths）
        Result result = run(null, "--execute", IllegalPaths.VALUE);

        assertEquals(1, result.exitCode);
        assertTrue(result.out.startsWith(IllegalPaths.INVALID_PREFIX), result.out);
        assertFalse(result.out.contains("InvalidPathException"), result.out);
    }
}
