package org.slackerdb.dbcli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.common.DbcliPaths;
import org.slackerdb.dbcli.common.IllegalPaths;
import org.slackerdb.dbcli.daemon.CliDaemon;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReportPlan}：{@code --ref} 隐含 log、{@code --report-dir} 的默认目录、以及两类用法错误。
 */
class ReportPlanTest {

    @Test
    @DisplayName("--ref 没给 --logfile：派生成 OUTPUT/logs/<脚本基名>.log，产物目录 OUTPUT/ref")
    void referenceImpliesALogUnderOutput(@TempDir Path dir) throws Exception {
        DbcliPaths paths = DbcliPaths.forDirectory(dir);
        Path script = Files.writeString(dir.resolve("run.sql"), "select 1;\n");

        ReportPlan plan = ReportPlan.resolve("golden.ref", null, null, paths, script);

        assertEquals(dir.toAbsolutePath().resolve("logs").resolve("run.log"), plan.logFileTarget());
        assertEquals(dir.toAbsolutePath().resolve("ref"), plan.reportDirectory());
        assertEquals(dir.toAbsolutePath().resolve("golden.ref"), plan.referenceFile());
        assertEquals(plan.logFileTarget(), plan.actualOutputFile(), "被比对产物就是那条 log");
    }

    @Test
    @DisplayName("显式 --logfile / --report-dir：绝对路径原样，报告目录用给定的那个")
    void explicitPathsWin(@TempDir Path dir) throws Exception {
        DbcliPaths paths = DbcliPaths.forDirectory(dir);
        Path script = Files.writeString(dir.resolve("run.sql"), "select 1;\n");
        Path log = Files.writeString(dir.resolve("trace.txt"), "");
        Path reports = Files.createDirectories(dir.resolve("reports"));

        ReportPlan plan = ReportPlan.resolve("golden.ref", log.toString(), reports.toString(),
                paths, script);

        assertEquals(log.toAbsolutePath().normalize(), plan.logFileTarget());
        assertEquals(reports.toAbsolutePath().normalize(), plan.reportDirectory());
    }

    @Test
    @DisplayName("没有 --ref：不落 log、不比对、也不建产物目录（OUTPUT/ref 按需创建）")
    void withoutReferenceNothingIsPlanned(@TempDir Path dir) throws Exception {
        ReportPlan plan = ReportPlan.resolve(null, null, null,
                DbcliPaths.forDirectory(dir), null);

        assertNull(plan.referenceFile());
        assertNull(plan.logFileTarget());
        assertNull(plan.actualOutputFile());
        assertNull(plan.reportDirectory(), "没有比对就没有产物目录");
        assertFalse(Files.exists(dir.resolve("ref")), "不该建 ref 目录");
    }

    @Test
    @DisplayName("用错就报用法错误：--report-dir 少了 --ref；stdin 下的 --ref 少了 --logfile")
    void usageErrors(@TempDir Path dir) {
        DbcliPaths paths = DbcliPaths.forDirectory(dir);
        ReportPlan.InvalidUsageException noReference = assertThrows(
                ReportPlan.InvalidUsageException.class,
                () -> ReportPlan.resolve(null, null, "reports", paths, null));
        assertEquals("--report-dir requires --reference.", noReference.getMessage());
        assertEquals(CliDaemon.EXIT_USAGE, noReference.exitCode());

        ReportPlan.InvalidUsageException noLog = assertThrows(
                ReportPlan.InvalidUsageException.class,
                () -> ReportPlan.resolve("golden.ref", null, null, paths, null));
        assertTrue(noLog.getMessage().startsWith("--ref needs a script to name the run log;"),
                noLog.getMessage());
        assertEquals(CliDaemon.EXIT_USAGE, noLog.exitCode());
    }

    @Test
    @DisplayName("ref 目录不存在：当场报错（不拖到收尾时含糊地说 文件不存在）")
    void missingReferenceDirectoryIsAnError(@TempDir Path dir) {
        DbcliPaths paths = DbcliPaths.forDirectory(dir);
        ReportPlan.InvalidUsageException invalid = assertThrows(
                ReportPlan.InvalidUsageException.class,
                () -> ReportPlan.resolve("no/such/dir/golden.ref", null, null, paths, null));
        assertTrue(invalid.getMessage().startsWith("Reference directory ["), invalid.getMessage());
        assertEquals(1, invalid.exitCode());
    }

    @Test
    @DisplayName("路径含非法字符：用法错误（退出码 1）+ 可读消息")
    void invalidPathIsAUsageError(@TempDir Path dir) {
        DbcliPaths paths = DbcliPaths.forDirectory(dir);
        ReportPlan.InvalidUsageException invalid = assertThrows(
                ReportPlan.InvalidUsageException.class,
                () -> ReportPlan.resolve("g.ref", IllegalPaths.VALUE, null, paths, null));
        assertTrue(invalid.getMessage().startsWith(IllegalPaths.INVALID_PREFIX), invalid.getMessage());
        assertEquals(1, invalid.exitCode());
    }
}
