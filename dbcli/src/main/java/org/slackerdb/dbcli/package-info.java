/**
 * 命令行层：参数解析、运行形态选择、收尾（退出码与 golden 比对）。
 *
 * <h2>阅读顺序</h2>
 * <ol>
 *   <li>{@link org.slackerdb.dbcli.Main}：进程入口（构造命令树、兜未捕获异常）；</li>
 *   <li>{@link org.slackerdb.dbcli.Cli}：picocli 命令 + 运行形态（控制台 / 脚本 / stdin / 守护）；</li>
 *   <li>{@link org.slackerdb.dbcli.RunPlan}（在哪儿跑）、
 *       {@link org.slackerdb.dbcli.ReportPlan}（log 与 golden 比对）、
 *       {@link org.slackerdb.dbcli.CliOutput}（输出策略）：三份"纯决策"对象，可单测；</li>
 *   <li>{@code engine}/{@code sql}/… ：真正的执行在那些包里，本层只做装配。</li>
 * </ol>
 *
 * <h2>两条硬约束</h2>
 * <ul>
 *   <li><b>退出码只有一处口径</b>：{@code ScriptContext.finalExitCode()}（脚本内部错误默认不改变进程退出码，
 *       见 README §9.3）。本层额外定义的是"命令行层面"的成败：用法错误 2、未捕获异常 255、崩溃 70；</li>
 *   <li><b>命令行选项按进程当前目录解析</b>，脚本里的相对路径按脚本所在目录解析——这两种基准的区分
 *       只由 {@link org.slackerdb.dbcli.RunPlan} 回答。</li>
 * </ul>
 */
package org.slackerdb.dbcli;
