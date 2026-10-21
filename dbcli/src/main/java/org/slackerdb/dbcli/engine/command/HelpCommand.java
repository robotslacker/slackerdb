package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.engine.ScriptRunner;
import org.slackerdb.dbcli.help.HelpCatalog;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code _HELP} / {@code _HELP <topic>}：文案全部来自 {@link HelpCatalog}（与 README 命令清单同源）。
 */
public final class HelpCommand implements ScriptCommand {

    @Override
    public Set<String> names() {
        return Set.of("HELP");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        String topic = ir.get("topic") == null ? "" : String.valueOf(ir.get("topic")).strip();
        if (topic.isEmpty()) {
            for (String line : HelpCatalog.overview()) {
                call.context().output().printData(line);
            }
            return;
        }
        List<String> detail = HelpCatalog.topic(topic, ScriptRunner.MAX_SCRIPT_DEPTH);
        if (detail == null) {
            call.reportError("Unknown help topic [" + topic + "].");
            return;
        }
        for (String line : detail) {
            call.context().output().printData(line);
        }
    }
}
