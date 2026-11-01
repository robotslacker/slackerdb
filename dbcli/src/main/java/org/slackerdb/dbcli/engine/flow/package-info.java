/**
 * 脚本控制流：{@code _IF}/{@code _ELSEIF}/{@code _ELSE}/{@code _ENDIF} 与
 * {@code _LOOP}/{@code _ENDLOOP}。
 *
 * <p>语法层一次只解析一条语句，因此块的配对、嵌套与循环重放都在这里完成：
 * {@link org.slackerdb.dbcli.engine.flow.ControlFlow} 接收已经切分/分析的语句，
 * 建出 {@link org.slackerdb.dbcli.engine.flow.FlowNode} 树，关闭根块后解释执行。</p>
 *
 * <h2>阅读顺序</h2>
 * <ol>
 *   <li>{@link org.slackerdb.dbcli.engine.flow.ControlFlow}：捕获/建树/执行；</li>
 *   <li>{@link org.slackerdb.dbcli.engine.flow.FlowNode}：执行期节点；</li>
 *   <li>{@link org.slackerdb.dbcli.engine.flow.LoopSpec}：循环形态；</li>
 *   <li>{@link org.slackerdb.dbcli.engine.flow.FlowHost}：对引擎的最小依赖。</li>
 * </ol>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li>状态属于 {@code ScriptSession}，不是 {@code ScriptRunner}：子脚本/作业不串块；</li>
 *   <li>结构错误只报可读文案；{@code failed}/{@code discard} 帧只配对、不执行；</li>
 *   <li>循环有深度/轮数上限；每轮输出一条 {@code Loop iteration ...} 状态行。</li>
 * </ul>
 */
package org.slackerdb.dbcli.engine.flow;
