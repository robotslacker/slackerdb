package org.slackerdb.dbcli.api;

/**
 * 一次 HTTP 请求的请求级失败（连接、超时、读取、编码、大小上限等）。
 */
public final class ApiException extends Exception {

    private static final long serialVersionUID = 1L;

    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
