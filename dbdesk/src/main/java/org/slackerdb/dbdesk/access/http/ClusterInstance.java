package org.slackerdb.dbdesk.access.http;

import java.util.List;
import java.util.Map;

/**
 * dbproxy {@code /admin/databases} 返回的一个实例。
 *
 * <p>内部实例（代理目录下扫描到的）可治理；外部实例（组播注册）只能查看，
 * 服务端也会对生命周期操作返回 409，客户端只是不展示按钮。</p>
 *
 * @param alias         路由别名
 * @param source        {@code management} / {@code external}
 * @param manageable    是否允许生命周期操作
 * @param name          数据库名
 * @param state         RUNNING / DEGRADED / STOPPED / UNKNOWN / STARTING / STOPPING
 * @param pid           进程号；外部实例为空
 * @param pgPort        数据端口，-1 表示未启用
 * @param mgmtPort      管理端口，-1 表示未启用
 * @param health        UP / DOWN / TIMEOUT / UNKNOWN
 * @param uptimeSeconds 运行时长；未知为空
 * @param error         无法路由/无法判断时的原因
 * @param dir           内部实例目录；外部实例为空
 * @param target        外部后端 {@code host:port}；内部实例为空
 * @param conf          详情接口返回的配置；列表接口为空
 */
public record ClusterInstance(
        String alias,
        String source,
        boolean manageable,
        String name,
        String state,
        Integer pid,
        int pgPort,
        int mgmtPort,
        String health,
        Long uptimeSeconds,
        String error,
        String dir,
        String target,
        Map<String, String> conf) {

    public ClusterInstance {
        conf = conf == null ? Map.of() : Map.copyOf(conf);
    }

    public boolean external() {
        return "external".equalsIgnoreCase(source);
    }

    public boolean running() {
        return "RUNNING".equalsIgnoreCase(state) || "DEGRADED".equalsIgnoreCase(state);
    }

    public boolean stopped() {
        return "STOPPED".equalsIgnoreCase(state) || "UNKNOWN".equalsIgnoreCase(state);
    }

    public boolean busyState() {
        return "STARTING".equalsIgnoreCase(state) || "STOPPING".equalsIgnoreCase(state);
    }

    public String stateText() {
        return state == null || state.isBlank() ? "UNKNOWN" : state;
    }

    public String sourceText() {
        return external() ? "external" : "management";
    }

    public String nameText() {
        return name == null || name.isBlank() ? "—" : name;
    }

    public String pidText() {
        return pid == null ? "—" : String.valueOf(pid);
    }

    public String pgPortText() {
        return pgPort > 0 ? String.valueOf(pgPort) : "—";
    }

    public String mgmtPortText() {
        return mgmtPort > 0 ? String.valueOf(mgmtPort) : "—";
    }

    public String healthText() {
        return health == null || health.isBlank() ? "—" : health;
    }

    public String locationText() {
        if (dir != null && !dir.isBlank()) {
            return dir;
        }
        if (target != null && !target.isBlank()) {
            return target;
        }
        return "—";
    }

    public String uptimeText() {
        if (uptimeSeconds == null || uptimeSeconds < 0) {
            return "—";
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

    public String errorText() {
        return error == null || error.isBlank() ? "—" : error;
    }

    /** 详情页按配置 key 排序后的行。 */
    public List<Map.Entry<String, String>> sortedConf() {
        return conf.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
    }
}
