package org.slackerdb.dbdesk.access.http;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /capabilities} 的映射（服务端契约 S-1）。
 *
 * <p><b>为什么用 record 而不是 Map</b>：控制台要用它决定菜单/按钮的显隐（降级矩阵），
 * 散着用 Map 取字段会在每个视图里重复写 {@code get("features")}。但**未知字段必须容忍**
 * （契约 R2：字段只增不减），所以解析时按需取、缺了给默认值。</p>
 *
 * @param apiVersion        管理 API 契约版本；只剩 {@code 1} 时正常启用
 * @param serverName        实例名（{@code --data}）
 * @param serverVersion     服务端版本
 * @param pid               服务端进程号
 * @param pgPort            pgwire 端口
 * @param managementPort    管理端口
 * @param managementEnabled 管理端口是否开启
 * @param accessMode        {@code READ_WRITE} / {@code READ_ONLY}
 * @param features          功能开关
 * @param plugins           已装载插件及其贡献
 * @param contracts         各子协议的契约版本
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
        Map<String, String> contracts) {

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

    /** 本控制台是否支持该服务端的契约版本。 */
    public boolean supportedApi() {
        return apiVersion == 1;
    }

    /** 有没有 ETL 插件（决定"ETL 调度"菜单是否渲染）。 */
    public boolean hasEtl() {
        return plugins.stream().anyMatch(Plugin::etl);
    }
}
