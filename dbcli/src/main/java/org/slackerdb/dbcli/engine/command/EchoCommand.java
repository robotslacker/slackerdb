package org.slackerdb.dbcli.engine.command;

import java.util.Map;
import java.util.Set;

/**
 * {@code _ECHO <标题>} … {@code echo off}：块体逐行按**数据**输出（变量已在引擎层替换过）。
 *
 * <p>{@code _ECHO} 那一行上的标题只当标题，不输出；空正文是合法的（什么都不打印）。</p>
 */
public final class EchoCommand implements ScriptCommand {

    @Override
    public Set<String> names() {
        return Set.of("ECHO");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        String block = ir.get("block") == null ? "" : String.valueOf(ir.get("block"));
        if (block.isEmpty()) {
            return;
        }
        for (String line : block.split("\n", -1)) {
            call.context().output().printData(line);
        }
    }
}
