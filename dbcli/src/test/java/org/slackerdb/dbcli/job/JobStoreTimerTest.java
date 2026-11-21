package org.slackerdb.dbcli.job;

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
 * 定时点（屏障）行为测试：**两个真实进程**验证"父放行、子等待"，外加超时、幂等、大小写与清理。
 *
 * <p>子进程是 {@link JobStoreProbe}（一个真的 JVM，用 {@code -cp} 起），因此这里验证的是
 * "另一个进程真的看得见我写下的放行、我也真的看得见它的到达"，而不是同一进程里两个对象互相骗自己。</p>
 *
 * <p>与旧的 {@code SqliteRendezvousTest} 相比：没有"运行代号"了——每次运行一个独立库文件，
 * 所以陈旧行清理的用例被"建库者清理自己的文件"取代。</p>
 */
class JobStoreTimerTest {

    @TempDir
    Path tempDir;

    private Path database() {
        return tempDir.resolve("jobs-test.db");
    }

    private SqliteJobStore owner() {
        return new SqliteJobStore(database(), "root", true);
    }

    // ------------------------------------------------------------------ 真进程会合

    @Test
    @DisplayName("子进程等待、父进程放行 → 子进程被唤醒并以 0 退出")
    void childWaitsAndTheParentReleases() throws Exception {
        try (SqliteJobStore parent = owner()) {
            Process child = JvmLauncher.start(JobStoreProbe.class.getName(),
                    database().toString(), "GO", "30000");
            try {
                assertTrue(awaitArrival("GO", "CHILD", 30_000L),
                        "子进程应当把自己的到达写进作业库");
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
        try (SqliteJobStore parent = owner()) {
            Process child = JvmLauncher.start(JobStoreProbe.class.getName(),
                    database().toString(), "GO", "none");
            try {
                assertTrue(awaitArrival("GO", "CHILD", 30_000L),
                        "子进程应当把自己的到达写进作业库");
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
    @DisplayName("父进程先放行、子进程后等待 → 子进程立即通过（打开同一库不会清掉放行）")
    void releaseBeforeTheChildWaitsPassesImmediately() throws Exception {
        try (SqliteJobStore parent = owner()) {
            parent.release("GO");
            Process child = JvmLauncher.start(JobStoreProbe.class.getName(),
                    database().toString(), "GO", "2000");
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
    @SuppressWarnings("try")
    void awaitTimesOutInsteadOfFailing() throws Exception {
        try (SqliteJobStore parent = owner()) {
            Process child = JvmLauncher.start(JobStoreProbe.class.getName(),
                    database().toString(), "NOBODY", "500");
            try {
                String output = JvmLauncher.awaitOutput(child, 30_000L);
                assertEquals(3, child.exitValue(), "超时应当是退出码 3，输出:\n" + output);
                assertTrue(output.contains("timeout"), output);
            } finally {
                JvmLauncher.awaitOutput(child, 1_000L);
            }
        }
    }

    @Test
    @DisplayName("点名大小写不敏感（**两个真实进程**）：父放行 GO、子等待 go 也会合成功")
    void pointsMatchIgnoringCaseAcrossProcesses() throws Exception {
        try (SqliteJobStore parent = owner()) {
            Process child = JvmLauncher.start(JobStoreProbe.class.getName(),
                    database().toString(), "go", "30000");
            try {
                assertTrue(awaitArrival("GO", "CHILD", 30_000L),
                        "子进程的到达必须落在规范形（大写的 GO）那一行上");
                assertTrue(child.isAlive(), "到达之后子进程应当还卡在会合点上");
                assertFalse(parent.await("go", 100L), "还没人放行时不该被放行");

                parent.release("GO");

                String output = JvmLauncher.awaitOutput(child, 30_000L);
                assertEquals(0, child.exitValue(),
                        "大小写不同的两个写法必须是同一个点，输出:\n" + output);
                assertTrue(output.contains("released"), output);
                assertTrue(parent.await("go", 1_000L), "go 看得见 GO 的放行");
                assertTrue(parent.await("Go", 1_000L), "Go 看得见 GO 的放行");
            } finally {
                JvmLauncher.awaitOutput(child, 1_000L);
            }
        }
    }

    // ------------------------------------------------------------------ 库的归属与清理

    @Test
    @DisplayName("关闭只释放连接，不删除运行库；子进程加入只关自己的连接")
    void closingKeepsTheJobDatabase() throws Exception {
        Path database = database();
        try (SqliteJobStore owner = new SqliteJobStore(database, "root", true)) {
            owner.release("GO");
            assertTrue(Files.isRegularFile(database), "作业库应当真的被建出来");
            try (SqliteJobStore child = new SqliteJobStore(database, "root/child", false)) {
                assertTrue(child.await("GO", 1_000L), "子进程看得见父进程的放行");
            }
            assertTrue(Files.isRegularFile(database), "非建库者不得删掉作业库");
        }
        assertTrue(Files.exists(database), "正常关闭不删除运行库");
    }

    @Test
    @DisplayName("放行原子且幂等：重复放行、放行后到达都得到同一个结果")
    void releaseIsAtomicAndIdempotent() throws InterruptedException {
        try (SqliteJobStore store = owner()) {
            store.release("GO");
            store.release("GO");
            assertTrue(store.await("GO", 1_000L));
            store.arrive("GO", "LATE");
            assertTrue(store.await("GO", 1_000L), "放行之后再到达也必须直接通过");
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 轮询作业库，等某个参与者的到达行出现（跨进程的"到达"只能这么观察）。 */
    private boolean awaitArrival(String point, String participant, long timeoutMillis)
            throws SQLException, ClassNotFoundException, InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (arrivalCount(point, participant) > 0) {
                return true;
            }
            Thread.sleep(20L);
        }
        return false;
    }

    private int arrivalCount(String point, String participant)
            throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC", true, getClass().getClassLoader());
        String sql = "select count(*) from job_arrive where point = ? and participant = ?";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             Statement pragma = connection.createStatement()) {
            pragma.execute("pragma busy_timeout=5000");
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setString(1, SqliteJobStore.normalizePoint(point));
                query.setString(2, participant);
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? row.getInt(1) : 0;
                }
            }
        }
    }
}
