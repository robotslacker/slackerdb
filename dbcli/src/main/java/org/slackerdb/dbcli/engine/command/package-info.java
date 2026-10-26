/**
 * {@code _} 命令实现（{@code _SET}/{@code _ASSERT}/{@code _SPOOL}/…）：一命令一类，只认 IR 与
 * {@link org.slackerdb.dbcli.engine.command.CommandCall}。
 *
 * <h2>新增一条命令要改哪里</h2>
 * <ol>
 *   <li>语法：{@code src/main/antlr4/.../BaseParser.g4}（+ 词法 mode）；</li>
 *   <li>IR：{@code script.SqlIrVisitor} 的对应 {@code visitXxx}；</li>
 *   <li>实现：本包新建一个 {@link org.slackerdb.dbcli.engine.command.ScriptCommand}；</li>
 *   <li>登记：{@link org.slackerdb.dbcli.engine.command.CommandCatalog}；</li>
 *   <li>文案：{@code help.HelpCatalog}（与 README 同源，测试会互相盯着）。</li>
 * </ol>
 *
 * <p>命令不持有引擎状态：会话状态从 {@code CommandCall.context()} 取，报错走 {@code CommandCall.reportError}
 * ——这样同一份实现能在父脚本、作业线程与单测里跑。</p>
 */
package org.slackerdb.dbcli.engine.command;
