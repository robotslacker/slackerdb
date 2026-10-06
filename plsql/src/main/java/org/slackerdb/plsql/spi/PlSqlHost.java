package org.slackerdb.plsql.spi;

import java.sql.SQLException;
import java.util.List;

/**
 * PL/SQL 引擎与宿主之间的契约。
 *
 * <p>引擎<b>只能</b>通过本接口访问数据库：这样 dbserver 才能继续拥有
 * SQL 改写、取消登记、审计、事务状态与会话可观测性等横切能力，
 * 而 plsql 模块保持"后端无关 + 可离线单测"（{@link DefaultJdbcHost}）。</p>
 */
public interface PlSqlHost {

    /**
     * 执行一条 SQL（DML/DDL/SELECT 均可）。
     *
     * @param sql   已经用 {@code ?} 占位过绑定参数的 SQL 文本
     * @param binds 绑定值，顺序与 {@code ?} 一致；可为 null/空
     */
    StatementHandle execute(String sql, List<Object> binds) throws SQLException;

    /**
     * 求值标量表达式，等价于执行 {@code SELECT <sqlExpression>} 并返回第一行第一列。
     *
     * @param sqlExpression 不含前导 {@code SELECT} 的 SQL 表达式
     */
    Object evaluate(String sqlExpression, List<Object> binds) throws SQLException;

    /**
     * 协作式取消检查点：引擎在每条语句前、每次循环回边调用。
     * 实现方发现"本会话已被取消"时抛出 {@link PlSqlCanceledException}。
     */
    void checkInterrupted() throws PlSqlCanceledException;

    /** 可选：向客户端发送 Notice（P7 起由 {@code RAISE NOTICE} 使用）。 */
    default void notice(String message) {
    }

    /** 可选：调试日志。 */
    default void debug(String format, Object... args) {
    }
}
