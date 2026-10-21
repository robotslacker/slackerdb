package org.slackerdb.dbcli.help;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _HELP} 与 README **同源**的守卫。
 *
 * <p>两件事必须永远成立（否则构建失败）：</p>
 * <ol>
 *   <li>{@code _HELP} 的总览列出 {@link HelpCatalog#usages()} 里的**每一条**用法，且总览里
 *       {@code see _HELP <topic>} 指向的主题都真的有详情；</li>
 *   <li>中英两份 README 里 {@code BEGIN/END GENERATED: help-catalog} 之间的内容与
 *       {@link HelpCatalog#readmeBlock()} **逐字节一致**——改命令不改 README 过不了这里。</li>
 * </ol>
 *
 * <p>README 块只有用法（语言无关），因此不需要为每条命令维护中英两份译文；
 * 语义与示例仍然在各语言的小节里。</p>
 */
class HelpCatalogReadmeTest {

    /** 两份 README 的候选位置：模块目录（surefire 的默认工作目录）与仓库根目录。 */
    private static final List<String> README_NAMES = List.of("README.adoc", "README-CN.adoc");

    /** 总览里 "see _HELP <topic>" 的引用。 */
    private static final Pattern SEE_HELP = Pattern.compile("see _HELP ([A-Z]+)");

    @Test
    @DisplayName("_HELP 总览列出命令表里的每一条用法")
    void overviewListsEveryUsage() {
        String overview = String.join("\n", HelpCatalog.overview());

        for (String usage : HelpCatalog.usages()) {
            assertTrue(overview.contains(usage), "总览缺少用法 [" + usage + "]");
        }
        assertEquals("Available commands:", HelpCatalog.overview().get(0), "总览第一行是契约：" + HelpCatalog.overview().get(0));
    }

    @Test
    @DisplayName("总览里 see _HELP <topic> 指向的主题必须真的有详情")
    void overviewReferencesResolveToTopics() {
        Matcher matcher = SEE_HELP.matcher(String.join("\n", HelpCatalog.overview()));
        while (matcher.find()) {
            String topic = matcher.group(1);
            assertNotNull(HelpCatalog.topic(topic, 16),
                    "总览指向 _HELP " + topic + "，但该主题没有详情");
        }
    }

    @Test
    @DisplayName("每个主题都有内容；未知主题返回 null；嵌套上限由调用方注入")
    void topicsExist() {
        for (String name : new String[] {"ASSERT", "COMPARE", "CONNECT", "DISCONNECT", "ECHO", "EXIT",
                "HELP", "HOST", "JOB", "LOAD", "SESSION", "SET", "SLEEP", "SPOOL", "SSH", "START", "WHENEVER"}) {
            List<String> lines = HelpCatalog.topic(name, 16);
            assertNotNull(lines, name);
            assertFalse(lines.isEmpty(), name);
        }
        assertNull(HelpCatalog.topic("NOPE", 16));
        assertTrue(String.join("\n", HelpCatalog.topic("START", 7)).contains("limited to 7 levels"),
                "START 的嵌套上限来自调用方（不是写死的 16）");
    }

    @Test
    @DisplayName("两份 README 的生成块与 HelpCatalog 逐字节一致（只容忍 CRLF/LF 差异）")
    void readmeBlocksAreInSync() throws IOException {
        String generated = HelpCatalog.readmeBlock();
        for (String name : README_NAMES) {
            Path file = locate(name);
            String block = generatedBlock(file);
            assertEquals(generated.strip(), block.strip(),
                    name + " 的命令速查块与 HelpCatalog 不同步："
                            + "把标记之间的内容换成下面这一份（来自 HelpCatalog.readmeBlock()），"
                            + "就恢复同源：\n" + generated);
        }
    }

    @Test
    @DisplayName("两份 README 都覆盖命令表里的每一条用法")
    void everyUsageAppearsInBothReadmes() throws IOException {
        for (String name : README_NAMES) {
            Path file = locate(name);
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String usage : HelpCatalog.usages()) {
                assertTrue(text.contains(usage), name + " 缺少用法 [" + usage + "]");
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 找到 README（模块目录优先，其次仓库根目录）；都不在时跳过（例如从安装目录跑测试）。 */
    private static Path locate(String name) {
        Path moduleLocal = Path.of(name);
        if (Files.isRegularFile(moduleLocal)) {
            return moduleLocal;
        }
        Path repositoryRoot = Path.of("dbcli", name);
        Assumptions.assumeTrue(Files.isRegularFile(repositoryRoot),
                "找不到 " + name + "（既不在当前目录也不在 dbcli/ 下）");
        return repositoryRoot;
    }

    /** README 里两个生成标记之间的内容（含标记行；行尾归一成 LF）。 */
    private static String generatedBlock(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int begin = text.indexOf(HelpCatalog.BEGIN_MARKER);
        int end = text.indexOf(HelpCatalog.END_MARKER);
        assertTrue(begin >= 0, file + " 缺少生成标记 [" + HelpCatalog.BEGIN_MARKER + "]");
        assertTrue(end > begin, file + " 缺少结束标记 [" + HelpCatalog.END_MARKER + "]");
        return text.substring(begin, end + HelpCatalog.END_MARKER.length());
    }
}
