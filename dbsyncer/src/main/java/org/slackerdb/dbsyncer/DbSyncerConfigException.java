package org.slackerdb.dbsyncer;

/**
 * 配置错误：宿主必须把它变成"拒绝启动"，而不是静默降级。
 */
public class DbSyncerConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String path;

    public DbSyncerConfigException(String path, String message) {
        super((path == null || path.isBlank() ? "" : path + ": ") + message);
        this.path = path;
    }

    public DbSyncerConfigException(String path, String message, Throwable cause) {
        super((path == null || path.isBlank() ? "" : path + ": ") + message, cause);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
