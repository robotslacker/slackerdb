/**
 * 文件比对（{@code _COMPARE}）：选项、差分内核、报告产物（{@code .suc}/{@code .dif}/{@code .html}）。
 *
 * <h2>阅读顺序</h2>
 * <ol>
 *   <li>{@link org.slackerdb.dbcli.compare.CompareCommandDispatcher}：IR → 选项/规则 → 比对 → 结果项；</li>
 *   <li>{@link org.slackerdb.dbcli.compare.FileComparer}：读文件、应用规则、产出 {@code CompareResult}；</li>
 *   <li>{@link org.slackerdb.dbcli.compare.LineDiffer}：有界 Myers / greedy 回退；</li>
 *   <li>{@link org.slackerdb.dbcli.compare.CompareReporter}：报告行 ⟷ 三种产物；</li>
 *   <li>{@link org.slackerdb.dbcli.compare.HtmlTemplateRenderer} + {@code resources/.../htmldiff/}：HTML 报告。</li>
 * </ol>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li><b>产物是"这一次结论"的载体</b>：成功删 {@code .dif/.html}、失败删 {@code .suc}、
 *       没比成（输入缺失/异常）三种都不留——否则按产物判断结果的 CI 会读错；</li>
 *   <li>产物名由目标文件派生，因此写/删之前必须确认它没有撞上**输入文件**（否则静默数据丢失）；</li>
 *   <li>比对失败由产出侧表达成 {@code CommandOutcome.Failure}（既是状态行又是失败），
 *       不靠引擎匹配文案；</li>
 *   <li>{@code .dif} 是逐字节契约：行号格式化必须固定 {@code Locale.ROOT}。</li>
 * </ul>
 */
package org.slackerdb.dbcli.compare;
