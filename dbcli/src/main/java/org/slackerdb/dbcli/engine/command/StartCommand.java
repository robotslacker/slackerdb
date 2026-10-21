package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.common.PathArguments;
import org.slackerdb.dbcli.engine.ScriptRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code _START <脚本> [参数…]}：串行执行另一个脚本，共享会话状态。
 *
 * <p>相对脚本名先在工作目录里找，再在当前脚本所在目录里找（子脚本常与父脚本放一起）。
 * 嵌套上限是 {@link ScriptRunner#MAX_SCRIPT_DEPTH}；跑完把**子脚本自己的**退出码记进会话，
 * 供随后的 {@code _ASSERT RUN.EXITCODE} 使用。</p>
 */
public final class StartCommand implements ScriptCommand {

    @Override
    public Set<String> names() {
        return Set.of("START");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        Object rawScript = ir.get("script");
        if (rawScript == null || String.valueOf(rawScript).isBlank()) {
            call.reportError("Missing script name for _START.");
            return;
        }
        String script = String.valueOf(rawScript);
        if (call.context().depth() >= ScriptRunner.MAX_SCRIPT_DEPTH) {
            call.reportError("_START nesting is too deep (limit "
                    + ScriptRunner.MAX_SCRIPT_DEPTH + "): [" + script + "]");
            return;
        }
        Path file;
        try {
            file = resolveScriptFile(call, script);
        } catch (PathArguments.InvalidPathException invalidPath) {
            call.reportError(invalidPath.getMessage());
            return;
        }
        if (file == null) {
            call.reportError("Script file [" + script + "] does not exist.");
            return;
        }
        List<String> childArgv = new ArrayList<>();
        if (ir.get("argv") instanceof List<?> argv) {
            for (Object argument : argv) {
                childArgv.add(String.valueOf(argument));
            }
        }
        int childExitCode = call.host().runChildScript(file, childArgv);
        // 文档承诺 RUN.EXITCODE 是"子脚本的退出码"：跑完就记下来，供随后的 _ASSERT 使用
        call.context().setLastSubScriptExitCode(childExitCode);
    }

    /** 子脚本定位：先按工作目录，再按当前脚本所在目录。 */
    private static Path resolveScriptFile(CommandCall call, String script) {
        Path candidate = call.resolvePath(script);
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        Path current = call.context().currentScriptFile();
        if (current != null && current.getParent() != null) {
            Path beside = current.getParent().resolve(script).normalize();
            if (Files.isRegularFile(beside)) {
                return beside;
            }
        }
        return null;
    }
}
