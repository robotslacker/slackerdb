package org.slackerdb.dbproxy.instance;

import java.nio.file.Path;

/**
 * 一次探测得到的实例快照。
 *
 * @param alias          别名（= 目录名）
 * @param dir            实例目录
 * @param state          观察态
 * @param dataName       conf 里的 {@code data=}，可能为空（配置损坏时）
 * @param pid            从 pid 文件读到且通过命令行校验的进程号；未知时为 {@code null}
 * @param pgPort         PG 端口，非法时为 -1
 * @param mgmtPort       管理口端口，未启用时为 -1
 * @param health         {@code UP} / {@code DOWN} / {@code TIMEOUT} / {@code UNKNOWN}
 * @param uptimeSeconds  进程启动时长；未知时为 {@code null}
 * @param error          无法路由/无法判断时的原因
 * @param conf           解析后的配置；读不出来时为 {@code null}
 */
public record ObservedInstance(
        String alias,
        Path dir,
        InstanceState state,
        String dataName,
        Integer pid,
        int pgPort,
        int mgmtPort,
        String health,
        Long uptimeSeconds,
        String error,
        InstanceConf conf) {

    public boolean routable() {
        return state.routable() && pgPort > 0 && dataName != null && !dataName.isEmpty();
    }

    public boolean running() {
        return state == InstanceState.RUNNING;
    }

    public String healthText() {
        return health == null || health.isEmpty() ? "-" : health;
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

    public String pidText() {
        return pid == null ? "-" : String.valueOf(pid);
    }
}
