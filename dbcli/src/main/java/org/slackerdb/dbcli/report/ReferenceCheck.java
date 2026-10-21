package org.slackerdb.dbcli.report;

import org.slackerdb.dbcli.compare.LineRules;
import org.slackerdb.dbcli.engine.ScriptOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * {@code --reference}（{@code --ref}）的收尾：停 tee → 严格 golden 比对 → 写 {@code .suc}/{@code .dif}。
 *
 * <p><b>为什么抽出来</b>：命令行（{@code Cli.finish}）与作业（{@code ScriptJobExecutor} 的
 * {@code reference} 运行选项）以前各写一份收尾逻辑，退出码规则、错误文案、报告目录语义
 * 迟早会分叉（作业那份当时就少了 {@code --report-dir} 的能力）。现在只有这一份：</p>
 * <ul>
 *   <li>结论一致 → 返回脚本自己的退出码；</li>
 *   <li>ref 不存在 → 脚本 0 时给 1（指错文件是命令行层面的失败）；</li>
 *   <li>结论不一致 → 脚本 0 时给 1，并提示差异文件位置。</li>
 * </ul>
 *
 * <p>消息走调用方给的 {@code message}：命令行是 stdout（终端），作业是自己的
 * {@code <作业名>.out}——两边都不需要知道对方的输出结构。</p>
 */
public final class ReferenceCheck {

    /**
     * 收尾结果。
     *
     * @param exitCode 合并后的退出码（脚本自己的退出码优先）
     * @param compared 是否真的做了比对（ref 不存在时为 false）
     */
    public record Outcome(int exitCode, boolean compared) {
    }

    private ReferenceCheck() {
    }

    /**
     * @param runCode         脚本自己的退出码
     * @param scriptName      脚本显示名（写进 {@code .suc}）
     * @param actualOutputFile 本次运行的输出文件（就是那条 log）
     * @param referenceFile   golden 文件（必须已存在）
     * @param reportDirectory {@code .suc}/{@code .dif} 的目录
     * @param output          脚本输出层（这里负责停 tee）
     * @param rules           脚本里声明的 mask/skip 规则（golden 比对继承它们）
     * @param message         报告行的去处（CLI 是 stdout，作业是 {@code <作业名>.out}）
     */
    public static Outcome finish(int runCode, String scriptName, Path actualOutputFile,
                                 Path referenceFile, Path reportDirectory, ScriptOutput output,
                                 LineRules rules, Consumer<String> message) {
        output.stopTee();
        if (!Files.isRegularFile(referenceFile)) {
            // 没有就是没有：不猜"这是第一次、应该放行"。要录制就自己把这次运行的 log 复制成 ref。
            message.accept("Reference file [" + referenceFile + "] does not exist.");
            return new Outcome(runCode == 0 ? 1 : runCode, false);
        }
        try {
            ReferenceReport.Comparison comparison = new ReferenceReport(scriptName, actualOutputFile,
                    referenceFile, reportDirectory, rules).write();
            if (comparison.identical()) {
                return new Outcome(runCode, true);
            }
            // 差异明细已经落在 <ref>.dif；这里只给两句：结论 + 去哪看差异
            message.accept("Compare failed: " + referenceFile);
            message.accept("See [" + comparison.diffFile() + "] for details.");
            return new Outcome(runCode == 0 ? 1 : runCode, true);
        } catch (IOException reportFailed) {
            message.accept("Cannot write the reference report for [" + referenceFile + "]: "
                    + reportFailed.getMessage());
            return new Outcome(1, true);
        }
    }
}
