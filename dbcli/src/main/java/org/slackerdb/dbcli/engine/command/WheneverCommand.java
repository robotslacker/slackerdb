package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.engine.ScriptContext;

import java.util.Map;
import java.util.Set;

/**
 * {@code _WHENEVER ERROR CONTINUE|EXIT n}：设置"出错之后怎么办"。
 *
 * <p>默认是 {@code continue}：错误照样打印、照样计数，但不改变退出码（退出码只由显式
 * {@code _WHENEVER ERROR EXIT n} / {@code _EXIT n} 与命令行层面的成败决定）。</p>
 */
public final class WheneverCommand implements ScriptCommand {

    @Override
    public Set<String> names() {
        return Set.of("WHENEVER");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        String condition = ir.get("condition") == null ? "error" : String.valueOf(ir.get("condition"));
        String action = ir.get("action") == null ? "continue" : String.valueOf(ir.get("action"));
        int exitCode = ir.get("exitCode") instanceof Integer value ? value : 0;
        call.context().setWhenever(new ScriptContext.Whenever(condition, action, exitCode));
    }
}
