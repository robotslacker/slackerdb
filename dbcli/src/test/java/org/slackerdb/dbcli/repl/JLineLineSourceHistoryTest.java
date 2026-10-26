package org.slackerdb.dbcli.repl;

import org.jline.reader.LineReader;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台历史：跨会话保存在 {@code ~/.slackerdb/dbcli.history}；不可写时安静降级（只用内存历史）。
 *
 * <p>这里直接驱动 {@link JLineLineSource#buildReader}（而不是 {@code system()}）：dumb 终端 + 预置输入
 * 就能完整跑一遍"读入 → 翻历史 → 写回文件"，不需要真 TTY。</p>
 */
class JLineLineSourceHistoryTest {

    private static DumbTerminal dumbTerminal(String input) throws Exception {
        return new DumbTerminal("dbcli", "dumb",
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("历史写回文件：敲过的行在会话结束后出现在 history 文件里")
    void historyIsWrittenToTheFile(@TempDir Path dir) throws Exception {
        Path history = dir.resolve("dbcli.history");
        try (DumbTerminal terminal = dumbTerminal("select 1;\n_exit\n")) {
            LineReader reader = JLineLineSource.buildReader(terminal, history);
            assertEquals("select 1;", reader.readLine("dbcli> "));
            assertEquals("_exit", reader.readLine("dbcli> "));
            reader.getHistory().save();
        }

        List<String> lines = Files.readAllLines(history, StandardCharsets.UTF_8);
        assertTrue(lines.contains("select 1;"), "历史文件里应当有这条语句: " + lines);
        assertTrue(lines.contains("_exit"), "历史文件里应当有这条命令: " + lines);
    }

    @Test
    @DisplayName("跨会话：上一次写下的历史在下次启动时被读进来（上下键能翻到）")
    void historyIsLoadedOnStartup(@TempDir Path dir) throws Exception {
        Path history = dir.resolve("dbcli.history");
        Files.writeString(history, "old-statement;\n", StandardCharsets.UTF_8);

        try (DumbTerminal terminal = dumbTerminal("new-statement;\n")) {
            LineReader reader = JLineLineSource.buildReader(terminal, history);

            assertEquals(1, reader.getHistory().size(), "启动时应当已读入文件里的历史");
            assertEquals("old-statement;", reader.getHistory().get(0));

            assertEquals("new-statement;", reader.readLine("dbcli> "));
            reader.getHistory().save();
        }

        List<String> lines = Files.readAllLines(history, StandardCharsets.UTF_8);
        assertEquals(List.of("old-statement;", "new-statement;"), lines,
                "新会话应当把两条都留在文件里（旧的读进来、新的追上去）");
    }

    @Test
    @DisplayName("历史文件不可写：降级为内存历史，不抛异常、不打印")
    void unwritableHistoryFallsBackToMemory(@TempDir Path dir) throws Exception {
        // 让 ~ 指向一个"是文件、不是目录"的路径：createDirectories 必然失败
        Path notADirectory = dir.resolve("not-a-directory");
        Files.writeString(notADirectory, "x");
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", notADirectory.toString());
            assertNull(JLineLineSource.defaultHistoryFile(), "不可写时应当返回 null（安静放弃）");
        } finally {
            System.setProperty("user.home", previousHome);
        }

        // null 历史文件 = 只用内存历史：取行照旧工作，且不写任何文件
        try (DumbTerminal terminal = dumbTerminal("select 1;\n")) {
            LineReader reader = JLineLineSource.buildReader(terminal, null);
            assertEquals("select 1;", reader.readLine("dbcli> "));
            reader.getHistory().save();   // 没有配置历史文件时必须是空操作，不能抛
            assertEquals(1, reader.getHistory().size());
        }
        assertFalse(Files.exists(notADirectory.resolve(".slackerdb")),
                "降级路径不该尝试创建目录");
    }

    @Test
    @DisplayName("默认历史文件是 ~/.slackerdb/dbcli.history")
    void defaultHistoryFileLivesUnderTheHomeDirectory(@TempDir Path dir) throws Exception {
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", dir.toString());
            Path history = JLineLineSource.defaultHistoryFile();

            assertEquals(dir.resolve(".slackerdb").resolve("dbcli.history"), history);
            assertTrue(Files.isDirectory(history.getParent()), "目录应当被建出来");
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }
}
