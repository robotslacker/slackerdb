package org.slackerdb.dbcli.rendezvous;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.JvmLauncher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨进程会合点（SQLite）的行为测试：**两个真实进程**验证"父放行、子等待"，外加超时与陈旧代数清理。
 *
 * <p>子进程是 {@link RendezvousProbe}（一个真的 JVM，用 {@code -cp} 起），因此这里验证的是
 * "另一个进程真的看得见我写下的放行、我也真的看得见它的到达"，而不是同一进程里两个对象互相骗自己。</p>
 */
class SqliteRendezvousTest {

    @TempDir
    Path tempDir;

    private Path database() {
        return tempDir.resolve(SqliteRendezvous.SYNC_DB_FILE_NAME);
    }

    // ------------------------------------------------------------------ 真进程会合

    @Test
    @DisplayName("子进程等待、父进程放行 → 子进程被唤醒并以 0 退出")
    void childWaitsAndTheParentReleases() throws Exception {
        String generation = "gen-child-waits";
        try (SqliteRendezvous parent = SqliteRendezvous.create(database(), generation)) {
            Process child = JvmLauncher.start(RendezvousProbe.class.getName(),
                    database().toString(), generation, "GO", "30000");
            try {
                assertTrue(awaitArrival("GO", "CHILD", generation, 30_000L),
                        "子进程应当把自己的到达写进同步库");
                assertTrue(child.isAlive(), "到达之后子进程应当还卡在会合点上");
                assertFalse(parent.await("GO", 100L), "没人放行时父进程自己也不该被放行");

                parent.release("GO");

                String output = JvmLauncher.awaitOutput(child, 30_000L);
                assertEquals(0, child.exitValue(), "被放行的子进程应当以 0 退出，输出:\n" + output);
                assertTrue(output.contains("arrived"), output);
                assertTrue(output.contains("released"), "子进程应当看到放行，输出:\n" + output);
            } finally {
                JvmLauncher.awaitOutput(child, 1_000L);
            }
        }
    }

    @Test
    @DisplayName("子进程用 NO_TIMEOUT 等待（不写 timeout=）：一直等，父进程放行后才通过")
    void childWaitsWithoutTimeoutUntilReleased() throws Exception {
        String generation = "gen-no-timeout";
        try (SqliteRendezvous parent = SqliteRendezvous.create(database(), generation)) {
            Process child = JvmLauncher.start(RendezvousProbe.class.getName(),
                    database().toString(), generation, "GO", "none");
            try {
                assertTrue(awaitArrival("GO", "CHILD", generation, 30_000L),
                        "子进程应当把自己的到达写进同步库");
                Thread.sleep(500L);
                assertTrue(child.isAlive(), "没有时限时子进程不该自己退出（不是默认超时）");

                parent.release("GO");

                String output = JvmLauncher.awaitOutput(child, 30_000L);
                assertEquals(0, child.exitValue(), "被放行的子进程应当以 0 退出，输出:\n" + output);
                assertTrue(output.contains("released"), "子进程应当看到放行，输出:\n" + output);
            } finally {
                JvmLauncher.awaitOutput(child, 1_000L);
            }
        }
    }

    @Test
    @DisplayName("父进程先放行、子进程后等待 → 子进程立即通过（且同代打开不会清掉父进程的放行）")
    void releaseBeforeTheChildWaitsPassesImmediately() throws Exception {
        String generation = "gen-parent-first";
        try (SqliteRendezvous parent = SqliteRendezvous.create(database(), generation)) {
            parent.release("GO");
            // 子进程用**同一个运行代号**打开父进程建的库：它绝不能把"已放行"清掉
            Process child = JvmLauncher.start(RendezvousProbe.class.getName(),
                    database().toString(), generation, "GO", "2000");
            try {
                String output = JvmLauncher.awaitOutput(child, 30_000L);
                assertEquals(0, child.exitValue(),
                        "先放行后等待必须立即通过（超时会给 3），输出:\n" + output);
                assertTrue(output.contains("released"), output);
            } finally {
                JvmLauncher.awaitOutput(child, 1_000L);
            }
        }
    }

    @Test
    @DisplayName("一直不放行 → 子进程超时并以 3 退出（超时是正常结果，不是异常）")
    // parent 只为"测试结束时关掉自己的连接"而存在（close() 的副作用），正文里不读它
    @SuppressWarnings("try")
    void awaitTimesOutInsteadOfFailing() throws Exception {
        String generation = "gen-timeout";
        try (SqliteRendezvous parent = SqliteRendezvous.create(database(), generation)) {
            Process child = JvmLauncher.start(RendezvousProbe.class.getName(),
                    database().toString(), generation, "NOBODY", "500");
            try {
                String output = JvmLauncher.awaitOutput(child, 30_000L);
                assertEquals(3, child.exitValue(), "超时应当是退出码 3，输出:\n" + output);
                assertTrue(output.contains("timeout"), output);
            } finally {
                JvmLauncher.awaitOutput(child, 1_000L);
            }
        }
    }

    // ------------------------------------------------------------------ 代数与清理

    @Test
    @DisplayName("陈旧代数清理：上一代留下的放行与到达行都不能冒充本次运行")
    void staleGenerationIsWipedOnFirstUse() throws Exception {
        Path database = database();
        String previous = "gen-previous";
        String current = "gen-current";

        // 上一次运行（被强杀，库留在磁盘上）：放行过 GO，也留下了到达行
        try (SqliteRendezvous previousRun = SqliteRendezvous.open(database, previous)) {
            previousRun.arrive("GO", "C1");
            previousRun.arrive("GO", "C2");
            previousRun.release("GO");
            assertTrue(previousRun.await("GO", 1_000L), "上一代自己看得见自己的放行");
        }
        assertEquals(2, arrivalCount("GO", previous), "上一代的到达行应当留在库里（模拟被强杀）");

        // 本次运行：首次使用就按运行代号把陈旧行清掉
        try (SqliteRendezvous currentRun = SqliteRendezvous.open(database, current)) {
            assertFalse(currentRun.await("GO", 300L),
                    "上一代的放行绝不能冒充本次放行（否则作业会直接冲过屏障）");
            assertEquals(0, arrivalCount("GO", previous),
                    "上一代的到达行必须被清掉（不能冒充本次到达）");

            currentRun.arrive("GO", "C1");
            assertEquals(1, arrivalCount("GO", current), "本次到达照常记录");
            currentRun.release("GO");
            assertTrue(currentRun.await("GO", 1_000L), "本次放行照常生效");
        }
    }

    @Test
    @DisplayName("建库者收尾时清理同步库（含 WAL 边车文件）；作业进程只关自己的连接")
    void theOwnerCleansUpTheSyncFiles() throws Exception {
        Path database = database();
        try (SqliteRendezvous owner = SqliteRendezvous.create(database, "gen-owner")) {
            owner.release("GO");
            assertTrue(Files.isRegularFile(database), "同步库应当真的被建出来");
        }
        assertFalse(Files.exists(database), "建库者收尾时应当删掉同步库");
        assertFalse(Files.exists(Path.of(database + "-wal")), "WAL 边车文件也要清掉");
        assertFalse(Files.exists(Path.of(database + "-shm")), "共享内存边车文件也要清掉");

        SqliteRendezvous opened = SqliteRendezvous.open(database, "gen-owner");
        opened.close();
        assertTrue(Files.exists(database), "非建库者（作业进程）不得删掉同步库");
    }

    @Test
    @DisplayName("放行原子且幂等：重复放行、放行后到达都得到同一个结果")
    void releaseIsAtomicAndIdempotent() throws InterruptedException {
        try (SqliteRendezvous rendezvous = SqliteRendezvous.create(database(), "gen-idempotent")) {
            rendezvous.release("GO");
            rendezvous.release("GO");
            assertTrue(rendezvous.await("GO", 1_000L));
            rendezvous.arrive("GO", "LATE");
            assertTrue(rendezvous.await("GO", 1_000L), "放行之后再到达也必须直接通过");
        }
    }

    @Test
    @DisplayName("点名大小写不敏感（**两个真实进程**）：父放行 GO、子等待 go 也会合成功")
    void pointsMatchIgnoringCaseAcrossProcesses() throws Exception {
        String generation = "gen-case";
        try (SqliteRendezvous parent = SqliteRendezvous.create(database(), generation)) {
            // 子进程写的是小写 go，父进程写的是 GO：同一个点，子进程必须被唤醒
            Process child = JvmLauncher.start(RendezvousProbe.class.getName(),
                    database().toString(), generation, "go", "30000");
            try {
                assertTrue(awaitArrival("GO", "CHILD", generation, 30_000L),
                        "子进程的到达必须落在规范形（大写的 GO）那一行上");
                assertTrue(child.isAlive(), "到达之后子进程应当还卡在会合点上");
                assertFalse(parent.await("go", 100L), "还没人放行时不该被放行");

                parent.release("GO");

                String output = JvmLauncher.awaitOutput(child, 30_000L);
                assertEquals(0, child.exitValue(),
                        "大小写不同的两个写法必须是同一个点，输出:\n" + output);
                assertTrue(output.contains("released"), output);
                // 库里只有规范形一行；两种写法的查询都命中它
                assertTrue(parent.await("go", 1_000L), "go 看得见 GO 的放行");
                assertTrue(parent.await("Go", 1_000L), "Go 看得见 GO 的放行");
            } finally {
                JvmLauncher.awaitOutput(child, 1_000L);
            }
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 轮询同步库，等某个参与者的到达行出现（跨进程的"到达"只能这么观察）。 */
    private boolean awaitArrival(String point, String participant, String generation, long timeoutMillis)
            throws SQLException, ClassNotFoundException, InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (arrivalCount(point, generation, participant) > 0) {
                return true;
            }
            Thread.sleep(20L);
        }
        return false;
    }

    private int arrivalCount(String point, String generation) throws SQLException, ClassNotFoundException {
        return arrivalCount(point, generation, null);
    }

    private int arrivalCount(String point, String generation, String participant)
            throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC", true, getClass().getClassLoader());
        String sql = "select count(*) from sync_arrive where point = ? and generation = ?"
                + (participant == null ? "" : " and participant = ?");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             Statement pragma = connection.createStatement()) {
            pragma.execute("pragma busy_timeout=5000");
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setString(1, point);
                query.setString(2, generation);
                if (participant != null) {
                    query.setString(3, participant);
                }
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? row.getInt(1) : 0;
                }
            }
        }
    }
}
