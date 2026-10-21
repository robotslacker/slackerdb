package org.slackerdb.dbcli.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --reference} 的两份产物（{@code .suc}/{@code .dif}）与严格比对口径的契约测试。
 *
 * <p>测的是"文件字节"这一层：命名规则、写/不写分支、TSV 列、{@code .dif} 复用 {@code CompareReporter}
 * 的格式、以及"CRLF 期望 vs LF 输出"的取舍。命令行接线见 {@code CliReferenceTest}。</p>
 */
class ReferenceReportTest {

    @TempDir
    Path tempDir;

    /** 造一份"实际输出"（stdout tee 的内容）与一份参考文件。 */
    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path sibling(String referenceName, String extension) {
        String base = referenceName.substring(0, referenceName.lastIndexOf('.'));
        return tempDir.resolve(base + extension);
    }

    @Test
    @DisplayName("一致：写 .suc（一行 TSV），不写 .dif，也不写 .rpt")
    void identicalWritesSuccessAndReport() throws Exception {
        Path reference = write("case1.ref", "hello\nworld\n");
        Path actual = write("case1.out", "hello\nworld\n");

        ReferenceReport.Comparison comparison =
                new ReferenceReport("case1.sql", actual, reference).write();

        assertTrue(comparison.identical());
        assertEquals(2, comparison.equalCount());
        assertEquals(0, comparison.differentCount());

        // .suc：一行 TSV、LF 行尾、三个字段
        String success = Files.readString(sibling("case1.ref", ".suc"), StandardCharsets.UTF_8);
        assertEquals("SUCCESS\tcase1.sql\t" + reference + "\n", success);

        assertFalse(Files.exists(sibling("case1.ref", ".dif")), "一致时不得写 .dif");
        assertFalse(Files.exists(sibling("case1.ref", ".rpt")), "没有 .rpt 这回事了");
    }

    @Test
    @DisplayName("--report-dir：产物写进指定目录（目录自动创建），参考文件旁边一个文件都不留")
    void reportDirectoryMovesTheArtifacts() throws Exception {
        Path reference = write("case9.ref", "hello\n");
        Path actual = write("case9.out", "hello\n");
        Path reportDirectory = tempDir.resolve("reports/nested");

        ReferenceReport.Comparison comparison =
                new ReferenceReport("case9.sql", actual, reference, reportDirectory).write();

        assertTrue(comparison.identical());
        assertTrue(Files.exists(reportDirectory.resolve("case9.suc")), "目录应被自动创建并写入 .suc");
        // 参考文件旁边不得留下任何产物（那通常是被版本控制/只读的夹具目录）
        assertFalse(Files.exists(sibling("case9.ref", ".suc")));
        // 产物里的路径仍然指向真实参考文件
        assertEquals("SUCCESS\tcase9.sql\t" + reference + "\n",
                Files.readString(reportDirectory.resolve("case9.suc"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("不一致：写 .dif（复用 CompareReporter 格式），不写 .suc；退出判定为 1")
    void differentWritesDiffAndReport() throws Exception {
        Path reference = write("case2.ref", "alpha\nbeta\n");
        Path actual = write("case2.out", "alpha\n");

        ReferenceReport.Comparison comparison =
                new ReferenceReport("case2.sql", actual, reference).write();

        assertFalse(comparison.identical());
        assertEquals(1, comparison.differentCount(), "少一行 => 一个删除操作");

        assertFalse(Files.exists(sibling("case2.ref", ".suc")), "不一致时不得写 .suc");

        // .dif：与 _COMPARE 的 .dif 同一格式（CRLF 行尾 + flag + 6 字符右对齐行号 + 空格 + 原文）
        byte[] diffBytes = Files.readAllBytes(sibling("case2.ref", ".dif"));
        assertEquals("      1 alpha\r\n+     2 beta\r\n", new String(diffBytes, StandardCharsets.UTF_8));
        assertTrue(comparison.diffFile().toString().endsWith(".dif"));

        assertFalse(Files.exists(sibling("case2.ref", ".rpt")), "没有 .rpt 这回事了");
    }

    @Test
    @DisplayName("严格口径：大小写与首尾空白都算差异（与 _COMPARE 的默认相反）")
    void strictComparison() throws Exception {
        Path reference = write("case3.ref", "ChinA\n");
        Path actual = write("case3.out", "china\n");
        assertFalse(new ReferenceReport("s.sql", actual, reference).write().identical(),
                "大小写不同必须算差异（golden 比对不开 NOCASE）");

        Path blankReference = write("case4.ref", "a \n");
        Path blankActual = write("case4.out", "a\n");
        assertFalse(new ReferenceReport("s.sql", blankActual, blankReference).write().identical(),
                "行尾多一个空格必须算差异（golden 比对不开 TRIM）");
    }

    @Test
    @DisplayName("行尾符的取舍：CRLF 期望 + LF 输出 ⇒ 内容一致（说明行只写在 .dif 里）")
    void crlfReferenceIsAcceptedWithANote() throws Exception {
        Path reference = write("case5.ref", "one\r\ntwo\r\n");
        Path actual = write("case5.out", "one\ntwo\n");

        ReferenceReport.Comparison comparison =
                new ReferenceReport("case5.sql", actual, reference).write();

        assertTrue(comparison.identical(), "行尾符不同不算内容差异");
        assertTrue(comparison.lineEndingsDiffer());

        assertTrue(Files.exists(sibling("case5.ref", ".suc")), "一致时写 .suc");
        assertFalse(Files.exists(sibling("case5.ref", ".dif")), "内容一致时不写 .dif");
        assertFalse(Files.exists(sibling("case5.ref", ".rpt")), "没有 .rpt 这回事了");
    }

    @Test
    @DisplayName(".dif 里行尾说明与内容差异同时出现：说明行在首行，内容差异照常列在下面")
    void crlfNoteIsWrittenNextToRealDifferences() throws Exception {
        Path reference = write("case6.ref", "one\r\nTWO\r\n");
        Path actual = write("case6.out", "one\ntwo\n");

        ReferenceReport.Comparison comparison =
                new ReferenceReport("case6.sql", actual, reference).write();

        assertFalse(comparison.identical());
        assertTrue(comparison.lineEndingsDiffer(), "参考文件是 CRLF、实际输出是 LF");
        // 界面把 CRLF 与单独的 CR 都当换行切行，所以 "one\r\n" 切出来是 "one"；但 mask/trim 都关着，
        // "TWO\r" 与 "two" 的差异会落在内容比较里（说明行正是为此存在）
        assertTrue(comparison.differentCount() >= 1);

        List<String> diff = Files.readString(sibling("case6.ref", ".dif"), StandardCharsets.UTF_8)
                .lines().toList();
        assertEquals(ReferenceReport.CRLF_NOTE, diff.get(0));
        assertTrue(diff.stream().anyMatch(line -> line.startsWith("+")), "差异行要照常列出: " + diff);
    }

    @Test
    @DisplayName("命名规则：与参考文件同目录同基名；无扩展名与点开头的名字也按同一条规则")
    void namingFollowsTheReferenceFile() throws Exception {
        Path noExtension = write("case7", "x\n");
        assertTrue(new ReferenceReport("s.sql", noExtension, noExtension).write().identical());
        assertTrue(Files.exists(tempDir.resolve("case7.suc")));

        // 多点名字只去掉最后一个扩展名
        Path multiDot = write("case8.data.ref", "x\n");
        new ReferenceReport("s.sql", multiDot, multiDot).write();
        assertTrue(Files.exists(tempDir.resolve("case8.data.suc")), "多点名字只去掉最后一个扩展名");
    }

    @Test
    @DisplayName("产物恒定：一致时删掉旧 .dif，不一致时删掉旧 .suc")
    void staleArtifactsAreRemoved(@TempDir Path dir) throws IOException {
        Path actual = Files.writeString(dir.resolve("run.log"), "a\n");
        Path reference = Files.writeString(dir.resolve("golden.ref"), "a\n");
        Path diff = dir.resolve("run.dif");
        Path success = dir.resolve("run.suc");
        Files.writeString(diff, "stale-diff");

        ReferenceReport.Comparison identical =
                new ReferenceReport("s.sql", actual, reference).write();
        assertTrue(identical.identical());
        assertFalse(Files.exists(diff), "一致时旧的 .dif 必须删掉");
        assertTrue(Files.exists(success));

        Files.writeString(actual, "b\n");
        ReferenceReport.Comparison different =
                new ReferenceReport("s.sql", actual, reference).write();
        assertFalse(different.identical());
        assertFalse(Files.exists(success), "不一致时旧的 .suc 必须删掉");
        assertTrue(Files.exists(diff));
    }
}
