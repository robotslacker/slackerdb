package org.slackerdb.dbcli.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.common.IllegalPaths;
import org.slackerdb.dbcli.engine.command.CommandCall;
import org.slackerdb.dbcli.engine.command.CommandCatalog;
import org.slackerdb.dbcli.engine.command.ScriptCommand;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行引擎的 hermetic 测试（用 {@link FakeSqlExecutor}，**不需要数据库**）。
 *
 * <p>覆盖的都是"引擎行为"而不是 JDBC 行为：语句累积（多行 / {@code create procedure} 的行首 {@code /}）、
 * {@code _ASSERT} 通过与失败、{@code _WHENEVER ERROR CONTINUE|EXIT n}、{@code _SPOOL} 的 tee、
 * {@code _ECHO}、{@code _START} 的递归与 {@code ARGVn}、{@code _EXIT} 退出码、未知命令、
 * 以及"错误 → 退出码 1 / 显式退出码优先"这条 §3.7 的核心语义。</p>
 */
class ScriptRunnerTest {

    @TempDir
    Path tempDir;

    private Harness harness;

    @BeforeEach
    void setUp() {
        harness = new Harness(tempDir);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ------------------------------------------------------------------ 语句累积

    @Test
    @DisplayName("多行语句累积：以 ';' 结尾才执行，语句原文保留内部换行")
    void accumulatesMultiLineStatement() {
        String script = "create table t (\n  id int\n);\nselect id from t;\n";

        int code = harness.run(script);

        assertEquals(0, code);
        assertEquals(List.of("create table t (\n  id int\n)", "select id from t"),
                harness.sql.statements);
    }

    @Test
    @DisplayName("create procedure 用行首 '/' 收尾：'/' 不计入语句原文")
    void accumulatesProcedureUntilSlash() {
        String script = "create procedure p() begin\n  select 1;\nend;\n/\nselect 2;\n";

        int code = harness.run(script);

        assertEquals(0, code);
        assertEquals(2, harness.sql.statements.size());
        assertEquals("create procedure p() begin\n  select 1;\nend;", harness.sql.statements.get(0));
        assertEquals("select 2", harness.sql.statements.get(1));
    }

    @Test
    @DisplayName("注释行与空行被跳过，语句内部的行原样保留")
    void skipsCommentAndBlankLines() {
        String script = "-- a comment\n\n# another comment\nselect 1;\n";

        int code = harness.run(script);

        assertEquals(0, code);
        assertEquals(List.of("select 1"), harness.sql.statements);
    }

    // -------------------------------------------------------------- 一行多条语句

    @Test
    @DisplayName("一行两条语句：按顶层 ';' 逐条执行")
    void twoStatementsOnOneLine() {
        int code = harness.run("select 1; select 2;\n");

        assertEquals(0, code);
        assertEquals(List.of("select 1", "select 2"), harness.sql.statements);
    }

    @Test
    @DisplayName("一行三条语句：逐条执行，顺序不变")
    void threeStatementsOnOneLine() {
        int code = harness.run("select 1; select 2; select 3;\n");

        assertEquals(0, code);
        assertEquals(List.of("select 1", "select 2", "select 3"), harness.sql.statements);
    }

    @Test
    @DisplayName("语句后面的行注释不进缓冲：'select 1; -- 注释' 之后缓冲为空（不会误报未写完）")
    void trailingCommentDoesNotKeepTheBuffer() {
        int code = harness.run("select 1; -- a comment; still a comment\n_assert 1 = 1;\n");

        assertEquals(0, code);
        assertEquals(List.of("select 1"), harness.sql.statements);
        assertTrue(harness.stdout().contains("Assert successful."), harness.stdout());
        assertEquals("", harness.stderr(), "注释残渣不得被当成语句交出去: " + harness.stderr());
    }

    @Test
    @DisplayName("引号里的 ';' 不是分隔符：'insert into t values (''a;b'')' 是一条语句")
    void semicolonInsideQuotesIsNotASeparator() {
        int code = harness.run("insert into t values ('a;b');\n");

        assertEquals(0, code);
        assertEquals(1, harness.sql.statements.size(), harness.sql.statements.toString());
        assertTrue(harness.sql.statements.get(0).contains("a;b"), harness.sql.statements.get(0));
    }

    @Test
    @DisplayName("{% %} 断言里的 ';' 不是分隔符")
    void semicolonInsideAssertBracesIsNotASeparator() {
        int code = harness.run("_assert {% ';' = ';' %};\n");

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("Assert successful."), harness.stdout());
    }

    @Test
    @DisplayName("'/' 收尾的块与一行多语句共存：procedure 内部的 ';' 不会被切坏")
    void slashTerminatedBlockSurvivesSplitting() {
        int code = harness.run("select 1;\n"
                + "create procedure p() begin\n"
                + "  select 2;\n"
                + "end;\n"
                + "/\n"
                + "select 3;\n");

        assertEquals(0, code);
        assertEquals(List.of("select 1", "create procedure p() begin\n  select 2;\nend;", "select 3"),
                harness.sql.statements);
    }

    @Test
    @DisplayName("末尾不完整的一段留在缓冲里继续累积，写完才执行")
    void incompleteTailKeepsAccumulating() {
        int code = harness.run("select 1; select 2\nfrom t;\n");

        assertEquals(0, code);
        assertEquals(List.of("select 1", "select 2\nfrom t"), harness.sql.statements);
    }

    @Test
    @DisplayName("多语句行里出现 _EXIT：同一行剩下的语句不再执行")
    void exitInTheMiddleOfALineStopsTheRest() {
        int code = harness.run("select 1; _exit 6; select 2;\n");

        assertEquals(6, code);
        assertEquals(List.of("select 1"), harness.sql.statements);
    }

    @Test
    @DisplayName("多语句行里的语法错误只影响那一条，后面的语句照跑")
    void syntaxErrorInOneStatementDoesNotAffectTheNext() {
        // `_assert` 形态非法 → 该条计一次错误；后面的 select 仍然执行
        int code = harness.run("_assert RESULT.NOSUCH = 1; select 2;\n");

        assertEquals(0, code);
        assertEquals(List.of("select 2"), harness.sql.statements);
        assertTrue(harness.stdout().contains("Assert fail. SyntaxError =>"), harness.stdout());
    }

    // ------------------------------------------------- 解析失败 → 原样送库（新口径）

    @Test
    @DisplayName("语法错语句（selec 1）：原始文本进执行层，stderr 是数据库的错误，没有任何解析文案")
    void syntaxErrorStatementIsSentToTheExecutionLayer() {
        String databaseError = "ERROR: syntax error at or near \"selec\"";
        harness.sql.setError("selec 1;", databaseError);

        int code = harness.run("selec 1;\n");

        assertEquals(0, code, "数据库报错 → 按既有错误语义退出码非 0");
        assertEquals(List.of("selec 1;"), harness.sql.statements,
                "原始文本（含语句终结符）必须送进执行层");
        assertTrue(harness.stderr().contains(databaseError), "要打印数据库的错误: " + harness.stderr());
        assertEquals("", harness.stdout(), "报错时 stdout 只放数据（这里没有数据）: " + harness.stdout());
        assertNoParserText(harness.stderr());
    }

    @Test
    @DisplayName("同一行多条语句、最后一条没有 ';'：仍然逐条执行，不被合并成一条")
    void unterminatedTailOnTheSameLineStillRunsEachStatement() {
        harness.sql.requireConnection();

        int code = harness.run("selec 1; selec 2\n");

        assertEquals(0, code);
        assertEquals(2, countOccurrences(harness.stderr(), "Not connected."), harness.stderr());
        assertEquals(List.of("selec 1;", "selec 2;"), harness.sql.statements,
                "第二句也要单独执行（补虚拟 ';'），不能把整行当一条文本送库");
    }

    @Test
    @DisplayName("未连接 + 语法错语句：送执行层后打印 Not connected.（不是解析文案）")
    void unparsableStatementWithoutAConnectionReportsNotConnected() {
        harness.sql.requireConnection();

        int code = harness.run("selec 1;\n");

        assertEquals(0, code);
        // 未连接时只看执行层的文案（错误行不带位置前缀）
        assertEquals("Not connected.", harness.stderr().strip(), "未连接时只看执行层的文案");
        assertEquals(List.of("selec 1;"), harness.sql.statements,
                "未连接也要走到执行层（判断沿用 isConnected 路径）");
        assertEquals("", harness.stdout(), harness.stdout());
        assertNoParserText(harness.stderr());
    }

    @Test
    @DisplayName("脚本尾部不完整（select 1,）：原始文本进执行层，没有 Incomplete statement")
    void incompleteTailGoesToTheExecutionLayer() {
        harness.sql.setError("select 1,", "ERROR: syntax error at end of input");

        int code = harness.run("select 1,\n");

        assertEquals(0, code);
        assertEquals(List.of("select 1,"), harness.sql.statements, "整段原文送执行层（不含行尾换行）");
        assertTrue(harness.stderr().contains("syntax error at end of input"), harness.stderr());
        assertNoParserText(harness.stderr());
    }

    @Test
    @DisplayName("回归：能解析的语句与 _ 命令行为不变（_connect / SQL / _assert / _job）")
    void parsableStatementsAndCommandsAreUnchanged() {
        harness.sql.setDefaultResult(FakeSqlExecutor.query(List.of("id"), List.of("INTEGER"),
                List.of(List.of(1)), "1 row selected."));

        int code = harness.run("_connect 127.0.0.1:43175/db1\n"
                + "select id from t;\n"
                + "_assert RESULT.ROWCOUNT = 1, one-row;\n"
                + "_job jobmanager on\n");

        assertEquals(0, code, harness.stderr());
        assertEquals("127.0.0.1", harness.sql.connects.get(0).get("host"));
        assertEquals(List.of("select id from t"), harness.sql.statements);
        assertTrue(harness.stdout().contains("Database connected."), harness.stdout());
        assertTrue(harness.stdout().contains("1 row selected."), harness.stdout());
        assertTrue(harness.stdout().contains("Assert [one-row] successful."), harness.stdout());
        assertTrue(harness.stdout().contains("Job manager started."), harness.stdout());
        assertEquals("", harness.stderr(), "能解析的命令不该产生任何错误: " + harness.stderr());
    }

    @Test
    @DisplayName("已删除的 _use 现在按未知命令处理：Unknown command，不打印解析文案、也不送执行层")
    void malformedKnownCommandUsesTheCommandFallback() {
        // `_use` 已从语法删除：整行认不出来，按未知命令报一条可读错误（不打印解析层文案）
        int code = harness.run("_use XXX\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Unknown command [_use]"), harness.stderr());
        assertTrue(harness.sql.statements.isEmpty(),
                "命令名认得出来也仍然不是 SQL，不得送执行层: " + harness.sql.statements);
        assertNoParserText(harness.stderr());
    }

    @Test
    @DisplayName("已知 _ 命令没写完（光秃秃的 _sleep）：报 incomplete，不是 Unknown command，也不送执行层")
    void underscoreCommandWithErrorsOnlyAtEndOfInputIsIncomplete() {
        // `_sleep` 的报错只有一条"missing … at '<EOF>'"：错误落在**输入末尾** ⇒ 没写完。
        // 脚本里没有下一行可等，于是 EOF 时报一条 Incomplete command（退出码 1）。
        int code = harness.run("_sleep\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Incomplete command [_sleep]"), harness.stderr());
        assertFalse(harness.stderr().contains("Unknown command"), harness.stderr());
        assertTrue(harness.sql.statements.isEmpty(),
                "没写完的 _ 命令不是 SQL，不得送执行层: " + harness.sql.statements);
        assertEquals("", harness.stdout(), harness.stdout());
        assertNoParserText(harness.stderr());
    }

    @Test
    @DisplayName("引号没闭合（_ssh execute \"abc / _assert \"abc）：写到一半 ⇒ incomplete，不是 Unknown command")
    void unterminatedQuoteIsIncompleteNotUnknownCommand() {
        // `_ssh execute "abc`：词法报错（认不出来的 `"abc`）一路延续到输入结尾 + 语法在 <EOF> 处报错 ⇒ 没写完；
        // `_assert "abc` 更能证明"词法侧 at-end"这条判据：只看语法层它会落到"写错了"（errorCode!=0 但 isFinished=true）。
        for (String script : new String[] {"_ssh execute \"abc\n", "_assert \"abc\n"}) {
            try (Harness fresh = new Harness(tempDir)) {
                int code = fresh.run(script);

                assertEquals(0, code, script);
                assertTrue(fresh.stderr().contains("Incomplete command [_"),
                        "脚本 >>>" + script.strip() + "<<< 实际 stderr: " + fresh.stderr());
                assertFalse(fresh.stderr().contains("Unknown command"), fresh.stderr());
                assertTrue(fresh.sql.statements.isEmpty(),
                        "没写完的 _ 命令不得送执行层: " + fresh.sql.statements);
                assertEquals("", fresh.stdout(), fresh.stdout());
                assertNoParserText(fresh.stderr());
            }
        }
    }

    @Test
    @DisplayName("不认识的 _ 命令（_bogus）：Unknown command 且不送执行层；_LOAD 的不支持目标另有可读错误")
    void unknownUnderscoreCommandsAreNeverSentToTheExecutionLayer() {
        try (Harness fresh = new Harness(tempDir)) {
            int code = fresh.run("_bogus\n");

            assertEquals(0, code);
            assertTrue(fresh.stderr().contains("Unknown command [_bogus]"),
                    "脚本 >>>_bogus<<< 实际 stderr: " + fresh.stderr());
            assertTrue(fresh.sql.statements.isEmpty(),
                    "未知命令不得送执行层: " + fresh.sql.statements);
            assertEquals("", fresh.stdout(), fresh.stdout());
            assertNoParserText(fresh.stderr());
        }
        // `_LOAD` 是认识的命令：不认识的是它的**目标**，文案必须说清这一点
        try (Harness fresh = new Harness(tempDir)) {
            assertEquals(0, fresh.run("_load map x\n"));

            assertTrue(fresh.stderr().contains("Unsupported _LOAD target [map x]"),
                    "实际 stderr: " + fresh.stderr());
            assertFalse(fresh.stderr().contains("Unknown command"), fresh.stderr());
            assertTrue(fresh.sql.statements.isEmpty(), fresh.sql.statements.toString());
            assertNoParserText(fresh.stderr());
        }
    }

    @Test
    @DisplayName("写错的 _ 命令只吃掉自己那一行：后面的语句照常执行（不会把后面的行卷进缓冲）")
    void invalidUnderscoreCommandDoesNotSwallowFollowingLines() {
        int code = harness.run("_bogus x\nselect 1;\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Unknown command [_bogus]"), harness.stderr());
        assertEquals(List.of("select 1"), harness.sql.statements,
                "写错的 _ 命令只吃掉自己：后续语句必须照常执行");
        assertNoParserText(harness.stderr());
    }

    @Test
    @DisplayName("脚本结束时语句仍未写完：原始文本送执行层，不打印任何解析文案（旧：Incomplete statement）")
    void incompleteTailIsSentToTheExecutionLayerAtEndOfScript() {
        int code = harness.run("select 1\n");

        // 解析层判"没写完"→ EOF 时按解析失败处理：整段原文交给执行层
        assertEquals(0, code, harness.stderr());
        assertEquals(List.of("select 1"), harness.sql.statements, "没写完的文本必须原样送执行层");
        assertEquals("", harness.stderr(), "不得有任何解析层文案: " + harness.stderr());
    }

    @Test
    @DisplayName("未知的 _ 命令：仍然是 Unknown command（命令层兜底），且**不**送执行层")
    void reportsUnknownCommand() {
        int code = harness.run("_NOTACOMMAND x\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Unknown command [_NOTACOMMAND]"),
                "实际 stderr: " + harness.stderr());
        // _ 开头的命令不是 SQL：不能被送进执行层（假执行层是 spy）
        assertTrue(harness.sql.statements.isEmpty(),
                "_ 命令不得被送进执行层: " + harness.sql.statements);
        assertEquals("", harness.stdout(), "_ 命令报错时 stdout 必须为空: " + harness.stdout());
    }

    // -------------------------------------------------------------------- SQL 输出

    @Test
    @DisplayName("查询：表 + 状态行进 stdout；_SET FEEDBACK OFF 只去掉状态行")
    void printsTableAndStatusLine() {
        harness.sql.setDefaultResult(FakeSqlExecutor.query(List.of("id"), List.of("INTEGER"),
                List.of(List.of(1), List.of(2)), "2 rows selected."));

        assertEquals(0, harness.run("select id from t;\n"));
        assertTrue(harness.stdout().contains("2 rows selected."), "实际 stdout: " + harness.stdout());
        assertTrue(harness.stdout().contains("| id |"), "实际 stdout: " + harness.stdout());

        Harness other = new Harness(tempDir);
        try {
            other.sql.setDefaultResult(FakeSqlExecutor.query(List.of("id"), List.of("INTEGER"),
                    List.of(List.of(1)), "1 row selected."));
            assertEquals(0, other.run("_set FEEDBACK OFF\nselect id from t;\n"));
            assertTrue(other.stdout().contains("| id |"), "表格仍要打印: " + other.stdout());
            assertFalse(other.stdout().contains("row selected."), "状态行应被关掉: " + other.stdout());
        } finally {
            other.close();
        }
    }

    @Test
    @DisplayName("SQL 错误：错误原文进 stderr、脚本以 1 退出、上一条模型仍可被断言观察")
    void reportsSqlError() {
        harness.sql.setError("select broken", "ERROR: boom");

        int code = harness.run("select broken;\n_assert RESULT.ERRORCODE = 1;\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("ERROR: boom"), "实际 stderr: " + harness.stderr());
        assertTrue(harness.stdout().contains("Assert successful."), "实际 stdout: " + harness.stdout());
    }

    // --------------------------------------------------------------------- ASSERT

    @Test
    @DisplayName("_ASSERT 通过：状态文案进 stdout，退出码 0")
    void assertPasses() {
        harness.sql.setDefaultResult(FakeSqlExecutor.query(List.of("id", "name"),
                List.of("INTEGER", "VARCHAR"), List.of(List.of(1, "alpha")), "1 row selected."));

        int code = harness.run("select id, name from t;\n"
                + "_assert RESULT.ROWCOUNT = 1;\n"
                + "_assert RESULT.ROWS[0][1] = 'alpha';\n"
                + "_assert RESULT.HEADERS[1] = 'name';\n"
                + "_assert {% 3==3 %};\n"
                + "_assert RESULT.STATUS CONTAINS 'selected';\n");

        assertEquals(0, code);
        assertEquals(5, countOccurrences(harness.stdout(), "Assert successful."), harness.stdout());
    }

    @Test
    @DisplayName("_ASSERT 带断言名：状态文案含 [名字]")
    void assertWithName() {
        int code = harness.run("_assert 1 = 1, mycheck;\n");

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("Assert [mycheck] successful."), harness.stdout());
    }

    @Test
    @DisplayName("_ASSERT 失败：状态文案进 stdout，同时按错误语句处理（stderr + 退出码 1）")
    void assertFails() {
        int code = harness.run("_assert 1 = 2, bad;\n");

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("Assert [bad] fail."), harness.stdout());
        assertTrue(harness.stderr().contains("Assert [bad] fail."), harness.stderr());
    }

    @Test
    @DisplayName("_ASSERT 形态非法：SyntaxError 文案，且不抛出异常")
    void assertSyntaxError() {
        int code = harness.run("_assert RESULT.NOSUCH = 1;\n");

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("Assert fail. SyntaxError =>"), harness.stdout());
    }

    // ------------------------------------------------------------------- WHENEVER

    @Test
    @DisplayName("_WHENEVER ERROR CONTINUE：出错后继续跑完，退出码为 1")
    void wheneverContinueKeepsRunning() {
        harness.sql.setError("select broken", "ERROR: boom");

        int code = harness.run("_whenever error continue\nselect broken;\n_assert 1 = 1;\n");

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("Assert successful."), harness.stdout());
    }

    @Test
    @DisplayName("_WHENEVER ERROR EXIT 7：第一次出错就立刻以 7 结束，后续语句不执行")
    void wheneverExitStopsImmediately() {
        harness.sql.setError("select broken", "ERROR: boom");

        int code = harness.run("_whenever error exit 7\nselect broken;\n_assert 1 = 1;\n");

        assertEquals(7, code);
        assertFalse(harness.stdout().contains("Assert successful."), harness.stdout());
    }

    // ------------------------------------------------------------------ 退出码

    @Test
    @DisplayName("_EXIT n / _QUIT n：立刻以 n 结束，后续语句不执行")
    void exitStopsWithCode() {
        // 退出是"整次运行"的状态，因此每条脚本用独立的夹具
        assertEquals(5, harness.run("_assert 1 = 1;\n_exit 5\n"));

        Harness quit = new Harness(tempDir);
        try {
            assertEquals(0, quit.run("_quit\n"));
        } finally {
            quit.close();
        }

        Harness neverReached = new Harness(tempDir);
        try {
            assertEquals(3, neverReached.run("_exit 3\n_assert 1 = 2;\n"));
            assertFalse(neverReached.stdout().contains("Assert"), neverReached.stdout());
        } finally {
            neverReached.close();
        }
    }

    @Test
    @DisplayName("显式退出码优先于错误计数")
    void explicitExitCodeWins() {
        harness.sql.setError("select broken", "ERROR: boom");

        int code = harness.run("select broken;\n_exit 9\n");

        assertEquals(9, code);
    }

    // -------------------------------------------------------------------- SPOOL

    @Test
    @DisplayName("_SPOOL：数据、状态行、回显的语句与错误都写进去；停止后不再写")
    void spoolTeesOutput() throws Exception {
        String script = "_spool spool.txt\n"
                + "_echo title\nhello\nworld\necho off\n"
                + "_assert 1 = 2, bad\n"
                + "_spool off\n"
                + "_echo title2\nafter\necho off\n";

        int code = harness.run(script);

        assertEquals(0, code);
        String spooled = Files.readString(tempDir.resolve("spool.txt"));
        assertTrue(spooled.contains("hello\nworld"), "spool 内容: " + spooled);
        assertTrue(spooled.contains("Spool started ["), "spool 内容: " + spooled);
        assertTrue(spooled.contains("Spool stopped."), "spool 内容: " + spooled);
        assertFalse(spooled.contains("after"), "停止之后的数据不得进 spool: " + spooled);
        assertTrue(harness.stdout().contains("after"), harness.stdout());
        // 与日志同一份内容：回显的语句与错误行也进 spool
        assertTrue(spooled.contains("_assert 1 = 2, bad"), "回显的语句也要进 spool: " + spooled);
        assertTrue(spooled.contains("Assert [bad] fail."), "错误也要进 spool: " + spooled);
    }

    @Test
    @DisplayName("_SPOOL OFF 按用户本意停止 spool，不生成名为 off 的文件")
    void spoolOffStopsSpooling() throws Exception {
        int code = harness.run("_spool spool.txt\n"
                + "_echo t\nhello\necho off\n"
                + "_spool off\n"
                + "_echo t2\nafter\necho off\n");

        assertEquals(0, code);
        String spooled = Files.readString(tempDir.resolve("spool.txt"));
        assertTrue(spooled.contains("hello"), "spool 内容: " + spooled);
        assertTrue(spooled.contains("Spool stopped."), "spool 内容: " + spooled);
        assertFalse(spooled.contains("after"), "停止之后的数据不得进 spool: " + spooled);
        assertFalse(Files.exists(tempDir.resolve("off")), "'off' 不得再被当成文件名");
    }

    @Test
    @DisplayName("_SPOOL OFF：没在 spool 时给 'Spool is not active.'（不是错误）")
    void spoolOffWithoutSpoolingIsReported() {
        int code = harness.run("_spool off\n");

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("Spool is not active."), harness.stdout());
    }

    @Test
    @DisplayName("不带参数的 _SPOOL 不支持：可读错误 + 退出码 1（停止 spool 只认 _SPOOL OFF）")
    void bareSpoolIsRejected() {
        int code = harness.run("_spool spool.txt\n"
                + "_echo t\nhello\necho off\n"
                + "_spool\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Spool needs a file name (use _SPOOL OFF to stop spooling)."),
                harness.stderr());
    }

    @Test
    @DisplayName("要输出到名为 off 的文件：写路径（_spool ./off）仍然可达")
    void spoolToFileNamedOffUsesAPath() throws Exception {
        int code = harness.run("_spool ./off\n_echo t\nhello\necho off\n_spool off\n");

        assertEquals(0, code);
        assertTrue(Files.isRegularFile(tempDir.resolve("off")), "./off 应当仍然指向名为 off 的文件");
        assertTrue(Files.readString(tempDir.resolve("off")).contains("hello"));
    }

    @Test
    @DisplayName("_SPOOL OFF 大小写不敏感：_SPOOL OFF / _spool Off 都是停 spool")
    void spoolOffIsCaseInsensitive() {
        int code = harness.run("_spool out.txt\n_spool OFF\n");

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("Spool stopped."), harness.stdout());
        assertFalse(Files.exists(tempDir.resolve("OFF")));
    }

    @Test
    @DisplayName("_SPOOL 打开失败：可读错误而不是异常")
    void spoolOpenFailure() {
        int code = harness.run("_spool no-such-dir/x.txt\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Cannot open spool file ["), harness.stderr());
    }

    // --------------------------------------------------------------------- ECHO

    @Test
    @DisplayName("_ECHO：块内容作为数据行输出")
    void echoPrintsBlock() {
        assertEquals(0, harness.run("_echo title\nline1\nline2\necho off\n"));
        assertTrue(harness.stdout().contains("line1\nline2\n"), harness.stdout());
    }

    @Test
    @DisplayName("_ECHO 的块为空（`_echo title` 紧跟 `echo off`）：合法、不输出、不报错")
    void echoWithEmptyBlockIsAccepted() {
        // 空块以前被判成"没写完"（Incomplete command）：ECHO_OFF 的正则要求"前置换行"，
        // 正文为空时那条正则永远匹配不上。现在正则允许"块就从 echo off 开始"，空块因此是合法的。
        int code = harness.run("_echo only-title\necho off\n");

        assertEquals(0, code, harness.stderr());
        assertEquals("", harness.stdout(), "空块没有任何数据行: " + harness.stdout());
        assertEquals("", harness.stderr(), harness.stderr());
        assertTrue(harness.sql.statements.isEmpty(),
                "_ 命令不得被送进执行层: " + harness.sql.statements);
    }

    @Test
    @DisplayName("_ECHO 没有收尾的 echo off（脚本结束）：仍然报 Incomplete command")
    void echoWithoutTerminatorIsIncomplete() {
        int code = harness.run("_echo only-title\nstill-body\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Incomplete command [_echo]"), harness.stderr());
        assertTrue(harness.sql.statements.isEmpty(), harness.sql.statements.toString());
    }

    // -------------------------------------------------------------------- START

    @Test
    @DisplayName("_START：子脚本读到自己的 ARGVn，父脚本的 ARGVn 恢复")
    void startPassesArgvAndRestoresParent() throws Exception {
        Files.writeString(tempDir.resolve("child.sql"),
                "_echo child\nchild=${ARGV1}/${ARGV2}\necho off\n");
        String script = "_echo p\nparent=${ARGV1}\necho off\n"
                + "_start child.sql C1 C2\n"
                + "_echo p2\nparent2=${ARGV1}\necho off\n";

        int code = harness.run(script, List.of("P1"));

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("parent=P1"), harness.stdout());
        assertTrue(harness.stdout().contains("child=C1/C2"), harness.stdout());
        assertTrue(harness.stdout().contains("parent2=P1"), harness.stdout());
    }

    @Test
    @DisplayName("_START 找不到脚本：可读错误")
    void startMissingScript() {
        int code = harness.run("_start no-such.sql\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Script file [no-such.sql] does not exist."), harness.stderr());
    }

    @Test
    @DisplayName("_START 递归过深：给可读错误而不是栈溢出")
    void startRecursionIsLimited() throws Exception {
        Files.writeString(tempDir.resolve("self.sql"), "_start self.sql\n");

        int code = harness.run("_start self.sql\n");

        assertEquals(0, code);
        assertEquals(1, countOccurrences(harness.stderr(), "nesting is too deep"), harness.stderr());
    }

    @Test
    @DisplayName("RUN.EXITCODE 是最近一次 _START 子脚本的退出码（文档语义）")
    void runExitCodeReflectsTheSubScript() throws Exception {
        Files.writeString(tempDir.resolve("ok.sql"), "_echo t\nok\necho off\n");
        Files.writeString(tempDir.resolve("bad.sql"), "_bogus 1;\n");

        int okCode = harness.run("_start ok.sql\n_assert RUN.EXITCODE = 0\n");
        assertEquals(0, okCode, harness.stderr());
        assertTrue(harness.stdout().contains("Assert successful."), harness.stdout());

        Harness failing = new Harness(tempDir);
        try {
            int badCode = failing.run("_start bad.sql\n_assert RUN.EXITCODE = 1\n");
            assertEquals(0, badCode, "子脚本报错当然会让整次运行是 1");
            assertTrue(failing.stdout().contains("Assert successful."), failing.stdout());
            assertFalse(failing.stdout().contains("Assert fail."), failing.stdout());
        } finally {
            failing.close();
        }
    }

    @Test
    @DisplayName("JOB.<名>.STATUS/EXITCODE 可被断言（文档里的取值器）")
    void jobStatusIsAssertable() throws Exception {
        Files.writeString(tempDir.resolve("job-child.sql"), "_echo t\njob-ran\necho off\n");
        String script = "_job jobmanager on\n"
                + "_job create J1 script=job-child.sql\n"
                + "_job start J1\n"
                + "_job wait J1 timeout=20\n"
                + "_assert JOB.J1.STATUS = DONE\n"
                + "_assert JOB.J1.EXITCODE = 0\n"
                + "_job show J1\n";

        int code = harness.run(script);

        assertEquals(0, code, harness.stderr());
        assertEquals(2, countOccurrences(harness.stdout(), "Assert successful."), harness.stdout());
    }

    @Test
    @DisplayName("未知命令的报错不带语句终结符：`_bogus;` 报 [_bogus]，不报 [_bogus;]")
    void unknownCommandNameHasNoTerminator() {
        int code = harness.run("_bogus;\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Unknown command [_bogus]."), harness.stderr());
        assertFalse(harness.stderr().contains("[_bogus;]"), harness.stderr());
    }

    // ---------------------------------------------------------------- SET / LOAD

    @Test
    @DisplayName("_SET 无参打印选项表；未知选项/非法取值给可读错误")
    void setOptions() {
        assertEquals(0, harness.run("_set\n"));
        assertTrue(harness.stdout().contains("Current set options:"), harness.stdout());
        assertTrue(harness.stdout().contains("feedback"), harness.stdout());
        assertTrue(harness.stdout().contains("autocommit"), harness.stdout());

        assertEquals(0, harness.run("_set BOGUS 1\n"));
        assertTrue(harness.stderr().contains("Unknown set option [BOGUS]."), harness.stderr());

        Harness third = new Harness(tempDir);
        try {
            assertEquals(0, third.run("_set FEEDBACK MAYBE\n"));
            assertTrue(third.stderr().contains("requires ON or OFF"), third.stderr());
        } finally {
            third.close();
        }
    }

    @Test
    @DisplayName("_SET TIMEOUT：非负整数校验，并**真的**套到执行层")
    void integerSetOptions() {
        assertEquals(0, harness.run("_set TIMEOUT 7\n"), harness.stderr());
        // 只记进选项表是不够的：这个值的消费方是执行层（建 Statement 时设超时）
        assertEquals(7, harness.sql.queryTimeoutSeconds());
        assertTrue(harness.stdout().isEmpty(), "设置选项本身不该产生输出: " + harness.stdout());

        Harness fractional = new Harness(tempDir);
        try {
            assertEquals(0, fractional.run("_set TIMEOUT 1.5\n"));
            assertTrue(fractional.stderr().contains("requires a non-negative integer"),
                    fractional.stderr());
        } finally {
            fractional.close();
        }
    }
    @Test
    @DisplayName("_SET @name 设变量、_SET AUTOCOMMIT 传到执行层")
    void setVariableAndAutoCommit() {
        assertEquals(0, harness.run("_set @who world\n_echo t\nhello ${who}\necho off\n"));
        assertTrue(harness.stdout().contains("hello world"), harness.stdout());

        assertEquals(0, harness.run("_set AUTOCOMMIT OFF\n"));
        assertFalse(harness.sql.isAutoCommit());

        Harness third = new Harness(tempDir);
        try {
            assertEquals(0, third.run("_set @x\n"));
            assertTrue(third.stderr().contains("Missing value for variable [x]."), third.stderr());
        } finally {
            third.close();
        }
    }

    // ------------------------------------------------------------- CONNECT/SESSION

    @Test
    @DisplayName("_CONNECT/_DISCONNECT/_SESSION 走同一条输出路径（fake 执行层）")
    void connectDisconnectAndSession() {
        int code = harness.run("_connect 127.0.0.1:43175/db1\n"
                + "_session save main\n"
                + "_disconnect\n");

        assertEquals(0, code);
        assertEquals("127.0.0.1", harness.sql.connects.get(0).get("host"));
        assertTrue(harness.stdout().contains("Database connected."), harness.stdout());
        assertTrue(harness.stdout().contains("Session save [main]."), harness.stdout());
        assertTrue(harness.stdout().contains("Database disconnected."), harness.stdout());

        Harness second = new Harness(tempDir);
        try {
            assertEquals(0, second.run("_disconnect\n"));
            assertTrue(second.stderr().contains("Not connected."), second.stderr());
        } finally {
            second.close();
        }
    }

    // ------------------------------------------------------------------- COMPARE

    @Test
    @DisplayName("_COMPARE 有差异：差异表 + Compare failed. + 退出码 1；相同则成功")
    void compareDrivesExitCode() throws Exception {
        Files.writeString(tempDir.resolve("work.txt"), "Hello\nChinA\nRussia\nAmerica\n");
        Files.writeString(tempDir.resolve("ref.txt"), "Hello\nChina\nAmerica\nFrance\n");

        int code = harness.run("_compare work.txt ref.txt NOCASE\n");

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("Compare failed."), harness.stdout());
        // 控制台不再打印差异表：差异在 work.dif 里
        assertFalse(harness.stdout().contains("Russia"), "差异表不该上屏: " + harness.stdout());
        assertTrue(Files.readString(tempDir.resolve("work.dif")).contains("Russia"), "差异应当写进 work.dif");

        Harness second = new Harness(tempDir);
        try {
            Files.writeString(tempDir.resolve("same1.txt"), "a\nb\n");
            Files.writeString(tempDir.resolve("same2.txt"), "a\nb\n");
            assertEquals(0, second.run("_compare same1.txt same2.txt\n"));
            assertTrue(second.stdout().contains("Compare successful."), second.stdout());
        } finally {
            second.close();
        }
    }

    // ---------------------------------------------------------------- 其它小命令

    @Test
    @DisplayName("_HELP：总览与主题；未知主题可读错误")
    void help() {
        assertEquals(0, harness.run("_help\n"));
        assertTrue(harness.stdout().contains("Available commands:"), harness.stdout());

        Harness second = new Harness(tempDir);
        try {
            assertEquals(0, second.run("_help ASSERT\n"));
            assertTrue(second.stdout().contains("_ASSERT"), second.stdout());
        } finally {
            second.close();
        }

        Harness third = new Harness(tempDir);
        try {
            assertEquals(0, third.run("_help NOPE\n"));
            assertTrue(third.stderr().contains("Unknown help topic [NOPE]."), third.stderr());
        } finally {
            third.close();
        }
    }

    @Test
    @DisplayName("_HELP：总览与主题里不再出现已删除的写法（_LOAD MAP / _JOB REGISTER / /mem）")
    void helpNeverMentionsRemovedCommands() {
        StringBuilder overview = new StringBuilder();
        assertEquals(0, harness.run("_help\n"));
        overview.append(harness.stdout());
        // 每个主题单独跑一遍：已删除的写法不得从任何一条帮助里漏出来
        for (String topic : new String[] {"CONNECT", "JOB", "LOAD", "SET", "SPOOL", "START",
                "COMPARE", "ASSERT", "WHENEVER", "ECHO", "HOST", "SESSION"}) {
            Harness topicHarness = new Harness(tempDir);
            try {
                assertEquals(0, topicHarness.run("_help " + topic + "\n"), topic);
                overview.append(topicHarness.stdout());
            } finally {
                topicHarness.close();
            }
        }
        String help = overview.toString();
        // 历史写法 _LOAD MAP 没有回来（_LOAD 唯一的合法目标是 JDBCDRIVER）
        for (String gone : new String[] {"LOAD MAP", "REGISTER", "DEREGISTER", "/mem"}) {
            assertFalse(help.contains(gone),
                    "_HELP 里不得再出现已删除的命令/写法 [" + gone + "]:\n" + help);
        }
        // map 规则（含它的文件格式）已随 _LOAD MAP 一起删除
        assertFalse(help.contains("map rules"), "_HELP 里不得再出现 map 规则:\n" + help);
        // 现在的 _LOAD 只宣传 JDBCDRIVER
        assertTrue(help.contains("_LOAD JDBCDRIVER <jar>"),
                "_HELP 里应当给出 _LOAD JDBCDRIVER 的用法:\n" + help);
        // 变量的唯一写法是 ${name}：帮助里不得再宣传双花括号写法，也不得再提已移除的 ENV(...) 写法
        assertTrue(help.contains("${name}"), "_HELP 里应当给出 ${name} 写法:\n" + help);
        assertFalse(help.contains("{{"), "_HELP 里不得再出现双花括号的变量写法:\n" + help);
        assertFalse(help.contains("ENV("), "_HELP 里不得再出现已移除的 ENV(...) 写法:\n" + help);
        // 两种 _CONNECT 写法（简写 / 全写）都要在帮助里说清楚，并且都要声明其余写法报无效连接串
        assertTrue(help.contains("Invalid connection string [...]"),
                "_HELP 里应当说明其他形式会报无效连接串:\n" + help);
        assertTrue(help.contains("_CONNECT <host>:<port>/<service>"),
                "帮助里应当给出 host:port/service 的简写形态:\n" + help);
        assertTrue(help.contains("_CONNECT [<user>[/<password>]@]jdbc:slackerdb://<host>:<port>/[<database>]"),
                "帮助里应当给出 jdbc:slackerdb:// 的全写形态:\n" + help);
        assertTrue(help.contains("_CONNECT [<user>[/<password>]@]jdbc:<scheme>:<url>"),
                "帮助里应当给出其它驱动的通用形态:\n" + help);
        assertTrue(help.contains("no defaults"),
                "简写形态仍然要写明没有缺省值:\n" + help);
    }

    @Test
    @DisplayName("_SLEEP 0 可用、非法时间给可读错误")
    void sleepValidatesItsArgument() {
        assertEquals(0, harness.run("_sleep 0\n"));

        Harness second = new Harness(tempDir);
        try {
            assertEquals(0, second.run("_sleep abc\n"));
            assertTrue(second.stderr().contains("Invalid sleep time [abc]:"), second.stderr());
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("_HOST：stdout 逐行进数据通道；非 0 退出码计一次错误")
    void hostCommand() {
        assertEquals(0, harness.run("_host echo dbcli-host-ok\n"));
        assertTrue(harness.stdout().contains("dbcli-host-ok"), harness.stdout());

        Harness second = new Harness(tempDir);
        try {
            assertEquals(0, second.run("_host exit 3\n"));
            assertTrue(second.stderr().contains("finished with exit code [3]"), second.stderr());
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("_SLEEP 是可中断的长操作：中断线程后立刻返回，并把 'Sleep interrupted.' 记成一次错误")
    void sleepIsInterruptible() throws Exception {
        try (Harness harness = new Harness(tempDir)) {
            java.util.concurrent.atomic.AtomicInteger exitCode = new java.util.concurrent.atomic.AtomicInteger(-1);
            Thread worker = new Thread(() -> exitCode.set(harness.run("_sleep 5\n")), "dbcli-sleep-probe");
            worker.start();
            Thread.sleep(300L);                    // 先让它真的睡进去
            long startedAt = System.nanoTime();
            worker.interrupt();
            worker.join(5_000L);

            assertFalse(worker.isAlive(), "中断之后 _SLEEP 必须立刻返回，而不是把 5 秒睡满");
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            assertTrue(elapsedMillis < 3_000L, "被中断的睡眠应当很快结束，实际 " + elapsedMillis + "ms");
            assertTrue(harness.stderr().contains("Sleep interrupted."), harness.stderr());
            assertEquals(0, exitCode.get(), "被中断的睡眠按一次错误计（退出码 1）");
        }
    }

    @Test
    @DisplayName("_HOST 是可中断的长操作：中断线程后销毁本地子进程并如实报告")
    void hostCommandIsInterruptible() throws Exception {
        // 跨平台的长命令：Windows 的 ping -n 6 约 5 秒，POSIX 的 sleep 6 就是 6 秒
        String longCommand = org.slackerdb.common.utils.OSUtil.isWindows()
                ? "ping -n 6 127.0.0.1"
                : "sleep 6";
        // 工作目录放在 target/ 而不是 @TempDir：被强杀的本地命令在 Windows 上会短暂攥着它的
        // 工作目录句柄，JUnit 因此删不掉临时目录（本类里只有这一条真的起子进程）。
        // target/ 由 `mvn clean` 清掉，正是不该让 JUnit 负责回收这类目录的原因。
        Path workDir = Path.of("target", "dbcli-host-interrupt-test");
        Files.createDirectories(workDir);
        try (Harness harness = new Harness(workDir)) {
            java.util.concurrent.atomic.AtomicInteger exitCode = new java.util.concurrent.atomic.AtomicInteger(-1);
            Thread worker = new Thread(() -> exitCode.set(harness.run("_host " + longCommand + "\n")),
                    "dbcli-host-probe");
            worker.start();
            Thread.sleep(500L);                    // 让子进程真的跑起来
            long startedAt = System.nanoTime();
            worker.interrupt();
            worker.join(10_000L);

            assertFalse(worker.isAlive(), "中断之后 _HOST 必须立刻返回（子进程已被销毁）");
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            assertTrue(elapsedMillis < 5_000L, "被中断的本地命令应当很快结束，实际 " + elapsedMillis + "ms");
            assertTrue(harness.stderr().contains("Host command interrupted ["),
                    "应当如实报告本地命令被中断: " + harness.stderr());
        }
    }

    @Test
    @DisplayName("_ECHO 里的变量替换：只有 ${} 一种写法；双花括号写法原样保留")
    void echoResolvesVariables() {
        int code = harness.run("_set @a 1\n_echo t\nA=${a} B={{a}} C={{unknown}}\necho off\n");

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("A=1 B={{a}} C={{unknown}}"), harness.stdout());
    }

    @Test
    @DisplayName("非法替换目标（表达式）：可读错误 + 该语句不执行")
    void illegalVariableExpressionIsReported() {
        int code = harness.run("_set @one 1\n_assert ${one+1} = 2;\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Unsupported variable expression [${one+1}]"),
                harness.stderr());
        assertFalse(harness.stdout().contains("Assert"), "该语句不得被执行: " + harness.stdout());
    }

    // -------------------------------------------------------------------- 工具

    /**
     * 新口径的守门断言：错误输出里**不得**出现任何解析层文案
     * （{@code line…:… expecting …} / {@code missing SQL_END} / {@code extraneous input} /
     * 脚本结尾不完整那一类）。使用者要看到的是数据库怎么说。
     */
    private static void assertNoParserText(String stderr) {
        for (String forbidden : new String[] {"expecting", "mismatched input", "extraneous input",
                "missing SQL_END", "Incomplete statement", "not terminated"}) {
            assertFalse(stderr.contains(forbidden),
                    "错误输出里不得出现解析层文案 [" + forbidden + "]: " + stderr);
        }
        assertFalse(stderr.matches("(?s).*line\\d+:\\d+.*"),
                "错误输出里不得出现解析器的行列位置: " + stderr);
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

    @Test
    @DisplayName("_exit 0 压过一切：出过错也按脚本作者显式指定的 0 退出")
    void exitZeroWinsOverErrors() {
        try (Harness harness = new Harness(tempDir)) {
            int code = harness.run("""
                    _assert 1 = 2, shouldFail
                    _exit 0
                    """);
            assertEquals(0, code, "_EXIT 是显式决定，_EXIT 0 就该得到 0");
        }
    }

    @Test
    @DisplayName("没有 _exit 时才听从运行结果：出过错 ⇒ 1，干净 ⇒ 0")
    void withoutExitTheRunResultDecides() {
        try (Harness failing = new Harness(tempDir)) {
            assertEquals(0, failing.run("_assert 1 = 2, shouldFail\n"), "没写 _exit ⇒ 有错就是 1");
        }
        try (Harness clean = new Harness(tempDir)) {
            assertEquals(0, clean.run("_assert 1 = 1, ok\n"), "没写 _exit ⇒ 干净就是 0");
        }
    }

    @Test
    @DisplayName("回归：显式非 0 退出码优先于错误计数（_exit 3 ⇒ 3、有错时 _exit 7 ⇒ 7）")
    void explicitNonZeroExitCodeWins() {
        // 每次运行用独立的 Harness：ScriptContext 是"一次运行"的状态，退出请求会留在里面
        try (Harness harness = new Harness(tempDir)) {
            assertEquals(3, harness.run("_exit 3\n"), "显式失败码应当原样返回");
        }
        try (Harness harness = new Harness(tempDir)) {
            assertEquals(7, harness.run("_assert 1 = 2, x\n_exit 7\n"), "显式失败码优先于错误计数 1");
        }
    }

    @Test
    @DisplayName("回归：脚本带 UTF-8 BOM 也能正常执行（Windows 工具默认会写 BOM）")
    void utf8BomIsTolerated() throws Exception {
        byte[] body = "_sleep 0\n_exit 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        java.nio.file.Path script = tempDir.resolve("bom.sql");
        java.nio.file.Files.write(script, withBom);

        try (Harness harness = new Harness(tempDir)) {
            assertEquals(0, harness.runner.runFile(script, List.of()), "带 BOM 的脚本应当正常跑完");
            assertEquals("", harness.stderr(), "不应因 BOM 报语法错误");
        }
    }

    // -------------------------------------------------- 通用 SQL / 美元引号体

    @Test
    @DisplayName("美元引号体：函数体里的 ';' 不切分，整条语句一次执行")
    void dollarQuotedFunctionIsOneStatement() {
        String script = "create or replace function f() returns int as $$\n"
                + "begin\n"
                + "  return 1;\n"
                + "end;\n"
                + "$$ language plpgsql;\n"
                + "select 1;\n";

        int code = harness.run(script);

        assertEquals(0, code, harness.stderr());
        assertEquals(List.of(
                        "create or replace function f() returns int as $$\nbegin\n  return 1;\nend;\n$$ language plpgsql",
                        "select 1"),
                harness.sql.statements);
    }

    @Test
    @DisplayName("DO $$…$$：函数体里的 ';' 与行首 '/' 都不切分")
    void dollarQuotedDoBlockIsOneStatement() {
        String script = "do $$ begin\n  raise notice 'x';\nend;\n$$;\nselect 1;\n";

        int code = harness.run(script);

        assertEquals(0, code, harness.stderr());
        assertEquals(List.of("do $$ begin\n  raise notice 'x';\nend;\n$$", "select 1"),
                harness.sql.statements);
    }

    @Test
    @DisplayName("通用 SQL（WITH / TRUNCATE / BEGIN;）整条执行，BEGIN; 的终结符不进语句原文")
    void genericStatementsAreExecuted() {
        String script = "with cte as (select 1 as x) select * from cte;\n"
                + "truncate table t;\n"
                + "begin;\n"
                + "commit;\n";

        int code = harness.run(script);

        assertEquals(0, code, harness.stderr());
        assertEquals(List.of("with cte as (select 1 as x) select * from cte", "truncate table t",
                "begin", "commit"), harness.sql.statements);
    }

    @Test
    @DisplayName("美元引号没闭合：语句一直判'没写完'（续行提示），脚本末尾把整段原文送执行层")
    void unclosedDollarQuoteTailIsSentToTheExecutionLayer() {
        int code = harness.run("do $$\nbegin\n  raise notice 'x';\n");

        assertEquals(0, code, harness.stderr());
        assertEquals(List.of("do $$\nbegin\n  raise notice 'x';"), harness.sql.statements,
                "脚本末尾没写完的那一段必须原样送执行层");
        assertEquals("", harness.stderr(), "不得有任何解析层文案: " + harness.stderr());
    }

    @Test
    @DisplayName("COPY … FROM '<文件>'：服务端读文件，语句照原样执行")
    void copyFromServerFileIsExecuted() {
        int code = harness.run("copy t from '/tmp/a.csv' with (format csv);\n");

        assertEquals(0, code, harness.stderr());
        assertEquals(List.of("copy t from '/tmp/a.csv' with (format csv)"), harness.sql.statements);
    }

    @Test
    @DisplayName("COPY … FROM STDIN：客户端明确拒绝（不把会挂住的语句发给服务端）")
    void copyFromStdinIsRefused() {
        int code = harness.run("copy t from stdin;\n");

        assertEquals(0, code, "被拒绝的语句算一次错误");
        assertTrue(harness.sql.statements.isEmpty(),
                "COPY … FROM STDIN 不应到达执行层: " + harness.sql.statements);
        assertTrue(harness.stderr().contains("COPY ... FROM STDIN"), harness.stderr());
    }

    // ---------------------------------------------------- 回归：本轮修掉的现有行为 BUG

    @Test
    @DisplayName("_ECHO 正文里的撇号不是字符串起点：块照旧以 echo off 收尾")
    void echoBlockToleratesQuotes() {
        int code = harness.run("_echo t\nit's fine\necho off\n");

        assertEquals(0, code);
        assertTrue(harness.stdout().contains("it's fine"), harness.stdout());
        assertFalse(harness.stdout().contains("Unknown command"),
                "块体里的撇号不该把整块变成未知命令: " + harness.stdout());
        assertFalse(harness.stderr().contains("Incomplete command"), harness.stderr());
    }


    @Test
    @DisplayName("_SLEEP 拒绝非有限值（1e400 = Infinity）：不再变成永不返回的等待")
    void sleepRejectsNonFiniteValues() {
        int code = harness.run("_sleep 1e400\n");

        assertEquals(0, code);
        assertTrue(harness.stderr().contains("Invalid sleep time [1e400]"), harness.stderr());
    }

    @Test
    @DisplayName("多行 _HOST 块（三引号包起来的多行命令）：脚本里也能跑完，不会报 Unknown command")
    void hostBlockRunsEveryCommand() {
        int code = harness.run("_host \"\"\"\necho one\necho two\n\"\"\"\n");

        assertEquals(0, code, harness.stderr());
        assertTrue(harness.stdout().contains("one"), harness.stdout());
        assertTrue(harness.stdout().contains("two"), harness.stdout());
        assertFalse(harness.stdout().contains("Unknown command"), harness.stdout());
    }

    @Test
    @DisplayName("_HOST：引号里的 & 不是分隔符")
    void hostKeepsQuotedAmpersand() {
        int code = harness.run("_host echo \"a & b\"\n");

        assertEquals(0, code, harness.stderr());
        assertTrue(harness.stdout().contains("& b"),
                "引号里的 & 属于同一条命令: " + harness.stdout());
        assertFalse(harness.stderr().contains("finished with exit code"),
                "不该被切成两条命令: " + harness.stderr());
    }

    @Test
    @DisplayName("一条语句的多个结果按顺序输出（CALL/DO），最近结果成为断言看到的结果")
    void multipleResultsArePrintedInOrder() {
        harness.sql.setResults("call p()", List.of(
                FakeSqlExecutor.query(List.of("A"), List.of("INTEGER"),
                        List.of(List.of(1)), "1 row selected."),
                FakeSqlExecutor.query(List.of("B"), List.of("VARCHAR"),
                        List.of(List.of("x")), "1 row selected.")));

        assertEquals(0, harness.run("call p();\n"));
        int first = harness.stdout().indexOf("| A");
        int second = harness.stdout().indexOf("| B");
        assertTrue(first >= 0 && second > first, "两个结果集都要打印且顺序不变: " + harness.stdout());
        assertEquals(2, harness.stdout().split("1 row selected\\.", -1).length - 1,
                "每个结果各有一行摘要: " + harness.stdout());
    }

    @Test
    @DisplayName("_START 的脚本名含非法路径字符：可读错误，不抛 InvalidPathException")
    void startWithInvalidPathIsReadable() {
        // 为什么喂 IR 而不是脚本文本：POSIX 上唯一非法的路径字符是 NUL（见 IllegalPaths），
        // 而 NUL 出现在脚本文本里会被词法器判成解析失败，命令根本执行不到
        // （只会得到一条 Unknown command）。被测的是 StartCommand 对非法路径的反应，
        // 不是词法器对 NUL 的态度，所以这里绕过文本层、走同一张命令表直接执行。
        harness.executeCommand("START", Map.of("script", IllegalPaths.VALUE));

        assertTrue(harness.stderr().contains(IllegalPaths.INVALID_PREFIX), harness.stderr());
    }

    @Test
    @DisplayName("_SPOOL 的文件名含非法路径字符：可读错误，不抛 InvalidPathException")
    void spoolWithInvalidPathIsReadable() {
        harness.executeCommand("SPOOL", Map.of("file", IllegalPaths.VALUE));

        assertTrue(harness.stderr().contains(IllegalPaths.INVALID_PREFIX), harness.stderr());
    }
    /** 测试用的小夹具：假执行层 + 两个内存流 + 一个临时工作目录。 */
    private static final class Harness implements AutoCloseable {

        private final FakeSqlExecutor sql = new FakeSqlExecutor();
        private final StringWriter out = new StringWriter();
        private final StringWriter err = new StringWriter();
        private final ScriptContext context;
        private final ScriptRunner runner;

        Harness(Path workingDirectory) {
            this(workingDirectory, Map.of(), false);
        }

        Harness(Path workingDirectory, Map<String, String> variables, boolean silent) {
            VariableResolver resolver = new VariableResolver(variables);
            this.context = new ScriptContext(sql, new ScriptOutput(new PrintWriter(out, true),
                    new PrintWriter(err, true), silent), resolver, workingDirectory);
            this.runner = new ScriptRunner(context);
        }

        int run(String script) {
            return runner.runText(script, "<test>", List.of());
        }

        int run(String script, List<String> argv) {
            return runner.runText(script, "<test>", argv);
        }

        /**
         * 直接执行一条命令的 IR：与 {@code ScriptRunner} 的分派走同一张 {@link CommandCatalog}，
         * 只是跳过文本解析器（用于喂"解析器携带不了"的取值，例如 NUL）。
         */
        void executeCommand(String name, Map<String, Object> ir) {
            ScriptCommand command = CommandCatalog.find(name);
            assertNotNull(command, "command [" + name + "] must be registered");
            Map<String, Object> withName = new LinkedHashMap<>(ir);
            withName.put("name", name);
            command.execute(new CommandCall(context, runner), withName);
        }

        String stdout() {
            return out.toString();
        }

        String stderr() {
            return err.toString();
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
