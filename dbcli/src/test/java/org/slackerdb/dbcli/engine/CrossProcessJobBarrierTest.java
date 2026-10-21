package org.slackerdb.dbcli.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.rendezvous.SqliteRendezvous;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **跨进程**会合（脚本级、真实进程）：父脚本跑在本 JVM里，三个 {@code mode=process} 作业是真的子进程，
 * 它们各自卡在 {@code TIMER:GO} 上，父脚本 {@code _job timer GO} 放行后一起继续。
 *
 * <p>与 {@link JobBarrierTest}（线程模式，进程内闸门）互补：这里父脚本与作业**不在同一个进程**，
 * 定时点因此只能经 SQLite 同步库传递——正是 {@code mode=process} 要补上的那块能力。</p>
 *
 * <p>脚本里没有 SQL，所以不需要数据库；作业体走生产路径（{@code ModeJobExecutor} →
 * {@code ProcessJobExecutor} → 再起一个 dbcli 子进程）。</p>
 */
class CrossProcessJobBarrierTest {

    @TempDir
    Path tempDir;

    private record Result(int exitCode, String stdout, String stderr) {
    }

    private Result runParent(String script) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode;
        try (FakeSqlExecutor executor = new FakeSqlExecutor();
             ScriptContext context = new ScriptContext(executor,
                     new ScriptOutput(new PrintWriter(out, true), new PrintWriter(err, true)),
                     new VariableResolver(Map.of()), tempDir)) {
            exitCode = new ScriptRunner(context).runText(script, "<job-parent>", List.of());
        }
        return new Result(exitCode, out.toString(), err.toString());
    }

    @Test
    @DisplayName("三个真实子进程在 TIMER:GO 上对齐，父脚本放行后各自继续并 exit 0")
    void threeProcessJobsAlignAtOneTimerPoint() throws Exception {
        for (int job = 1; job <= 3; job++) {
            Files.writeString(tempDir.resolve("c" + job + ".sql"), String.join("\n",
                    "_echo t",
                    "C" + job + "-before-barrier",
                    "echo off",
                    "_job wait TIMER:GO timeout=60",
                    "_echo t",
                    "C" + job + "-after-barrier",
                    "echo off",
                    ""));
        }
        Result result = runParent(String.join("\n",
                "_job jobmanager on",
                "_job create C1 script=c1.sql mode=process",
                "_job create C2 script=c2.sql mode=process",
                "_job create C3 script=c3.sql mode=process",
                "_job start C1",
                "_job start C2",
                "_job start C3",
                "_sleep 1",
                "_job timer GO",
                "_job wait C1 timeout=60",
                "_job wait C2 timeout=60",
                "_job wait C3 timeout=60",
                "_job jobmanager off",
                ""));

        assertEquals(0, result.exitCode(), "stderr: " + result.stderr() + "\nstdout: " + result.stdout());
        assertTrue(result.stdout().contains("Timer point [GO] released."), result.stdout());
        for (int job = 1; job <= 3; job++) {
            String name = "C" + job;
            assertTrue(result.stdout().contains("Job [" + name + "] started."),
                    "父脚本应当看到作业启动: " + result.stdout());
            assertTrue(result.stdout().contains("Job [" + name + "] finished with exit code 0."),
                    name + " 应当在放行之后成功结束: " + result.stdout());

            List<String> lines = Files.readAllLines(tempDir.resolve(name + ".out")).stream()
                    .filter(line -> line.startsWith(name))
                    .toList();
            assertEquals(List.of(name + "-before-barrier", name + "-after-barrier"), lines,
                    name + " 的输出必须完整且顺序正确（屏障前后各一行）");
            assertTrue(Files.readString(tempDir.resolve(name + ".out"))
                            .contains("Timer point [GO] released."),
                    name + " 应当在子进程里看到放行");
            assertFalse(Files.readString(tempDir.resolve(name + ".out")).isBlank(),
                    name + " 的 .out 应当有内容: " + Files.readString(tempDir.resolve(name + ".out")));
        }
        // 作业的数据行绝不能混进父脚本的 stdout
        for (int job = 1; job <= 3; job++) {
            assertFalse(result.stdout().contains("C" + job + "-before-barrier"), result.stdout);
        }
        // 父脚本自己用的是同步库：作业进程读的是同一份（收尾时被建库者清理）
        assertFalse(Files.exists(tempDir.resolve(SqliteRendezvous.SYNC_DB_FILE_NAME)),
                "管理器收尾后不该留下同步库");
    }

    @Test
    @DisplayName("大小写错配也照样会合：子进程 `_job wait timer:go` / `Timer:Go` vs 父进程 `_job timer GO`")
    void caseMismatchedPointNamesStillMeetAcrossProcesses() throws Exception {
        // 两个作业进程各自用不同的大小写写法等同一个点；父脚本用大写的 GO 放行。
        // 两边是**真的两个 JVM**，所以这条验证的是"跨进程的点名规范化一致"，不是同进程里的巧合。
        Files.writeString(tempDir.resolve("c1.sql"), String.join("\n",
                "_echo t",
                "C1-before-barrier",
                "echo off",
                "_job wait timer:go timeout=60",
                "_echo t",
                "C1-after-barrier",
                "echo off",
                ""));
        Files.writeString(tempDir.resolve("c2.sql"), String.join("\n",
                "_echo t",
                "C2-before-barrier",
                "echo off",
                "_job wait Timer:Go timeout=60",
                "_echo t",
                "C2-after-barrier",
                "echo off",
                ""));

        Result result = runParent(String.join("\n",
                "_job jobmanager on",
                "_job create C1 script=c1.sql mode=process",
                "_job create C2 script=c2.sql mode=process",
                "_job start C1",
                "_job start C2",
                "_sleep 1",
                "_job timer GO",
                "_job wait C1 timeout=60",
                "_job wait C2 timeout=60",
                "_job jobmanager off",
                ""));

        assertEquals(0, result.exitCode(), "stderr: " + result.stderr() + "\nstdout: " + result.stdout());
        // 父脚本回显的是**它自己写的原样名字**
        assertTrue(result.stdout().contains("Timer point [GO] released."), result.stdout());
        for (String name : List.of("C1", "C2")) {
            assertTrue(result.stdout().contains("Job [" + name + "] finished with exit code 0."),
                    name + " 必须被跨进程放行（大小写不同也是同一个点）: " + result.stdout);
        }
        // 每个作业进程回显的是**它自己写的原样名字**，而不是规范化后的大写
        String c1 = Files.readString(tempDir.resolve("C1.out"));
        String c2 = Files.readString(tempDir.resolve("C2.out"));
        assertTrue(c1.contains("Timer point [go] released."),
                "子进程应当回显自己写的小写 go，实际:\n" + c1);
        assertTrue(c2.contains("Timer point [Go] released."),
                "子进程应当回显自己写的 Go（前缀 Timer: 不算名字），实际:\n" + c2);
        assertTrue(c1.contains("C1-after-barrier"), c1);
        assertTrue(c2.contains("C2-after-barrier"), c2);
        assertFalse(Files.exists(tempDir.resolve(SqliteRendezvous.SYNC_DB_FILE_NAME)),
                "管理器收尾后不该留下同步库");
    }
}
