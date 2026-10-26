/**
 * 脚本执行引擎：一行文本进来之后决定它是"还没写完"、"一条 SQL"、"一条命令"还是"解析失败"。
 *
 * <h2>阅读顺序</h2>
 * <ol>
 *   <li>{@link org.slackerdb.dbcli.engine.ScriptRunner}：推进 + 分派（本包的门面）；</li>
 *   <li>{@link org.slackerdb.dbcli.engine.ScriptSession}：累积缓冲与语句切分；</li>
 *   <li>{@link org.slackerdb.dbcli.engine.LexicalScanner} /
 *       {@link org.slackerdb.dbcli.engine.StatementBoundary} /
 *       {@link org.slackerdb.dbcli.engine.StatementSplitter}：唯一的词法规则与边界判定；</li>
 *   <li>{@link org.slackerdb.dbcli.engine.ScriptContext}：一次运行的会话状态（选项/变量/退出码）；</li>
 *   <li>协作对象：{@code SqlStatementExecutor}（SQL 执行与输出）、{@code CommandOutcomeRenderer}
 *       （分派结果 → 输出）、{@code ErrorReporter}（错误计数与 {@code _WHENEVER}）、
 *       {@code StatementDiagnostics}（TIMING/TIME）、{@code StatementText}（纯文本判断）；</li>
 *   <li>{@link org.slackerdb.dbcli.engine.ScriptOutput} + {@code OutputTaps}：输出通道与旁路文件。</li>
 * </ol>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li><b>stdout 是逐字节比对的产物</b>：数据/状态/诊断行走 stdout，错误也只走 stdout；
 *       语句回显与控制台转录只进日志；行尾固定 {@code \n}；</li>
 *   <li><b>解析层文案不外泄</b>：分析器认不下来的输入只会得到三类可读结果
 *       （{@code Unknown command}/{@code Incomplete command}/数据库错误原文）；</li>
 *   <li><b>命令实现一命令一类</b>，登记在 {@link org.slackerdb.dbcli.engine.command.CommandCatalog}。</li>
 * </ul>
 */
package org.slackerdb.dbcli.engine;
