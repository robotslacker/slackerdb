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
 * {@code --profile <script>}：在主脚本/控制台**之前**执行，并且它的效果对后续可见。
 *
 * <p>覆盖四件事：执行顺序、变量可见性、可重复与顺序、失败即停（含 profile 里的 {@code _EXIT}）。
 * 全部 hermetic（只用不需要数据库的命令）。</p>
 */
class CliProfileTest {

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

    /** {@code lines != null} 时进交互式控制台（注入取行来源，不需要 TTY）。 */
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
        Files.writeString(file, text);
        return file;
    }

    @Test
    @DisplayName("--profile 先跑：输出顺序是 profile → 主脚本")
    void profileRunsBeforeTheMainScript() throws Exception {
        Path profile = script("profile.sql", "_echo t\nfrom-profile\necho off\n");
        Path main = script("main.sql", "_echo t\nfrom-main\necho off\n");

        Result result = run("--execute", main.toString(), "--profile", profile.toString());

        assertEquals(0, result.exitCode);
        assertEquals("from-profile\nfrom-main\n", result.out);
        assertEquals("", result.err);
    }

    @Test
    @DisplayName("--profile 里的 _SET 对主脚本可见（同一个会话）")
    void profileVariablesAreVisible() throws Exception {
        Path profile = script("profile.sql", "_set @who profile-value\n");
        Path main = script("main.sql", "_echo t\nwho=${who}\necho off\n");

        Result result = run("--execute", main.toString(), "--profile", profile.toString());

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("who=profile-value"), result.out);
    }

    @Test
    @DisplayName("--profile 可重复：按给定顺序执行")
    void profilesRunInOrder() throws Exception {
        Path first = script("p1.sql", "_echo t\none\necho off\n");
        Path second = script("p2.sql", "_echo t\ntwo\necho off\n");
        Path main = script("main.sql", "_echo t\nmain\necho off\n");

        Result result = run("--execute", main.toString(),
                "--profile", first.toString(), "--profile", second.toString());

        assertEquals("one\ntwo\nmain\n", result.out);
    }

    @Test
    @DisplayName("--profile 对控制台同样生效（控制台开始之前跑）")
    void profileAppliesToTheConsoleToo() throws Exception {
        Path profile = script("profile.sql", "_set @who profile-value\n_echo t\nprofile-line\necho off\n");

        Result result = run(new ScriptedLineSource(List.of("_echo t", "who=${who}", "echo off")),
                "--profile", profile.toString(),
                "--logfile", tempDir.resolve("console.log").toString());

        assertEquals(0, result.exitCode);
        // profile 的输出在横幅之前，控制台的数据行在横幅之后：两段都要出现
        assertTrue(result.out.contains("profile-line"), result.out);
        assertTrue(result.out.contains("who=profile-value"), result.out);
        assertEquals("", result.err, "dbcli 从不写 stderr");
    }

    @Test
    @DisplayName("--profile 文件不存在：stderr 报错 + 退出 1，且主脚本不跑")
    void missingProfileIsReported() throws Exception {
        Path main = script("main.sql", "_echo t\nfrom-main\necho off\n");

        Result result = run("--execute", main.toString(),
                "--profile", tempDir.resolve("no-such-profile.sql").toString());

        assertEquals(1, result.exitCode);
        assertTrue(result.out.contains("Profile script ["), result.out);
        assertEquals("", result.err, "profile 失败时主脚本不得执行");
    }

    @Test
    @DisplayName("profile 失败（非 0 退出码）：主脚本不跑，退出码原样返回")
    void failingProfileStopsTheRun() throws Exception {
        Path profile = script("bad.sql", "_assert 1 = 2, bad\n");
        Path main = script("main.sql", "_echo t\nfrom-main\necho off\n");

        Result result = run("--execute", main.toString(), "--profile", profile.toString());

        assertEquals(0, result.exitCode);
        assertFalse(result.out.contains("from-main"), "主脚本不得执行: " + result.out);
        assertTrue(result.out.contains("Assert [bad] fail."), "profile 自己的错误仍然如实报出: " + result.out);
    }

    @Test
    @DisplayName("profile 里的 _EXIT n：整次运行到此为止（退出码 n，主脚本不跑）")
    void exitInProfileStopsTheRun() throws Exception {
        Path profile = script("exit.sql", "_exit 9\n");
        Path main = script("main.sql", "_echo t\nfrom-main\necho off\n");

        Result result = run("--execute", main.toString(), "--profile", profile.toString());

        assertEquals(9, result.exitCode);
        assertEquals("", result.err);
    }
}
