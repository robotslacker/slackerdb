package org.slackerdb.dbcli.ssh;

/**
 * SSH 连接参数（对应 {@code _SSH connect <host> with user <user> [password <pwd>] [keyfile <key>]}）。
 *
 * <p>字段名与 IR 的键名一一对应（大小写按 {@code keyFile}，IR 的键名就是那样）。</p>
 *
 * @param host     主机名或 IP（IR 里的 {@code host}）
 * @param port     端口，默认 22
 * @param user     用户名（IR 里的 {@code user}）
 * @param password 口令；与 {@code keyFile} 互斥（IR 里两者也互斥）
 * @param keyFile  私钥文件路径；与 {@code password} 互斥
 */
public record SshConnectOptions(String host, int port, String user, String password, String keyFile) {

    /** 默认 SSH 端口。 */
    public static final int DEFAULT_PORT = 22;

    public SshConnectOptions {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("SSH host 不能为空");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("SSH user 不能为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("SSH 端口不合法: " + port);
        }
    }

    public static SshConnectOptions password(String host, String user, String password) {
        return new SshConnectOptions(host, DEFAULT_PORT, user, password, null);
    }

    public static SshConnectOptions keyFile(String host, String user, String keyFilePath) {
        return new SshConnectOptions(host, DEFAULT_PORT, user, null, keyFilePath);
    }

    public SshConnectOptions withPort(int newPort) {
        return new SshConnectOptions(host, newPort, user, password, keyFile);
    }

    /** 供日志/错误提示用（不含口令、不含私钥内容）。 */
    public String describe() {
        return user + "@" + host + ":" + port + (keyFile != null ? " (keyfile)" : " (password)");
    }
}
