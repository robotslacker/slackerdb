/**
 * SQL 执行层：引擎与数据库之间**唯一**的接缝。
 *
 * <h2>阅读顺序</h2>
 * <ol>
 *   <li>{@link org.slackerdb.dbcli.sql.SqlExecutor}：接口即契约（不抛异常、错误码、
 *       多结果、取消/超时/自动提交）；</li>
 *   <li>{@link org.slackerdb.dbcli.sql.JdbcSqlExecutor}：JDBC 实现（连接串、会话、多结果、读行）；</li>
 *   <li>渲染：{@link org.slackerdb.dbcli.sql.ResultRenderers} →
 *       {@link org.slackerdb.dbcli.sql.SqlTableRenderer} → {@link org.slackerdb.dbcli.sql.CellRenderer}；</li>
 *   <li>{@link org.slackerdb.dbcli.sql.SqlColumnTypes}/{@link org.slackerdb.dbcli.sql.RenderOptions}：对齐与格式的判据。</li>
 * </ol>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li>{@code execute}/{@code executeAll} <b>不抛异常</b>：SQL 异常（以及驱动的运行时异常）都收敛成
 *       {@code errorCode=1} 的 {@code ResultModel}，错误原文由 {@code lastError()} 取；</li>
 *   <li>值渲染必须**稳定**（{@code BigDecimal} 不用科学计数法、时间不用平台默认格式、LOB 不打身份哈希）：
 *       stdout 要参与 golden 逐字节比对；</li>
 *   <li>会话级状态（autocommit/查询超时）属于执行层，新连接必须继承它。</li>
 * </ul>
 */
package org.slackerdb.dbcli.sql;
