package org.slackerdb.dbcli.repl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.BuildInfo;
import org.slackerdb.dbcli.assertion.ResultModel;
import org.slackerdb.dbcli.common.CommandOutcome;
import org.slackerdb.dbcli.engine.ScriptContext;
import org.slackerdb.dbcli.engine.ScriptOutput;
import org.slackerdb.dbcli.engine.ScriptRunner;
import org.slackerdb.dbcli.engine.VariableResolver;
import org.slackerdb.dbcli.sql.JdbcSqlExecutor;
import org.slackerdb.dbcli.sql.SqlExecutor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交互式控制台的 hermetic 测试（**不需要 TTY，也不需要数据库**）。
 *
 * <p>脚本只用不需要数据库的命令（{@code _ECHO}/{@code _ASSERT}/{@code _SET}/{@code _EXIT}），
 * 因此这里验证的是控制台本身的行为：**语句只在顶层 {@code ;} 或顶格的 {@code /} 处收尾并执行**
 * （回车不等价于执行）、提示符与续行提示、EOF/Ctrl-C/Ctrl-D 的处理、{@code _EXIT} 的退出码、
 * 横幅只走 stderr（stdout 只放数据）。</p>
 *
 * <p>"控制台与批处理产出相同 stdout / {@code --logfile}"这条要求在
 * {@code CliConsoleModeTest} 里从命令行一侧验证（那里两种跑法用同一套参数）。</p>
 */
class InteractiveConsoleTest {

    @TempDir
    Path tempDir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private SqlExecutor executor;
    private ScriptContext context;
    private ScriptRunner runner;

    @BeforeEach
    void setUp() {
        executor = new JdbcSqlExecutor();
        context = new ScriptContext(executor, new ScriptOutput(new PrintWriter(out, true),
                new PrintWriter(err, true)), new VariableResolver(Map.of()), tempDir);
        runner = new ScriptRunner(context);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private int run(ScriptedLineSource lines) {
        return new InteractiveConsole(runner, context, lines, new PrintWriter(err, true)).run();
    }

    @Test
    @DisplayName("回车不执行：不带 ';' 的语句回车后仍在等（续行提示），补上 ';' 才执行")
    void enterDoesNotExecuteWithoutTerminator() {
        ScriptedLineSource lines = new ScriptedLineSource(List.of("SELECT 1", ";"));

        int code = run(lines);

        assertEquals(0, code, "未连接 ⇒ 补上 ';' 之后语句才被执行（失败）: " + err);
        assertEquals(1, countOccurrences(err.toString(), "Not connected."),
                "只该执行一次（回车不能执行半条语句）: " + err);
        assertEquals(InteractiveConsole.PROMPT, lines.prompts().get(0), lines.prompts().toString());
        assertEquals(InteractiveConsole.CONTINUATION_PROMPT, lines.prompts().get(1),
                "没有 ';' 就不该回到主提示符: " + lines.prompts());
    }

    @Test
    @DisplayName("_ 命令按行收尾：单行 _ 命令不需要 ';'，回车即执行（与批处理一致）")
    void singleLineCommandNeedsNoSemicolon() {
        int code = run(new ScriptedLineSource(List.of("_assert 1 = 1")));

        assertEquals(0, code, err.toString());
        assertTrue(out.toString().contains("Assert successful."), out.toString());
    }

    @Test
    @DisplayName("字符串里的 ';' 不是收尾：一直到引号外那个 ';' 才执行，且整条只执行一次")
    void semicolonInsideAStringDoesNotTerminate() {
        ScriptedLineSource lines = new ScriptedLineSource(List.of("SELECT 'a;b'", ";"));

        int code = run(lines);

        assertEquals(0, code, err.toString());
        assertEquals(1, countOccurrences(err.toString(), "Not connected."),
                "引号里的 ';' 不能把语句切开: " + err);
        assertEquals(InteractiveConsole.CONTINUATION_PROMPT, lines.prompts().get(1),
                "引号里的 ';' 之后仍应继续等: " + lines.prompts());
    }

    @Test
    @DisplayName("顶格的 '/' 收尾：多行块写完 '/' 才执行，行尾的 ';' 不算写完")
    void aLineWithOnlySlashTerminatesTheBlock() {
        ScriptedLineSource lines = new ScriptedLineSource(
                List.of("begin", "select 1;", "end;", "/"));

        int code = run(lines);

        assertEquals(0, code, "未连接 ⇒ 块被送库（失败）: " + err);
        assertEquals(1, countOccurrences(err.toString(), "Not connected."),
                "块体里的 ';' 不该提前执行: " + err);
        assertFalse(err.toString().contains("Unknown command"), err.toString());
        // 块体三行期间都是续行提示，'/' 执行完才回到主提示符
        assertEquals(InteractiveConsole.CONTINUATION_PROMPT, lines.prompts().get(1), lines.prompts().toString());
        assertEquals(InteractiveConsole.CONTINUATION_PROMPT, lines.prompts().get(2), lines.prompts().toString());
        assertEquals(InteractiveConsole.CONTINUATION_PROMPT, lines.prompts().get(3), lines.prompts().toString());
        assertEquals(InteractiveConsole.PROMPT, lines.prompts().get(4), lines.prompts().toString());
    }

    @Test
    @DisplayName("行尾反斜杠不再续行：它是语句正文的一部分（既不触发执行，也不会被删掉）")
    void trailingBackslashIsNotAContinuation() {
        RecordingExecutor recording = new RecordingExecutor();
        runWithRecording(recording, List.of("SELECT 1, \\", "2;"));

        assertEquals(1, recording.statements.size(), "两行只该拼成一条语句: " + recording.statements);
        assertTrue(recording.statements.get(0).contains("\\\n"),
                "反斜杠必须原样留在语句里（不再当续行符删掉）: " + recording.statements.get(0));
    }

    @Test
    @DisplayName("单独的 ';' 被忽略：上一句已经执行过，不该再送库报语法错误")
    void loneSemicolonIsIgnored() {
        int code = run(new ScriptedLineSource(List.of("_assert 1 = 1", ";")));

        assertEquals(0, code, err.toString());
        assertFalse(err.toString().contains("syntax"), err.toString());
        assertFalse(err.toString().contains("Not connected"), err.toString());
    }

    @Test
    @DisplayName("执行期 Ctrl-C：调用执行层的 cancelCurrentStatement，并在 stderr 留一行")
    void interruptCancelsTheRunningStatement() {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        SqlExecutor recording = new SqlExecutor() {
            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public void connect(Map<String, Object> ir) {
            }

            @Override
            public void disconnect() {
            }

            @Override
            public List<org.slackerdb.dbcli.common.CommandOutcome> session(String action, String name) {
                return List.of();
            }

            @Override
            public org.slackerdb.dbcli.assertion.ResultModel execute(String statement) {
                return org.slackerdb.dbcli.assertion.ResultModel.empty();
            }

            @Override
            public String lastError() {
                return null;
            }

            @Override
            public void cancelCurrentStatement() {
                cancelled.set(true);
            }
        };
        StringWriter cancelErr = new StringWriter();
        ScriptContext cancelContext = new ScriptContext(recording,
                new ScriptOutput(new PrintWriter(new StringWriter(), true),
                        new PrintWriter(cancelErr, true)),
                new VariableResolver(Map.of()), tempDir);
        try {
            InteractiveConsole console = new InteractiveConsole(new ScriptRunner(cancelContext),
                    cancelContext, new ScriptedLineSource(List.of()),
                    new PrintWriter(cancelErr, true));
            console.cancelRunningStatement();
        } finally {
            cancelContext.close();
        }

        assertTrue(cancelled.get(), "信号处理器必须把 Ctrl-C 翻译成 Statement.cancel()");
        assertTrue(cancelErr.toString().contains("^C (cancelling)"), cancelErr.toString());
    }

    @Test
    @DisplayName("粘贴多行（无 ';'）：逐行喂入，但没有收尾符就一条也不执行，直到会话收尾才整段执行一次")
    void pastedBlockWithoutTerminatorWaitsAndRunsOnceAtTheEnd() {
        // 取行实现返回带换行的字符串 = 一次 bracketed paste（控制台按行拆开喂进会话）
        int code = run(new ScriptedLineSource(List.of("SELECT 1,\n2")));

        assertEquals(0, code, "未连接 ⇒ 语句执行失败（说明它被送库了）: " + err);
        assertEquals(1, countOccurrences(err.toString(), "Not connected."),
                "整段必须只执行一次（且不是在每行末尾就执行）: " + err);
    }

    @Test
    @DisplayName("粘贴多行（带 ';'）：按 ';' 切成多条语句依次执行")
    void pastedBlockWithSemicolonsRunsEachStatement() {
        int code = run(new ScriptedLineSource(List.of("_assert 1 = 1;\n_assert 2 = 2;")));

        assertEquals(0, code, err.toString());
        assertEquals(2, countOccurrences(out.toString(), "Assert successful."), out.toString());
    }

    @Test
    @DisplayName("多行语句：语句没写完时用续行提示符，写完后回到主提示符")
    void continuationPromptWhileAStatementIsIncomplete() {
        ScriptedLineSource lines = new ScriptedLineSource(List.of("_echo title", "hello", "echo off"));

        int code = run(lines);

        assertEquals(0, code);
        assertTrue(out.toString().contains("hello"), out.toString());
        List<String> prompts = lines.prompts();
        assertEquals(InteractiveConsole.PROMPT, prompts.get(0));
        assertEquals(InteractiveConsole.CONTINUATION_PROMPT, prompts.get(1), prompts.toString());
        assertEquals(InteractiveConsole.CONTINUATION_PROMPT, prompts.get(2), prompts.toString());
        // 最后多读一次才拿到 EOF，此时缓冲已清空 → 又是主提示符
        assertEquals(InteractiveConsole.PROMPT, prompts.get(3), prompts.toString());
    }

    @Test
    @DisplayName("横幅只有客户端名字一行（stderr）：没有用法/连接状态那行，且无法关闭")
    void bannerIsJustTheClientNameOnStderr() {
        run(new ScriptedLineSource(List.of("_echo t", "data", "echo off")));

        assertEquals(BuildInfo.name() + System.lineSeparator(), err.toString(),
                "横幅只该有客户端名字一行: " + err);
        assertFalse(err.toString().contains("Interactive console"), err.toString());
        assertEquals("data\n", out.toString(), "stdout 只能有数据");
        assertFalse(err.toString().contains("--nologo"), err.toString());
    }

    @Test
    @DisplayName("EOF（Ctrl-D）：收尾退出，没写完的语句原样送执行层（未连接 ⇒ Not connected.，无解析文案）")
    void eofSendsTheUnfinishedTailToTheExecutionLayer() {
        ScriptedLineSource lines = new ScriptedLineSource(List.of("select 1"));

        int code = run(lines);

        assertEquals(0, code, "执行层报错 → 退出码 1");
        // 真执行层（未连接）：解析失败的那一段被原样送进去，使用者看到的是执行层的文案
        assertEquals("Not connected.", stripBanner(err.toString()), err.toString());
        assertFalse(err.toString().contains("Incomplete statement"), err.toString());
        assertFalse(err.toString().contains("expecting"), err.toString());
    }

    /** stderr 去掉开头那行横幅（横幅总是打印，位置在控制台输出的第一行）。 */
    private static String stripBanner(String text) {
        String banner = BuildInfo.name() + System.lineSeparator();
        return (text.startsWith(banner) ? text.substring(banner.length()) : text).strip();
    }

    @Test
    @DisplayName("_ 命令没写完：给续行提示（不是 Unknown command），补齐 echo off 后正常执行")
    void incompleteUnderscoreCommandKeepsTheContinuationPrompt() {
        ScriptedLineSource lines = new ScriptedLineSource(List.of("_echo t", "hi", "echo off"));

        int code = run(lines);

        assertEquals(0, code, err.toString());
        assertEquals("hi\n", out.toString(), "补齐收尾后数据照常输出");
        // 第一个提示符是"还没输入"时的主提示符；喂了 `_echo t` 之后两次取行都该是续行提示
        assertEquals(InteractiveConsole.PROMPT, lines.prompts().get(0), lines.prompts().toString());
        assertEquals(InteractiveConsole.CONTINUATION_PROMPT, lines.prompts().get(1),
                "还没写完时不该给主提示符: " + lines.prompts());
        assertEquals(InteractiveConsole.CONTINUATION_PROMPT, lines.prompts().get(2),
                lines.prompts().toString());
        assertFalse(err.toString().contains("Unknown command"), err.toString());
        assertFalse(err.toString().contains("Incomplete"), err.toString());
    }

    @Test
    @DisplayName("引号没闭合（_ssh execute \"abc）：续行提示后补上引号就能解析，不再报 Unknown command")
    void unterminatedQuoteKeepsReadingUntilItIsClosed() {
        // 第一行没闭合引号 ⇒ 没写完（续行提示）；第二行把引号补上 ⇒ 解析成功并分派到 _ssh
        // （未连接 ⇒ 运行期错误 "SSH not connected."，这不是解析层的 Unknown command）
        ScriptedLineSource lines = new ScriptedLineSource(List.of("_ssh execute \"abc", "\""));

        int code = run(lines);

        assertEquals(0, code, "SSH 未连接是运行期错误，退出码 1");
        assertEquals(InteractiveConsole.PROMPT, lines.prompts().get(0), lines.prompts().toString());
        assertEquals(InteractiveConsole.CONTINUATION_PROMPT, lines.prompts().get(1),
                "引号没闭合时应当给续行提示: " + lines.prompts());
        assertTrue(err.toString().contains("SSH not connected."),
                "补齐引号后应当解析成功并走到 _ssh 分派: " + err);
        assertFalse(err.toString().contains("Unknown command"), err.toString());
        assertFalse(err.toString().contains("Incomplete"), err.toString());
    }

    @Test
    @DisplayName("写错的 _ 命令：立刻报 Unknown command，提示符回到主提示符，下一条照常执行")
    void invalidUnderscoreCommandReportsImmediately() {
        ScriptedLineSource lines = new ScriptedLineSource(List.of("_bogus", "_assert 1 = 1;"));

        int code = run(lines);

        assertEquals(0, code, "报过错的会话退出码为 1");
        assertTrue(err.toString().contains("Unknown command [_bogus]"), err.toString());
        // "写错了"不该进入续行状态：下一次取行仍用主提示符
        assertEquals(InteractiveConsole.PROMPT, lines.prompts().get(1), lines.prompts().toString());
        assertTrue(out.toString().contains("Assert successful."), out.toString());
    }

    @Test
    @DisplayName("Ctrl-C：丢掉没写完的缓冲，后面的输入从头开始（不是退出控制台）")
    void interruptDiscardsThePendingStatement() {
        // 第二次 readLine 抛中断：此时缓冲里是 "_echo title"
        ScriptedLineSource lines = new ScriptedLineSource(
                List.of("_echo title", "_echo t", "hello", "echo off"), Set.of(1));

        int code = run(lines);

        assertEquals(0, code);
        assertTrue(out.toString().contains("hello"), out.toString());
        assertFalse(out.toString().contains("title"), "被取消的输入不得出现在输出里: " + out);
        assertTrue(lines.prompts().get(2).equals(InteractiveConsole.PROMPT),
                "中断之后应当回到主提示符: " + lines.prompts());
    }

    @Test
    @DisplayName("_EXIT n：控制台立刻收尾，退出码就是 n，后续输入不再执行")
    void exitStopsTheConsole() {
        ScriptedLineSource lines = new ScriptedLineSource(
                List.of("_assert 1 = 1;", "_exit 5", "_echo t", "never", "echo off"));

        int code = run(lines);

        assertEquals(5, code);
        assertTrue(out.toString().contains("Assert successful."), out.toString());
        assertFalse(out.toString().contains("never"), out.toString());
        assertEquals(2, lines.prompts().size(), "读到 _exit 之后不该再取行: " + lines.prompts());
    }

    @Test
    @DisplayName("一行多条语句：粘一行就逐条执行（与批处理同一段切分逻辑）")
    void severalStatementsOnOneLine() {
        ScriptedLineSource lines = new ScriptedLineSource(List.of("_assert 1 = 1; _assert 2 = 2;"));

        int code = run(lines);

        assertEquals(0, code);
        assertEquals(2, countOccurrences(out.toString(), "Assert successful."), out.toString());
    }

    /**
     * 用"记录收到的语句文本"的执行层跑一遍控制台。
     *
     * <p>有些断言（"送库的到底是什么"）只能看语句原文，不能只看执行次数：例如"行尾 {@code \} 是否被
     * 当成续行符删掉"在次数上完全看不出来。</p>
     */
    private void runWithRecording(RecordingExecutor executor, List<String> input) {
        try (ScriptContext recordingContext = new ScriptContext(executor,
                new ScriptOutput(new PrintWriter(new StringWriter(), true),
                        new PrintWriter(new StringWriter(), true)),
                new VariableResolver(Map.of()), tempDir)) {
            new InteractiveConsole(new ScriptRunner(recordingContext), recordingContext,
                    new ScriptedLineSource(input), new PrintWriter(new StringWriter(), true)).run();
        }
    }

    /** 执行层假实现：只记录收到的语句原文（不连库，因此不会失败）。 */
    private static final class RecordingExecutor implements SqlExecutor {

        private final List<String> statements = new ArrayList<>();

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void connect(Map<String, Object> ir) {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public List<CommandOutcome> session(String action, String sessionName) {
            return List.of();
        }

        @Override
        public ResultModel execute(String statement) {
            statements.add(statement);
            return ResultModel.empty();
        }

        @Override
        public String lastError() {
            return null;
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
