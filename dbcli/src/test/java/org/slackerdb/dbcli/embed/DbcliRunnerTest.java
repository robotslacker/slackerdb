package org.slackerdb.dbcli.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 嵌入入口 {@link DbcliRunner} 的契约：退出码、空脚本、参数校验。
 *
 * <p>真正连数据库的端到端行为在 dbserver 的 {@code SchedulerDbcliTaskTest} 里
 * （它用一个真实实例验证"连回自己 + 变量替换 + 落库"）。</p>
 */
class DbcliRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("脚本里的 _EXIT n 原样成为返回的退出码")
    void exitCodeComesFromTheScript() throws Exception {
        StringWriter out = new StringWriter();
        DbcliRunner.Result result = DbcliRunner.run(DbcliRunner.Request.builder()
                .scriptText("_EXIT 5", "<inline>")
                .out(new PrintWriter(out, true))
                .err(new PrintWriter(out, true))
                .workingDirectory(tempDir)
                .build());

        assertEquals(5, result.exitCode());
        assertEquals(0, result.errorCount());
    }

    @Test
    @DisplayName("空脚本文本是合法的（什么都不做，退出码 0）")
    void emptyScriptIsAllowed() throws Exception {
        DbcliRunner.Result result = runText("");

        assertEquals(0, result.exitCode());
    }

    @Test
    @DisplayName("脚本文件不存在：抛用法错误，而不是静默成功")
    void missingScriptFileIsRejected() {
        DbcliRunner.DbcliException error = assertThrows(DbcliRunner.DbcliException.class, () ->
                DbcliRunner.run(DbcliRunner.Request.builder()
                        .script(tempDir.resolve("nope.sql"))
                        .out(new PrintWriter(new StringWriter(), true))
                        .err(new PrintWriter(new StringWriter(), true))
                        .build()));

        assertTrue(error.getMessage().contains("does not exist"), error.getMessage());
    }

    @Test
    @DisplayName("script 与 scriptText 必须二选一")
    void exactlyOneScriptFormIsRequired() {
        assertThrows(DbcliRunner.DbcliException.class, () ->
                DbcliRunner.run(DbcliRunner.Request.builder()
                        .out(new PrintWriter(new StringWriter(), true))
                        .err(new PrintWriter(new StringWriter(), true))
                        .build()));
        assertThrows(DbcliRunner.DbcliException.class, () ->
                DbcliRunner.run(DbcliRunner.Request.builder()
                        .script(tempDir.resolve("a.sql"))
                        .scriptText("select 1", "<inline>")
                        .out(new PrintWriter(new StringWriter(), true))
                        .err(new PrintWriter(new StringWriter(), true))
                        .build()));
    }

    @Test
    @DisplayName("多行 logon 被拒绝（_connect 只能是一行）")
    void multiLineLogonIsRejected() {
        DbcliRunner.DbcliException error = assertThrows(DbcliRunner.DbcliException.class, () ->
                DbcliRunner.run(DbcliRunner.Request.builder()
                        .scriptText("_ECHO", "<inline>")
                        .logon("jdbc:slackerdb://127.0.0.1:1/mem\nselect 1")
                        .out(new PrintWriter(new StringWriter(), true))
                        .err(new PrintWriter(new StringWriter(), true))
                        .build()));

        assertTrue(error.getMessage().contains("single line"), error.getMessage());
    }

    @Test
    @DisplayName("变量表按 ${NAME} 替换（与 CLI 的 --var 同一条路径）")
    void variablesAreExpanded() throws Exception {
        Path script = tempDir.resolve("vars.sql");
        Files.writeString(script, "_EXIT ${CODE}");

        DbcliRunner.Result result = DbcliRunner.run(DbcliRunner.Request.builder()
                .script(script)
                .variables(Map.of("CODE", "3"))
                .out(new PrintWriter(new StringWriter(), true))
                .err(new PrintWriter(new StringWriter(), true))
                .workingDirectory(tempDir)
                .build());

        assertEquals(3, result.exitCode());
    }

    private DbcliRunner.Result runText(String text) throws Exception {
        return DbcliRunner.run(DbcliRunner.Request.builder()
                .scriptText(text, "<inline>")
                .out(new PrintWriter(new StringWriter(), true))
                .err(new PrintWriter(new StringWriter(), true))
                .workingDirectory(tempDir)
                .build());
    }
}
