package org.slackerdb.dbcli.report;

import org.slackerdb.dbcli.common.Artifacts;
import org.slackerdb.dbcli.compare.CompareOptions;
import org.slackerdb.dbcli.compare.CompareReporter;
import org.slackerdb.dbcli.compare.CompareResult;
import org.slackerdb.dbcli.compare.FileComparer;
import org.slackerdb.dbcli.compare.LineRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * {@code --reference <参考文件>} 的 golden 比对与两份报告产物（{@code .dif} / {@code .suc}）。
 *
 * <p>运行形态：脚本**照常**把数据写到 stdout（stdout 契约不变），{@code --reference} 只额外做两件事 ——
 * 把这次运行真实的 stdout 字节 tee 到一个临时文件，跑完之后拿它与参考文件比对。因此本类完全是
 * "旁路"：它读两个文件、写三个旁路文件，**不参与脚本执行，也不写 stdout**。</p>
 *
 * <h2>产物命名与内容（与参考文件同目录、同名、不同扩展名）</h2>
 * <table border="1">
 *   <caption>两份产物</caption>
 *   <tr><th>产物</th><th>何时写</th><th>内容</th></tr>
 *   <tr>
 *     <td>{@code <工作文件去扩展名>.suc}</td><td><b>一致时</b>才写</td>
 *     <td>一行 TSV（LF 行尾）：{@code SUCCESS\t<script 显示名>\t<参考文件路径>}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code <工作文件去扩展名>.dif}</td><td><b>不一致时</b>才写</td>
 *     <td>{@link CompareReporter} 的 {@code .dif} 格式（同一份差异格式，全工具只有一种）</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么用"严格选项"，与 {@code _COMPARE} 的默认不同</h2>
 * <p>{@code _COMPARE} 的默认是"剥离首尾空白 + 忽略大小写"，因为它是**给别人写的脚本**用的比对命令，
 * 宽松一点更好用。而 {@code --reference} 的用途是 <b>golden 比对</b>：判据必须是"这段程序真的输出了
 * 一模一样的字节"，任何"帮它抹平"的选项都会把真实差异藏起来。所以这里固定：</p>
 * <ul>
 *   <li><b>大小写敏感</b>（不开 {@code NOCASE}）；</li>
 *   <li><b>不剥离首尾空白</b>（不开 {@code TRIM}，连行尾的一个空格都算差异）；</li>
 *   <li><b>只应用脚本里显式声明的 {@code MASKLINE}/{@code SKIPLINE} 规则</b>（{@code rules} 参数，由
 *       {@code _COMPARE} 声明）：那是 review 的人写下的放宽，不是工具自动识别的；</li>
 *   <li>相等判定就是 {@code String.equals}（{@link LineDiffer} 的默认重载）。</li>
 * </ul>
 * <p>唯一的宽容处是**行尾符**：比较前先试"原始字节相等"，不等再按 LF 切行、切行时去掉行尾的 {@code \r}
 * 与 {@code \n}（与 {@code FileComparer} 同一套切分，见 {@code TextNormalizer}）。这样
 * "CRLF 的期望文件 vs LF 的 stdout" 会得到"内容一致"的结论——否则 Windows 上换一份期望文件就全红，
 * 而那不是程序输出的差异。为了让这个取舍**可见**，行尾确实不同且**内容有差异**时会
 * 在 {@code .dif} 开头写一行说明；只有行尾不同（内容一致）时不出 {@code .dif}，此时结论是
 * "一致"、{@code .suc} 照常写，行尾差异不影响退出码。</p>
 *
 * <h2>退出码</h2>
 * <p>不一致 → 1（与 {@code _COMPARE} 失败一致）；一致 → 0。两种情况的 stdout 内容都不受影响。</p>
 */
public final class ReferenceReport {

    /** 一致时写进 {@code .suc} 的第一个字段。 */
    public static final String SUCCESS = "SUCCESS";

    /** 行尾差异的说明行（差异文件与摘要表都用它，只会在行尾确实不同时出现）。 */
    public static final String CRLF_NOTE =
            "# note: line endings differ (CRLF vs LF); line content is compared with the CR stripped";

    /** 一次 golden 比对的结论（纯数据，便于测试）。 */
    public record Comparison(Path actualFile, Path referenceFile, Path diffFile, Path successFile,
                             int actualLines, int referenceLines,
                             int equalCount, int differentCount, boolean lineEndingsDiffer) {

        /** 两侧一致（按本类的严格口径）。 */
        public boolean identical() {
            return differentCount == 0;
        }

    }

    private final String scriptName;
    private final Path actualFile;
    private final Path referenceFile;

    /** 产物目录；{@code null} = 与参考文件同目录（历史行为）。 */
    private final Path reportDirectory;

    /**
     * 脚本里声明的 mask/skip 规则（{@code _COMPARE MASKLINE/SKIPLINE}）；{@code null} 表示没有。
     *
     * <p><b>这是内置比对唯一的放宽手段</b>：大小写、首尾空白、算法仍固定为严格口径，
     * 只有"易变值"能靠人在 review 时写进脚本的规则被掩掉。不做任何自动识别。</p>
     */
    private final LineRules rules;

    /**
     * @param scriptName 脚本显示名（进 {@code .suc} 的第一行）
     * @param actualFile 这次运行真实 stdout 的 tee 文件
     * @param referenceFile 参考文件（golden）
     */
    public ReferenceReport(String scriptName, Path actualFile, Path referenceFile) {
        this(scriptName, actualFile, referenceFile, null, null);
    }

    /**
     * @param reportDirectory 产物目录（{@code --report-dir}）；{@code null} 表示写在参考文件旁边
     */
    public ReferenceReport(String scriptName, Path actualFile, Path referenceFile, Path reportDirectory) {
        this(scriptName, actualFile, referenceFile, reportDirectory, null);
    }

    /**
     * @param reportDirectory 产物目录（{@code --report-dir}）；{@code null} 表示写在参考文件旁边
     * @param rules           脚本声明的 mask/skip 规则（{@code null} = 不应用任何规则）
     *
     * <p><b>为什么要能挪走产物</b>：参考文件通常放在只读夹具目录或受版本控制的目录里，
     * 每次跑测试都往里写 {@code .suc/.dif} 会污染工作区、也让 CI 无法集中收集报告。
     * 给一个目录之后，产物文件名仍是"工作文件去扩展名 + 后缀"，因此多个脚本混在一个目录里
     * 也不会互相覆盖。</p>
     */
    public ReferenceReport(String scriptName, Path actualFile, Path referenceFile, Path reportDirectory,
                           LineRules rules) {
        this.scriptName = scriptName == null || scriptName.isBlank() ? "<unknown>" : scriptName;
        this.actualFile = actualFile;
        this.referenceFile = referenceFile;
        this.reportDirectory = reportDirectory == null ? null : reportDirectory.toAbsolutePath().normalize();
        this.rules = rules;
    }

    /**
     * 比对并写三份产物。
     *
     * @return 比对结论（含产物路径）
     * @throws IOException 读不到两侧文件，或产物目录不存在/写不了（调用方转成可读错误 + 退出 1）
     */
    public Comparison write() throws IOException {
        byte[] actualBytes = Files.readAllBytes(actualFile);
        byte[] referenceBytes = Files.readAllBytes(referenceFile);

        // --report-dir：连目录一起建出来（"第一次跑"不该因为目录不存在就失败）；
        // 相对路径在上层（Cli）已按工作目录解析过
        if (reportDirectory != null) {
            Files.createDirectories(reportDirectory);
        }

        // 复用 _COMPARE 的内核，**固定用严格选项**：大小写敏感、不剥离首尾空白。
        // 唯一的放宽是脚本声明的 mask/skip 规则（rules）：那是 review 的人显式写下的，
        // 不是工具自动识别的。行尾按内核既有的切分处理（CRLF 与单独的 CR 都当换行）。
        CompareOptions options = new CompareOptions();
        options.setCaseSensitive(true);
        options.setIgnoreHeadTailBlank(false);
        options.setMask(false);
        options.setIgnoreBlank(false);
        CompareResult result = FileComparer.compare(referenceFile, actualFile, options, rules);

        boolean different = !result.identical();
        // 行尾符/末尾换行不同时留一条说明：内容没差异时它就是"为什么字节不同"的答案，
        // 内容有差异时它提醒读者"差异文件里的行尾是平台混合的"（见类注释的取舍）
        boolean lineEndingsDiffer = endingsDiffer(actualBytes, referenceBytes);

        // 复用 _COMPARE 的报告器：.dif 的格式因此只有一种（工作侧 = 这次运行的实际输出）
        List<String> reportLines = different ? new CompareReporter(result).reportLines() : List.of();

        Comparison comparison = new Comparison(actualFile, referenceFile,
                sibling(".dif"), sibling(".suc"),
                result.targetLines(), result.referenceLines(),
                result.equalCount(), result.differentCount(), lineEndingsDiffer);

        if (comparison.identical()) {
            writeSuccessFile(comparison);
            // 产物恒定：本次一致时，上一次不一致留下的 .dif 必须删掉
            Artifacts.deleteStale(comparison.diffFile());
        } else {
            Artifacts.deleteStale(comparison.successFile());
            writeDiffFile(comparison, reportLines);
        }
        return comparison;
    }

    /**
     * {@code <工作文件去扩展名><后缀>}：产物名跟着**本次运行的 work 文件**（那条 log）走，不跟 golden 走。
     *
     * <p>为什么按工作文件：golden 通常放在只读夹具/受版本控制的目录里，产物名也不该被它"绑架"——
     * 同一个 golden 可能被多个脚本引用，按工作文件命名才不会互相覆盖。
     * 给了 {@code --report-dir} 时落在该目录；否则与工作文件同目录（{@code Cli} 默认就是这么给它目录的）。
     * 前导点不算扩展名，{@code .hidden} → {@code .hidden.suc}。</p>
     */
    private Path sibling(String extension) {
        Path name = actualFile.getFileName();
        String base = name == null ? actualFile.toString() : name.toString();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        if (reportDirectory != null) {
            return reportDirectory.resolve(base + extension);
        }
        Path parent = actualFile.toAbsolutePath().getParent();
        return parent == null ? Path.of(base + extension) : parent.resolve(base + extension);
    }

    private void writeSuccessFile(Comparison comparison) throws IOException {
        Files.write(comparison.successFile(),
                (SUCCESS + "\t" + scriptName + "\t" + referenceFile + "\n")
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /**
     * 写 {@code .dif}：<b>与 {@code CompareReporter} 完全同一种格式</b>（CRLF 行尾、含 {@code ' '} 与
     * {@code 'S'} 行、行号 6 字符右对齐），只是行尾不同的说明作为一行注释放在最前面。
     */
    private void writeDiffFile(Comparison comparison, List<String> reportLines) throws IOException {
        StringBuilder builder = new StringBuilder();
        if (comparison.lineEndingsDiffer()) {
            builder.append(CRLF_NOTE).append("\r\n");
        }
        for (String line : reportLines) {
            builder.append(line).append("\r\n");
        }
        Files.write(comparison.diffFile(), builder.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /**
     * 行尾符说明的判据：两侧内容的行尾符（CRLF 数量）或末尾换行不同。
     *
     * <p>只在"内容判定为一致、但原始字节不同"时才用到（见 {@link #write()}）：那时差异只可能来自
     * 行尾符或末尾换行，把这件事写成一行说明，读者不会误以为"程序输出变了"。</p>
     */
    private static boolean endingsDiffer(byte[] actualBytes, byte[] referenceBytes) {
        return crlfCount(actualBytes) != crlfCount(referenceBytes)
                || endsWithNewline(actualBytes) != endsWithNewline(referenceBytes);
    }

    private static int crlfCount(byte[] bytes) {
        int count = 0;
        for (int index = 1; index < bytes.length; index++) {
            if (bytes[index] == '\n' && bytes[index - 1] == '\r') {
                count++;
            }
        }
        return count;
    }

    private static boolean endsWithNewline(byte[] bytes) {
        return bytes.length > 0 && bytes[bytes.length - 1] == '\n';
    }
}
