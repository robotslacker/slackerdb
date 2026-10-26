package org.slackerdb.common.profiles;

/**
 * 契约 C-4：一条连接配置。{@code dbcli} 与控制台共用同一个模型与同一个文件。
 *
 * <p>字段刻意是"主机/端口/库名"而不是 JDBC URL：URL 的形态随驱动变，
 * 而 host/port/database 是所有客户端都能理解的最小集合；需要 URL 时用
 * {@link #jdbcUrl()} 统一拼（只支持 pgwire 这一种，拼错了能立刻看出来）。</p>
 *
 * @param name      显示名（唯一，upsert 的键）
 * @param kind      类型，目前只有 {@code server}
 * @param host      pgwire 主机
 * @param port      pgwire 端口
 * @param portX     管理口端口（0 = 没有管理口）
 * @param database  默认库
 * @param user      用户名（可为空；口令**不落盘**）
 * @param options   额外连接参数（原样拼到 URL 的查询串）
 */
public record ConnectionProfile(String name, String kind, String host, int port, int portX,
                                String database, String user, java.util.Map<String, String> options) {

    public static final String KIND_SERVER = "server";

    public ConnectionProfile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("connection name must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("database must not be blank");
        }
        name = name.strip();
        host = host.strip();
        kind = kind == null || kind.isBlank() ? KIND_SERVER : kind.strip();
        user = user == null ? "" : user.strip();
        options = options == null ? java.util.Map.of() : java.util.Map.copyOf(options);
    }

    /** 便捷构造：默认 kind=server、无额外参数。 */
    public static ConnectionProfile of(String name, String host, int port, int portX, String database, String user) {
        return new ConnectionProfile(name, KIND_SERVER, host, port, portX, database, user, java.util.Map.of());
    }

    /** pgwire JDBC URL。 */
    public String jdbcUrl() {
        StringBuilder url = new StringBuilder("jdbc:postgresql://").append(host).append(':').append(port)
                .append('/').append(database);
        String separator = "?";
        for (java.util.Map.Entry<String, String> option : options.entrySet()) {
            if (option.getKey() == null || option.getKey().isBlank()) {
                continue;
            }
            url.append(separator).append(option.getKey()).append('=').append(option.getValue());
            separator = "&";
        }
        return url.toString();
    }

    /** 管理口地址；{@code portX<=0} 时返回空串（表示"不管管理面"）。 */
    public String managementUrl() {
        return portX <= 0 ? "" : "http://" + host + ":" + portX;
    }
}
