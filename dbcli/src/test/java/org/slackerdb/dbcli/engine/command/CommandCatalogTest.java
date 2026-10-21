package org.slackerdb.dbcli.engine.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbcli.engine.CommandDispatcher;
import org.slackerdb.dbcli.help.HelpCatalog;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命令表与命令实现的一致性：{@code _HELP} 里出现的每条命令都必须有实现，反过来也必须出现在帮助里。
 *
 * <p>这两边以前是两套手写清单（{@code ScriptRunner} 的 switch + 帮助文本），
 * 现在分别是 {@link CommandCatalog} 与 {@link HelpCatalog}，由本测试把它们钉在一起。</p>
 */
class CommandCatalogTest {

    @Test
    @DisplayName("帮助里的每条 _ 命令都有实现（命令表或既有分派器）")
    void everyHelpCommandHasAnImplementation() {
        for (String usage : HelpCatalog.usages()) {
            String token = usage.strip().split("[ \\t]", 2)[0];
            if (!token.startsWith("_")) {
                continue;
            }
            String name = token.substring(1);
            assertTrue(CommandCatalog.supports(name) || CommandDispatcher.supports(name),
                    "帮助里有 [" + token + "]，但没有对应的实现（命令表/分派器都没有）");
        }
    }

    @Test
    @DisplayName("命令表里的每条命令都出现在帮助里")
    void everyCatalogCommandAppearsInHelp() {
        String help = String.join("\n", HelpCatalog.overview());
        for (String name : CommandCatalog.names()) {
            assertTrue(help.contains("_" + name),
                    "命令 [" + name + "] 实现了，但 _HELP 里没有它（同源化要求两边同时可见）");
        }
    }

    @Test
    @DisplayName("登记是唯一且可查的：EXIT/QUIT 同一条实现，未知名字返回 null")
    void catalogIsUniqueAndQueryable() {
        assertNotNull(CommandCatalog.find("EXIT"));
        assertNotNull(CommandCatalog.find("quit"), "名字不区分大小写，且 EXIT/QUIT 是同一实现");
        assertSame(CommandCatalog.find("EXIT"), CommandCatalog.find("QUIT"));
        assertFalse(CommandCatalog.supports("NOPE"));
        assertListHasAll(CommandCatalog.names(),
                List.of("ASSERT", "ECHO", "EXIT", "HELP", "HOST", "LOAD", "QUIT", "SET", "SLEEP", "SPOOL",
                        "START", "WHENEVER"));
    }

    private static void assertListHasAll(List<String> actual, List<String> expected) {
        for (String name : expected) {
            assertTrue(actual.contains(name), "命令表缺少 [" + name + "]，实际: " + actual);
        }
    }
}
