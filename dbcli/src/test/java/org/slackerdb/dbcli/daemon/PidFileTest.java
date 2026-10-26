package org.slackerdb.dbcli.daemon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pidfile 的读写契约：写入是"整体替换"（读方看不到半个数字），删除是幂等的。
 *
 * <p>父进程每 50ms 读一次这个文件，所以"写到一半"的内容会被当成真的 pid 报出去——
 * 这组测试锁住写入方式与读取的严格性。</p>
 */
class PidFileTest {

    @Test
    @DisplayName("写入后能读回；重复写入取最新值（内容始终是完整的一行 pid）")
    void writesAndReadsBack(@TempDir Path dir) throws Exception {
        PidFile pidFile = new PidFile(dir.resolve("run.pid"));

        pidFile.write(1234L);
        assertEquals(1234L, pidFile.read());
        assertEquals("1234\n", Files.readString(pidFile.path(), StandardCharsets.UTF_8));

        pidFile.write(99L);
        assertEquals(99L, pidFile.read());
        assertEquals("99\n", Files.readString(pidFile.path(), StandardCharsets.UTF_8),
                "替换写：旧内容不残留");
    }

    @Test
    @DisplayName("目录不存在时会建出来（--pidfile 指向新目录）")
    void createsParentDirectories(@TempDir Path dir) throws Exception {
        PidFile pidFile = new PidFile(dir.resolve("nested/logs/run.pid"));

        pidFile.writeCurrent();

        assertTrue(Files.isRegularFile(pidFile.path()));
        assertEquals(ProcessHandle.current().pid(), pidFile.read());
    }

    @Test
    @DisplayName("内容不是完整的十进制数时读不出来（半个数字/带别的内容都按 null）")
    void unreadableContentIsNull(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.pid");
        PidFile pidFile = new PidFile(file);

        Files.writeString(file, "", StandardCharsets.UTF_8);
        assertNull(pidFile.read(), "空文件");

        Files.writeString(file, "12\n34", StandardCharsets.UTF_8);
        assertNull(pidFile.read(), "多行内容");

        Files.writeString(file, "pid=12", StandardCharsets.UTF_8);
        assertNull(pidFile.read(), "带前缀");

        Files.writeString(file, "12", StandardCharsets.UTF_8);
        assertEquals(12L, pidFile.read(), "没有行尾换行也能读");
    }

    @Test
    @DisplayName("删除是幂等的：删掉之后读回 null，重复删除仍然成功")
    void deleteIsIdempotent(@TempDir Path dir) throws Exception {
        PidFile pidFile = new PidFile(dir.resolve("gone.pid"));
        pidFile.write(7L);

        assertTrue(pidFile.delete());
        assertFalse(pidFile.exists());
        assertNull(pidFile.read());

        assertTrue(pidFile.delete(), "文件本来就不存在也算成功");
        assertTrue(new PidFile(dir.resolve("never.pid")).delete());
    }
}
