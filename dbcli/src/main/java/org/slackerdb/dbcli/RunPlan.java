package org.slackerdb.dbcli;

import org.slackerdb.dbcli.common.DbcliPaths;

import java.nio.file.Path;

/**
 * 这次运行"跑哪个脚本"：解析后的脚本文件 + 三个运行目录。
 *
 * <p>脚本的相对名按 {@link DbcliPaths#findScript(String, Path)}（入口脚本为 WORK → HOME）；
 * 运行时工作目录是 {@link DbcliPaths#work()}（见 {@code ScriptContext}）。</p>
 *
 * @param scriptFile {@code --execute <文件>} 解析后的绝对路径；stdin/控制台为 null
 * @param paths      本次运行的 HOME/WORK/OUTPUT
 */
public record RunPlan(Path scriptFile, DbcliPaths paths) {

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
     * @throws MissingScriptException 给了脚本名但 WORK/HOME 都找不到
     */
    public static RunPlan resolve(String execute, DbcliPaths paths) throws MissingScriptException {
        Path scriptFile = null;
        if (execute != null && !"-".equals(execute)) {
            try {
                scriptFile = paths.findScript(execute, null);
            } catch (IllegalArgumentException invalidPath) {
                throw new MissingScriptException(invalidPath.getMessage());
            }
            if (scriptFile == null) {
                throw new MissingScriptException("Script file [" + execute + "] does not exist (searched ["
                        + paths.work().resolve(execute).normalize() + "], ["
                        + paths.home().resolve(execute).normalize() + "]).");
            }
        }
        return new RunPlan(scriptFile, paths);
    }

    /** 运行时工作目录（脚本里的相对写/`_HOST` cwd）。 */
    public Path workingDirectory() {
        return paths.work();
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

    /** 入口脚本所在目录（没有脚本时为 WORK）；用于“调用脚本目录”的相对解析。 */
    public Path scriptDirectory() {
        if (scriptFile == null || scriptFile.getParent() == null) {
            return paths.work();
        }
        return scriptFile.getParent();
    }
}
