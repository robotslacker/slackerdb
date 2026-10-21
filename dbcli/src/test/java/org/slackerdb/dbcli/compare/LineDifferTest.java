package org.slackerdb.dbcli.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 差分算法测试（只有一种算法：Myers）。
 *
 * <p>Myers 的轨迹回溯很容易写错，所以这里的重点不是"某几个样例"，而是**性质校验 + 模糊测试**：</p>
 * <ol>
 *   <li><b>可还原</b>：把编辑脚本套回参考侧必须得到目标侧（EQUAL+INSERT 的文本按序 == 目标；
 *       EQUAL+DELETE 的文本按序 == 参考）；</li>
 *   <li><b>行号完备</b>：参考侧每一行恰好被"EQUAL 或 DELETE"覆盖一次，目标侧同理；</li>
 *   <li><b>最短</b>：距离必须等于 {@code n + m - 2·|LCS|}（测试里用一份朴素的 DP 独立算 LCS 作对照）。</li>
 * </ol>
 */
class LineDifferTest {

    private static List<String> lines(String... values) {
        return new ArrayList<>(List.of(values));
    }

    /** 独立算出的"最短编辑脚本"距离（朴素 LCS DP，只在测试里用于对照）。 */
    private static int shortestDistance(List<String> reference, List<String> target) {
        int n = reference.size();
        int m = target.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = reference.get(i).equals(target.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        return n + m - 2 * lcs[0][0];
    }

    /** 性质 1+2：编辑脚本必须能还原两侧，且行号完备。 */
    private static void assertScriptIsValid(List<String> reference, List<String> target) {
        DiffResult result = LineDiffer.diff(reference, target);

        List<String> fromReference = new ArrayList<>();
        List<String> fromTarget = new ArrayList<>();
        int expectedReferenceLine = 0;
        int expectedTargetLine = 0;
        for (DiffResult.Operation operation : result.operations()) {
            switch (operation.kind()) {
                case EQUAL -> {
                    fromReference.add(operation.text());
                    fromTarget.add(operation.text());
                    expectedReferenceLine++;
                    expectedTargetLine++;
                    assertEquals(expectedReferenceLine, operation.leftLine(),
                            "EQUAL 的参考侧行号应当逐行递增");
                    assertEquals(expectedTargetLine, operation.rightLine(),
                            "EQUAL 的目标侧行号应当逐行递增");
                }
                case DELETE -> {
                    fromReference.add(operation.text());
                    expectedReferenceLine++;
                    assertEquals(expectedReferenceLine, operation.leftLine(),
                            "DELETE 的参考侧行号应当逐行递增");
                    assertEquals(-1, operation.rightLine(), "DELETE 不应有目标侧行号");
                }
                case INSERT -> {
                    fromTarget.add(operation.text());
                    expectedTargetLine++;
                    assertEquals(-1, operation.leftLine(), "INSERT 不应有参考侧行号");
                    assertEquals(expectedTargetLine, operation.rightLine(),
                            "INSERT 的目标侧行号应当逐行递增");
                }
            }
        }
        assertEquals(reference, fromReference, "脚本套回参考侧必须等于参考侧");
        assertEquals(target, fromTarget, "脚本套到目标侧必须等于目标侧");
        assertEquals(reference.size(), expectedReferenceLine, "参考侧行数必须被完全覆盖");
        assertEquals(target.size(), expectedTargetLine, "目标侧行数必须被完全覆盖");
    }

    @Test
    @DisplayName("相同输入 → 全部 EQUAL，没有差异")
    void identicalInput() {
        List<String> input = lines("a", "b", "c");
        DiffResult result = LineDiffer.diff(input, input);

        assertEquals(0, result.differentCount(), "相同输入不应有差异");
        assertEquals(3, result.equalCount());
        assertScriptIsValid(input, input);
    }

    @Test
    @DisplayName("典型样例：一行改动、插入、删除、空文件")
    void typicalCases() {
        assertScriptIsValid(lines("a", "b", "c"), lines("a", "x", "c"));
        assertScriptIsValid(lines("a", "c"), lines("a", "b", "c"));
        assertScriptIsValid(lines("a", "b", "c"), lines("a", "c"));
        assertScriptIsValid(lines(), lines("a"));
        assertScriptIsValid(lines("a"), lines());
        assertScriptIsValid(lines(), lines());
    }

    @Test
    @DisplayName("空文件与非空文件：距离等于另一侧行数")
    void emptyFiles() {
        DiffResult result = LineDiffer.diff(lines(), lines("a", "b"));
        assertEquals(2, result.insertedCount());
        assertEquals(0, result.deletedCount());

        DiffResult reversed = LineDiffer.diff(lines("a", "b"), lines());
        assertEquals(2, reversed.deletedCount());
        assertEquals(0, reversed.insertedCount());
    }

    @Test
    @DisplayName("模糊测试：随机输入下脚本可还原、行号完备、距离最短")
    void fuzzProperties() {
        Random random = new Random(20261016L);
        String[] alphabet = {"a", "b", "c", "d"};
        for (int round = 0; round < 300; round++) {
            List<String> reference = new ArrayList<>();
            List<String> target = new ArrayList<>();
            int referenceSize = random.nextInt(9);
            int targetSize = random.nextInt(9);
            for (int i = 0; i < referenceSize; i++) {
                reference.add(alphabet[random.nextInt(alphabet.length)]);
            }
            for (int i = 0; i < targetSize; i++) {
                target.add(alphabet[random.nextInt(alphabet.length)]);
            }

            assertScriptIsValid(reference, target);
            assertEquals(shortestDistance(reference, target),
                    LineDiffer.diff(reference, target).differentCount(),
                    "距离必须最短: reference=" + reference + " target=" + target);
        }
    }

    @Test
    @DisplayName("大输入（>1000 行）走有界路径：一行改动仍精确，行号按原文件偏移")
    void largeInputStaysExactWhenTheBudgetFits() {
        List<String> reference = new ArrayList<>();
        List<String> target = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            reference.add("line-" + i);
        }
        target.addAll(reference);
        target.set(100, "changed");

        DiffResult result = LineDiffer.diff(reference, target);

        assertEquals(2, result.differentCount(), "一行改动应产生 1 删 1 插");
        assertScriptIsValid(reference, target);
        List<DiffResult.Operation> changed = result.operations().stream()
                .filter(operation -> operation.kind() != DiffResult.Kind.EQUAL)
                .toList();
        assertEquals(101, changed.get(0).leftLine(), "行号必须是原文件行号（不是裁剪后的相对行号）");
        assertEquals(101, changed.get(1).rightLine(), "行号必须是原文件行号");
        assertTrue(changed.get(0).text().startsWith("line-"), changed.get(0).text());
    }
}
