/**
 * 交互式控制台：取行来源与控制台循环。
 *
 * <ul>
 *   <li>{@link org.slackerdb.dbcli.repl.LineSource}：取行接口（测试注入"预置输入行"的接缝）；</li>
 *   <li>{@link org.slackerdb.dbcli.repl.JLineLineSource}：JLine 实现（行编辑/历史/中断）；</li>
 *   <li>{@link org.slackerdb.dbcli.repl.InteractiveConsole}：控制台循环（把每一行喂给
 *       {@code engine.ScriptSession}，与批处理共用同一条执行路径）。</li>
 * </ul>
 *
 * <p>控制台没有自己的输入语法：一条语句只在顶层 {@code ;}、行首 {@code /} 或单行 {@code _} 命令处收尾，
 * 与批处理完全一致。</p>
 */
package org.slackerdb.dbcli.repl;
