package org.slackerdb.dbcli.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大输入下的差分：**必须算得完、必须不 OOM**，并且结果仍然可用。
 *
 * <p>背景：LCS 的 DP 表是 {@code (n+1)(m+1)} 个 int，Myers 的轨迹最坏是 {@code O((n+m)²)} 个 int。
 * 实测 8 000 × 8 000 行全不同即 OOM（见 {@code docs/dbcli-assessment.md} 附录 A-2），
 * 而"几万行 dump 的回归比对"正是本工具的主要用例。{@link LineDiffer} 因此对超过 1 000 行的输入
 * 改用"裁剪 + 预算 + 贪心回退"。</p>
 *
 * <p>本类锁住四件事：</p>
 * <ol>
 *   <li>完全不同的 2 万 × 2 万行**能算完**（默认堆；旧实现在这里 OOM）；</li>
 *   <li>Myers 的轨迹预算到点会**放弃**（返回 null），而不是把堆吃掉；</li>
 *   <li>大输入里"少量真实差异"仍然被精确定位（裁剪 + 重同步起作用）；</li>
 *   <li>console 差异表有行数上限，且剩余条数会被显式报出来。</li>
 * </ol>
 */
class LineDifferLargeInputTest {

    private static final int LINES = 20_000;

    private static List<String> lines(String prefix, int count) {
        List<String> lines = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            lines.add(prefix + index);
        }
        return lines;
    }

    @Test
    @DisplayName("2 万 × 2 万行全不同：20 秒内算完，差异计数完整（旧实现 OOM）")
    void completelyDifferentLargeInputsComplete() {
        List<String> reference = lines("a", LINES);
        List<String> target = lines("b", LINES);

        long startedAt = System.nanoTime();
        DiffResult diff = LineDiffer.diff(reference, target);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue(elapsedMillis < 20_000L, "大输入必须能在有限时间内算完，实际 " + elapsedMillis + " ms");
        assertEquals(LINES, diff.deletedCount(), "参考侧每一行都应记为删除");
        assertEquals(LINES, diff.insertedCount(), "目标侧每一行都应记为插入");
        assertEquals(0, diff.equalCount());
        assertEquals(LINES * 2, diff.operations().size());
    }

    @Test
    @DisplayName("Myers 轨迹预算：超出预算立刻放弃（返回 null），不吞掉堆")
    void myersGivesUpWhenTheTraceBudgetIsExceeded() {
        List<String> reference = lines("a", 5_000);
        List<String> target = lines("b", 5_000);

        // 5 000+5 000 行全不同 ⇒ d 会一路涨到 10 000；轨迹预算只够几十轮。
        // 不设预算的对照**故意不写**：那正是会 OOM 的调用方式（本类的存在就是为了不再这样用它）。
        assertNull(LineDiffer.myers(reference, target, String::equals, 100_000L),
                "预算不够时必须放弃，让调用方退回贪心算法");
    }

    @Test
    @DisplayName("大输入里的少量真实差异仍被精确定位（裁剪 + 重同步）")
    void smallDifferencesInLargeFilesAreLocated() {
        List<String> reference = lines("line-", LINES);
        List<String> target = new ArrayList<>(reference);
        target.set(10, "changed-at-ten");       // 0 基下标 10 ⇒ 第 11 行
        target.remove(15_000);                  // 删掉第 15 001 行
        target.add(17_000, "inserted-at-17000"); // 在第 17 000 行位置插入

        DiffResult diff = LineDiffer.diff(reference, target);

        // 3 处变化 ⇒ 编辑脚本最多几行；绝不能退化成"整体替换"
        assertTrue(diff.differentCount() <= 8,
                "少量差异不该变成整体替换，实际 differentCount=" + diff.differentCount()
                        + " ops=" + diff.operations().size());
        assertTrue(diff.operations().stream().anyMatch(
                        operation -> operation.kind() == DiffResult.Kind.DELETE
                                && operation.text().equals("line-11")),
                "第 11 行的替换必须出现在差异里");
        assertTrue(diff.operations().stream().anyMatch(
                        operation -> "inserted-at-17000".equals(operation.text())
                                && operation.kind() == DiffResult.Kind.INSERT),
                "插入行必须出现在差异里");
    }

    @Test
    @DisplayName("大差异量下 .dif 的报告行仍然完整（不再有 console 截断这回事）")
    void diffFileKeepsEveryLine(@TempDir Path tempDir) throws Exception {
        // 300 行全不同 ⇒ 300 删除 + 300 插入 = 600 差异行
        Path reference = tempDir.resolve("ref.txt");
        Path target = tempDir.resolve("work.txt");
        Files.write(reference, lines("a", 300), StandardCharsets.UTF_8);
        Files.write(target, lines("b", 300), StandardCharsets.UTF_8);

        CompareResult result = FileComparer.compare(reference, target, new CompareOptions(), null);
        CompareReporter reporter = new CompareReporter(result);

        assertEquals(600, result.differentCount());
        assertEquals(600, reporter.reportLines().stream()
                        .filter(line -> line.charAt(0) == '-' || line.charAt(0) == '+')
                        .count(),
                ".dif 的报告行仍然是完整的");
        assertTrue(reporter.writeDiffFile().toString().endsWith("work.dif"));
    }
}
