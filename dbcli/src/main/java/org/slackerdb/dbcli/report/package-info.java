/**
 * golden 比对收尾（{@code --reference}）：退出码规则与 {@code .suc}/{@code .dif} 产物的**唯一**实现。
 *
 * <p>{@link org.slackerdb.dbcli.report.ReferenceCheck} 同时被命令行与作业收尾调用，
 * 因此"脚本退出码优先、比对不一致给 1"这条规则只有一处。</p>
 */
package org.slackerdb.dbcli.report;
