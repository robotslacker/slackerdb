package org.slackerdb.dbproxy.instance;

/**
 * 状态列表里的一个后端：内部实例与外部注册在这里被统一成同一张表。
 *
 * <p>刻意做成不引用 {@code PostgresProxyTarget} 的扁平结构：列表/CLI/JSON 只需要这些字段，
 * 也让 instance 包不必反向依赖 server 包。</p>
 *
 * @param alias          别名（路由键）
 * @param source         内部 / 外部
 * @param state          观察态；外部后端固定为 RUNNING（组播租约在续，代理无法进一步探活）
 * @param dataName       库名（外部后端取通告里的 name）
 * @param pid            进程号；外部后端为 null
 * @param pgPort         数据端口
 * @param mgmtPort       管理口；未启用为 -1
 * @param health         UP/DOWN/TIMEOUT/UNKNOWN；外部后端为 UNKNOWN
 * @param uptimeSeconds  运行时长；外部后端为 null
 * @param error          无法路由/无法判断时的原因
 * @param dir            内部实例目录；外部后端为 null
 * @param target         外部后端的 {@code host:port}；内部实例为 null
 */
public record BackendView(
        String alias,
        BackendSource source,
        InstanceState state,
        String dataName,
        Integer pid,
        int pgPort,
        int mgmtPort,
        String health,
        Long uptimeSeconds,
        String error,
        String dir,
        String target) {

    /** 是否允许生命周期操作（只有内部实例允许）。 */
    public boolean manageable() {
        return source == BackendSource.MANAGEMENT;
    }

    public boolean routable() {
        return state.routable() && pgPort > 0;
    }

    public static BackendView management(ObservedInstance instance) {
        return new BackendView(instance.alias(), BackendSource.MANAGEMENT, instance.state(),
                instance.dataName(), instance.pid(), instance.pgPort(), instance.mgmtPort(),
                instance.health(), instance.uptimeSeconds(), instance.error(),
                instance.dir() == null ? null : instance.dir().toString(), null);
    }

    public static BackendView external(String alias, String host, int port, int portX, String database) {
        return new BackendView(alias, BackendSource.EXTERNAL, InstanceState.RUNNING, database,
                null, port, portX, InstanceProbe.HEALTH_UNKNOWN, null, null, null, host + ":" + port);
    }

    public String healthText() {
        return health == null || health.isEmpty() ? "-" : health;
    }

    public String pidText() {
        return pid == null ? "-" : String.valueOf(pid);
    }

    public String uptimeText() {
        if (uptimeSeconds == null || uptimeSeconds < 0) {
            return "-";
        }
        long total = uptimeSeconds;
        long days = total / 86400;
        long hours = (total % 86400) / 3600;
        long minutes = (total % 3600) / 60;
        if (days > 0) {
            return days + "d" + hours + "h";
        }
        if (hours > 0) {
            return hours + "h" + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return total + "s";
    }
}
