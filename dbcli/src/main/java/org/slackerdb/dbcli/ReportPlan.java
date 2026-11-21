package org.slackerdb.dbcli;

import org.slackerdb.dbcli.common.DbcliPaths;
import org.slackerdb.dbcli.daemon.CliDaemon;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 这次运行的"记录与参照比对"计划：{@code --logfile}、{@code --reference}（{@code --ref}）、
 * {@code --report-dir} 解析后的三个路径。
 *
 * <p>路径规则由 {@link DbcliPaths} 决定：</p>
 * <ul>
 *   <li>{@code --ref} 隐含一条 log：没给 {@code --logfile} 时派生成
 *       {@code OUTPUT/logs/<脚本基名>.log}；</li>
 *   <li>{@code --report-dir} 只为 {@code --reference} 存在；默认 {@code OUTPUT/ref}；</li>
 *   <li>{@code --logfile}/{@code --report-dir} 相对 OUTPUT，绝对原样；</li>
 *   <li>golden 读取：相对 WORK → HOME，绝对原样。</li>
 * </ul>
 */
public record ReportPlan(Path logFileTarget, Path referenceFile, Path reportDirectory) {

    /** 用法错误（打印一行、退出 {@link #exitCode()}）。 */
    public static final class InvalidUsageException extends Exception {
        private static final long serialVersionUID = 1L;

        private final int exitCode;

        InvalidUsageException(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    /**
     * @param reference       {@code --reference}/{@code --ref} 的原始取值（null = 没有）
     * @param logFile         {@code --logfile} 的原始取值（null = 没有）
     * @param reportDir       {@code --report-dir} 的原始取值（null = 没有）
     * @param paths           本次运行目录
     * @param scriptFile      入口脚本（用于派生默认 log；stdin/控制台为 null）
     */
    public static ReportPlan resolve(String reference, String logFile, String reportDir,
                                     DbcliPaths paths, Path scriptFile)
            throws InvalidUsageException {
        try {
            Path referenceFile = reference == null ? null : paths.findInput(reference, null);
            Path logFileTarget = logFile == null ? null : paths.outputFile(logFile);
            if (referenceFile != null) {
                Path referenceDirectory = referenceFile.getParent();
                if (referenceDirectory != null && !Files.isDirectory(referenceDirectory)) {
                    throw new InvalidUsageException(
                            "Reference directory [" + referenceDirectory + "] does not exist.", 1);
                }
                if (logFileTarget == null) {
                    if (scriptFile == null) {
                        throw new InvalidUsageException("--ref needs a script to name the run log; "
                                + "use --logfile <file> with stdin or the console.",
                                CliDaemon.EXIT_USAGE);
                    }
                    logFileTarget = paths.refLog(scriptFile);
                }
            }
            if (reportDir != null && referenceFile == null) {
                throw new InvalidUsageException("--report-dir requires --reference.",
                        CliDaemon.EXIT_USAGE);
            }
            // 只有真的要比对时才需要产物目录：普通运行不建 OUTPUT/ref（按需创建，见 DbcliPaths）
            Path reportDirectory = null;
            if (referenceFile != null) {
                reportDirectory = reportDir != null
                        ? paths.outputDirectory(reportDir)
                        : paths.refReportDir();
            }
            return new ReportPlan(logFileTarget, referenceFile, reportDirectory);
        } catch (IllegalArgumentException invalidPath) {
            throw new InvalidUsageException(invalidPath.getMessage(), 1);
        } catch (IllegalStateException cannotPrepareOutput) {
            // OUTPUT 是按需创建的：建不出来/锁不上时给一行可读错误，而不是一路冒成未捕获异常
            throw new InvalidUsageException(cannotPrepareOutput.getMessage(), 1);
        }
    }

    /** 本次运行的"被比对产物"：有 {@code --ref} 时一定存在（显式 log 或派生出来的 log）。 */
    public Path actualOutputFile() {
        return referenceFile == null ? null : logFileTarget;
    }
}
