package org.slackerdb.dbdesk.access.http;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /status} 的完整快照（管理口 S-2）。
 *
 * <p>字段按"只增不减"解析：缺失字段给默认值，未知字段忽略。参数与 usage 保持为
 * {@code Map<String,Object>}，因为服务端会持续增加指标，视图只需要通用键值展示。</p>
 */
public record ServerStatus(
        Server server,
        Database database,
        Map<String, Object> parameters,
        Map<String, Object> usage,
        List<String> extensions,
        List<PluginInfo> plugins,
        List<SessionInfo> sessions,
        String error) {

    public record Server(
            String status,
            String version,
            String build,
            long pid,
            String now,
            String bootTime,
            String runTime) {
    }

    public record Database(
            String version,
            String size,
            String memoryUsage,
            String walSize) {
    }

    public record PluginInfo(
            String id,
            String filename,
            String filepath,
            String state,
            String version,
            String description,
            String mountTime) {
    }

    public boolean up() {
        return server != null && "RUNNING".equalsIgnoreCase(server.status());
    }
}
