package org.slackerdb.dbcli;

import org.slackerdb.dbcli.common.PathArguments;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 这次运行"在哪儿跑"：脚本文件、脚本工作目录、命令行选项的基准目录。
 *
 * <p>从 {@link Cli} 里抽出来的原因：{@code call()} 以前把参数解析、路径推导、连接、profile、
 * 控制台、参照比对全写在一个方法里，{@code --execute} 的路径规则（脚本里的相对路径跟脚本、
 * 命令行选项跟进程当前目录）散落在几十行中间，改一处很难看清影响。</p>
 *
 * <p>不变的规则：<b>脚本里</b>的相对路径以 {@link #workingDirectory()} 为基准；
 * <b>命令行选项</b>（{@code --profile}/{@code --logfile}/{@code --reference}/{@code --report-dir}/
 * {@code --pidfile}）以 {@link #processDirectory()} 为基准。</p>
 *
 * @param scriptFile       {@code --execute <文件>} 解析后的绝对路径；stdin/控制台为 null
 * @param workingDirectory 脚本所在目录（脚本里的相对路径基准）；stdin/控制台是进程当前目录
 * @param processDirectory 进程当前目录（命令行选项的基准）
 */
public record RunPlan(Path scriptFile, Path workingDirectory, Path processDirectory) {

    /** 脚本文件不存在（命令行层面的错误：打印一行、退出 1）。 */
    public static final class MissingScriptException extends Exception {
        private static final long serialVersionUID = 1L;

        MissingScriptException(String message) {
            super(message);
        }
    }

    /**
     * 解析 {@code --execute}（{@code -} 与 null 都表示"没有脚本文件"）。
     *
     * @throws MissingScriptException 给了脚本名但文件不存在
     */
    public static RunPlan resolve(String execute) throws MissingScriptException {
        Path scriptFile;
        if (execute == null || "-".equals(execute)) {
            scriptFile = null;
        } else {
            try {
                scriptFile = PathArguments.resolve(Path.of("").toAbsolutePath(), execute);
            } catch (PathArguments.InvalidPathException invalidPath) {
                // 路径写错是命令行层面的错误：一行可读消息，与"文件不存在"同一个返回码（1）
                throw new MissingScriptException(invalidPath.getMessage());
            }
        }
        if (scriptFile != null && !Files.isRegularFile(scriptFile)) {
            throw new MissingScriptException("Script file [" + execute + "] does not exist.");
        }
        Path processDirectory = Path.of("").toAbsolutePath();
        Path workingDirectory = scriptFile == null || scriptFile.getParent() == null
                ? processDirectory : scriptFile.getParent();
        return new RunPlan(scriptFile, workingDirectory, processDirectory);
    }

    /**
     * 脚本的显示名（错误文案与 {@code --ref} 的 {@code .suc} 用）：
     * 文件名 / {@code <stdin>} / {@code <console>}。
     */
    public String scriptName(String stdinScript) {
        if (scriptFile != null) {
            return scriptFile.getFileName() == null ? scriptFile.toString()
                    : scriptFile.getFileName().toString();
        }
        return stdinScript != null ? "<stdin>" : "<console>";
    }
}
