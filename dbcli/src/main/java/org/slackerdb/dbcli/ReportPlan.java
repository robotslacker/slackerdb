package org.slackerdb.dbcli;

import org.slackerdb.dbcli.common.PathArguments;
import org.slackerdb.dbcli.daemon.CliDaemon;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 这次运行的"记录与参照比对"计划：{@code --logfile}、{@code --reference}（{@code --ref}）、
 * {@code --report-dir} 解析后的三个路径。
 *
 * <p>规则（与 README §4 一致）：</p>
 * <ul>
 *   <li>{@code --ref} 隐含一条 log：没给 {@code --logfile} 时派生成 {@code <脚本>.log}（在脚本旁边）；
 *       stdin/控制台没有脚本名，必须显式给 {@code --logfile}；</li>
 *   <li>{@code --report-dir} 只为 {@code --reference} 存在；默认值是**本次运行 log 的所在目录**
 *       （不是 golden 旁边——golden 常在只读夹具/版本控制目录里）；</li>
 *   <li>命令行选项一律按**进程当前目录**解析，与脚本放在哪里无关。</li>
 * </ul>
 *
 * @param logFileTarget    {@code --logfile}（或 {@code --ref} 隐含的 {@code <脚本>.log}）；null = 不落 log
 * @param referenceFile    {@code --reference}；null = 不做 golden 比对
 * @param reportDirectory  {@code .suc}/{@code .dif} 的目录（{@code --ref} 为 null 时没有意义）
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
     * @param processDirectory 命令行选项的基准目录
     * @param scriptFile      脚本文件（用于派生默认 log；stdin/控制台为 null）
     */
    public static ReportPlan resolve(String reference, String logFile, String reportDir,
                                     Path processDirectory, Path scriptFile)
            throws InvalidUsageException {
        try {
            Path referenceFile = reference == null ? null : resolvePath(processDirectory, reference);
            Path logFileTarget = logFile == null ? null : resolvePath(processDirectory, logFile);
            if (referenceFile != null) {
                Path referenceDirectory = referenceFile.getParent();
                if (referenceDirectory != null && !Files.isDirectory(referenceDirectory)) {
                    // 目录都不在 ⇒ 显然是路径写错，当场报错（否则只会在收尾时得到一条含糊的"文件不存在"）
                    throw new InvalidUsageException(
                            "Reference directory [" + referenceDirectory + "] does not exist.", 1);
                }
                if (logFileTarget == null) {
                    // "--execute 自带 --logfile"：有脚本名才能派生 log；stdin/控制台没有，必须显式给
                    if (scriptFile == null) {
                        throw new InvalidUsageException("--ref needs a script to name the run log; "
                                + "use --logfile <file> with stdin or the console.",
                                CliDaemon.EXIT_USAGE);
                    }
                    logFileTarget = defaultRunLog(scriptFile);
                }
            }
            if (reportDir != null && referenceFile == null) {
                // 产物目录只为 --reference 存在：少了参考文件就没有"比对产物"这回事，写错要当场说清
                throw new InvalidUsageException("--report-dir requires --reference.",
                        CliDaemon.EXIT_USAGE);
            }
            Path reportDirectory;
            if (reportDir != null) {
                reportDirectory = resolvePath(processDirectory, reportDir);
            } else {
                Path logParent = logFileTarget == null ? null : logFileTarget.toAbsolutePath().getParent();
                reportDirectory = logParent != null ? logParent : processDirectory;
            }
            return new ReportPlan(logFileTarget, referenceFile, reportDirectory);
        } catch (PathArguments.InvalidPathException invalidPath) {
            // 路径写错（空值/非法字符）是用法错误：一行可读消息，退出码 1
            throw new InvalidUsageException(invalidPath.getMessage(), 1);
        }
    }

    /** 本次运行的"被比对产物"：有 {@code --ref} 时一定存在（显式 log 或派生出来的 {@code <脚本>.log}）。 */
    public Path actualOutputFile() {
        return referenceFile == null ? null : logFileTarget;
    }

    /** {@code --ref} 隐含的 log：脚本旁边、脚本名去扩展名 + {@code .log}。 */
    private static Path defaultRunLog(Path scriptFile) {
        String name = scriptFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path parent = scriptFile.getParent();
        Path log = parent == null ? Path.of(base + ".log") : parent.resolve(base + ".log");
        return log.toAbsolutePath().normalize();
    }

    /** 相对路径按给定的基准目录解析。 */
    private static Path resolvePath(Path base, String path) {
        return PathArguments.resolve(base, path);
    }
}
