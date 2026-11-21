package org.slackerdb.dbcli.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqliteJobStore} 的 hermetic 测试：状态机 CAS、停止意图优先级、认领版本、
 * scope 隔离与"每次运行一个库文件"。
 *
 * <p>这里直接操作存储层（不启动作业），把编排语义钉死在最小单元上；脚本级行为由
 * {@code engine.JobBarrierTest} / {@code job.JobManagerTest} 覆盖。</p>
 */
class JobStoreTest {

    @TempDir
    Path tempDir;

    private SqliteJobStore owner() {
        return new SqliteJobStore(tempDir.resolve("jobs.db"), "root", true);
    }

    private static JobRecord start(SqliteJobStore store, String name) {
        JobRecord created = store.create(name, JobMode.THREAD, null);
        assertTrue(store.markRunning(created.jobId(), created.definitionVersion(),
                "dbcli-job-" + name, ProcessHandle.current().pid(), 0L));
        return store.findById(created.jobId());
    }

    @Test
    @DisplayName("create → RUNNING → DONE：CAS 拿启动权，终态写一次")
    void createStartFinish() {
        try (SqliteJobStore store = owner()) {
            JobRecord created = store.create("J", JobMode.THREAD, null);
            assertEquals(JobState.CREATED, created.state());
            assertEquals(1L, created.definitionVersion());
            assertEquals(JobRecord.NO_EXIT_CODE, created.exitCodeOrMin());

            assertFalse(store.markRunning(created.jobId(), 999L, "wrong", 0L, 0L),
                    "版本不符不能抢到启动权");
            assertTrue(store.markRunning(created.jobId(), created.definitionVersion(),
                    "dbcli-job-J", 0L, 0L));
            assertEquals(JobState.RUNNING, store.findById(created.jobId()).state());

            JobRecord finished = store.finish(created.jobId(), 0, null);
            assertEquals(JobState.DONE, finished.state());
            assertEquals(0, finished.exitCodeOrMin());
            assertTrue(finished.isTerminal());
            // 再写一次终态不会改写
            assertEquals(JobState.DONE, store.finish(created.jobId(), 9, "late").state());
        }
    }

    @Test
    @DisplayName("重名：唯一约束给出既有文案；不同 scope 可以同名")
    void duplicateNamesAreScoped() {
        try (SqliteJobStore root = owner();
             SqliteJobStore child = new SqliteJobStore(tempDir.resolve("jobs.db"), "root/child")) {
            root.create("J", JobMode.THREAD, null);
            JobException duplicate = assertThrows(JobException.class,
                    () -> root.create("J", JobMode.THREAD, null));
            assertEquals("Job [J] already exists.", duplicate.getMessage());

            child.create("J", JobMode.THREAD, null);
            assertEquals(1, root.list().size());
            assertEquals(1, child.list().size());
            assertEquals("root", root.list().get(0).scope());
            assertEquals("root/child", child.list().get(0).scope());
        }
    }

    @Test
    @DisplayName("SET：重置运行期字段与认领，版本递增；运行中不允许")
    void redefineResetsRuntimeStateAndAck() {
        try (SqliteJobStore store = owner()) {
            JobRecord created = store.create("J", JobMode.THREAD, null);
            assertTrue(store.markRunning(created.jobId(), 1L, "dbcli-job-J", 0L, 0L));
            store.finish(created.jobId(), 3, null);
            store.acknowledge(created.jobId(), 1L);
            assertTrue(store.unacknowledgedFailures().isEmpty());

            JobRecord redefined = store.redefine("J", JobMode.THREAD, 500L);
            assertEquals(JobState.CREATED, redefined.state());
            assertEquals(2L, redefined.definitionVersion());
            assertNull(redefined.exitCode());
            assertEquals(500L, redefined.timeoutMillis());
            assertFalse(redefined.acknowledged(), "SET 必须重置认领");
            assertTrue(store.unacknowledgedFailures().isEmpty(), "CREATED 不算失败");

            assertTrue(store.markRunning(redefined.jobId(), 2L, "dbcli-job-J", 0L, 0L));
            store.finish(redefined.jobId(), 3, null);
            assertEquals(1, store.unacknowledgedFailures().size(),
                    "重新定义之后的失败必须能被收尾发现（不继承旧认领）");

            assertFalse(store.markRunning(redefined.jobId(), 2L, "again", 0L, 0L),
                    "同一版本只能启动一次");

            JobRecord runningNow = store.create("RUNNING", JobMode.THREAD, null);
            assertTrue(store.markRunning(runningNow.jobId(), 1L, "t", 0L, 0L));
            assertNull(store.redefine("RUNNING", JobMode.THREAD, null),
                    "运行中的定义不允许改");
        }
    }

    @Test
    @DisplayName("停止优先级：ABORTED 不被超时覆盖；TIMEOUT 只在没有停止意图时生效")
    void stopTerminalBeatsTimeout() {
        try (SqliteJobStore store = owner()) {
            JobRecord created = store.create("ABORTED", JobMode.THREAD, 1_000L);
            assertTrue(store.markRunning(created.jobId(), 1L, "t", 0L, 0L));
            store.requestStop(created.jobId(), JobState.ABORTED, true);
            assertTrue(store.markTimeout(created.jobId()), "RUNNING 行可以记录超时标记");
            JobRecord finished = store.finish(created.jobId(), 0, null);
            assertEquals(JobState.ABORTED, finished.state(), "已有停止意图优先于超时");
            assertTrue(finished.timeoutHit());

            JobRecord timed = store.create("TIMEOUT", JobMode.THREAD, 1_000L);
            assertTrue(store.markRunning(timed.jobId(), 1L, "t", 0L, 0L));
            assertTrue(store.markTimeout(timed.jobId()));
            assertEquals(JobState.TIMEOUT, store.finish(timed.jobId(), 0, null).state());
        }
    }

    @Test
    @DisplayName("超时标记只作用于 RUNNING 行；ABORT/SHUTDOWN 只能升 forced，不能降级")
    void stopFlagsAreMonotonic() {
        try (SqliteJobStore store = owner()) {
            JobRecord created = store.create("J", JobMode.THREAD, null);
            assertEquals(JobState.CREATED, created.state());
            assertFalse(store.markTimeout(created.jobId()), "CREATED 不接受超时标记");

            assertTrue(store.markRunning(created.jobId(), 1L, "t", 0L, 0L));
            store.requestStop(created.jobId(), JobState.ABORTED, true);
            JobRecord after = store.requestStop(created.jobId(), JobState.SHUTDOWN, false);
            assertEquals(JobState.ABORTED, after.stopTerminal(), "非强制请求不能覆盖 ABORT");
            assertTrue(after.stopForced(), "forced 只升不降");

            // 反过来：先 shutdown、再 abort（强制）→ 升级为 ABORTED
            JobRecord other = store.create("K", JobMode.THREAD, null);
            assertTrue(store.markRunning(other.jobId(), 1L, "t", 0L, 0L));
            store.requestStop(other.jobId(), JobState.SHUTDOWN, false);
            assertEquals(JobState.ABORTED,
                    store.requestStop(other.jobId(), JobState.ABORTED, true).stopTerminal(),
                    "强制 ABORT 可以升级已有的 SHUTDOWN 意图");
        }
    }

    @Test
    @DisplayName("认领按定义版本：旧定义的认领不会带到 SET 之后的新定义")
    void acknowledgementIsVersioned() {
        try (SqliteJobStore store = owner()) {
            JobRecord created = store.create("J", JobMode.THREAD, null);
            assertTrue(store.markRunning(created.jobId(), 1L, "t", 0L, 0L));
            store.finish(created.jobId(), 0, null);
            store.acknowledge(created.jobId(), created.definitionVersion());
            assertTrue(store.findById(created.jobId()).acknowledged());

            // 用旧版本号认领新定义：必须无效
            JobRecord redefined = store.redefine("J", JobMode.THREAD, null);
            store.acknowledge(redefined.jobId(), created.definitionVersion());
            assertFalse(store.findById(redefined.jobId()).acknowledged());
        }
    }

    @Test
    @DisplayName("等待：RUNNING 行在超时后原样返回（timedOut 由调用方判断），timeout=0 只看一眼")
    void awaitReturnsRunningOnTimeout() throws Exception {
        try (SqliteJobStore store = owner()) {
            JobRecord running = start(store, "J");
            long started = System.nanoTime();
            JobRecord after = store.awaitTerminal(running.jobId(), 60L);
            assertEquals(JobState.RUNNING, after.state());
            assertTrue((System.nanoTime() - started) / 1_000_000L >= 50L);

            long immediate = System.nanoTime();
            assertEquals(JobState.RUNNING, store.awaitTerminal(running.jobId(), 0L).state());
            assertTrue((System.nanoTime() - immediate) / 1_000_000L < 1_000L, "0 不应等待");

            store.requestStop(running.jobId(), JobState.ABORTED, true);
            store.finish(running.jobId(), 1, null);
            assertEquals(JobState.ABORTED, store.awaitTerminal(running.jobId(), 1_000L).state());
        }
    }

    @Test
    @DisplayName("定时点跨 scope 共用；不同运行文件互不影响")
    void timerPointsAreGlobalButRunsAreIsolated() throws Exception {
        Path database = tempDir.resolve("jobs.db");
        try (SqliteJobStore root = new SqliteJobStore(database, "root", true);
             SqliteJobStore child = new SqliteJobStore(database, "root/child", false);
             SqliteJobStore otherRun = new SqliteJobStore(tempDir.resolve("other.db"), "root", true)) {
            root.release("GO");
            assertTrue(child.await("GO", 1_000L), "跨 scope 的放行必须命中");
            assertTrue(child.knows("go"), "大小写不同也是同一个点");
            assertFalse(otherRun.knows("GO"), "另一次运行的库不能看见这个点");
            assertFalse(otherRun.await("GO", 100L));
        }
    }

    @Test
    @DisplayName("未启动作业的 abort/shutdown：由调用方把 CREATED 直接落到终态")
    void createdJobCanBeFinishedByTheRequester() {
        try (SqliteJobStore store = owner()) {
            JobRecord created = store.create("J", JobMode.THREAD, null);
            JobRecord aborted = store.finishCreated(created.jobId(), JobState.ABORTED);
            assertEquals(JobState.ABORTED, aborted.state());
            assertFalse(store.markRunning(created.jobId(), created.definitionVersion(), "t", 0L, 0L),
                    "已经终态的行不能再启动");

            JobRecord another = store.create("K", JobMode.THREAD, null);
            assertEquals(JobState.SHUTDOWN, store.finishCreated(another.jobId(), JobState.SHUTDOWN).state());
        }
    }

    @Test
    @DisplayName("收尾：失败补报的文案来源按 scope 隔离")
    void failuresAreScoped() {
        Path database = tempDir.resolve("jobs.db");
        try (SqliteJobStore root = new SqliteJobStore(database, "root", true);
             SqliteJobStore child = new SqliteJobStore(database, "root/child", false)) {
            JobRecord failed = root.create("FAIL", JobMode.THREAD, null);
            root.markRunning(failed.jobId(), 1L, "t", 0L, 0L);
            root.finish(failed.jobId(), 5, null);

            List<JobRecord> rootFailures = root.unacknowledgedFailures();
            assertEquals(1, rootFailures.size());
            assertEquals("FAIL", rootFailures.get(0).name());
            assertTrue(child.unacknowledgedFailures().isEmpty(), "子 scope 不该看到父 scope 的失败");

            root.acknowledgeFailures();
            assertTrue(root.unacknowledgedFailures().isEmpty());
        }
    }
}
