package org.slackerdb.dbcli.compare;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件比对器：读两个文件 → 按规则筛行 → 差分 → 出 {@link CompareResult}。
 *
 * <p>**输出格式不在这里**（见 {@code CompareReporter}）：本类只产出结构化结果，
 * 这样"算法/语义"与"格式"可以分别验证。</p>
 *
 * <h2>字符集（固定 UTF-8）</h2>
 * 一律按 **UTF-8 严格解码**；遇到非法字节报可读错误（而不是替换成 {@code ?} 后报"比对失败"）。
 * 没有"换成别的编码"的选项：本工具只做 UTF-8（{@code _COMPARE SET WORK|REFERENCE ENCODING} 不存在）。
 *
 * <h2>行号</h2>
 * 结果里的行号是**原文件行号**（work line# / ref line# 同义）：
 * 被 skip 规则或 {@code igblank} 丢掉的行走不到，但保留行的行号不因过滤而偏移。
 */
public final class FileComparer {

    private FileComparer() {
    }

    /** 带原始行号的一行。 */
    private record IndexedLine(int number, String text) {
    }

    /**
     * 比对两个文件。
     *
     * @param referenceFile 参考文件（IR 的 {@code referenceFile}，即 {@code _COMPARE} 的**第 2 个**参数）
     * @param targetFile    目标文件（IR 的 {@code targetFile}，即第 1 个参数）
     * @param options       选项（case/trim/igblank/mask）
     * @param rules         行规则（skip），可为 null
     */
    public static CompareResult compare(Path referenceFile, Path targetFile,
                                        CompareOptions options, LineRules rules) throws IOException {
        List<String> rawReference = TextNormalizer.splitLines(readUtf8(referenceFile));
        List<String> rawTarget = TextNormalizer.splitLines(readUtf8(targetFile));

        // mask 规则**只应用一次**（与 mask 选项无关）：结果里保留"已 mask 的原始行"，
        // 报告器据此取工作侧文本，避免"出报告时再读文件 + 再 mask 一次"（后者对 a→aa 这类规则会重复放大）
        List<String> maskedReference = applyMaskRules(rawReference, rules);
        List<String> maskedTarget = applyMaskRules(rawTarget, rules);

        List<IndexedLine> reference = prepare(index(maskedReference), options, rules);
        List<IndexedLine> target = prepare(index(maskedTarget), options, rules);

        List<String> referenceTexts = textsOf(reference);
        List<String> targetTexts = textsOf(target);

        LineEquality equality = new LineEquality(options);
        DiffResult diff = LineDiffer.diff(referenceTexts, targetTexts, equality::equal);

        // 把"下标"上的操作映射回原文件行号与展示文本
        List<DiffResult.Operation> operations = new ArrayList<>(diff.operations().size());
        for (DiffResult.Operation operation : diff.operations()) {
            switch (operation.kind()) {
                case EQUAL -> {
                    IndexedLine left = reference.get(operation.leftLine() - 1);
                    IndexedLine right = target.get(operation.rightLine() - 1);
                    operations.add(new DiffResult.Operation(DiffResult.Kind.EQUAL, left.number(), right.number(),
                            left.text()));
                }
                case DELETE -> {
                    IndexedLine left = reference.get(operation.leftLine() - 1);
                    operations.add(new DiffResult.Operation(DiffResult.Kind.DELETE, left.number(), -1, left.text()));
                }
                case INSERT -> {
                    IndexedLine right = target.get(operation.rightLine() - 1);
                    operations.add(new DiffResult.Operation(DiffResult.Kind.INSERT, -1, right.number(), right.text()));
                }
            }
        }

        return new CompareResult(
                referenceFile.toString(),
                targetFile.toString(),
                operations,
                reference.size(),
                target.size(),
                rawReference.size() - reference.size(),
                rawTarget.size() - target.size(),
                List.copyOf(maskedReference),
                List.copyOf(maskedTarget));
    }

    /** 按 mask 规则改写全部行（空白规则集时原样返回）；**与 {@code mask} 选项无关**。 */
    private static List<String> applyMaskRules(List<String> lines, LineRules rules) {
        if (rules == null || rules.maskRuleCount() == 0) {
            return lines;
        }
        List<String> masked = new ArrayList<>(lines.size());
        for (String line : lines) {
            masked.add(rules.applyMask(line));
        }
        return masked;
    }

    /** 打上原始行号。 */
    private static List<IndexedLine> index(List<String> lines) {
        List<IndexedLine> indexed = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            indexed.add(new IndexedLine(i + 1, lines.get(i)));
        }
        return indexed;
    }

    private static List<String> textsOf(List<IndexedLine> lines) {
        List<String> texts = new ArrayList<>(lines.size());
        for (IndexedLine line : lines) {
            texts.add(line.text());
        }
        return texts;
    }

    /**
     * 按 skip 规则丢行，再按 {@code igblank} 丢空行。
     *
     * <p>mask 改写已在 {@link #applyMaskRules} 阶段完成（**只做一次**），这里不再重复应用。</p>
     */
    private static List<IndexedLine> prepare(List<IndexedLine> lines, CompareOptions options, LineRules rules) {
        List<IndexedLine> kept = new ArrayList<>(lines.size());
        for (IndexedLine line : lines) {
            String text = line.text();
            // skip 是"整行匹配 + 强制忽略大小写"；匹配对象是参与比较的那一行
            // （剥离首尾空白时按剥离后的文本比，即 trim 对 skip 同样生效）
            String matchTarget = options.ignoreHeadTailBlank() ? text.strip() : text;
            if (rules != null && rules.skipRuleCount() > 0 && rules.shouldSkip(matchTarget)) {
                continue;
            }
            if (options.ignoreBlank() && text.isBlank()) {
                continue;
            }
            kept.add(line);
        }
        return kept;
    }

    /**
     * 严格按 UTF-8 读文件。
     *
     * <p><b>为什么不用 {@code Files.readString(path, UTF_8)}</b>：在本机 JDK（21.0.10 Oracle）上，
     * {@code Files.readString(path, charset)} 走 {@code JavaLangAccess.newStringNoRepl} 的路径，
     * 对 GBK 家族会返回**错误文本**（实测 7 字节的 GBK "中文行\n" 被读成 8 个 Latin Extended-A 字符），
     * 而 {@code new String(bytes, charset)} 与显式 {@code CharsetDecoder} 都正确。
     * 虽然 dbcli 只用 UTF-8（该路径对 UTF-8 是正确的），这里仍统一走显式解码器：
     * 行为可控、语义严格（非法字节报错而不是替换），将来若真要用别的编码也不会踩这个坑。</p>
     */
    static String readUtf8(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        // 容忍 UTF-8 BOM：Windows 的工具（记事本、PowerShell 的 Set-Content）默认会写 BOM，
        // 不剥掉它，第一行就会带上 \uFEFF 而被判成"与期望不同"——那不是内容差异。
        // 与脚本读取（engine.Utf8Text）同一条口径。
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException decodeError) {
            throw new IOException("cannot decode file [" + file + "] as UTF-8 (dbcli only supports UTF-8): "
                    + decodeError, decodeError);
        }
    }

    /** 是否以 UTF-8 BOM（{@code EF BB BF}）开头。 */
    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
    }
}
