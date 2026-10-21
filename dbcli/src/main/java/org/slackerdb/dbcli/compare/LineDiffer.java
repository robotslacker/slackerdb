package org.slackerdb.dbcli.compare;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * 行级差分：**只有一种算法**（Myers），没有可选项。
 *
 * <p>为什么只留一个：{@code _COMPARE} 的算法名以前有 {@code AUTO|LCS|MYERS|DIFFLIB} 四个取值，
 * 其中 {@code AUTO} 等价 {@code MYERS}、{@code DIFFLIB} 用 LCS 实现——实际只有两套代码，
 * 而"用哪套"对使用者要的结论（两份输出一不一样、差在哪几行）没有任何影响。
 * 因此这里只保留默认路径用的 Myers（{@code O((n+m)·d)}，差异少时远快于 LCS 的 {@code O(n·m)}），
 * 并**不提供**选择算法的开关。</p>
 *
 * <p><b>相等语义是参数</b>（{@link BiPredicate}）：{@code _COMPARE} 需要"参考行当正则"这种
 * 非对称比较，所以算法本身不假设 {@code String.equals}。默认重载用整串相等。</p>
 *
 * <h2>内存上限（为什么必须有 "大输入" 这条分支）</h2>
 * <p>Myers 的轨迹是 {@code O(d·(n+m))} 个 int：完全不同的两份文件里 {@code d ≈ n+m}，
 * 于是最坏 {@code O((n+m)²)}。实测 8 000 行 × 8 000 行全不同即 OOM（见
 * {@code docs/dbcli-assessment.md} 附录 A-2），而"几万行 dump 的回归比对"正是本工具的主要用例。</p>
 *
 * <p>因此 <b>两侧总行数超过 {@value #LARGE_INPUT_LINES} 时</b>走"有界"路径：</p>
 * <ol>
 *   <li><b>裁剪</b>公共前缀/公共后缀（两侧相同的那一段永远可以匹配，等价性不受影响）；</li>
 *   <li>剩下的中段先试 Myers，但给轨迹一个**硬预算**（{@value #MAX_TRACE_CELLS} 个 int ≈ 32 MB），
 *       超预算立刻放弃——"差异很少的大文件"因此仍得到精确（最短）编辑脚本；</li>
 *   <li>预算不够时退回 {@link #greedy} 的**窗口重同步**算法：内存 O(1)、时间 O((n+m)·window)，
 *       结果通常不是最短编辑脚本，但差异位置足够准确，而且**一定能算完**。</li>
 * </ol>
 *
 * <p><b>小输入（≤ {@value #LARGE_INPUT_LINES} 行）完全不进这条分支</b>：一次 Myers 求最短编辑脚本，
 * 结果与以前的默认路径逐字节一致。</p>
 */
public final class LineDiffer {

    /**
     * Myers 轨迹允许的最大 int 数（约 8M 个 ≈ 32 MB）。
     *
     * <p>轨迹是 {@code (d+1)} 份长度为 {@code 2(n+m)+1} 的快照。预算决定"差异多到什么程度就放弃精确算法"：
     * 20 000 × 20 000 行时一份快照是 80 001 个 int，于是 d 超过约 100 就退回贪心——
     * 那正是"完全不同的两份大文件"的形状，也是原本会 OOM 的形状。</p>
     */
    private static final long MAX_TRACE_CELLS = 8_000_000L;

    /**
     * 走"有界路径"的总行数阈值（两侧行数之和）。
     *
     * <p>取 1 000 是为了让精确算法的**最坏**内存仍在 24 MB 以内，同时保证所有既有小输入测试的行为不变。</p>
     */
    private static final int LARGE_INPUT_LINES = 1_000;

    /** 贪心回退的重同步窗口：向后看这么多行，找"下一处能对齐的位置"。 */
    private static final int GREEDY_WINDOW = 16;

    private LineDiffer() {
    }

    /** 用整串相等比较。 */
    public static DiffResult diff(List<String> reference, List<String> target) {
        return diff(reference, target, String::equals);
    }

    /**
     * 计算差分。
     *
     * @param reference 参考侧行
     * @param target    目标侧行
     * @param equal     相等判定：{@code test(referenceLine, targetLine)}；参考侧在前（mask 需要这个方向）
     */
    public static DiffResult diff(List<String> reference, List<String> target,
                                  BiPredicate<String, String> equal) {
        if ((long) reference.size() + target.size() <= LARGE_INPUT_LINES) {
            // 小输入：一次 Myers，最短编辑脚本
            return myers(reference, target, equal);
        }
        return diffBounded(reference, target, equal);
    }

    // ------------------------------------------------------------------ 有界路径

    /**
     * 大输入的有界路径：裁剪 + 预算内 Myers + 贪心回退（见类注释）。
     *
     * <p>行号在拼回去时按两侧各自的偏移量平移，因此调用方看到的仍是**原文件行号**。</p>
     */
    private static DiffResult diffBounded(List<String> reference, List<String> target,
                                          BiPredicate<String, String> equal) {
        int prefix = commonPrefix(reference, target, equal);
        int suffix = commonSuffix(reference, target, equal, prefix);
        int referenceEnd = reference.size() - suffix;
        int targetEnd = target.size() - suffix;

        List<DiffResult.Operation> operations = new ArrayList<>(reference.size() + target.size());
        for (int index = 0; index < prefix; index++) {
            operations.add(new DiffResult.Operation(DiffResult.Kind.EQUAL, index + 1, index + 1,
                    reference.get(index)));
        }
        diffMiddle(reference.subList(prefix, referenceEnd), target.subList(prefix, targetEnd),
                equal, prefix, prefix, operations);
        for (int index = 0; index < suffix; index++) {
            operations.add(new DiffResult.Operation(DiffResult.Kind.EQUAL,
                    referenceEnd + index + 1, targetEnd + index + 1, reference.get(referenceEnd + index)));
        }
        return new DiffResult(operations);
    }

    /** 两侧中段对齐：预算内 Myers，否则贪心；行号按 {@code referenceOffset/targetOffset} 平移。 */
    private static void diffMiddle(List<String> reference, List<String> target,
                                   BiPredicate<String, String> equal, int referenceOffset,
                                   int targetOffset, List<DiffResult.Operation> operations) {
        int referenceCount = reference.size();
        int targetCount = target.size();
        if (referenceCount == 0) {
            for (int index = 0; index < targetCount; index++) {
                operations.add(new DiffResult.Operation(DiffResult.Kind.INSERT, -1,
                        targetOffset + index + 1, target.get(index)));
            }
            return;
        }
        if (targetCount == 0) {
            for (int index = 0; index < referenceCount; index++) {
                operations.add(new DiffResult.Operation(DiffResult.Kind.DELETE,
                        referenceOffset + index + 1, -1, reference.get(index)));
            }
            return;
        }
        DiffResult precise = myers(reference, target, equal, MAX_TRACE_CELLS);
        if (precise != null) {
            appendShifted(precise, referenceOffset, targetOffset, operations);
            return;
        }
        appendShifted(greedy(reference, target, equal, GREEDY_WINDOW), referenceOffset, targetOffset,
                operations);
    }

    /** 公共前缀长度（用同一相等判定：大小写/trim/mask 的语义因此完全一致）。 */
    private static int commonPrefix(List<String> reference, List<String> target,
                                    BiPredicate<String, String> equal) {
        int limit = Math.min(reference.size(), target.size());
        int length = 0;
        while (length < limit && equal.test(reference.get(length), target.get(length))) {
            length++;
        }
        return length;
    }

    /** 公共后缀长度（不与前缀重叠：{@code prefix} 之后最多到较短一侧的末尾）。 */
    private static int commonSuffix(List<String> reference, List<String> target,
                                    BiPredicate<String, String> equal, int prefix) {
        int limit = Math.min(reference.size(), target.size()) - prefix;
        int length = 0;
        while (length < limit
                && equal.test(reference.get(reference.size() - 1 - length),
                target.get(target.size() - 1 - length))) {
            length++;
        }
        return length;
    }

    /** 把"相对行号"的编辑脚本按偏移量平移成"原文件行号"。 */
    private static void appendShifted(DiffResult diff, int referenceOffset, int targetOffset,
                                      List<DiffResult.Operation> operations) {
        for (DiffResult.Operation operation : diff.operations()) {
            operations.add(new DiffResult.Operation(operation.kind(),
                    operation.leftLine() < 0 ? -1 : operation.leftLine() + referenceOffset,
                    operation.rightLine() < 0 ? -1 : operation.rightLine() + targetOffset,
                    operation.text()));
        }
    }

    /**
     * 窗口重同步的贪心差分（内存 O(1)，时间 O((n+m)·window)）。
     *
     * <p>做法：两侧逐行对齐；不相等时向后看 {@code window} 行，最早出现的"能重新对齐"的位置
     * （参考侧跳过 d 行 / 目标侧跳过 d 行）决定删或插；窗口内找不到就当成一行替换。
     * 结果不是最短编辑脚本，但差异位置足够准确，而且**任何输入都算得完**——这正是大文件需要的性质。</p>
     */
    static DiffResult greedy(List<String> reference, List<String> target,
                             BiPredicate<String, String> equal, int window) {
        List<DiffResult.Operation> operations = new ArrayList<>(reference.size() + target.size());
        int referenceIndex = 0;
        int targetIndex = 0;
        while (referenceIndex < reference.size() && targetIndex < target.size()) {
            if (equal.test(reference.get(referenceIndex), target.get(targetIndex))) {
                operations.add(new DiffResult.Operation(DiffResult.Kind.EQUAL,
                        referenceIndex + 1, targetIndex + 1, reference.get(referenceIndex)));
                referenceIndex++;
                targetIndex++;
                continue;
            }
            int skipReference = 0;
            int skipTarget = 0;
            for (int distance = 1; distance <= window; distance++) {
                if (referenceIndex + distance < reference.size()
                        && equal.test(reference.get(referenceIndex + distance), target.get(targetIndex))) {
                    skipReference = distance;
                    break;
                }
                if (targetIndex + distance < target.size()
                        && equal.test(reference.get(referenceIndex), target.get(targetIndex + distance))) {
                    skipTarget = distance;
                    break;
                }
            }
            if (skipReference > 0) {
                for (int offset = 0; offset < skipReference; offset++) {
                    operations.add(new DiffResult.Operation(DiffResult.Kind.DELETE,
                            referenceIndex + offset + 1, -1, reference.get(referenceIndex + offset)));
                }
                referenceIndex += skipReference;
            } else if (skipTarget > 0) {
                for (int offset = 0; offset < skipTarget; offset++) {
                    operations.add(new DiffResult.Operation(DiffResult.Kind.INSERT, -1,
                            targetIndex + offset + 1, target.get(targetIndex + offset)));
                }
                targetIndex += skipTarget;
            } else {
                operations.add(new DiffResult.Operation(DiffResult.Kind.DELETE,
                        referenceIndex + 1, -1, reference.get(referenceIndex)));
                operations.add(new DiffResult.Operation(DiffResult.Kind.INSERT, -1,
                        targetIndex + 1, target.get(targetIndex)));
                referenceIndex++;
                targetIndex++;
            }
        }
        while (referenceIndex < reference.size()) {
            operations.add(new DiffResult.Operation(DiffResult.Kind.DELETE,
                    referenceIndex + 1, -1, reference.get(referenceIndex)));
            referenceIndex++;
        }
        while (targetIndex < target.size()) {
            operations.add(new DiffResult.Operation(DiffResult.Kind.INSERT, -1,
                    targetIndex + 1, target.get(targetIndex)));
            targetIndex++;
        }
        return new DiffResult(operations);
    }

    // ------------------------------------------------------------------ Myers

    /** 无预算上限的 Myers（小输入路径；大输入请走带预算的重载）。 */
    static DiffResult myers(List<String> reference, List<String> target, BiPredicate<String, String> equal) {
        return myers(reference, target, equal, Long.MAX_VALUE);
    }

    /**
     * Myers 的 O((n+m)·d) 贪心算法（带轨迹回溯），并在轨迹超过 {@code maxTraceCells} 时**放弃**。
     *
     * <p>实现要点：{@code v[k]} 记录对角线 k 上走到的最远 x；每一轮 d 保存 {@code v} 的快照，
     * 回溯时按快照反推出编辑脚本。</p>
     *
     * @param maxTraceCells 轨迹预算（int 个数）；{@link Long#MAX_VALUE} 表示不限
     * @return 编辑脚本；轨迹超出预算时返回 <b>null</b>（调用方据此退回贪心算法）
     */
    static DiffResult myers(List<String> reference, List<String> target, BiPredicate<String, String> equal,
                            long maxTraceCells) {
        int n = reference.size();
        int m = target.size();
        int max = n + m;
        if (max == 0) {
            return new DiffResult(new ArrayList<>());
        }
        List<int[]> trace = new ArrayList<>();
        int[] v = new int[2 * max + 1];
        int offset = max;
        int foundD = -1;
        for (int d = 0; d <= max; d++) {
            // 先检查预算再快照：否则"完全不同的两份大文件"会在这里一次性分配 (n+m)² 个 int
            if (d > 0 && (long) (d + 1) * v.length > maxTraceCells) {
                return null;
            }
            int[] snapshot = v.clone();
            trace.add(snapshot);
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    x = v[offset + k + 1];
                } else {
                    x = v[offset + k - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && equal.test(reference.get(x), target.get(y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    foundD = d;
                    break;
                }
            }
            if (foundD >= 0) {
                break;
            }
        }
        if (foundD < 0) {
            // 理论上不可能（d 到 n+m 一定能到终点），但预算/边界异常时宁可退回贪心也不返回半成品
            return null;
        }
        // 回溯：从 (n, m) 沿轨迹倒推
        List<DiffResult.Operation> reverse = new ArrayList<>();
        int x = n;
        int y = m;
        for (int d = foundD; d > 0; d--) {
            int[] previous = trace.get(d);
            int k = x - y;
            int previousK;
            if (k == -d || (k != d && previous[offset + k - 1] < previous[offset + k + 1])) {
                previousK = k + 1;
            } else {
                previousK = k - 1;
            }
            int previousX = previous[offset + previousK];
            int previousY = previousX - previousK;
            // 先记录"走过的对角线"（相等行）
            while (x > previousX && y > previousY) {
                reverse.add(new DiffResult.Operation(DiffResult.Kind.EQUAL, x, y, reference.get(x - 1)));
                x--;
                y--;
            }
            if (x == previousX) {
                // 向下走 = 插入 target 的一行
                reverse.add(new DiffResult.Operation(DiffResult.Kind.INSERT, -1, y, target.get(y - 1)));
                y--;
            } else {
                // 向右走 = 删除 reference 的一行
                reverse.add(new DiffResult.Operation(DiffResult.Kind.DELETE, x, -1, reference.get(x - 1)));
                x--;
            }
        }
        while (x > 0 && y > 0 && equal.test(reference.get(x - 1), target.get(y - 1))) {
            reverse.add(new DiffResult.Operation(DiffResult.Kind.EQUAL, x, y, reference.get(x - 1)));
            x--;
            y--;
        }
        while (x > 0) {
            reverse.add(new DiffResult.Operation(DiffResult.Kind.DELETE, x, -1, reference.get(x - 1)));
            x--;
        }
        while (y > 0) {
            reverse.add(new DiffResult.Operation(DiffResult.Kind.INSERT, -1, y, target.get(y - 1)));
            y--;
        }
        java.util.Collections.reverse(reverse);
        return new DiffResult(reverse);
    }
}
