/**
 * 脚本 → IR：本模块自有的裁剪版 SQL/命令语法（ANTLR）与访问器。
 *
 * <h2>阅读顺序</h2>
 * <ol>
 *   <li>{@code engine} 先调用 {@link org.slackerdb.dbcli.script.SqlAnalyzer#analyze(String)}；</li>
 *   <li>语法文件在 {@code src/main/antlr4/org/slackerdb/dbcli/antlr/}（{@code SQLLexer.g4} /
 *       {@code SQLParser.g4}，后两者由 {@code BaseLexer.g4}/{@code BaseParser.g4} 组合而成）；</li>
 *   <li>{@link org.slackerdb.dbcli.script.SqlIrVisitor}：把语法树变成 {@code Map} 形状的 IR；</li>
 *   <li>{@link org.slackerdb.dbcli.script.JdbcUrlConnect}：连接串解析（与 IR 分开，便于单测）；</li>
 *   <li>{@link org.slackerdb.dbcli.script.IrErrorListener}：把解析错误收敛成 {@code isFinished/errorCode}。</li>
 * </ol>
 *
 * <h2>IR 约定（维护者必读）</h2>
 * <ul>
 *   <li><b>{@code isFinished=false} 只表示"语句还没写完"</b>（缺 {@code ;}/缺行首 {@code /}）；
 *       真正的语法错误只置 {@code errorCode=1}；</li>
 *   <li>IR 的键是**字符串约定**：生产者在 {@code SqlIrVisitor}，消费者在 {@code sql}/{@code engine}/
 *       {@code compare} 等包，靠测试对齐（{@code SqlIrContractTest}）。新增/改名一个键时，
 *       搜索全部消费点；</li>
 *   <li>访问器给出的可读业务错误（{@code parserError=false}）与解析层文案是两类，引擎对它们分流处理。</li>
 * </ul>
 */
package org.slackerdb.dbcli.script;
