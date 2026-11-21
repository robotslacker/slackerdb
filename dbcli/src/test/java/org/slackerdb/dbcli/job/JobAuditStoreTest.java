package org.slackerdb.dbcli.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code WORK/dbcli_log.db} 的 job_audit：每次 RUNNING→终态恰好一行。 */
class JobAuditStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("finish 写一行审计；重复 finish 不重复写")
    void finishWritesOneAuditRow() {
        Path database = tempDir.resolve("dbcli_log.db");
        try (SqliteJobStore store = new SqliteJobStore(database, "root")) {
            JobRecord created = store.create("A", JobMode.PROCESS, null);
            long before = System.currentTimeMillis();
            assertTrue(store.markRunning(created.jobId(), created.definitionVersion(), "",
                    ProcessHandle.current().pid(), 0L));
            JobRecord ended = store.finish(created.jobId(), 0, null, "child.sql");
            long after = System.currentTimeMillis();
            store.finish(created.jobId(), 9, "late", "wrong.sql");

            List<JobAuditRecord> audit = store.listAudit();
            assertEquals(1, audit.size(), "一次执行只能有一行审计");
            JobAuditRecord row = audit.get(0);
            assertEquals(created.jobId(), row.jobId());
            assertEquals("root", row.scope());
            assertEquals("A", row.jobName());
            assertEquals("child.sql", row.scriptName());
            assertEquals("process", row.mode());
            assertTrue(row.pid() > 0, "至少要有可识别的进程 ID");
            assertTrue(row.startedAtMillis() >= before && row.startedAtMillis() <= after);
            assertTrue(row.finishedAtMillis() >= row.startedAtMillis());
            assertEquals(0, row.exitCode().intValue());
            assertEquals(JobState.DONE, row.state());
        }
    }

    @Test
    @DisplayName("启动失败也记录一条 FAILED 审计")
    void failedStartIsAudited() {
        Path database = tempDir.resolve("dbcli_log.db");
        try (SqliteJobStore store = new SqliteJobStore(database, "root")) {
            JobRecord created = store.create("B", JobMode.THREAD, null);
            store.markFailedToStart(created.jobId(), "cannot open output", "bad.sql");
            store.markFailedToStart(created.jobId(), "again", "bad.sql");

            List<JobAuditRecord> audit = store.listAudit();
            assertEquals(1, audit.size());
            assertEquals(JobState.FAILED, audit.get(0).state());
            assertNull(audit.get(0).exitCode());
            assertEquals("bad.sql", audit.get(0).scriptName());
        }
    }

    @Test
    @DisplayName("多次独立运行各写一行，审计按完成顺序累积")
    void repeatedRunsEachAppendARow() {
        Path database = tempDir.resolve("dbcli_log.db");
        try (SqliteJobStore store = new SqliteJobStore(database, "root")) {
            for (String name : List.of("R1", "R2", "R3")) {
                JobRecord created = store.create(name, JobMode.PROCESS, null);
                store.markRunning(created.jobId(), created.definitionVersion(), "pid 4242",
                        ProcessHandle.current().pid(), 0L);
                store.finish(created.jobId(), 0, null, "repeat.sql");
            }

            List<JobAuditRecord> audit = store.listAudit();
            assertEquals(3, audit.size());
            assertEquals(List.of("R1", "R2", "R3"),
                    audit.stream().map(JobAuditRecord::jobName).toList());
            assertTrue(audit.stream().allMatch(row -> row.pid() == 4242L),
                    "进程作业审计应使用 runtime_id 里的子进程 PID");
        }
    }
}
