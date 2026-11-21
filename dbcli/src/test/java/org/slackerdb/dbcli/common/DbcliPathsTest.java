package org.slackerdb.dbcli.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三目录模型的单元测试：默认值、关系拒绝、清空/lock、脚本查找顺序、产物落点。
 */
class DbcliPathsTest {

    @Test
    @DisplayName("默认值：WORK=<tmp>/dbcli-<pid>，OUTPUT=<HOME>/output（不创建，按需）")
    void defaultsUseTempWorkAndOutputUnderHome(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        DbcliPaths paths = DbcliPaths.resolve(home.toString(), null, null, false);
        try {
            assertEquals(home.toRealPath().resolve("output"), paths.output());
            assertFalse(Files.exists(paths.output()), "普通运行不创建 OUTPUT");
            assertEquals("dbcli-" + ProcessHandle.current().pid(),
                    paths.work().getFileName().toString());
        } finally {
            deleteTree(paths.work());
        }
    }

    private static void deleteTree(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(path)) {
                for (Path child : children.toList()) {
                    deleteTree(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    @Test
    @DisplayName("显式目录：HOME/WORK 解析为真实路径并创建；OUTPUT 等到真要写时才建")
    void resolvesAndCreatesWritableRoots(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path work = dir.resolve("work");
        Path output = dir.resolve("output");

        DbcliPaths paths = DbcliPaths.resolve(home.toString(), work.toString(), output.toString(), false);
        paths.prepareRun(true);
        try {
            assertEquals(home.toRealPath(), paths.home());
            assertEquals(work.toRealPath(), paths.work());
            assertTrue(Files.isDirectory(paths.work()));
            assertEquals(dir.toRealPath().resolve("output"), paths.output());
            assertFalse(Files.exists(output), "没有往 OUTPUT 写东西时不建它");
            assertFalse(Files.exists(paths.outputLock()), "没建 OUTPUT 也就不锁它");

            // 第一次真要写（--logfile/_COMPARE/_JOB 都走这一条）才创建并锁住
            paths.ensureOutput();
            assertTrue(Files.isDirectory(output));
            assertEquals(output.toRealPath(), paths.output());
            assertTrue(Files.exists(paths.outputLock()), "按需创建后立刻锁住");
        } finally {
            paths.close();
        }
    }

    @Test
    @DisplayName("危险关系：HOME==WORK、WORK==OUTPUT、相互嵌套、HOME 在 WORK 内都拒绝")
    void rejectsDangerousRelations(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path work = Files.createDirectories(dir.resolve("work"));
        Path output = Files.createDirectories(dir.resolve("output"));
        Files.createDirectories(work.resolve("code"));

        assertThrows(DbcliPaths.DirectoryException.class,
                () -> DbcliPaths.resolve(home.toString(), home.toString(), output.toString(), false));
        assertThrows(DbcliPaths.DirectoryException.class,
                () -> DbcliPaths.resolve(home.toString(), work.toString(), work.toString(), false));
        assertThrows(DbcliPaths.DirectoryException.class,
                () -> DbcliPaths.resolve(home.toString(), work.toString(),
                        work.resolve("nested").toString(), false));
        assertThrows(DbcliPaths.DirectoryException.class,
                () -> DbcliPaths.resolve(work.resolve("code").toString(),
                        work.toString(), output.toString(), false));
    }

    @Test
    @DisplayName("root 清空：保留根与 .dbcli/lock，删除旧内容/旧作业库")
    void clearsContentsAndKeepsLock(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path work = Files.createDirectories(dir.resolve("work"));
        Path output = Files.createDirectories(dir.resolve("output"));
        Files.writeString(work.resolve("old.txt"), "old");
        Files.writeString(output.resolve("old.out"), "old");

        DbcliPaths paths = DbcliPaths.resolve(home.toString(), work.toString(), output.toString(), false);
        paths.prepareRun(true);
        try {
            assertFalse(Files.exists(work.resolve("old.txt")));
            assertFalse(Files.exists(output.resolve("old.out")));
            assertTrue(Files.exists(paths.workLock()), "lock 必须保留");
            assertTrue(Files.exists(paths.outputLock()), "lock 必须保留");
        } finally {
            paths.close();
        }
        assertFalse(Files.exists(paths.workLock()), "正常退出删锁");
        assertFalse(Files.exists(paths.outputLock()), "正常退出删锁");
    }

    @Test
    @DisplayName("锁冲突：同一个 WORK/OUTPUT 的第二个 root 被拒绝")
    void secondRootIsRejected(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path work = Files.createDirectories(dir.resolve("work"));
        Path output = Files.createDirectories(dir.resolve("output"));

        DbcliPaths first = DbcliPaths.resolve(home.toString(), work.toString(), output.toString(), false);
        first.prepareRun(true);
        try {
            DbcliPaths second = DbcliPaths.resolve(home.toString(), work.toString(), output.toString(), false);
            assertThrows(DbcliPaths.DirectoryException.class, () -> second.prepareRun(true));
        } finally {
            first.close();
        }
    }

    @Test
    @DisplayName("脚本查找：WORK → 调用脚本目录 → HOME；WORK 同名覆盖")
    void scriptLookupOrder(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path work = Files.createDirectories(dir.resolve("work"));
        Path output = Files.createDirectories(dir.resolve("output"));
        Path calling = Files.createDirectories(dir.resolve("calling"));
        Files.writeString(home.resolve("b.sql"), "home");
        Files.writeString(calling.resolve("b.sql"), "beside");
        Files.writeString(work.resolve("b.sql"), "work");

        DbcliPaths paths = DbcliPaths.resolve(home.toString(), work.toString(), output.toString(), false);
        assertEquals(paths.work().resolve("b.sql"), paths.findScript("b.sql", calling));

        Files.delete(work.resolve("b.sql"));
        assertEquals(calling.resolve("b.sql"), paths.findScript("b.sql", calling));

        Files.delete(calling.resolve("b.sql"));
        assertEquals(paths.home().resolve("b.sql"), paths.findScript("b.sql", calling));
    }

    @Test
    @DisplayName("相对写路径落角色根；绝对写路径原样")
    void relativeAndAbsoluteWritePaths(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path work = Files.createDirectories(dir.resolve("work"));
        Path output = Files.createDirectories(dir.resolve("output"));
        DbcliPaths paths = DbcliPaths.resolve(home.toString(), work.toString(), output.toString(), false);

        assertEquals(paths.work().resolve("a.txt"), paths.workFile("a.txt"));
        assertEquals(paths.output().resolve("b.txt"), paths.outputFile("b.txt"));
        Path absolute = dir.resolve("outside.txt");
        assertEquals(absolute, paths.outputFile(absolute.toString()));
    }

    @Test
    @DisplayName("_COMPARE 产物在 OUTPUT/<目标基名>，没有 compare 子目录")
    void compareArtifactsLiveDirectlyUnderOutput(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path work = Files.createDirectories(dir.resolve("work"));
        Path output = Files.createDirectories(dir.resolve("output"));
        DbcliPaths paths = DbcliPaths.resolve(home.toString(), work.toString(), output.toString(), false);

        assertEquals(paths.output().resolve("work.dif"), paths.compareReportPath(work.resolve("work.txt"), ".dif"));
        assertEquals(paths.output().resolve("work.suc"), paths.compareReportPath(work.resolve("work.txt"), ".suc"));
    }
}
