package org.slackerdb.dbcli.compare;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code _COMPARE} 的固定产物：console 差异表 + 三个按目标文件派生的文件（{@code .suc} / {@code .dif} / {@code .html}）。
 *
 * <p>输入是内核的 {@link CompareResult}（结构化操作序列）。中间产物是"报告行"列表，三种输出都从它派生：</p>
 * <pre>
 *   报告行（行号回填后）  ' ' 两侧相同 | '-' 仅工作（目标）文件有 | '+' 仅参考文件有 | 'S' 被忽略
 *   console rows        只取 '-' 与 '+' 行 → [#, work line#, ref line#, content]
 *   &lt;targetFile 去扩展名&gt;.dif   完整报告行（含 ' ' 与 'S'），CRLF 行尾，**有差异才写**
 *   &lt;targetFile 去扩展名&gt;.html  四个模板按序拼接后逐片段加换行，CRLF 行尾，**有差异才写**
 *   &lt;targetFile 去扩展名&gt;.suc   一行 TSV {@code SUCCESS\t<work>\t<ref>}，LF 行尾，**一致才写**
 *
 * 产物名字恒定按**目标（工作）文件**派生、与目标文件同目录，没有"输出到哪"的选项。
 * </pre>
 *
 * <h2>行号与文本来自哪一侧（最容易写错的一处）</h2>
 * 报告里 {@code ' '} 与 {@code '-'} 行都取**工作（目标）文件的原始行**与**工作文件行号**，
 * {@code '+'} 行取参考文件的行号与原文。内核的 {@link DiffResult.Operation} 恰好相反地命名了左右：
 * 它以**参考侧为左**，{@code DELETE} = 仅参考侧（= 报告里的 {@code '+'}）、
 * {@code INSERT} = 仅目标侧（= 报告里的 {@code '-'}）、{@code EQUAL} 的文本是参考侧文本。
 * ⇒ {@link #algorithmRows(CompareResult)} 负责这次翻转映射，并把每个非 equal 块内的
 * {@code '-'} 行排到 {@code '+'} 行之前（见下）。
 *
 * <h2>工作侧文本的来源</h2>
 * 报告里 {@code ' '} 行要显示**工作文件**那一行的原文（内核给的是参考侧文本），而 {@code 'S'} 行
 * 只能从原文件取（被 skip/igblank 丢掉的行走不到算法）。两者都取自
 * {@link CompareResult#targetFileLines()} —— 那是"比对时用的同一份数据"（已按 mask 规则改写过、
 * 未做 skip/igblank 过滤，下标 = 行号 - 1），因此出报告时**不需要再读一遍文件**，也不会出现
 * "比对完成到出报告之间文件被改写"导致报告与比对不一致的问题。需要脱离比对结果构造时用
 * {@link #CompareReporter(CompareResult, List)}。
 *
 * <h2>三处设计取舍（语义正确 + 可解释）</h2>
 * <ol>
 *   <li><b>替换块内的行序</b>：内核以参考侧为左，替换块内会先给 {@code '+'}（{@code DELETE}）
 *       再给 {@code '-'}（{@code INSERT}）；而 {@code .dif} 的形态要求**先 {@code '-'} 后 {@code '+'}**
 *       （例：{@code work=A,B} / {@code ref=A,C} ⇒ {@code "      1 A\r\n-     2 B\r\n+     2 C\r\n"}）。
 *       另用 600 轮随机输入 × LCS/MYERS 验证"块内所有 {@code '-'} 在 {@code '+'} 之前"这一不变量
 *       无例外，故按块重排。</li>
 *   <li><b>{@code 'S'} 行能真的出来</b>：skip 命中任意位置（含非末行）都能补出 {@code 'S'} 行。
 *       本实现的行号按原文件独立维护、行列表不做别名共享，因此不存在"删行之后仍用原始行号索引"
 *       造成的错位（见 §7.3.1 第 4 条）。</li>
 *   <li><b>展示文本保持原文</b>：归一化（trim/case）只用于"是否相等"的判定，**不就地改写**待展示的行，
 *       因此 {@code .dif}/console 里显示的是原文件那一行，与 {@link TextNormalizer} 的约定一致。</li>
 * </ol>
 *
 * <h2>HTML 模板</h2>
 * 模板片段在 {@code src/main/resources/org/slackerdb/dbcli/compare/htmldiff/}（四个：
 * {@code header}/{@code body.header}/{@code body.content}/{@code footer}.html），按
 * header → body.header(lines=注释行，恒空) → body.content(lines=报告行 + workFile/refFile) →
 * footer(time) 的顺序拼接。渲染规则见 {@link HtmlTemplateRenderer}。
 * <b>逐字节未做穷举核对</b>：只对**一个**样例人工核对过（同一输入、同一路径、固定时间戳下 18035 字节），
 * 换输入（尤其 {@code 'S'} 行、含 {@code "}/{@code &} 的行）仍需人工核对。
 */
public final class CompareReporter {

    /** console 差异表表头（规范 §6.2 的表头原文）。 */
    public static final List<String> CONSOLE_HEADERS = List.of("#", "work line#", "ref line#", "content");

    /** 两侧相同时的状态行（规范 §1.3）。 */
    public static final String STATUS_SUCCESSFUL = "Compare successful.";

    /** {@code .suc} 的第一个字段（与 {@code --reference} 的 {@code .suc} 同一约定）。 */
    public static final String SUCCESS = "SUCCESS";

    /** 存在差异时的状态行；**不是错误**，errorCode 记 0（规范 §1.3）。 */
    public static final String STATUS_FAILED = "Compare failed.";


    /** HTML 模板在 classpath 上的位置（见类注释的"HTML 模板"一节）。 */
    private static final String TEMPLATE_ROOT = "/org/slackerdb/dbcli/compare/htmldiff/";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 报告行既不是 {@code ' '}/{@code '+'}/{@code '-'} 时抛出（规范 §7 的错误文案）。 */
    public static final class CompareReportException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CompareReportException(String message) {
            super(message);
        }
    }

    private final CompareResult result;
    private final List<String> reportLines;


    /**
     * 用比对结果里的**原始行**出报告 —— **不再二次读取目标文件**。
     *
     * <p>工作侧文本（{@code ' '} 行与 {@code 'S'} 行）直接取 {@link CompareResult#targetFileLines()}：
     * 那是"比对时用的同一份数据"（已按 mask 规则改写过、未做 skip/igblank 过滤，下标 = 行号 - 1）。
     * 这样避免了两个问题：出报告时文件已被改写导致报告与比对不一致；以及再读一次文件带来的额外 I/O。</p>
     */
    public CompareReporter(CompareResult result) {
        this(result, result.targetFileLines());
    }

    /** 用给定的"工作文件原始行"构造（测试或"比对后文件已被改写"的场景）。 */
    public CompareReporter(CompareResult result, List<String> workLines) {
        this.result = result;
        this.reportLines = reassemble(algorithmRows(result), List.copyOf(workLines));
    }

    public CompareResult result() {
        return result;
    }

    /** 状态行：两侧相同 → {@link #STATUS_SUCCESSFUL}；有差异 → {@link #STATUS_FAILED}。 */
    public String status() {
        return result.identical() ? STATUS_SUCCESSFUL : STATUS_FAILED;
    }

    /**
     * 报告行：{@code ' '}/{@code '-'}/{@code '+'}/{@code 'S'} 四种，
     * 形如 {@code "-     3 Russia"}（flag + 右对齐行号 + 1 空格 + 原文，原文不 strip）。
     * 行号格**最小** 6 字符；行号 >= 1,000,000 时自然变宽（读取按数字解析，不按固定下标）。
     */
    public List<String> reportLines() {
        return reportLines;
    }


    /** {@code <targetFile 去掉扩展名>.dif} 的路径（与目标文件同目录，无时间戳）。 */
    public Path diffFilePath() {
        return reportPath(".dif");
    }

    /** {@code <targetFile 去掉扩展名>.html} 的路径（与目标文件同目录，无时间戳）。 */
    public Path htmlFilePath() {
        return reportPath(".html");
    }

    /** {@code <targetFile 去掉扩展名>.suc} 的路径（与目标文件同目录，无时间戳）。 */
    public Path successFilePath() {
        return reportPath(".suc");
    }

    /**
     * 写 {@code .suc}：**一致时**才写，一行 TSV（LF 行尾）{@code SUCCESS\t<work>\t<ref>}。
     *
     * <p>与 {@code --reference} 的 {@code .suc} 是同一套约定（{@link org.slackerdb.dbcli.report.ReferenceReport}
     * 写的是 {@code SUCCESS\t<script>\t<ref>}）：一行、制表符分隔、人能直接读也方便 grep。
     * {@code _COMPARE} 没有"脚本显示名"，第一列之后放被比对的两个文件。</p>
     *
     * @return 写入的路径；两侧**不**一致时不写文件，返回 null
     */
    public Path writeSuccessFile() throws IOException {
        if (!result.identical()) {
            return null;
        }
        Path file = successFilePath();
        Files.write(file, (SUCCESS + "\t" + result.referencePath() + "\t" + result.targetPath() + "\n")
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return file;
    }

    /**
     * 写 {@code .dif}：完整报告行（**含** {@code ' '} 与 {@code 'S'}），CRLF 行尾，覆盖写。
     *
     * @return 写入的路径；**两侧相同时不写文件**，返回 null（契约 §6.3）
     */
    public Path writeDiffFile() throws IOException {
        if (result.identical()) {
            return null;
        }
        Path file = diffFilePath();
        writeWithCrlf(file, reportLines);
        return file;
    }

    /**
     * 写 {@code .html}（四个模板片段拼接；逐行加 LF 后统一成 CRLF，即"文本模式写文件"的效果）。
     *
     * @param generatedAt 页脚时间（由调用方注入，便于断言）
     * @return 写入的路径；**两侧相同时不写文件**，返回 null（契约 §6.4 的写入同样在成功分支之外）
     */
    public Path writeHtmlFile(LocalDateTime generatedAt) throws IOException {
        if (result.identical()) {
            return null;
        }
        Path file = htmlFilePath();
        Files.write(file, htmlDocument(generatedAt).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return file;
    }

    // ------------------------------------------------------------------ 报告行组装

    /**
     * 内核操作 → "算法行"（{@code '%s%6d %s'}）。
     *
     * <p>翻转映射：{@code INSERT}（仅目标/工作侧）→ {@code '-'}、{@code DELETE}（仅参考侧）→ {@code '+'}、
     * {@code EQUAL} → {@code ' '}；并且**按块重排**：每个连续的"非 equal"块里，先出全部 {@code '-'}，
     * 再出全部 {@code '+'}（见类注释的实测证据）。行号取原文件行号。</p>
     */
    static List<String> algorithmRows(CompareResult result) {
        List<String> rows = new ArrayList<>();
        List<DiffResult.Operation> operations = result.operations();
        int index = 0;
        while (index < operations.size()) {
            if (operations.get(index).kind() == DiffResult.Kind.EQUAL) {
                rows.add(row(" ", operations.get(index).rightLine(), operations.get(index).text()));
                index++;
                continue;
            }
            // 一个替换块：块内 '-' 与 '+' 的先后由这里决定，见类注释
            List<DiffResult.Operation> inserts = new ArrayList<>();
            List<DiffResult.Operation> deletes = new ArrayList<>();
            while (index < operations.size() && operations.get(index).kind() != DiffResult.Kind.EQUAL) {
                DiffResult.Operation operation = operations.get(index++);
                if (operation.kind() == DiffResult.Kind.INSERT) {
                    inserts.add(operation);
                } else {
                    deletes.add(operation);
                }
            }
            inserts.sort((left, right) -> Integer.compare(left.rightLine(), right.rightLine()));
            deletes.sort((left, right) -> Integer.compare(left.leftLine(), right.leftLine()));
            for (DiffResult.Operation operation : inserts) {
                rows.add(row("-", operation.rightLine(), operation.text()));
            }
            for (DiffResult.Operation operation : deletes) {
                rows.add(row("+", operation.leftLine(), operation.text()));
            }
        }
        return rows;
    }

    private static String row(String flag, int lineNumber, String content) {
        return flag + formatLineNumber(lineNumber) + " " + content;
    }

    /**
     * 行号回填：把算法行映射回原文件行，并补出被 skip/igblank 丢掉的工作文件行
     * （{@code 'S'} 行，只在差异报告里出现）。
     *
     * <p>{@code ' '} 与 {@code '-'} 行的文本取工作文件原文；{@code '+'} 行的文本保留算法行自身的文本
     * （内核的 {@code DELETE} 文本就是参考文件原文，故不必再读参考文件）。</p>
     *
     * @throws CompareReportException 行首不是 {@code ' '}/{@code '+'}/{@code '-'}（文案已冻结，规范 §7）
     */
    static List<String> reassemble(List<String> algorithmRows, List<String> workLines) {
        List<String> report = new ArrayList<>();
        int workLastPos = 0;
        for (String line : algorithmRows) {
            char flag = line.isEmpty() ? '\0' : line.charAt(0);
            switch (flag) {
                case '+' -> report.add("+" + formatLineNumber(lineNumber(line)) + " " + contentOf(line));
                case '-' -> {
                    int lineNumber = lineNumber(line);
                    appendIgnoredLines(report, workLines, workLastPos, lineNumber);
                    report.add("-" + formatLineNumber(lineNumber) + " " + workLine(workLines, lineNumber));
                    workLastPos = lineNumber;
                }
                case ' ' -> {
                    int lineNumber = lineNumber(line);
                    appendIgnoredLines(report, workLines, workLastPos, lineNumber);
                    report.add(" " + formatLineNumber(lineNumber) + " " + workLine(workLines, lineNumber));
                    workLastPos = lineNumber;
                }
                default -> throw new CompareReportException("Missed line number. Bad compare result. [" + line + "]");
            }
        }
        // 工作文件末尾被 skip/igblank 丢掉的行也要补成 'S' 行：它们只出现在"最后一条保留行之后"，
        // 上面的循环补不到（循环只在两条保留行之间补）。少了这些行，.dif 就无法解释"为什么判为一致"。
        appendIgnoredLines(report, workLines, workLastPos, workLines.size() + 1);
        return report;
    }

    /** 补出 (workLastPos, lineNumber) 之间被忽略的工作文件行，标记 {@code 'S'}。 */
    private static void appendIgnoredLines(List<String> report, List<String> workLines,
                                           int workLastPos, int lineNumber) {
        for (int position = workLastPos + 1; position < lineNumber; position++) {
            report.add("S" + formatLineNumber(position) + " " + workLine(workLines, position));
        }
    }

    /**
     * 从一行报告里取行号：跳过旗标与对齐用的空格，再读一串数字。
     *
     * <p>**不能**按固定下标取（原来是 {@code substring(1, 7)}）：行号格是 {@code %6d}（最小宽度 6），
     * 行号 >= 1,000,000 时字段自然变成 7 位以上，固定下标会把 1000000 读成 100000、
     * 正文也从错误的位置切起——大文件报告会静默串位。</p>
     */
    private static int lineNumber(String line) {
        int start = digitsStart(line);
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        if (start == end) {
            throw new CompareReportException("Missed line number. Bad compare result. [" + line + "]");
        }
        try {
            return Integer.parseInt(line.substring(start, end));
        } catch (NumberFormatException badNumber) {
            throw new CompareReportException("Missed line number. Bad compare result. [" + line + "]");
        }
    }

    /** 行号数字串的起始下标（旗标之后、跳过对齐空格）。 */
    private static int digitsStart(String line) {
        if (line.length() < 2) {
            throw new CompareReportException("Missed line number. Bad compare result. [" + line + "]");
        }
        int index = 1;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    /** 正文：行号之后那一个空格之后的全部内容（正文自身的首尾空白原样保留）。 */
    private static String contentOf(String line) {
        int index = digitsStart(line);
        while (index < line.length() && Character.isDigit(line.charAt(index))) {
            index++;
        }
        if (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index >= line.length() ? "" : line.substring(index);
    }

    private static String workLine(List<String> workLines, int lineNumber) {
        if (lineNumber < 1 || lineNumber > workLines.size()) {
            throw new CompareReportException("report line number [" + lineNumber + "] is outside the work file ("
                    + workLines.size() + " lines)");
        }
        return workLines.get(lineNumber - 1);
    }

    /**
     * 行号格子：右对齐，**最小**宽度 6（{@code String.format(Locale.ROOT, "%6d", lineno)}；
     * 更大的行号原样变宽，不截断）。
     *
     * <p>{@code Locale.ROOT} 是必须的：{@code %d} 会按默认 locale 本地化数字位，在
     * {@code ar-EG} 之类的区域会写出阿拉伯-印度数字，破坏 {@code .dif} 的逐字节契约
     * （而自家解析仍然成功，所以这个 bug 不会自我暴露）。</p>
     */
    private static String formatLineNumber(int lineNumber) {
        return String.format(Locale.ROOT, "%6d", lineNumber);
    }

    // ------------------------------------------------------------------ 输出文件

    /** {@code os.path.splitext} 的等价物：只认最后一个点，且前导点不算扩展名（{@code .hidden} → {@code .hidden.dif}）。 */
    private Path reportPath(String extension) {
        return artifactPath(Path.of(result.targetPath()), extension);
    }

    /**
     * 产物路径（{@code .suc}/{@code .dif}/{@code .html}）的**唯一**派生规则：与目标文件同目录、同基名。
     *
     * <p>公开是因为调用方要在写之前判断"这个产物路径是不是撞上了某个输入文件"（见
     * {@code CompareCommandDispatcher.ensureArtifactsDoNotClobberInputs}）。</p>
     */
    public static Path artifactPath(Path target, String extension) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return target.resolveSibling(base + extension);
    }

    private static void writeWithCrlf(Path file, List<String> lines) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append("\r\n");
        }
        Files.write(file, builder.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    // ------------------------------------------------------------------ HTML

    private String htmlDocument(LocalDateTime generatedAt) throws IOException {
        List<Map<String, Object>> headerLines = new ArrayList<>();
        List<Map<String, Object>> bodyLines = new ArrayList<>();
        for (String line : reportLines) {
            if (line.charAt(0) == 'M') {
                headerLines.add(parseHeaderLine(line));
            } else {
                bodyLines.add(parseBodyLine(line));
            }
        }
        Map<String, Object> contentData = new LinkedHashMap<>();
        contentData.put("lines", bodyLines);
        contentData.put("workFile", result.targetPath());
        contentData.put("refFile", result.referencePath());

        List<String> fragments = List.of(
                HtmlTemplateRenderer.render(template("header.html"), Map.of()),
                HtmlTemplateRenderer.render(template("body.header.html"), Map.<String, Object>of("lines", headerLines)),
                HtmlTemplateRenderer.render(template("body.content.html"), contentData),
                HtmlTemplateRenderer.render(template("footer.html"),
                        Map.of("time", generatedAt.format(TIME_FORMAT))));
        // 逐片段追加一个 "\n"，再统一把每个 "\n" 换成 "\r\n"（等价"文本模式写文件"）
        return (String.join("\n", fragments) + "\n").replace("\n", "\r\n");
    }

    /** 注释区的数据项（没有任何报告行以 'M' 开头，故实际用不到）。 */
    private static Map<String, Object> parseHeaderLine(String line) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flag", String.valueOf(line.charAt(0)));
        data.put("source", line.substring(1));
        return data;
    }

    /**
     * 正文区的数据项：{@code source = line[1:].lstrip()}，再按第一个空格切出行号与正文，正文 strip
     * （模板里的 JS 数据行按这个形状生成）。
     */
    private static Map<String, Object> parseBodyLine(String line) {
        String source = line.substring(1).stripLeading();
        int space = source.indexOf(' ');
        String number = space < 0 ? source : source.substring(0, space);
        String text = space < 0 ? "" : source.substring(space + 1);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flag", String.valueOf(line.charAt(0)));
        data.put("number", number);
        data.put("source", text.strip());
        return data;
    }

    /** 读 classpath 模板；CRLF 先归一成 LF，保证拼接结果的行尾完全由本类决定。 */
    private static String template(String name) throws IOException {
        try (InputStream stream = CompareReporter.class.getResourceAsStream(TEMPLATE_ROOT + name)) {
            if (stream == null) {
                throw new IOException("html template not found on classpath: " + TEMPLATE_ROOT + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
