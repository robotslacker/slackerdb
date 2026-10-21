package org.slackerdb.dbcli.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbcli.common.CommandOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompareCommandDispatcher} 的契约测试：七个 action、选项/规则的累积状态、
 * 四类错误文案与非 UTF-8 编码拒绝。
 *
 * <p>IR 形状照 {@code SqlIrVisitor.visitCompare} 的产出手写（{@code action}" + " {@code compareOptions}/
 * {@code source}/{@code target}/{@code targetFile}/{@code referenceFile}），不依赖 ANTLR。</p>
 *
 * <p><b>四类错误文案</b>：{@code ERROR: File <f> does not exist!}、{@code unknown compare option [<key>]}、
 * {@code unknown compare option [bogus]} 在下面各有测试；第四类
 * {@code Missed line number. Bad compare result. [<row>]} 由 {@link CompareReporter#reassemble} 抛出
 * （本进程自己组装报告行，IR 层面无法喂进畸形行），其逐字断言在
 * {@code CompareReporterTest#malformedReportLine}，分派器只负责把它转成 {@link CommandOutcome.Error}。</p>
 */
class CompareCommandDispatcherTest {

    private static final List<String> WORK = List.of("Hello", "ChinA", "Russia", "America");
    private static final List<String> REF = List.of("Hello", "China", "America", "France");
    private static final String CONTRACT_DIF =
            "      1 Hello\r\n      2 ChinA\r\n-     3 Russia\r\n      4 America\r\n+     4 France\r\n";

    private static Map<String, Object> ir(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return map;
    }

    private static Path write(Path dir, String name, List<String> lines) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private static String firstError(List<CommandOutcome> outcomes) {
        for (CommandOutcome outcome : outcomes) {
            if (outcome instanceof CommandOutcome.Error error) {
                return error.message();
            }
        }
        return null;
    }

    /** 第一条非空状态行的文本（{@code _COMPARE} 的结论行）。 */
    private static String firstStatus(List<CommandOutcome> outcomes) {
        for (CommandOutcome outcome : outcomes) {
            if (outcome instanceof CommandOutcome.Status status && status.text() != null) {
                return status.text();
            }
        }
        return null;
    }

    private static CommandOutcome.Table firstTable(List<CommandOutcome> outcomes) {
        for (CommandOutcome outcome : outcomes) {
            if (outcome instanceof CommandOutcome.Table table) {
                return table;
            }
        }
        return null;
    }

    private static List<String> statusTexts(List<CommandOutcome> outcomes) {
        List<String> texts = new ArrayList<>();
        for (CommandOutcome outcome : outcomes) {
            if (outcome instanceof CommandOutcome.Status status) {
                texts.add(status.text());
            }
        }
        return texts;
    }

    private static Map<String, Object> compareIr() {
        return ir("action", "compare", "targetFile", "work.txt", "referenceFile", "ref.txt");
    }

    // ------------------------------------------------------------------ 选项与规则的累积

    @Test
    @DisplayName("set 是累加：只覆盖给到的键，之前的 SET 不被清掉")
    void setAccumulates(@TempDir Path dir) {
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);

        assertEquals(List.of(new CommandOutcome.Status(null)),
                dispatcher.execute(ir("action", "set", "compareOptions", ir("case", true))));
        assertTrue(dispatcher.options().caseSensitive());

        dispatcher.execute(ir("action", "set", "compareOptions", ir("mask", true)));
        assertTrue(dispatcher.options().caseSensitive(), "上一次 SET 的 case 仍然生效（set 累加）");
        assertTrue(dispatcher.options().mask());

        // trim 名实相符：SET TRIM ⇒ 剥离首尾空白
        dispatcher.execute(ir("action", "set", "compareOptions", ir("trim", true)));
        assertTrue(dispatcher.options().ignoreHeadTailBlank(), "IR trim=true（SET TRIM）⇒ 剥离首尾空白");
    }

    @Test
    @DisplayName("reset：所有选项回默认值 + 清空所有 mask/skip 规则，输出一条无状态结果")
    void resetClearsEverything(@TempDir Path dir) {
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);
        dispatcher.execute(ir("action", "set", "compareOptions", ir("case", true)));
        dispatcher.execute(ir("action", "skip", "source", "cc*dd"));
        dispatcher.execute(ir("action", "mask", "source", "\"ee ff\"", "target", "\"hh kk\""));

        assertEquals(List.of(new CommandOutcome.Status(null)), dispatcher.execute(ir("action", "reset")));
        assertTrue(dispatcher.options().caseSensitive(), "RESET 回到 CASE");
        assertFalse(dispatcher.options().ignoreHeadTailBlank(), "RESET 回到 NOTRIM");
        assertEquals(0, dispatcher.rules().skipRuleCount(), "RESET 清空 skip 规则");
        assertEquals(0, dispatcher.rules().maskRuleCount(), "RESET 清空 mask 规则");
    }

    @Test
    @DisplayName("skip 去重 / noskip 删除；mask 同一 source 保留第一次 / nomask 删除")
    void rulesAccumulate(@TempDir Path dir) {
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);

        dispatcher.execute(ir("action", "skip", "source", "cc*dd"));
        dispatcher.execute(ir("action", "skip", "source", "cc*dd"));
        assertEquals(1, dispatcher.rules().skipRuleCount(), "skip 去重");
        dispatcher.execute(ir("action", "noskip", "source", "cc*dd"));
        assertEquals(0, dispatcher.rules().skipRuleCount());

        // MASKLINE 的语义就是"把 source 匹配到的内容换成 target"，分派器会把
        // source/target 的引号剥掉（期望值里就没有引号了）
        dispatcher.execute(ir("action", "mask", "source", "\"ee ff\"", "target", "\"hh kk\""));
        dispatcher.execute(ir("action", "mask", "source", "\"ee ff\"", "target", "\"zz zz\""));
        assertEquals(1, dispatcher.rules().maskRuleCount());
        assertEquals("ee ff", dispatcher.rules().maskRules().get(0).source(), "引号被剥掉");
        assertEquals("hh kk", dispatcher.rules().maskRules().get(0).target(), "重复声明保留第一次");
        assertEquals("hh kk", dispatcher.rules().applyMask("ee ff"),
                "规则真的生效：ee ff → hh kk");
        dispatcher.execute(ir("action", "nomask", "source", "\"ee ff\""));
        assertEquals(0, dispatcher.rules().maskRuleCount());
    }

    @Test
    @DisplayName("非法正则：给出可读错误（不静默判为不匹配）")
    void invalidPatternIsReported(@TempDir Path dir) {
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);
        assertEquals("invalid pattern [[]: Unclosed character class",
                firstError(dispatcher.execute(ir("action", "skip", "source", "["))));
        assertEquals(0, dispatcher.rules().skipRuleCount(), "非法规则不入集合");
    }

    // ------------------------------------------------------------------ SET 打印（固定行序）

    @Test
    @DisplayName("SET 无参：打印当前设置 + Skiplines/Masklines 三张表（标题用 Status 行表示）")
    void setWithoutOptionsPrintsSettings(@TempDir Path dir) {
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);
        List<CommandOutcome> settings = dispatcher.execute(ir("action", "set", "compareOptions", ir()));

        assertEquals("Current compare settings:", ((CommandOutcome.Status) settings.get(0)).text());
        CommandOutcome.Table options = (CommandOutcome.Table) settings.get(1);
        assertEquals(List.of("Option", "Current value", "Default value"), options.headers());
        // 布尔选项显示成**开关名**（不是 True/False）：默认就是 NOMASK / CASE / NOIGBLANK / NOTRIM。
        // （output / algorithm / workEncoding 这些选项都已删除，表里只剩这四个开关。）
        assertEquals(List.of(
                List.<Object>of("mask", "NOMASK", "NOMASK"),
                List.<Object>of("case", "CASE", "CASE"),
                List.<Object>of("igblank", "NOIGBLANK", "NOIGBLANK"),
                List.<Object>of("trim", "NOTRIM", "NOTRIM")), options.rows());
        assertEquals(2, settings.size(), "没有规则时不打印 Skiplines/Masklines");

        dispatcher.execute(ir("action", "set", "compareOptions", ir("case", false)));
        dispatcher.execute(ir("action", "skip", "source", "cc*dd"));
        dispatcher.execute(ir("action", "mask", "source", "\"ee ff\"", "target", "\"hh kk\""));
        List<CommandOutcome> full = dispatcher.execute(ir("action", "set"));

        assertEquals(6, full.size(), "选项表 + Skiplines + Masklines（各带一行标题）");
        assertEquals("mask", ((CommandOutcome.Table) full.get(1)).rows().get(0).get(0));
        // 开关的显示形态：Current = NOCASE（本行刚 SET NOCASE），Default 仍是 CASE
        assertEquals("NOCASE", ((CommandOutcome.Table) full.get(1)).rows().get(1).get(1));
        assertEquals("Skiplines:", ((CommandOutcome.Status) full.get(2)).text());
        assertEquals(List.of(List.<Object>of("cc*dd")), ((CommandOutcome.Table) full.get(3)).rows());
        assertEquals(List.of("SkipPattern"), ((CommandOutcome.Table) full.get(3)).headers());
        assertEquals("Masklines:", ((CommandOutcome.Status) full.get(4)).text());
        // 引号已在登记时剥掉（MASKLINE 的 source/target 就是去引号后的正则）
        assertEquals(List.<Object>of("ee ff", "hh kk"), ((CommandOutcome.Table) full.get(5)).rows().get(0));
    }

    @Test
    @DisplayName("未知 compare 选项：先报未知、再输出空结果；已处理的合法键仍然生效（部分生效语义）")
    void unknownCompareOption(@TempDir Path dir) {
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);
        List<CommandOutcome> outcomes = dispatcher.execute(
                ir("action", "set", "compareOptions", ir("case", true, "bogus", "1")));

        assertEquals(new CommandOutcome.Error("unknown compare option [bogus]"), outcomes.get(0));
        assertEquals(new CommandOutcome.Status(null), outcomes.get(1));
        assertTrue(dispatcher.options().caseSensitive(), "bogus 之前的合法键已经生效");
    }

    // ------------------------------------------------------------------ compare

    @Test
    @DisplayName("compare：console rows + 固定写 work.dif / work.html；相对路径基于构造时的工作目录（契约 §8）")
    void compareWritesReports(@TempDir Path dir) throws IOException {
        write(dir, "work.txt", WORK);
        write(dir, "ref.txt", REF);
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);
        // 用"忽略大小写 + 剥离首尾空白"的口径对照契约样例（默认已改成 CASE / NOTRIM）
        dispatcher.execute(ir("action", "set", "compareOptions", ir("case", false, "trim", true)));


        List<CommandOutcome> outcomes = dispatcher.execute(compareIr());
        // 控制台恒定不打印差异表：只有一条状态行
        assertEquals(List.of(new CommandOutcome.Status("Compare failed.")), outcomes,
                "Compare failed. 不是 error（errorCode 记 0）");

        // 相对路径 work.txt 落在 workingDirectory 里，故 .dif 也在那里（不是 JVM 的 cwd）
        // 差异时固定写 .dif；HTML 默认不写（要 _SET DIFF_REPORT_HTML ON）；不写 .suc
        assertEquals(CONTRACT_DIF, Files.readString(dir.resolve("work.dif"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("work.html")), "HTML 默认不写");
        assertFalse(Files.exists(dir.resolve("work.suc")));
    }

    @Test
    @DisplayName("两侧相同：一条 Compare successful. 状态行 + 固定写 work.suc（不写 .dif/.html）")
    void compareSuccess(@TempDir Path dir) throws IOException {
        write(dir, "work.txt", WORK);
        write(dir, "ref.txt", WORK);
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);

        assertEquals(List.of(new CommandOutcome.Status("Compare successful.")), dispatcher.execute(compareIr()));
        assertFalse(Files.exists(dir.resolve("work.dif")));
        assertFalse(Files.exists(dir.resolve("work.html")));
        assertEquals(CompareReporter.SUCCESS + "\t" + dir.resolve("ref.txt") + "\t" + dir.resolve("work.txt") + "\n",
                Files.readString(dir.resolve("work.suc"), StandardCharsets.UTF_8),
                ".suc 是一行 TSV：SUCCESS / 参考文件 / 工作文件");
    }

    @Test
    @DisplayName("产物恒定：成功时删掉旧的 .dif/.html，失败时删掉旧的 .suc")
    void artifactsFollowTheCurrentVerdict(@TempDir Path dir) throws IOException {
        write(dir, "work.txt", WORK);
        write(dir, "ref.txt", REF);
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);
        dispatcher.setDiffReportHtml(true);

        assertEquals("Compare failed.", firstStatus(dispatcher.execute(compareIr())));
        assertTrue(Files.exists(dir.resolve("work.dif")));
        assertTrue(Files.exists(dir.resolve("work.html")));

        write(dir, "work.txt", REF);
        assertEquals("Compare successful.", firstStatus(dispatcher.execute(compareIr())));
        assertTrue(Files.exists(dir.resolve("work.suc")));
        assertFalse(Files.exists(dir.resolve("work.dif")), "旧的 .dif 必须删掉");
        assertFalse(Files.exists(dir.resolve("work.html")), "旧的 .html 必须删掉");

        write(dir, "work.txt", WORK);
        assertEquals("Compare failed.", firstStatus(dispatcher.execute(compareIr())));
        assertTrue(Files.exists(dir.resolve("work.dif")));
        assertFalse(Files.exists(dir.resolve("work.suc")), "旧的 .suc 必须删掉");
    }
    @Test
    @DisplayName("OUTPUT 选项已删除：差异表恒定打印，SET OUTPUT 按未知选项报错")
    void outputOptionIsGone(@TempDir Path dir) throws IOException {
        write(dir, "work.txt", WORK);
        write(dir, "ref.txt", REF);
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);

        assertEquals("unknown compare option [output]", firstError(dispatcher.execute(
                ir("action", "set", "compareOptions", ir("output", List.of("diffFile"))))));

        // 不需要任何设置：一次 _COMPARE 就写 .dif，且不上屏
        List<CommandOutcome> outcomes = dispatcher.execute(compareIr());
        assertTrue(outcomes.stream().noneMatch(CommandOutcome.Table.class::isInstance), "不上屏差异表");
        assertTrue(Files.exists(dir.resolve("work.dif")), ".dif 恒定写出");
    }

    @Test
    @DisplayName("本行 MASK：是一次性选项，不污染累计状态（用的是当前选项的副本）")
    void perRequestOptionsDoNotLeak(@TempDir Path dir) throws IOException {
        write(dir, "work.txt", List.of("id=2 ts=999"));
        write(dir, "ref.txt", List.of("id=\\d+ ts=\\d+"));
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);

        assertEquals(List.of(new CommandOutcome.Status("Compare successful.")), dispatcher.execute(
                ir("action", "compare", "targetFile", "work.txt", "referenceFile", "ref.txt",
                        "compareOptions", ir("mask", true))), "开 mask 后参考行当正则，两边相等");
        assertFalse(dispatcher.options().mask(), "一次性选项不进累计状态");
        assertEquals("Compare failed.", firstStatus(dispatcher.execute(compareIr())),
                "没有 mask 时这两行不相等");
    }

    // ------------------------------------------------------------------ 四类错误文案

    @Test
    @DisplayName("文件不存在：ERROR: File <f> does not exist!（先 targetFile 后 referenceFile；引号不剥离）")
    void missingFiles(@TempDir Path dir) throws IOException {
        write(dir, "work.txt", WORK);
        write(dir, "ref.txt", REF);
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);

        assertEquals(List.of(new CommandOutcome.Error("ERROR: File nosuchwork.txt does not exist!")),
                dispatcher.execute(ir("action", "compare", "targetFile", "nosuchwork.txt",
                        "referenceFile", "ref.txt")));
        assertEquals("ERROR: File nosuchref.txt does not exist!", firstError(dispatcher.execute(
                ir("action", "compare", "targetFile", "work.txt", "referenceFile", "nosuchref.txt"))));
        assertEquals("ERROR: File nosuchwork.txt does not exist!", firstError(dispatcher.execute(
                ir("action", "compare", "targetFile", "nosuchwork.txt", "referenceFile", "nosuchref.txt"))),
                "两个都不存在时报第 1 个");
        // 规范 §8：引号是路径的一部分、不会被剥离，因此同样报文件不存在
        assertEquals("ERROR: File \"work.txt\" does not exist!", firstError(dispatcher.execute(
                ir("action", "compare", "targetFile", "\"work.txt\"", "referenceFile", "ref.txt"))));
    }

    @Test
    @DisplayName("算法选项已删除：SET ALGORITHM 现在按未知选项报错（只有一种算法，没有可选的）")
    void algorithmOptionIsGone(@TempDir Path dir) throws IOException {
        write(dir, "work.txt", WORK);
        write(dir, "ref.txt", REF);
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);

        // SET ALGORITHM 已经不存在：按未知选项报错（单行 _compare 上的 compareOptions 仍是"未知键忽略"）
        assertEquals("unknown compare option [algorithm]", firstError(dispatcher.execute(
                ir("action", "set", "compareOptions", ir("algorithm", "myers")))));
    }

    // ------------------------------------------------------------------ 字符集

    @Test
    @DisplayName("字符集选项已删除：SET WORK/REFERENCE ENCODING 现在按未知选项报错（固定 UTF-8）")
    void encodingOptionsAreGone(@TempDir Path dir) throws IOException {
        write(dir, "work.txt", WORK);
        write(dir, "ref.txt", REF);
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);

        List<CommandOutcome> outcomes = dispatcher.execute(
                ir("action", "set", "compareOptions", ir("workEncoding", "GBK")));
        assertEquals(new CommandOutcome.Error("unknown compare option [workEncoding]"), outcomes.get(0));
        assertEquals(new CommandOutcome.Status(null), outcomes.get(1), "报错之后仍然输出空结果");

        assertEquals("unknown compare option [refEncoding]", firstError(dispatcher.execute(
                ir("action", "set", "compareOptions", ir("refEncoding", "gb18030")))));

        // 没有"改回 UTF-8"这回事：本来就只有 UTF-8，比对照常
        assertEquals("Compare failed.", firstStatus(dispatcher.execute(compareIr())));
    }

    // ------------------------------------------------------------------ 其它

    @Test
    @DisplayName("未知 action：明确报错（不静默什么都不输出；与 _SSH 分派器一致）")
    void unknownAction(@TempDir Path dir) {
        CompareCommandDispatcher dispatcher = new CompareCommandDispatcher(dir);
        assertEquals(List.of(new CommandOutcome.Error("Unsupported compare action [bogus]")),
                dispatcher.execute(ir("action", "bogus")));
    }
}
