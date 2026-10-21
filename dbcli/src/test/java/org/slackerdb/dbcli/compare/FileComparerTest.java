package org.slackerdb.dbcli.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 比对器与归一化/规则的测试：覆盖三个归一化选项、mask/skip 规则与文件编码（只支持 UTF-8）。
 */
class FileComparerTest {

    private static CompareOptions defaults() {
        return new CompareOptions();
    }

    /** 把操作序列描述成 ASCII 安全的文本（码点转义），避免诊断信息被报告文件编码搞乱。 */
    private static String describe(List<DiffResult.Operation> operations) {
        StringBuilder builder = new StringBuilder("[");
        for (DiffResult.Operation operation : operations) {
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(operation.kind()).append('(').append(operation.leftLine()).append(',')
                    .append(operation.rightLine()).append(")=");
            for (int i = 0; i < operation.text().length(); i++) {
                builder.append(String.format("U+%04X ", (int) operation.text().charAt(i)));
            }
        }
        return builder.append(']').toString();
    }

    @Test
    @DisplayName("完全相同 → identical；只差一行 → 1 删 1 插")
    void identicalAndOneChange() throws IOException {
        Path reference = Files.createTempFile("cmp-ref", ".txt");
        Path target = Files.createTempFile("cmp-tgt", ".txt");
        try {
            Files.writeString(reference, "a\nb\nc\n");
            Files.writeString(target, "a\nb\nc\n");
            CompareResult same = FileComparer.compare(reference, target, defaults(), null);
            assertTrue(same.identical(), "完全相同应当 identical");
            assertEquals(3, same.equalCount());

            Files.writeString(target, "a\nx\nc\n");
            CompareResult changed = FileComparer.compare(reference, target, defaults(), null);
            assertFalse(changed.identical());
            assertEquals(1, changed.deletedCount());
            assertEquals(1, changed.insertedCount());
            assertEquals("b", changed.operations().stream()
                    .filter(op -> op.kind() == DiffResult.Kind.DELETE).findFirst().orElseThrow().text());
            assertEquals("x", changed.operations().stream()
                    .filter(op -> op.kind() == DiffResult.Kind.INSERT).findFirst().orElseThrow().text());
        } finally {
            Files.deleteIfExists(reference);
            Files.deleteIfExists(target);
        }
    }

    @Test
    @DisplayName("默认区分大小写（CASE）：大小写不同算差异；NOCASE 后才忽略")
    void caseSensitiveByDefault() throws IOException {
        Path reference = Files.createTempFile("cmp-ref", ".txt");
        Path target = Files.createTempFile("cmp-tgt", ".txt");
        try {
            Files.writeString(reference, "HELLO\nWorld\n");
            Files.writeString(target, "hello\nWORLD\n");
            assertFalse(FileComparer.compare(reference, target, defaults(), null).identical(),
                    "默认区分大小写（NOCASE 才忽略）");

            CompareOptions noCase = defaults();
            noCase.setCaseSensitive(false);
            assertTrue(FileComparer.compare(reference, target, noCase, null).identical(),
                    "NOCASE 之后大小写不同不算差异");
        } finally {
            Files.deleteIfExists(reference);
            Files.deleteIfExists(target);
        }
    }

    @Test
    @DisplayName("trim 默认关闭（NOTRIM）：行首尾空白差异算差异；TRIM 后才忽略。igblank 丢弃空行")
    void trimAndIgnoreBlank() throws IOException {
        Path reference = Files.createTempFile("cmp-ref", ".txt");
        Path target = Files.createTempFile("cmp-tgt", ".txt");
        try {
            Files.writeString(reference, "a\n");
            Files.writeString(target, "   a   \n");
            assertFalse(FileComparer.compare(reference, target, defaults(), null).identical(),
                    "默认不剥离首尾空白：空白差异算差异");

            CompareOptions trimmed = defaults();
            trimmed.setIgnoreHeadTailBlank(true);
            assertTrue(FileComparer.compare(reference, target, trimmed, null).identical(),
                    "TRIM 之后首尾空白不算差异");

            Files.writeString(reference, "a\n\nb\n");
            Files.writeString(target, "a\nb\n");
            assertFalse(FileComparer.compare(reference, target, defaults(), null).identical(),
                    "默认不忽略空行");
            CompareOptions igBlank = defaults();
            igBlank.setIgnoreBlank(true);
            assertTrue(FileComparer.compare(reference, target, igBlank, null).identical(),
                    "igblank 打开后空行被丢弃，应当 identical");
        } finally {
            Files.deleteIfExists(reference);
            Files.deleteIfExists(target);
        }
    }

    @Test
    @DisplayName("mask：把**参考行当正则**去匹配工作行（单向语义）；skip 丢行")
    void maskAndSkipRules() throws IOException {
        Path reference = Files.createTempFile("cmp-ref", ".txt");
        Path target = Files.createTempFile("cmp-tgt", ".txt");
        try {
            // 参考侧是"模式"，工作侧是"实际值"
            Files.writeString(reference, "id=\\d+ ts=\\d+\n");
            Files.writeString(target, "id=2 ts=999\n");

            assertFalse(FileComparer.compare(reference, target, defaults(), null).identical(),
                    "不开 mask 时这两行不相等");

            CompareOptions masked = defaults();
            masked.setMask(true);
            assertTrue(FileComparer.compare(reference, target, masked, null).identical(),
                    "开 mask 后参考行的正则应当匹配工作行");

            // 方向相反时不匹配（工作行当正则会失败/不匹配）
            Files.writeString(reference, "id=2 ts=999\n");
            Files.writeString(target, "id=\\d+ ts=\\d+\n");
            assertFalse(FileComparer.compare(reference, target, masked, null).identical(),
                    "mask 是单向的：参考行才是正则");

            // skip 规则丢掉 data- 开头的行（整行匹配语义，故写成 data-.*）
            Files.writeString(reference, "id=1 ts=100\ndata-1\n");
            Files.writeString(target, "id=2 ts=999\ndata-2\n");
            LineRules skipRules = new LineRules();
            skipRules.add(LineRules.Rule.Type.SKIP, "data-.*", null);
            CompareResult skipped = FileComparer.compare(reference, target, defaults(), skipRules);
            assertEquals(1, skipped.skippedReference(), "参考侧应丢掉 1 行");
            assertEquals(1, skipped.skippedTarget(), "目标侧应丢掉 1 行");
            assertFalse(skipped.identical(), "剩下的一行数字不同，应当仍算差异");
        } finally {
            Files.deleteIfExists(reference);
            Files.deleteIfExists(target);
        }
    }

    @Test
    @DisplayName("只支持 UTF-8 —— 非 UTF-8 字节报可读错误（没有换编码的选项）")
    void utf8Only() throws IOException {
        Path reference = Files.createTempFile("cmp-ref", ".txt");
        Path target = Files.createTempFile("cmp-tgt", ".txt");
        try {
            // 参考文件是 GBK 字节：按 UTF-8 严格解码必须报可读错误（绝不"读成乱码还报比对失败"）
            Files.write(reference, "中文行\n".getBytes(Charset.forName("GBK")));
            Files.writeString(target, "中文行\n");

            IOException decodeError = assertThrows(IOException.class,
                    () -> FileComparer.compare(reference, target, defaults(), null));
            assertTrue(decodeError.getMessage().contains("only supports UTF-8"),
                    "解码失败要给可读错误，实际: " + decodeError.getMessage());
        } finally {
            Files.deleteIfExists(reference);
            Files.deleteIfExists(target);
        }
    }

    @Test
    @DisplayName("行切分：CRLF 与缺少末尾换行都能正确处理")
    void lineSplitting() {
        assertEquals(List.of("a", "b"), TextNormalizer.splitLines("a\r\nb\r\n"));
        assertEquals(List.of("a", "b"), TextNormalizer.splitLines("a\nb"));
        assertEquals(List.of(), TextNormalizer.splitLines(""));
        assertEquals(List.of(), TextNormalizer.splitLines(null));
        assertEquals(List.of("a", ""), TextNormalizer.splitLines("a\n\n"), "末尾空行应作为一行");
    }

    @Test
    @DisplayName("选项：applyIr 直接按键套用（键名与字段同名同义）、reset 回到默认")
    void options() {
        CompareOptions options = defaults();
        options.applyIr(java.util.Map.of(
                "case", true, "mask", true, "igblank", true, "trim", false));
        assertTrue(options.caseSensitive(), "IR case=true ⇒ 大小写敏感");
        assertTrue(options.mask());
        assertTrue(options.ignoreBlank());
        assertFalse(options.ignoreHeadTailBlank(), "IR trim=false（SET NOTRIM）⇒ 不剥离首尾空白");

        // IR trim=true（脚本里写 _COMPARE SET ... TRIM）⇒ 剥离首尾空白
        CompareOptions trimmed = defaults();
        trimmed.applyIr(java.util.Map.of("trim", true));
        assertTrue(trimmed.ignoreHeadTailBlank(), "IR trim=true（SET TRIM）⇒ 剥离首尾空白");

        options.reset();
        assertTrue(options.caseSensitive(), "RESET 回到 CASE");
        assertFalse(options.mask());
        assertFalse(options.ignoreHeadTailBlank(), "RESET 回到 NOTRIM");
    }

    @Test
    @DisplayName("mask 规则的正则非法 → 可读的异常（不静默）")
    void invalidPattern() {
        LineRules rules = new LineRules();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> rules.add(LineRules.Rule.Type.MASK, "[", "x"));
        assertTrue(error.getMessage().contains("invalid pattern"));
    }

    @Test
    @DisplayName("UTF-8 BOM 被容忍：带 BOM 的期望文件与不带 BOM 的实际输出视为一致")
    void utf8BomIsIgnored() throws IOException {
        // Windows 工具（记事本 / Set-Content）默认会写 BOM；不剥掉它，第一行会带上 U+FEFF 而假报差异
        Path reference = Files.createTempFile("cmp-ref", ".txt");
        Path target = Files.createTempFile("cmp-target", ".txt");
        try {
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] body = "alpha\nbeta\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] withBom = new byte[bom.length + body.length];
            System.arraycopy(bom, 0, withBom, 0, bom.length);
            System.arraycopy(body, 0, withBom, bom.length, body.length);
            Files.write(reference, withBom);
            Files.writeString(target, "alpha\nbeta\n");

            CompareResult result = FileComparer.compare(reference, target, defaults(), null);

            assertTrue(result.identical(),
                    "BOM 不是内容差异，实际差异数 " + result.differentCount());
            assertEquals(2, result.referenceLines());
        } finally {
            Files.deleteIfExists(reference);
            Files.deleteIfExists(target);
        }
    }
}
