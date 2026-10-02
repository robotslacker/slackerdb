package org.slackerdb.plsql.spi;

/**
 * 会话被 {@code CancelRequest} / {@code KILL SESSION} 取消时由宿主抛出，
 * 由引擎向上传播，最终映射为 SQLSTATE {@code 57014}。
 *
 * <p>这是<b>控制流</b>异常，不是错误：引擎不得把它当成普通 SQL 错误吞掉。</p>
 */
public class PlSqlCanceledException extends RuntimeException {

    public PlSqlCanceledException(String message) {
        super(message);
    }

    public PlSqlCanceledException(String message, Throwable cause) {
        super(message, cause);
    }
}
