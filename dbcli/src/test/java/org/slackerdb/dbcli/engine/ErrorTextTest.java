package org.slackerdb.dbcli.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 错误文案**就是消息本身**：不带 {@code 文件:行号} 前缀，也不带 {@code _START} 调用链。
 *
 * <p>为什么刻意去掉：日志与 {@code --ref} 的 golden 都是给人 review、也常常要手工修改的产物；
 * 一旦带上行号，脚本里加一行就得同步改 golden，反而让 ref 难维护。</p>
 */
class ErrorTextTest {

    @TempDir
    Path tempDir;

    private StringWriter out;
    private StringWriter err;
    private FakeSqlExecutor sql;
    private ScriptContext context;

    @BeforeEach
    void setUp() {
        out = new StringWriter();
        err = new StringWriter();
        sql = new FakeSqlExecutor();
        sql.requireConnection();
        context = new ScriptContext(sql,
                new ScriptOutput(new PrintWriter(out, true), new PrintWriter(err, true)),
                new VariableResolver(Map.of()), tempDir);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private int runFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return new ScriptRunner(context).runFile(file, List.of());
    }

    @Test
    @DisplayName("脚本里的错误：一行纯消息，没有 文件:行号 前缀")
    void scriptErrorsHaveNoLocationPrefix() throws IOException {
        int code = runFile("plain.sql", "_bogus\n");

        assertEquals(0, code);
        assertEquals("Unknown command [_bogus]. Run _HELP for the command list.\n", err.toString(),
                "错误行不带位置前缀");
        assertFalse(err.toString().contains("plain.sql"), "stderr 里不该出现脚本文件名: " + err);
    }

    @Test
    @DisplayName("多行语句与同一行多条语句：文案一样，不因行号变化")
    void messageIsIndependentOfLineNumbers() throws IOException {
        int code = runFile("lines.sql", "\n\n\nselect\n  1\nfrom dual where\n\n");

        assertEquals(0, code);
        assertEquals("Not connected.\n", err.toString(), "第 4 行开始的语句报错也只给消息");
    }

    @Test
    @DisplayName("_START 嵌套：子脚本的错误同样只是一行消息（没有 父 → 子 调用链）")
    void nestedScriptsHaveNoCallChain() throws IOException {
        Files.writeString(tempDir.resolve("child.sql"), "_bogus\n", StandardCharsets.UTF_8);
        int code = runFile("parent.sql", "_start child.sql\n");

        assertEquals(0, code);
        assertEquals("Unknown command [_bogus]. Run _HELP for the command list.\n", err.toString(),
                "不拼调用链: " + err);
    }

    @Test
    @DisplayName("未写完的命令（EOF）：消息里点名是哪条命令，但不带位置")
    void incompleteCommandAtEndOfFile() throws IOException {
        int code = runFile("eof.sql", "_echo never-closed\nbody\n");

        assertEquals(0, code);
        assertEquals("Incomplete command [_echo]: the command is not finished yet.\n", err.toString());
    }
}
