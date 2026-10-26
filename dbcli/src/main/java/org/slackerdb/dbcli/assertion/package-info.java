/**
 * {@code _ASSERT}：固定形态的断言求值器与结果模型。
 *
 * <p>断言只能对**显式建模**的数据取值（{@link org.slackerdb.dbcli.assertion.ResultModel}：
 * 最近一条 SQL 的结果 + 变量快照 + 作业状态），不引入表达式引擎；语法错与断言失败都产出可读状态行。</p>
 */
package org.slackerdb.dbcli.assertion;
