package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.common.IllegalPaths;
import org.slackerdb.dbcli.daemon.CliDaemon;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReportPlan}：{@code --ref} 隐含 log、{@code --report-dir} 的默认目录、以及两类用法错误。
 */
class ReportPlanTest {

    @Test
    @DisplayName("--ref 没给 --logfile：派生成 <脚本>.log（脚本旁边），产物目录跟着 log 走")
    void referenceImpliesALogNextToTheScript(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("run.sql"), "select 1;\n");

        ReportPlan plan = ReportPlan.resolve("golden.ref", null, null,
                Path.of("").toAbsolutePath(), script);

        assertEquals(dir.toAbsolutePath().resolve("run.log"), plan.logFileTarget());
        assertEquals(dir.toAbsolutePath(), plan.reportDirectory(), "默认产物目录 = log 所在目录");
        assertEquals(Path.of("golden.ref").toAbsolutePath(), plan.referenceFile());
        assertEquals(plan.logFileTarget(), plan.actualOutputFile(), "被比对产物就是那条 log");
    }

    @Test
    @DisplayName("显式 --logfile / --report-dir：按进程当前目录解析，报告目录用给定的那个")
    void explicitPathsWin(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("run.sql"), "select 1;\n");
        Path log = Files.writeString(dir.resolve("trace.txt"), "");
        Path reports = Files.createDirectories(dir.resolve("reports"));

        ReportPlan plan = ReportPlan.resolve("golden.ref", log.toString(), reports.toString(),
                dir.toAbsolutePath(), script);

        assertEquals(log.toAbsolutePath().normalize(), plan.logFileTarget());
        assertEquals(reports.toAbsolutePath().normalize(), plan.reportDirectory());
    }

    @Test
    @DisplayName("没有 --ref：不落 log、不比对")
    void withoutReferenceNothingIsPlanned() throws Exception {
        ReportPlan plan = ReportPlan.resolve(null, null, null, Path.of("").toAbsolutePath(), null);

        assertNull(plan.referenceFile());
        assertNull(plan.logFileTarget());
        assertNull(plan.actualOutputFile());
    }

    @Test
    @DisplayName("用错就报用法错误：--report-dir 少了 --ref；stdin 下的 --ref 少了 --logfile")
    void usageErrors() {
        ReportPlan.InvalidUsageException noReference = assertThrows(
                ReportPlan.InvalidUsageException.class,
                () -> ReportPlan.resolve(null, null, "reports", Path.of("").toAbsolutePath(), null));
        assertEquals("--report-dir requires --reference.", noReference.getMessage());
        assertEquals(CliDaemon.EXIT_USAGE, noReference.exitCode());

        ReportPlan.InvalidUsageException noLog = assertThrows(
                ReportPlan.InvalidUsageException.class,
                () -> ReportPlan.resolve("golden.ref", null, null, Path.of("").toAbsolutePath(), null));
        assertTrue(noLog.getMessage().startsWith("--ref needs a script to name the run log;"),
                noLog.getMessage());
        assertEquals(CliDaemon.EXIT_USAGE, noLog.exitCode());
    }

    @Test
    @DisplayName("ref 目录不存在：当场报错（不拖到收尾时含糊地说 文件不存在 ）")
    void missingReferenceDirectoryIsAnError() {
        Path processDirectory = Path.of("").toAbsolutePath();
        ReportPlan.InvalidUsageException invalid = assertThrows(
                ReportPlan.InvalidUsageException.class,
                () -> ReportPlan.resolve("no/such/dir/golden.ref", null, null, processDirectory, null));
        assertTrue(invalid.getMessage().startsWith("Reference directory ["), invalid.getMessage());
        assertEquals(1, invalid.exitCode());
    }

    @Test
    @DisplayName("路径含非法字符：用法错误（退出码 1）+ 可读消息")
    void invalidPathIsAUsageError() {
        // 取值必须"在本平台上非法"：* 只在 Windows 上非法，POSIX 上要用 NUL（见 IllegalPaths）
        ReportPlan.InvalidUsageException invalid = assertThrows(ReportPlan.InvalidUsageException.class,
                () -> ReportPlan.resolve("g.ref", IllegalPaths.VALUE, null,
                        Path.of("").toAbsolutePath(), null));
        assertTrue(invalid.getMessage().startsWith(IllegalPaths.INVALID_PREFIX), invalid.getMessage());
        assertEquals(1, invalid.exitCode());
    }
}
