package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三目录模型的 CLI 行为：`_START`/`_JOB` 跟着调用脚本走、相对写路径落 WORK/OUTPUT、
 * `_COMPARE` 产物直接在 OUTPUT 根、入口脚本不能预置在 WORK。
 */
class CliDirectoryModelTest {

    @TempDir
    Path tempDir;

    private record Result(String out, int exitCode) {
    }

    private Result run(String... args) throws Exception {
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream(new byte[0]));
        StringWriter out = new StringWriter();
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true));
        String[] withDirs = new String[args.length + 6];
        withDirs[0] = "--home_dir";
        withDirs[1] = tempDir.toString();
        withDirs[2] = "--work_dir";
        withDirs[3] = tempDir.resolve("work").toString();
        withDirs[4] = "--output_dir";
        withDirs[5] = tempDir.resolve("output").toString();
        System.arraycopy(args, 0, withDirs, 6, args.length);
        int exitCode = commandLine.execute(withDirs);
        return new Result(out.toString(), exitCode);
    }

    private Path script(String relative, String text) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("普通脚本运行不创建 OUTPUT/output、ref 等目录（只有 _COMPARE/--ref 才需要）")
    void plainRunDoesNotCreateOutputDirectories() throws Exception {
        Path a = script("plain.sql", "_ECHO t\nplain-ok\necho off\n");

        Result result = run("--execute", a.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("plain-ok"), result.out);
        assertFalse(Files.exists(tempDir.resolve("output")), "普通运行不该留下 output/");
        assertFalse(Files.exists(tempDir.resolve("output").resolve("ref")), "不该留下 output/ref");
    }

    @Test
    @DisplayName("_START：sub/a.sql 里的 b.sql 命中 sub/b.sql（不需要 HOME 指向 sub）")
    void startResolvesBesideTheCallingScript() throws Exception {
        script("sub/b.sql", "_ECHO t\nbeside-ok\necho off\n");
        Path a = script("sub/a.sql", "_START b.sql\n");

        Result result = run("--execute", a.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(result.out.contains("beside-ok"), result.out);
    }

    @Test
    @DisplayName("_JOB thread：script=b.sql 命中定义脚本目录 sub/b.sql，.out 落 OUTPUT/jobs")
    void threadJobResolvesBesideTheDefiningScript() throws Exception {
        script("sub/b.sql", "_ECHO t\njob-beside-ok\necho off\n");
        Path a = script("sub/a.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE J script=b.sql mode=thread
                _JOB START J
                _JOB WAIT J
                """);

        Result result = run("--execute", a.toString());

        assertEquals(0, result.exitCode, result.out);
        String jobOut = Files.readString(tempDir.resolve("output").resolve("jobs").resolve("J.out"),
                StandardCharsets.UTF_8);
        assertTrue(jobOut.contains("job-beside-ok"), jobOut);
    }

    @Test
    @DisplayName("_JOB process：父进程把 sub/b.sql 解析成绝对路径交给子进程")
    void processJobResolvesBesideTheDefiningScript() throws Exception {
        script("sub/b.sql", "_ECHO t\nprocess-beside-ok\necho off\n");
        Path a = script("sub/a.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE J script=b.sql mode=process
                _JOB START J
                _JOB WAIT J
                """);

        Result result = run("--execute", a.toString());

        assertEquals(0, result.exitCode, result.out);
        String jobOut = Files.readString(tempDir.resolve("output").resolve("jobs").resolve("J.out"),
                StandardCharsets.UTF_8);
        assertTrue(jobOut.contains("process-beside-ok"), jobOut);
    }

    @Test
    @DisplayName("相对写路径：_SPOOL 落 WORK，--logfile 落 OUTPUT")
    void relativeWritesUseWorkAndOutput() throws Exception {
        Path a = script("io.sql", """
                _SPOOL spool.txt
                _ECHO t
                hello
                echo off
                _SPOOL OFF
                """);

        Result result = run("--execute", a.toString(), "--logfile", "logs/run.log");

        assertEquals(0, result.exitCode, result.out);
        assertTrue(Files.exists(tempDir.resolve("work").resolve("spool.txt")), "spool 落 WORK");
        assertTrue(Files.exists(tempDir.resolve("output").resolve("logs").resolve("run.log")),
                "--logfile 相对 OUTPUT");
    }

    @Test
    @DisplayName("_COMPARE 产物直接在 OUTPUT/<目标基名>，没有 compare 子目录")
    void compareArtifactsLiveDirectlyUnderOutput() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a\nb\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("b.txt"), "a\nX\n", StandardCharsets.UTF_8);
        Path a = script("compare.sql", "_COMPARE a.txt b.txt\n");

        Result result = run("--execute", a.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(Files.exists(tempDir.resolve("output").resolve("a.dif")), "OUTPUT/a.dif");
        assertFalse(Files.exists(tempDir.resolve("output").resolve("compare")),
                "不应创建 compare 子目录");
    }

    @Test
    @DisplayName("_JOB 的 script 也能命中运行期生成到 WORK 的临时脚本")
    void runtimeJobScriptGeneratedInWorkIsFound() throws Exception {
        Path a = script("temp-job.sql", """
                _SET FEEDBACK OFF
                _SET ECHO OFF
                _SPOOL tmp-job.sql
                _ECHO t
                select 1
                echo off
                _SPOOL OFF
                _JOB JOBMANAGER ON
                _JOB CREATE TMP script=tmp-job.sql mode=thread
                _JOB START TMP
                _JOB WAIT TMP
                """);

        Result result = run("--execute", a.toString());

        assertEquals(0, result.exitCode, result.out);
        String jobOut = Files.readString(tempDir.resolve("output").resolve("jobs").resolve("TMP.out"),
                StandardCharsets.UTF_8);
        assertFalse(jobOut.contains("does not exist"), jobOut);
    }

    @Test
    @DisplayName("运行期生成到 WORK 的脚本：_START 能命中，且子进程不清掉它")
    void runtimeScriptGeneratedInWorkIsFound() throws Exception {
        Path a = script("temp-child.sql", """
                _SET FEEDBACK OFF
                _SET ECHO OFF
                _SPOOL tmp-child.sql
                _ECHO t
                select 1
                echo off
                _SPOOL OFF
                _START tmp-child.sql
                """);

        Result result = run("--execute", a.toString());

        assertEquals(0, result.exitCode, result.out);
    }

    @Test
    @DisplayName("每次运行都创建 WORK/dbcli_log.db，即使完全没有 _JOB；关闭不删除")
    void runDatabaseIsAlwaysCreatedAndKept() throws Exception {
        Path a = script("no-job.sql", "_ECHO t\nno-job\necho off\n");

        Result result = run("--execute", a.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(Files.isRegularFile(tempDir.resolve("work").resolve("dbcli_log.db")),
                "没有 _JOB 也必须创建 WORK/dbcli_log.db");
        assertFalse(Files.exists(tempDir.resolve("work").resolve(".dbcli").resolve("jobs")),
                "不再使用 .dbcli/jobs/<runId>.db");
    }

    @Test
    @DisplayName("每次 JOB 结束都写 job_audit：线程/进程、失败、重复运行各一行")
    void jobAuditRecordsEveryRun() throws Exception {
        script("audit-ok.sql", "_ECHO t\nok\necho off\n");
        script("audit-fail.sql", "_EXIT 7\n");
        script("audit-thread.sql", "_ECHO t\nthread\necho off\n");
        Path parent = script("audit-parent.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE P1 script=audit-ok.sql mode=process
                _JOB START P1
                _JOB WAIT P1
                _JOB CREATE P2 script=audit-ok.sql mode=process
                _JOB START P2
                _JOB WAIT P2
                _JOB CREATE T1 script=audit-thread.sql mode=thread
                _JOB START T1
                _JOB WAIT T1
                _JOB CREATE F1 script=audit-fail.sql mode=process
                _JOB START F1
                _JOB WAIT F1
                """);

        Result result = run("--execute", parent.toString());
        assertEquals(0, result.exitCode, result.out);

        Class.forName("org.sqlite.JDBC");
        Path database = tempDir.resolve("work").resolve("dbcli_log.db");
        Map<String, AuditRow> rows = new HashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select job_name, script_name, mode, pid, started_at_ms, finished_at_ms, "
                             + "exit_code, state from job_audit order by audit_id")) {
            while (resultSet.next()) {
                AuditRow row = new AuditRow(
                        resultSet.getString("job_name"),
                        resultSet.getString("script_name"),
                        resultSet.getString("mode"),
                        resultSet.getLong("pid"),
                        resultSet.getLong("started_at_ms"),
                        resultSet.getLong("finished_at_ms"),
                        resultSet.getObject("exit_code") == null ? null : resultSet.getInt("exit_code"),
                        resultSet.getString("state"));
                rows.put(row.jobName(), row);
                assertTrue(row.pid() > 0, "审计必须有进程 ID");
                assertTrue(row.finishedAtMillis() >= row.startedAtMillis());
            }
        }

        assertEquals(4, rows.size(), "P1/P2/T1/F1 各一行: " + rows);
        AuditRow p1 = rows.get("P1");
        AuditRow p2 = rows.get("P2");
        assertEquals("audit-ok.sql", p1.scriptName());
        assertEquals("process", p1.mode());
        assertEquals("DONE", p1.state());
        assertEquals(0, p1.exitCode().intValue());
        assertTrue(p1.pid() != ProcessHandle.current().pid(), "进程作业记子进程 PID");
        assertEquals("DONE", p2.state(), "重复启动的进程作业也各记一行");
        assertEquals("audit-ok.sql", p2.scriptName());

        AuditRow t1 = rows.get("T1");
        assertEquals("thread", t1.mode());
        assertEquals(ProcessHandle.current().pid(), t1.pid(),
                "线程作业没有独立子进程，记当前 dbcli PID");

        AuditRow f1 = rows.get("F1");
        assertEquals("FAILED", f1.state());
        assertEquals(7, f1.exitCode().intValue(), "退出码要进审计");
    }

    @Test
    @DisplayName("进程作业内部再起孙作业：两行审计，子进程 PID 一致")
    void nestedJobAuditKeepsChildPid() throws Exception {
        script("audit-ok.sql", "_ECHO t\nok\necho off\n");
        script("audit-chain.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE G script=audit-ok.sql mode=thread
                _JOB START G
                _JOB WAIT G
                """);
        Path parent = script("audit-nested.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE C script=audit-chain.sql mode=process
                _JOB START C
                _JOB WAIT C
                """);

        Result result = run("--execute", parent.toString());
        assertEquals(0, result.exitCode, result.out);

        Class.forName("org.sqlite.JDBC");
        Path database = tempDir.resolve("work").resolve("dbcli_log.db");
        Map<String, AuditRow> rows = new HashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select job_name, script_name, mode, pid, started_at_ms, finished_at_ms, "
                             + "exit_code, state from job_audit order by audit_id")) {
            while (resultSet.next()) {
                AuditRow row = new AuditRow(
                        resultSet.getString("job_name"),
                        resultSet.getString("script_name"),
                        resultSet.getString("mode"),
                        resultSet.getLong("pid"),
                        resultSet.getLong("started_at_ms"),
                        resultSet.getLong("finished_at_ms"),
                        resultSet.getObject("exit_code") == null ? null : resultSet.getInt("exit_code"),
                        resultSet.getString("state"));
                rows.put(row.jobName(), row);
            }
        }

        assertEquals(2, rows.size(), "父进程作业 C 和孙作业 G 各一行: " + rows);
        AuditRow c = rows.get("C");
        AuditRow g = rows.get("G");
        assertEquals("process", c.mode());
        assertEquals("audit-chain.sql", c.scriptName());
        assertEquals("thread", g.mode());
        assertEquals("audit-ok.sql", g.scriptName());
        assertEquals(c.pid(), g.pid(), "孙线程作业与父进程作业在同一子 dbcli 进程里");
        assertTrue(c.pid() != ProcessHandle.current().pid(), "C 记录的是子 dbcli PID");
    }

    private record AuditRow(String jobName, String scriptName, String mode, long pid,
                            long startedAtMillis, long finishedAtMillis, Integer exitCode,
                            String state) {
    }

    @Test
    @DisplayName("使用 _JOB 时复用同一个 WORK/dbcli_log.db")
    void jobManagerReusesRunDatabase() throws Exception {
        script("child.sql", "_EXIT 0\n");
        Path parent = script("with-job.sql", """
                _JOB JOBMANAGER ON
                _JOB CREATE J script=child.sql mode=thread
                _JOB START J
                _JOB WAIT J
                """);

        Result result = run("--execute", parent.toString());

        assertEquals(0, result.exitCode, result.out);
        assertTrue(Files.isRegularFile(tempDir.resolve("work").resolve("dbcli_log.db")),
                "JobManager 使用固定运行库");
        assertFalse(Files.exists(tempDir.resolve("work").resolve(".dbcli").resolve("jobs")),
                "不再按 runId 建作业库");
    }

    @Test
    @DisplayName("入口脚本预置在 WORK：启动清空前拒绝（退出 2）")
    void entryScriptInsideWorkIsRejected() throws Exception {
        Path work = Files.createDirectories(tempDir.resolve("work"));
        Path entry = work.resolve("a.sql");
        Files.writeString(entry, "_ECHO t\nx\necho off\n", StandardCharsets.UTF_8);

        Result result = run("--execute", entry.toString());

        assertEquals(2, result.exitCode, result.out);
        assertTrue(result.out.contains("is inside the work/output directory"), result.out);
    }
}
