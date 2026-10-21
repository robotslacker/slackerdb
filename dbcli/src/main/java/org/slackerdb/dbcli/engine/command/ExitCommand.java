package org.slackerdb.dbcli.engine.command;

import java.util.Map;
import java.util.Set;

/**
 * {@code _EXIT [n]} / {@code _QUIT [n]}：立刻以 {@code n}（默认 0）结束**整次运行**。
 *
 * <p>它不改写其他状态：退出码由 {@code ScriptContext.finalExitCode()} 统一裁决，
 * 而"已经请求退出"会让后续输入（同一行的剩余语句、后续行）不再执行。</p>
 */
public final class ExitCommand implements ScriptCommand {

    @Override
    public Set<String> names() {
        return Set.of("EXIT", "QUIT");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        int exitValue = ir.get("exitValue") instanceof Integer value ? value : 0;
        call.context().requestExit(exitValue);
    }
}
