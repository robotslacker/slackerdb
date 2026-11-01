/**
 * {@code _ASSERT}：固定形态的断言求值器与结果模型。
 *
 * <p>断言只能对**显式建模**的数据取值（{@link org.slackerdb.dbcli.assertion.ResultModel}：
 * 最近一条 SQL 的结果 + 变量快照 + 作业状态），不引入表达式引擎；语法错与断言失败都产出可读状态行。</p>
 *
 * <p>{@link org.slackerdb.dbcli.assertion.ConditionEvaluator} 把同一套固定形态求值用于
 * {@code _IF}/{@code _LOOP} 的布尔条件；它额外支持 {@code TRUE}/{@code FALSE} 常量，
 * 但不改变 {@code _ASSERT} 的状态文案契约。</p>
 */
package org.slackerdb.dbcli.assertion;
