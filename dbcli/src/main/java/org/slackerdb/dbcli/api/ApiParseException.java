package org.slackerdb.dbcli.api;

/**
 * {@code _API} 参数解析错误：消息直接给使用者看，不带 ANTLR 位置文案。
 */
public final class ApiParseException extends Exception {

    private static final long serialVersionUID = 1L;

    public ApiParseException(String message) {
        super(message);
    }

    public ApiParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
