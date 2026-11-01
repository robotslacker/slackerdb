package org.slackerdb.dbcli.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.assertion.ResultModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 控制流与真实 ScriptSession/ScriptRunner 的集成：输出、循环重放、结果条件、控制台续行状态。 */
class ScriptFlowTest {

    @TempDir
    Path tempDir;

    private Harness harness;

    private Harness harness() {
        harness = new Harness(tempDir);
        return harness;
    }

    @Test
    @DisplayName("IF/ELSEIF/ELSE：只执行命中的分支，_ECHO 输出正确")
    void ifBranches() {
        Harness h = harness();

        int code = h.run("""
                _IF {% 1 = 2 %}
                  _ECHO t
                  one
                  echo off
                _ELSEIF {% 2 = 2 %}
                  _ECHO t
                  two
                  echo off
                _ELSE
                  _ECHO t
                  three
                  echo off
                _ENDIF
                """);

        assertEquals(0, code, h.stderr());
        assertTrue(h.stdout().contains("two"), h.stdout());
        assertFalse(h.stdout().contains("one"), h.stdout());
        assertFalse(h.stdout().contains("three"), h.stdout());
    }

    @Test
    @DisplayName("_LOOP n：每条 body 语句重放 n 次，每轮一条进度行")
    void countLoopReplaysStatements() {
        Harness h = harness();

        int code = h.run("""
                _LOOP 3
                  insert into t values (1);
                _ENDLOOP
                """);

        assertEquals(0, code, h.stderr());
        assertEquals(List.of("insert into t values (1)", "insert into t values (1)",
                "insert into t values (1)"), h.sql.statements);
        assertEquals(3, count(h.stdout(), "Loop iteration "), h.stdout());
    }

    @Test
    @DisplayName("LOOPTRACE OFF：循环仍执行，但不打印进度行")
    void loopTraceOff() {
        Harness h = harness();

        int code = h.run("""
                _SET LOOPTRACE OFF
                _LOOP 2
                  insert into t values (1);
                _ENDLOOP
                """);

        assertEquals(0, code, h.stderr());
        assertEquals(2, h.sql.statements.size());
        assertFalse(h.stdout().contains("Loop iteration"), h.stdout());
    }

    @Test
    @DisplayName("FEEDBACK OFF 也抑制进度行，但循环照常执行")
    void feedbackOffSuppressesLoopTrace() {
        Harness h = harness();

        int code = h.run("""
                _SET FEEDBACK OFF
                _LOOP 2
                  insert into t values (1);
                _ENDLOOP
                """);

        assertEquals(0, code, h.stderr());
        assertEquals(2, h.sql.statements.size());
        assertFalse(h.stdout().contains("Loop iteration"), h.stdout());
    }

    @Test
    @DisplayName("IF 条件读取最近一条 SQL 的结果：RESULT.ROWS[0][0]")
    void conditionUsesLastSqlResult() {
        Harness h = harness();
        h.sql.setResult("select count(*) as n from t", FakeSqlExecutor.query(
                List.of("n"), List.of("INTEGER"), List.of(List.of(3)), "1 row selected."));

        int code = h.run("""
                select count(*) as n from t;
                _IF {% RESULT.ROWS[0][0] = 3 %}
                  _ECHO t
                  three
                  echo off
                _ELSE
                  _ECHO t
                  other
                  echo off
                _ENDIF
                """);

        assertEquals(0, code, h.stderr());
        assertTrue(h.stdout().contains("three"), h.stdout());
        assertFalse(h.stdout().contains("other"), h.stdout());
    }

    @Test
    @DisplayName("_LOOP WHILE / UNTIL 与 _LOOP BREAK")
    void whileUntilAndBreak() {
        Harness h = harness();
        h.sql.setResult("select n from t", FakeSqlExecutor.query(
                List.of("n"), List.of("INTEGER"), List.of(List.of(1)), "1 row selected."));

        int code = h.run("""
                _LOOP WHILE {% FALSE %}
                  insert into never values (1);
                _ENDLOOP
                _LOOP 5
                  insert into once values (1);
                  _LOOP BREAK
                  insert into after-break values (1);
                _ENDLOOP
                _LOOP UNTIL {% TRUE %}
                  insert into until-body values (1);
                _ENDLOOP
                """);

        assertEquals(0, code, h.stderr());
        assertEquals(List.of("insert into once values (1)", "insert into until-body values (1)"),
                h.sql.statements);
        assertTrue(h.stdout().contains("Loop did not execute (0 iterations)."), h.stdout());
        assertTrue(h.stdout().contains("Loop iteration 1 of 5."), h.stdout());
        assertTrue(h.stdout().contains("Loop iteration 1."), h.stdout());
    }

    @Test
    @DisplayName("嵌套循环：内层每轮一条 depth 进度行")
    void nestedLoops() {
        Harness h = harness();

        int code = h.run("""
                _LOOP 2
                  insert into outer_t values (1);
                  _LOOP 2
                    insert into inner_t values (1);
                  _ENDLOOP
                _ENDLOOP
                """);

        assertEquals(0, code, h.stderr());
        assertEquals(1, count(h.stdout(), "Loop iteration 1 of 2."), h.stdout());
        assertEquals(1, count(h.stdout(), "Loop iteration 2 of 2."), h.stdout());
        assertEquals(4, count(h.stdout(), "(depth 2)"), h.stdout());
        assertEquals(2, h.sql.statements.stream().filter("insert into outer_t values (1)"::equals).count());
        assertEquals(4, h.sql.statements.stream().filter("insert into inner_t values (1)"::equals).count());
    }

    @Test
    @DisplayName("_ECHO 块在循环里按轮次输出数据")
    void echoBlockInsideLoop() {
        Harness h = harness();

        int code = h.run("""
                _LOOP 3
                  _ECHO t
                  hello
                  echo off
                _ENDLOOP
                """);

        assertEquals(0, code, h.stderr());
        assertEquals(3, count(h.stdout(), "hello"), h.stdout());
        assertEquals(3, count(h.stdout(), "Loop iteration "), h.stdout());
    }

    @Test
    @DisplayName("循环体里的多行 SQL 保持原样重放")
    void multiLineSqlInsideLoop() {
        Harness h = harness();

        int code = h.run("""
                _LOOP 2
                  insert into t
                    values (1);
                _ENDLOOP
                """);

        assertEquals(0, code, h.stderr());
        assertEquals(List.of("insert into t\n    values (1)", "insert into t\n    values (1)"), h.sql.statements);
    }

    @Test
    @DisplayName("_EXIT 在循环里立即结束整次运行")
    void exitInsideLoop() {
        Harness h = harness();

        int code = h.run("""
                _LOOP 5
                  _EXIT 7
                _ENDLOOP
                insert into never values (1);
                """);

        assertEquals(7, code, h.stderr());
        assertEquals(1, count(h.stdout(), "Loop iteration "), h.stdout());
        assertEquals(List.of(), h.sql.statements);
    }

    @Test
    @DisplayName("未关闭块：报错、不执行半截块")
    void unclosedBlockIsReported() {
        Harness h = harness();

        int code = h.run("""
                _IF {% TRUE %}
                  insert into t values (1);
                """);

        assertEquals(0, code, h.stderr());
        assertTrue(h.stderr().contains("_IF requires _ENDIF"), h.stderr());
        assertEquals(List.of(), h.sql.statements);
    }

    @Test
    @DisplayName("条件错误：放弃整个 IF，但后续语句继续")
    void conditionErrorContinues() {
        Harness h = harness();

        int code = h.run("""
                _IF {% RESULT.NOPE = 1 %}
                  insert into never values (1);
                _ELSE
                  insert into also-never values (1);
                _ENDIF
                insert into after values (1);
                """);

        assertEquals(0, code, h.stderr());
        assertTrue(h.stderr().contains("If condition error"), h.stderr());
        assertEquals(List.of("insert into after values (1)"), h.sql.statements);
    }

    @Test
    @DisplayName("_WHENEVER ERROR EXIT：条件错误按设置退出")
    void wheneverExitOnConditionError() {
        Harness h = harness();

        int code = h.run("""
                _WHENEVER ERROR EXIT 3
                _IF {% RESULT.NOPE = 1 %}
                  insert into never values (1);
                _ENDIF
                insert into after values (1);
                """);

        assertEquals(3, code, h.stderr());
        assertEquals(List.of(), h.sql.statements);
    }

    @Test
    @DisplayName("块内 create procedure ... / 整段捕获，不按内部 ';' 切碎")
    void procedureInsideIf() {
        Harness h = harness();

        int code = h.run("""
                _IF {% TRUE %}
                create procedure p() begin
                  select 1;
                end;
                /
                _ENDIF
                """);

        assertEquals(0, code, h.stderr());
        assertEquals(List.of("create procedure p() begin\n  select 1;\nend;"), h.sql.statements);
    }

    @Test
    @DisplayName("_ECHO 数据里出现 _ENDIF 不会提前关块")
    void endifInsideEchoBlockDoesNotClose() {
        Harness h = harness();

        int code = h.run("""
                _IF {% TRUE %}
                _ECHO t
                _ENDIF
                still-data
                echo off
                insert into after values (1);
                _ENDIF
                """);

        assertEquals(0, code, h.stderr());
        assertTrue(h.stdout().contains("_ENDIF"), h.stdout());
        assertTrue(h.stdout().contains("still-data"), h.stdout());
        assertEquals(List.of("insert into after values (1)"), h.sql.statements);
    }

    @Test
    @DisplayName("块内多行 _HOST \"\"\"：边界在 \"\"\" 收尾，不吞掉 _ENDIF")
    void hostBlockInsideIf() {
        Harness h = harness();

        int code = h.run("""
                _IF {% TRUE %}
                _HOST \"\"\"
                echo host-block
                \"\"\"
                _ENDIF
                """);

        assertEquals(0, code, h.stderr());
        assertTrue(h.stdout().contains("host-block"), h.stdout());
    }

    @Test
    @DisplayName("_START 子脚本里的控制流不污染父脚本")
    void childScriptFlowDoesNotLeak() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("child.sql"), """
                _IF {% TRUE %}
                  insert into child values (1);
                _ENDIF
                """);
        Harness h = harness();

        int code = h.run("""
                _IF {% TRUE %}
                  _START child.sql
                  insert into parent values (1);
                _ENDIF
                """);

        assertEquals(0, code, h.stderr());
        assertEquals(List.of("insert into child values (1)", "insert into parent values (1)"),
                h.sql.statements);
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /** 假执行层 + 两个内存流。 */
    private static final class Harness implements AutoCloseable {
        private final FakeSqlExecutor sql = new FakeSqlExecutor();
        private final StringWriter out = new StringWriter();
        private final StringWriter err = new StringWriter();
        private final ScriptContext context;
        private final ScriptRunner runner;

        Harness(Path workingDirectory) {
            VariableResolver resolver = new VariableResolver(Map.of());
            this.context = new ScriptContext(sql, new ScriptOutput(new PrintWriter(out, true),
                    new PrintWriter(err, true), false), resolver, workingDirectory);
            this.runner = new ScriptRunner(context);
        }

        int run(String script) {
            return runner.runText(script, "<test>", List.of());
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
