package org.slackerdb.dbcli.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompareReporter} 的契约测试。
 *
 * <p>断言用真实的比对样例（console 差异表与 {@code .dif} 字节）；默认选项（忽略大小写 + 剥离首尾空白）下：</p>
 * <pre>
 *   work = Hello/ChinA/Russia/America   （targetFile，_COMPARE 的第 1 个参数）
 *   ref  = Hello/China/America/France   （referenceFile，第 2 个参数）
 *   .dif = b'      1 Hello\r\n      2 ChinA\r\n-     3 Russia\r\n      4 America\r\n+     4 France\r\n'
 *   rows = [["-","     3","","Russia"], ["+","","     4","France"]]
 * </pre>
 *
 * <p>HTML 的逐字节内容规范里标为"未锁定"，故这里只断言结构与转义规则；完整字节只用一个固定时间戳的
 * 样例人工核对过（见 {@link CompareReporter} 类注释里的限制说明）。</p>
 */
class CompareReporterTest {

    private static final List<String> WORK = List.of("Hello", "ChinA", "Russia", "America");
    private static final List<String> REF = List.of("Hello", "China", "America", "France");

    /** 规范 §6.3 的 {@code .dif} 字节（含 CRLF 与末尾换行）。 */
    private static final String CONTRACT_DIF =
            "      1 Hello\r\n      2 ChinA\r\n-     3 Russia\r\n      4 America\r\n+     4 France\r\n";

    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 10, 16, 2, 26, 11);

    private static Path write(Path dir, String name, List<String> lines) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** 把两侧写盘后真跑内核，得到报告器（固定用"忽略大小写 + 剥离首尾空白"的口径，便于对照契约样例）。 */
    private static CompareReporter reporter(Path dir, List<String> work, List<String> ref,
                                            LineRules rules) throws IOException {
        Path target = write(dir, "work.txt", work);
        Path reference = write(dir, "ref.txt", ref);
        CompareOptions options = new CompareOptions();
        options.setCaseSensitive(false);
        options.setIgnoreHeadTailBlank(true);
        return new CompareReporter(FileComparer.compare(reference, target, options, rules));
    }

    @Test
    @DisplayName("契约 §6.3：报告行与 .dif 逐字节（真实样例）")
    void contractSample(@TempDir Path dir) throws IOException {
        CompareReporter reporter = reporter(dir, WORK, REF, null);

        assertEquals("Compare failed.", reporter.status());

        // 报告行（' '、'-'、'+'、'S' 四种标记）：' ' 行取**工作文件**的行号与原文，
        // 因此 'ChinA' 的大小写被保留
        assertEquals(List.of("      1 Hello", "      2 ChinA", "-     3 Russia",
                "      4 America", "+     4 France"), reporter.reportLines());

        Path diff = reporter.writeDiffFile();
        assertEquals(dir.resolve("work.dif"), diff, ".dif 与 targetFile 同目录、同名去扩展名");
        assertEquals(CONTRACT_DIF, Files.readString(diff, StandardCharsets.UTF_8));
        // 再按字节核对一遍（readString 不做换行归一化，这里显式锁 CRLF 的字节数）
        assertEquals(CONTRACT_DIF.getBytes(StandardCharsets.UTF_8).length, Files.size(diff));
    }

    @Test
    @DisplayName("契约 §6.3 第二个样例：替换块内 '-' 排在 '+' 之前（内核原生顺序是反的）")
    void replacementBlockOrder(@TempDir Path dir) throws IOException {
        // 期望的 .dif 字节：b'      1 A\r\n-     2 B\r\n+     2 C\r\n'（'-' 在替换块内排在 '+' 之前）
        CompareReporter reporter = reporter(dir, List.of("A", "B"), List.of("A", "C"), null);
        assertEquals(List.of("      1 A", "-     2 B", "+     2 C"), reporter.reportLines());
        assertEquals("      1 A\r\n-     2 B\r\n+     2 C\r\n",
                Files.readString(reporter.writeDiffFile(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("两侧相同：Compare successful.、**不写** .dif/.html（契约 §6.3）")
    void identicalWritesNothing(@TempDir Path dir) throws IOException {
        CompareReporter reporter = reporter(dir, WORK, WORK, null);

        assertEquals("Compare successful.", reporter.status());

        assertNull(reporter.writeDiffFile(), "成功时不写 .dif");
        assertNull(reporter.writeHtmlFile(FIXED_TIME), "成功时不写 .html");
        assertFalse(Files.exists(reporter.diffFilePath()));
        assertFalse(Files.exists(reporter.htmlFilePath()));
    }

    @Test
    @DisplayName("行号格子是 6 字符右对齐（'%6d'）、content 不 strip（契约 §6.2）")
    void lineNumbersAndRawContent(@TempDir Path dir) throws IOException {
        List<String> work = new ArrayList<>();
        for (int index = 1; index <= 11; index++) {
            work.add("line " + index);
        }
        work.add("  different  ");
        List<String> ref = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            ref.add("line " + index);
        }
        CompareReporter reporter = reporter(dir, work, ref, null);

        // 默认选项会剥离首尾空白后比较，但**展示**的是原文件那一行（归一化只用于"是否相等"的判定，
        // 不就地改写原文；见 CompareReporter 类注释；行列表不做别名共享）
        assertEquals(List.of("-    12   different  ", "+    12 line 12"),
                reporter.reportLines().stream().filter(line -> line.charAt(0) != ' ').toList(),
                "行号是 6 字符右对齐，content 不 strip");
    }

    @Test
    @DisplayName("' ' 与 'S' 行只在 .dif 里（§7.3.1 第 4 条：'S' 行按字面逻辑实现）")
    void ignoredLinesOnlyInDiffFile(@TempDir Path dir) throws IOException {
        LineRules rules = new LineRules();
        rules.add(LineRules.Rule.Type.SKIP, "SKIPME", null);

        // work 的第 2 行命中 SKIPLINE，两侧都删掉它后：ref=[A,B,Y]、work=[A,B,X]
        CompareReporter reporter = reporter(dir, List.of("A", "SKIPME", "B", "X"), List.of("A", "B", "Y"), rules);

        assertEquals(List.of("      1 A", "S     2 SKIPME", "      3 B", "-     4 X", "+     3 Y"),
                reporter.reportLines(), ".dif 里补出被 skip 掉的工作文件行（标记 'S'）");
        assertEquals("      1 A\r\nS     2 SKIPME\r\n      3 B\r\n-     4 X\r\n+     3 Y\r\n",
                Files.readString(reporter.writeDiffFile(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("工作文件末尾被 SKIPLINE 丢掉的行也补成 S 行（报告要能解释为什么一致）")
    void trailingIgnoredLinesAreReported(@TempDir Path dir) throws IOException {
        LineRules rules = new LineRules();
        rules.add(LineRules.Rule.Type.SKIP, "SKIPME", null);

        CompareReporter reporter = reporter(dir, List.of("A", "B", "SKIPME"), List.of("A", "B"), rules);

        assertEquals(List.of("      1 A", "      2 B", "S     3 SKIPME"), reporter.reportLines());
    }

    @Test
    @DisplayName("行号 1000000 不被截断：报告行的行号按数字读取，不按固定 6 字符字段")
    void largeLineNumbersAreNotTruncated() {
        assertEquals(List.of("+1000000 X"),
                CompareReporter.reassemble(List.of("+1000000 X"), List.of()),
                "旧实现按 substring(1,7) 会把它读成 100000，正文也切错位");
    }
    @Test
    @DisplayName("契约 §7：报告行行首不是 ' '/'+'/'-' → Missed line number. Bad compare result. [<row>]")
    void malformedReportLine() {
        CompareReporter.CompareReportException error = assertThrows(CompareReporter.CompareReportException.class,
                () -> CompareReporter.reassemble(List.of("?     3 Russia"), List.of("Hello", "ChinA", "Russia")));
        assertEquals("Missed line number. Bad compare result. [?     3 Russia]", error.getMessage());
    }

    @Test
    @DisplayName("输出文件名 = targetFile 去扩展名 + .dif/.html（multi.dot.name.txt → multi.dot.name.dif）")
    void reportPaths(@TempDir Path dir) throws IOException {
        Path reference = write(dir, "reference.txt", REF);
        Path target = write(dir, "multi.dot.name.txt", WORK);
        CompareReporter reporter = new CompareReporter(
                FileComparer.compare(reference, target, new CompareOptions(), null));
        assertEquals(dir.resolve("multi.dot.name.dif"), reporter.diffFilePath());
        assertEquals(dir.resolve("multi.dot.name.html"), reporter.htmlFilePath());

        Path noExtension = write(dir, "noext", WORK);
        CompareReporter noExtReporter = new CompareReporter(
                FileComparer.compare(reference, noExtension, new CompareOptions(), null));
        assertEquals(dir.resolve("noext.dif"), noExtReporter.diffFilePath());
    }

    @Test
    @DisplayName(".html：四段模板按序拼接、数据行来自报告行、页脚时间注入、全文件 CRLF")
    void htmlDocument(@TempDir Path dir) throws IOException {
        CompareReporter reporter = reporter(dir, WORK, REF, null);
        Path html = reporter.writeHtmlFile(FIXED_TIME);
        assertEquals(dir.resolve("work.html"), html);

        String document = Files.readString(html, StandardCharsets.UTF_8);
        assertTrue(document.startsWith("<!DOCTYPE html>\r\n"), "header.html 必须最先出现");
        assertTrue(document.contains("Main content area:  " + reporter.result().targetPath() + " vs "
                + reporter.result().referencePath() + " </h4>"), "body.content.html 的标题行（area: 后两个空格）");
        assertTrue(document.contains("<h4>Comment area</h4>"), "body.header.html 的注释区");
        // parseBodyLine: flag=line[0]、number=第二个字段、source=其余（strip）
        assertTrue(document.contains("        {flag: ' ', number:\"1\", source:\"Hello\"},\r\n"));
        assertTrue(document.contains("        {flag: '-', number:\"3\", source:\"Russia\"},\r\n"));
        assertTrue(document.contains("        {flag: '+', number:\"4\", source:\"France\"},\r\n"));
        assertTrue(document.contains(", number:\"4\", source:\"France\"},"), "每个报告行一条 JS 数据");
        assertTrue(document.contains("<p>Report generated at: 2026-10-16 02:26:11</p>"), "页脚时间来自入参");
        assertTrue(document.contains("</html>\r\n\r\n"), "footer 片段后还有一个换行（每个片段写完都补 '\\n'）");
        for (int index = 0; index < document.length(); index++) {
            if (document.charAt(index) == '\n') {
                assertEquals('\r', document.charAt(index - 1), "第 " + index + " 个字符处是裸 LF，应为 CRLF");
            }
        }
    }

    @Test
    @DisplayName(".html 的数据行按转义规则处理（& 先转）")
    void htmlEscapesContent(@TempDir Path dir) throws IOException {
        CompareReporter reporter = reporter(dir, List.of("a&b"), List.of("a<b>"), null);
        String document = Files.readString(reporter.writeHtmlFile(FIXED_TIME), StandardCharsets.UTF_8);
        assertTrue(document.contains("source:\"a&amp;b\""), "& 必须转成 &amp;（且只转一次）");
        assertTrue(document.contains("source:\"a&lt;b&gt;\""), "< > 转义");
    }
}
