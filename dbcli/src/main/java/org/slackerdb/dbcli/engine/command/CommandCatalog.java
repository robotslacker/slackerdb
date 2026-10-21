package org.slackerdb.dbcli.engine.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 命令表：IR 名字 → 实现。**一命令一类，登记只在这里**。
 *
 * <p>{@code _HELP} 的用法清单在 {@code HelpCatalog}，实现登记在这里——两边是同一份命令清单的
 * 两个侧面，新增一条命令时必须同时出现在两处（{@code HelpCatalogReadmeTest} 与
 * {@code CommandCatalogTest} 会互相盯着）。</p>
 */
public final class CommandCatalog {

    /** 登记顺序不影响行为（按名字查），但列出来就是"这个客户端认识哪些命令"。 */
    private static final List<ScriptCommand> COMMANDS = List.of(
            new AssertCommand(),
            new EchoCommand(),
            new ExitCommand(),
            new HelpCommand(),
            new HostCommand(),
            new LoadCommand(),
            new SetCommand(),
            new SleepCommand(),
            new SpoolCommand(),
            new StartCommand(),
            new WheneverCommand());

    private static final Map<String, ScriptCommand> BY_NAME;
    private static final List<String> NAMES;
    static {
        Map<String, ScriptCommand> byName = new LinkedHashMap<>();
        for (ScriptCommand command : COMMANDS) {
            // 同一实现可给多个名字（EXIT/QUIT），集合本身无序：按字母序固定下来
            List<String> commandNames = new java.util.ArrayList<>(command.names());
            java.util.Collections.sort(commandNames);
            for (String name : commandNames) {
                ScriptCommand previous = byName.put(name.toUpperCase(Locale.ROOT), command);
                if (previous != null) {
                    throw new IllegalStateException("command [" + name + "] is registered twice");
                }
            }
        }
        BY_NAME = Map.copyOf(byName);
        // 顺序单独存：Map.copyOf 不保证迭代顺序，而 _HELP/测试要的是"登记顺序"
        NAMES = List.copyOf(new java.util.ArrayList<>(byName.keySet()));
    }

    private CommandCatalog() {
    }


    /** 按 IR 名字找实现（大写，不区分大小写）；不认识返回 null。 */
    public static ScriptCommand find(String name) {
        return name == null ? null : BY_NAME.get(name.toUpperCase(Locale.ROOT));
    }

    public static boolean supports(String name) {
        return find(name) != null;
    }

    /** 全部命令名（按登记顺序；测试用）。 */
    public static List<String> names() {
        return NAMES;
    }
}
