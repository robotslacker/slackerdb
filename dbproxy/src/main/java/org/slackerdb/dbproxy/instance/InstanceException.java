package org.slackerdb.dbproxy.instance;

/**
 * 实例操作失败。
 *
 * <p>{@link #status()} 直接作为管理口的 HTTP 状态码；CLI 侧则把 message 原样回显。</p>
 */
public class InstanceException extends RuntimeException {

    public static final int BAD_REQUEST = 400;
    public static final int NOT_FOUND = 404;
    public static final int CONFLICT = 409;
    public static final int INTERNAL = 500;
    public static final int BAD_GATEWAY = 502;
    public static final int UNAVAILABLE = 503;
    public static final int GATEWAY_TIMEOUT = 504;

    private final int status;

    public InstanceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public InstanceException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
