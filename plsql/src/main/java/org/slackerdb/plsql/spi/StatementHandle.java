package org.slackerdb.plsql.spi;

import java.sql.SQLException;

/**
 * 一条 SQL 语句的执行句柄。
 *
 * <p>由宿主（dbserver）实现：它负责绑定参数、登记取消、审计与错误翻译；
 * 引擎只通过本接口读取结果，不得接触任何 JDBC 类型。</p>
 */
public interface StatementHandle extends AutoCloseable {

    /** 结果集向下移动一行；无结果集或已到末尾返回 false。 */
    boolean next() throws SQLException;

    /** 读取当前行第 {@code oneBasedIndex} 列（1 起）。 */
    Object get(int oneBasedIndex) throws SQLException;

    /** 结果集列数；无结果集时返回 0。 */
    int columnCount() throws SQLException;

    /** 受影响行数；无更新计数时返回 -1。 */
    long updateCount() throws SQLException;

    /** 是否产生了结果集。 */
    boolean hasResultSet();

    /** 执行时使用的原始 SQL（用于错误消息）。 */
    String sql();

    /**
     * 释放底层资源。<b>幂等</b>：可以重复调用；实现方不得抛受检异常
     * （清理失败不应掩盖业务异常）。
     */
    @Override
    void close();
}
