package org.slackerdb.dbcli.compare;

import org.slackerdb.dbcli.common.Artifacts;
import org.slackerdb.dbcli.common.CommandOutcome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code _COMPARE} 的动作分派器：把 IR（{@code SqlIrVisitor.visitCompare} 的输出）变成"要打印的结果"。
 *
 * <p>七个 action 与累积状态：</p>
 * <pre>
 *   mask     _COMPARE MASKLINE "a"=>"b"   → 加一条 mask 规则（同一 source 不覆盖，保留第一次）
 *   nomask   _COMPARE NOMASKLINE "a"      → 删掉该 source 的 mask 规则
 *   skip     _COMPARE SKIPLINE "a"        → 加一条 skip 规则（去重）
 *   noskip   _COMPARE NOSKIPLINE "a"      → 删掉该 source 的 skip 规则
 *   set      _COMPARE SET ...             → 把 IR 的 compareOptions **累加**进当前选项；无参则打印当前设置
 *   reset    _COMPARE RESET               → 选项回默认值 + 清空全部 mask/skip 规则
 *   compare  _COMPARE a b [选项...]        → 本次请求的选项快照 + {@link FileComparer} + {@link CompareReporter}
 * </pre>
 *
 * <p>结果是 {@link CommandOutcome} 列表（与 {@code _SSH} 的 {@code SshCommandDispatcher} 同构）：
 * 状态行 → {@link CommandOutcome.Status}（{@code null} 表示无输出）、结果集 → {@link CommandOutcome.Table}、
 * 错误 → {@link CommandOutcome.Error}。设置表的标题（如 {@code "Current compare settings:"}）表示为
 * **紧邻在 Table 前面的一条 Status**，引擎按顺序输出即可得到正确的控制台文本。</p>
 *
 * <h2>错误文案（已冻结，测试逐字节断言）</h2>
 * <pre>
 *   ERROR: File &lt;f&gt; does not exist!            （先查 targetFile，再查 referenceFile）
 *   unknown compare option [&lt;key&gt;]              （小写 u；报错之后仍输出空结果，已处理的合法键仍然生效）
 *   Missed line number. Bad compare result. [&lt;row&gt;]
 * </pre>
 *
 * <h2>设计取舍（判定标准是语义正确 + 可解释）</h2>
 * <ol>
 *   <li><b>非法正则与未知 action 明确报错</b>：非法 mask/skip 正则、未知 action 都给可读错误，
 *       不静默判为"不匹配/无输出" —— 静默会让使用者完全看不到原因，无从定位。</li>
 * </ol>
 *
 * <h2>路径解析</h2>
 * 相对路径基于**构造时传入的工作目录**（相对路径的基准是本次
 * 运行的工作目录）。本类只做
 * {@code workingDirectory.resolve(name)}。输出文件始终与目标文件同目录（{@link CompareReporter}）。
 * 引号**不剥离**：{@code _COMPARE "a.txt" "b.txt"} 的引号是路径的一部分，两边都是"文件不存在"。
 */
public final class CompareCommandDispatcher {

    /** IR 里可能出现的选项键（4 个；其它键算"未知选项"）。字符集与输出文件名都不是可选项。 */
    private static final Set<String> OPTION_KEYS = Set.of(
            "mask", "case", "igblank", "trim");

    /** {@code _COMPARE SET} 打印的行序（固定顺序）。 */
    private static final List<String> OPTION_PRINT_ORDER = List.of(
            "mask", "case", "igblank", "trim");

    /** 选项默认值（用于 {@code _COMPARE SET} 的 "Default value" 列）。 */
    private static final CompareOptions DEFAULTS = new CompareOptions();

    private final Path workingDirectory;
    private final CompareOptions options = new CompareOptions();
    /** {@code _SET DIFF_REPORT_HTML} 的当前值（默认 off）。 */
    private boolean diffReportHtml;
    private final LineRules rules = new LineRules();

    public CompareCommandDispatcher(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /** 当前累积的选项（引擎/测试可读，例如日志）。 */
    public CompareOptions options() {
        return options;
    }

    /** 当前累积的行规则（skip；mask 规则见指令表，不参与比较）。 */
    public LineRules rules() {
        return rules;
    }

    /**
     * {@code _SET DIFF_REPORT_HTML}（默认 off）：有差异时是否**另外**写一份 {@code .html}。
     *
     * <p>{@code .dif} 永远写；HTML 只是"想让人看"时的附加产物，因此默认关。</p>
     */
    public void setDiffReportHtml(boolean enabled) {
        this.diffReportHtml = enabled;
    }

    /** 执行一条 {@code _COMPARE} 命令。 */
    public List<CommandOutcome> execute(Map<String, Object> ir) {
        Object rawAction = ir.get("action");
        String action = rawAction == null ? "" : String.valueOf(rawAction);
        List<CommandOutcome> outcomes = new ArrayList<>();
        switch (action) {
            case "set" -> set(ir, outcomes);
            case "reset" -> {
                options.reset();
                rules.clear();
                outcomes.add(noOutput());
            }
            case "skip" -> addSkipRule(ir, outcomes);
            case "noskip" -> removeSkipRule(ir, outcomes);
            case "mask" -> addMaskRule(ir, outcomes);
            case "nomask" -> removeMaskRule(ir, outcomes);
            case "compare" -> compare(ir, outcomes);
            // 未知 action：明确报错（与 _SSH 分派器一致），不静默 yield 0 条
            default -> outcomes.add(new CommandOutcome.Error("Unsupported compare action [" + action + "]"));
        }
        return outcomes;
    }

    // ------------------------------------------------------------------ set / reset

    private void set(Map<String, Object> ir, List<CommandOutcome> outcomes) {
        Map<String, Object> requested = compareOptions(ir);
        if (requested.isEmpty()) {
            // SET 后面没跟任何选项 → 打印当前设置（契约 §6.1）
            printSettings(outcomes);
            return;
        }
        for (Map.Entry<String, Object> entry : requested.entrySet()) {
            if (!OPTION_KEYS.contains(entry.getKey())) {
                outcomes.add(new CommandOutcome.Error("unknown compare option [" + entry.getKey() + "]"));
                continue;
            }
            // 逐键覆盖：遇到未知键先报错，已处理的合法键仍然生效（故这里不提前 return）
            applyOption(entry.getKey(), entry.getValue(), outcomes);
        }
        // 无论有没有未知键，最后都产出"无输出"的一条结果
        outcomes.add(noOutput());
    }

    private void applyOption(String key, Object value, List<CommandOutcome> outcomes) {
        options.applyIr(Map.of(key, value));
    }

    /** {@code _COMPARE SET}（无参）的三张表；标题用紧邻的 Status 行表示，见类注释。 */
    private void printSettings(List<CommandOutcome> outcomes) {
        outcomes.add(new CommandOutcome.Status("Current compare settings:"));
        List<List<Object>> settingRows = new ArrayList<>();
        for (String key : OPTION_PRINT_ORDER) {
            settingRows.add(List.<Object>of(key, displayValue(currentValue(key)), displayValue(defaultValue(key))));
        }
        outcomes.add(new CommandOutcome.Table(List.of("Option", "Current value", "Default value"), settingRows));

        if (rules.skipRuleCount() > 0) {
            outcomes.add(new CommandOutcome.Status("Skiplines:"));
            List<List<Object>> skipRows = new ArrayList<>();
            for (LineRules.Rule rule : rules.skipRules()) {
                skipRows.add(List.<Object>of(rule.source()));
            }
            outcomes.add(new CommandOutcome.Table(List.of("SkipPattern"), skipRows));
        }
        if (rules.maskRuleCount() > 0) {
            outcomes.add(new CommandOutcome.Status("Masklines:"));
            List<List<Object>> maskRows = new ArrayList<>();
            for (LineRules.Rule rule : rules.maskRules()) {
                maskRows.add(List.<Object>of(rule.source(), rule.target()));
            }
            outcomes.add(new CommandOutcome.Table(List.of("SourcePattern", "TargetPattern"), maskRows));
        }
    }

    /** "Current value" 列。 */
    private Object currentValue(String key) {
        return optionValue(options, key);
    }

    /** "Default value" 列。 */
    private static Object defaultValue(String key) {
        return optionValue(DEFAULTS, key);
    }

    /**
     * 某个选项在给定 {@link CompareOptions} 下的值（{@code _COMPARE SET} 的 Current/Default 列）。
     *
     * <p>布尔选项显示成**脚本里写的那两个开关名**，而不是 True/False：这样表格和脚本文本一一对应，
     * 也能一眼看出默认值到底是哪个开关——{@code case=NOCASE}、{@code trim=TRIM}、
     * {@code mask=NOMASK}、{@code igblank=NOIGBLANK}。</p>
     */
    private static Object optionValue(CompareOptions source, String key) {
        return switch (key) {
            case "mask" -> source.mask() ? "MASK" : "NOMASK";
            case "case" -> source.caseSensitive() ? "CASE" : "NOCASE";
            case "igblank" -> source.ignoreBlank() ? "IGBLANK" : "NOIGBLANK";
            // SET TRIM ⇒ 剥离首尾空白为 true
            case "trim" -> source.ignoreHeadTailBlank() ? "TRIM" : "NOTRIM";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ skip / mask 规则

    private void addSkipRule(Map<String, Object> ir, List<CommandOutcome> outcomes) {
        String source = pattern(ir, "source", outcomes);
        if (source == null) {
            return;
        }
        // 去重：同一 source 只登记一次
        if (!rules.skipSources().contains(source)) {
            try {
                rules.add(LineRules.Rule.Type.SKIP, source, null);
            } catch (IllegalArgumentException invalidPattern) {
                outcomes.add(new CommandOutcome.Error(invalidPattern.getMessage()));
            }
        }
        outcomes.add(noOutput());
    }

    private void removeSkipRule(Map<String, Object> ir, List<CommandOutcome> outcomes) {
        String source = pattern(ir, "source", outcomes);
        if (source == null) {
            return;
        }
        rules.add(LineRules.Rule.Type.UNSKIP, source, null);
        outcomes.add(noOutput());
    }

    private void addMaskRule(Map<String, Object> ir, List<CommandOutcome> outcomes) {
        // MASKLINE 的语义就是"把 source 按正则匹配到的内容换成 target"，因此这里把
        // source/target 的引号剥掉（`MASKLINE "a"=>"b"` 就是"把 a 替换成 b"），规则真正生效。
        String source = unquote(pattern(ir, "source", outcomes));
        if (source == null) {
            return;
        }
        String target = unquote(text(ir, "target"));
        // 重复声明同一 source 保留第一次
        boolean exists = rules.maskRules().stream().anyMatch(rule -> rule.source().equals(source));
        if (!exists) {
            try {
                rules.add(LineRules.Rule.Type.MASK, source, target == null ? "" : target);
            } catch (IllegalArgumentException invalidPattern) {
                outcomes.add(new CommandOutcome.Error(invalidPattern.getMessage()));
            }
        }
        outcomes.add(noOutput());
    }

    private void removeMaskRule(Map<String, Object> ir, List<CommandOutcome> outcomes) {
        String source = unquote(pattern(ir, "source", outcomes));
        if (source == null) {
            return;
        }
        rules.add(LineRules.Rule.Type.UNMASK, source, null);
        outcomes.add(noOutput());
    }

    /** 剥掉一层成对的引号（MASKLINE 的 source/target 在脚本里是带引号的字面量）。 */
    private static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 取规则的正则；缺失时给出明确错误（缺 key 不崩栈，只报一条可读错误）。 */
    private static String pattern(Map<String, Object> ir, String key, List<CommandOutcome> outcomes) {
        String value = text(ir, key);
        if (value == null) {
            outcomes.add(new CommandOutcome.Error("Missed compare pattern. Bad compare request."));
            return null;
        }
        return value;
    }

    // ------------------------------------------------------------------ compare

    private void compare(Map<String, Object> ir, List<CommandOutcome> outcomes) {
        String targetName = text(ir, "targetFile");
        String referenceName = text(ir, "referenceFile");

        // 本次请求的选项 = 当前选项的快照 + 本行给出的选项（按键覆盖快照）
        CompareOptions request = options.copy();
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : compareOptions(ir).entrySet()) {
            if (OPTION_KEYS.contains(entry.getKey())) {
                overrides.put(entry.getKey(), entry.getValue());
            }
            // 不在已知键里的选项在 compare 分支被静默忽略（只有 set 分支会报未知选项）
        }
        request.applyIr(overrides);

        // 检查顺序已冻结：先文件存在性，再（没有别的检查了）；错误文案用**原始路径字符串**
        //（不做引号剥离，报错里就是脚本里写的那一串）
        if (targetName == null || !exists(workingDirectory, targetName)) {
            outcomes.add(new CommandOutcome.Error("ERROR: File " + targetName + " does not exist!"));
            return;
        }
        if (referenceName == null || !exists(workingDirectory, referenceName)) {
            outcomes.add(new CommandOutcome.Error("ERROR: File " + referenceName + " does not exist!"));
            return;
        }
        Path target = workingDirectory.resolve(targetName);
        Path reference = workingDirectory.resolve(referenceName);
        try {
            CompareResult result = FileComparer.compare(reference, target, request, rules);
            CompareReporter reporter = new CompareReporter(result);
            if (result.identical()) {
                // 一致：写 work.suc（一行 TSV），控制台只给状态行、没有表格
                reporter.writeSuccessFile();
                // 上一次的差异产物必须删掉：产物是"这一次比对结论"的载体，留着旧 .dif/.html
                // 会让按产物判断结果的人（CI 收集、存在性检查、grep）读到与状态行矛盾的结论。
                Artifacts.deleteStale(reporter.diffFilePath());
                Artifacts.deleteStale(reporter.htmlFilePath());
                outcomes.add(new CommandOutcome.Status(CompareReporter.STATUS_SUCCESSFUL));
                return;
            }
            // 不一致：固定写 work.dif（与目标文件同目录、名字由目标文件派生）；
            // HTML 只在 _SET DIFF_REPORT_HTML ON 时另写一份。
            // 控制台**不打印差异表**：差异看文件（stdout 保持"只有状态行"）
            reporter.writeDiffFile();
            Artifacts.deleteStale(reporter.successFilePath());
            if (diffReportHtml) {
                reporter.writeHtmlFile(LocalDateTime.now());
            } else {
                // 开关关掉时也要收掉上一次的 .html，否则它会冒充"这一次的差异报告"
                Artifacts.deleteStale(reporter.htmlFilePath());
            }
            outcomes.add(new CommandOutcome.Status(CompareReporter.STATUS_FAILED));
        } catch (IOException | IllegalArgumentException | CompareReporter.CompareReportException failure) {
            outcomes.clear();
            outcomes.add(new CommandOutcome.Error(messageOf(failure)));
        }
    }

    // ------------------------------------------------------------------ 工具


    private static CommandOutcome noOutput() {
        return new CommandOutcome.Status(null);
    }

    /**
     * "这个相对路径指向的文件存在吗"。
     *
     * <p>路径串非法时（Windows 上 {@code _COMPARE "a.txt" "b.txt"} 的引号就是非法字符）按"文件不存在"
     * 处理 —— 引号不剥离，因此它在两边都是"文件不存在"（契约 §8）。</p>
     */
    private static boolean exists(Path workingDirectory, String name) {
        try {
            return Files.isRegularFile(workingDirectory.resolve(name));
        } catch (java.nio.file.InvalidPathException invalidPath) {
            return false;
        }
    }

    private static String messageOf(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static Map<String, Object> compareOptions(Map<String, Object> ir) {
        Object value = ir.get("compareOptions");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            return copy;
        }
        return Map.of();
    }

    private static String text(Map<String, Object> ir, String key) {
        Object value = ir.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 选项值在设置表里的显示形态：
     * 字符串原样、布尔 {@code True/False}、列表 {@code ['a', 'b']}。
     */
    private static String displayValue(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        if (value instanceof List<?> list) {
            StringBuilder builder = new StringBuilder("[");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    builder.append(", ");
                }
                builder.append('\'').append(list.get(index)).append('\'');
            }
            return builder.append(']').toString();
        }
        return String.valueOf(value);
    }
}
