package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.common.DbcliPaths;
import org.slackerdb.dbcli.common.IllegalPaths;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunPlan}：{@code --execute} 的路径规则（入口脚本 WORK→HOME；运行时 cwd = WORK）。
 */
class RunPlanTest {

    @Test
    @DisplayName("--execute <文件>：脚本绝对化，工作目录 = WORK（不再跟脚本目录）")
    void resolvesScriptAndWorkingDirectory(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("a.sql"), "select 1;\n");
        DbcliPaths paths = DbcliPaths.forDirectory(dir);

        RunPlan plan = RunPlan.resolve(script.toString(), paths);

        assertEquals(script.toAbsolutePath().normalize(), plan.scriptFile());
        assertEquals(paths.work(), plan.workingDirectory());
        assertEquals(dir.toAbsolutePath().normalize(), plan.scriptDirectory());
    }

    @Test
    @DisplayName("--execute - / 不给 --execute：没有脚本文件，工作目录是 WORK")
    void stdinAndConsoleUseWorkDirectory(@TempDir Path dir) throws Exception {
        DbcliPaths paths = DbcliPaths.forDirectory(dir);
        for (String execute : new String[] {null, "-"}) {
            RunPlan plan = RunPlan.resolve(execute, paths);
            assertNull(plan.scriptFile(), String.valueOf(execute));
            assertEquals(paths.work(), plan.workingDirectory(), String.valueOf(execute));
        }
    }

    @Test
    @DisplayName("脚本不存在：命令行层面的错误（消息里回显使用者写的那一串）")
    void missingScriptIsAnError(@TempDir Path dir) {
        DbcliPaths paths = DbcliPaths.forDirectory(dir);
        Path missingFile = dir.resolve("nope.sql");
        RunPlan.MissingScriptException missing = assertThrows(RunPlan.MissingScriptException.class,
                () -> RunPlan.resolve(missingFile.toString(), paths));
        assertTrue(missing.getMessage().startsWith("Script file ["), missing.getMessage());
        assertTrue(missing.getMessage().contains("] does not exist"), missing.getMessage());
    }

    @Test
    @DisplayName("脚本显示名：文件名 / <stdin> / <console>")
    void scriptName(@TempDir Path dir) throws Exception {
        DbcliPaths paths = DbcliPaths.forDirectory(dir);
        Path script = Files.writeString(dir.resolve("named.sql"), "select 1;\n");
        assertEquals("named.sql", RunPlan.resolve(script.toString(), paths).scriptName(null));
        assertEquals("<stdin>", RunPlan.resolve("-", paths).scriptName("select 1;"));
        assertEquals("<console>", RunPlan.resolve(null, paths).scriptName(null));
    }

    @Test
    @DisplayName("路径含非法字符：可读的 Invalid path [...]，不是 JDK 的 InvalidPathException")
    void invalidPathIsReadable(@TempDir Path dir) {
        // 取值必须"在本平台上非法"，否则这里只会得到"文件不存在"（见 IllegalPaths）
        DbcliPaths paths = DbcliPaths.forDirectory(dir);
        RunPlan.MissingScriptException invalid = assertThrows(RunPlan.MissingScriptException.class,
                () -> RunPlan.resolve(IllegalPaths.VALUE, paths));
        assertTrue(invalid.getMessage().startsWith(IllegalPaths.INVALID_PREFIX), invalid.getMessage());
    }
}
