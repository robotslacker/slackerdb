package org.slackerdb.dbdesk.access.http;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /capabilities} 的映射（服务端契约 S-1）。
 *
 * <p>dbserver 与 dbproxy 都返回这个端点，但字段不同：实例有 {@code server/features}，
 * 代理有 {@code role/provisioning/managementForwarding}。解析时全部按“缺字段给默认值”处理，
 * 客户端据此判断当前管理口到底指向谁。</p>
 *
 * @param apiVersion            管理 API 契约版本
 * @param serverName            实例名（dbserver 才有）
 * @param serverVersion         服务端版本（dbserver 才有）
 * @param pid                   服务端进程号（dbserver 才有）
 * @param pgPort                pgwire 端口
 * @param managementPort        管理端口
 * @param managementEnabled     管理端口是否开启
 * @param accessMode            {@code READ_WRITE} / {@code READ_ONLY}
 * @param features              功能开关
 * @param plugins               已装载插件及其贡献
 * @param contracts             各子协议的契约版本
 * @param role                  代理身份声明；dbproxy 为 {@code proxy}，dbserver 为 {@code null}
 * @param provisioning          代理的实例管理能力；dbserver 为 {@code null}
 * @param managementForwarding  代理是否声明了 {@code /<alias>} 管理转发
 */
public record Capabilities(
        int apiVersion,
        String serverName,
        String serverVersion,
        long pid,
        int pgPort,
        int managementPort,
        boolean managementEnabled,
        String accessMode,
        Features features,
        List<Plugin> plugins,
        Map<String, String> contracts,
        String role,
        Provisioning provisioning,
        boolean managementForwarding) {

    /** 功能开关（S-1 的 {@code features}）。 */
    public record Features(boolean dataService, boolean mcp, boolean plugins, boolean sqlRepl,
                           boolean copyOut, boolean sqlHistory, boolean apiHistory,
                           boolean definitions, boolean aiChat, boolean backupTasks,
                           boolean logTail) {
    }

    /**
     * 插件条目。
     *
     * @param etl 是否贡献了 ETL 能力（来自 {@code contributes.capabilities.etl} 或
     *            {@code contributes.etl}）—— 菜单显隐只看这个，**不看插件 id 字符串**
     */
    public record Plugin(String id, String state, boolean etl) {
    }

    /**
     * dbproxy 的实例管理声明。
     *
     * <p>DbDesk 不根据 {@code enabled} 做特殊分支：代理没配 {@code data_dir} 时，
     * 后续操作由代理返回错误，界面按通用错误展示。</p>
     */
    public record Provisioning(boolean enabled, String mode, String adminBase,
                               List<String> features, List<String> backendSources) {
    }

    /**
     * 当前管理口是不是 dbproxy。
     *
     * <p>优先看显式的 {@code role=proxy}；老代理可能没有这个字段，则用
     * {@code provisioning}/{@code managementForwarding} 是否存在兜底。</p>
     */
    public boolean isProxy() {
        return "proxy".equalsIgnoreCase(role) || provisioning != null || managementForwarding;
    }

    /** 本控制台是否支持该服务端的契约版本。 */
    public boolean supportedApi() {
        return apiVersion == 1;
    }

    /** 有没有 ETL 插件（决定“ETL 调度”菜单是否渲染）。 */
    public boolean hasEtl() {
        return plugins.stream().anyMatch(Plugin::etl);
    }
}
