package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.common.IllegalPaths;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunPlan}：{@code --execute} 的路径规则（"脚本里的相对路径跟脚本、命令行选项跟进程当前目录"）。
 */
class RunPlanTest {

    @Test
    @DisplayName("--execute <文件>：脚本绝对化，工作目录 = 脚本所在目录")
    void resolvesScriptAndWorkingDirectory(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("a.sql"), "select 1;\n");

        RunPlan plan = RunPlan.resolve(script.toString());

        assertEquals(script.toAbsolutePath().normalize(), plan.scriptFile());
        assertEquals(script.toAbsolutePath().normalize().getParent(), plan.workingDirectory());
        assertEquals(Path.of("").toAbsolutePath(), plan.processDirectory());
    }

    @Test
    @DisplayName("--execute - / 不给 --execute：没有脚本文件，工作目录是进程当前目录")
    void stdinAndConsoleUseTheProcessDirectory() throws Exception {
        for (String execute : new String[] {null, "-"}) {
            RunPlan plan = RunPlan.resolve(execute);
            assertNull(plan.scriptFile(), String.valueOf(execute));
            assertEquals(Path.of("").toAbsolutePath(), plan.workingDirectory(), String.valueOf(execute));
        }
    }

    @Test
    @DisplayName("脚本不存在：命令行层面的错误（消息里回显使用者写的那一串）")
    void missingScriptIsAnError(@TempDir Path dir) {
        RunPlan.MissingScriptException missing = assertThrows(RunPlan.MissingScriptException.class,
                () -> RunPlan.resolve(dir.resolve("nope.sql").toString()));
        assertTrue(missing.getMessage().startsWith("Script file ["), missing.getMessage());
        assertTrue(missing.getMessage().endsWith("] does not exist."), missing.getMessage());
    }

    @Test
    @DisplayName("脚本显示名：文件名 / <stdin> / <console>")
    void scriptName(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("named.sql"), "select 1;\n");
        assertEquals("named.sql", RunPlan.resolve(script.toString()).scriptName(null));
        assertEquals("<stdin>", RunPlan.resolve("-").scriptName("select 1;"));
        assertEquals("<console>", RunPlan.resolve(null).scriptName(null));
    }

    @Test
    @DisplayName("路径含非法字符：可读的 Invalid path [...]，不是 JDK 的 InvalidPathException")
    void invalidPathIsReadable() {
        // 取值必须"在本平台上非法"，否则这里只会得到"文件不存在"（见 IllegalPaths）
        RunPlan.MissingScriptException invalid = assertThrows(RunPlan.MissingScriptException.class,
                () -> RunPlan.resolve(IllegalPaths.VALUE));
        assertTrue(invalid.getMessage().startsWith(IllegalPaths.INVALID_PREFIX), invalid.getMessage());
    }
}
