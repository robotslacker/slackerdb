package org.slackerdb.dbdesk.access.data;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条已注册的数据服务定义。
 *
 * <p>服务端列表接口历史上有两个键名：{@code parameter}（对象）和 {@code parameters}
 * （数组）。这里统一成 {@code parameters}，客户端两种都读，避免把服务端历史包袱带进 UI。</p>
 */
public record DataServiceDefinition(
        String serviceName,
        String serviceVersion,
        String category,
        String serviceType,
        String sql,
        String description,
        String searchPath,
        String snapshotLimit,
        List<Parameter> parameters) {

    public record Parameter(String name, String defaultValue) {
    }

    public DataServiceDefinition {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public String key() {
        return (serviceType == null ? "" : serviceType) + "#" + serviceName + "#" + serviceVersion;
    }

    public static DataServiceDefinition fromJson(JsonNode node) {
        List<Parameter> parameters = new ArrayList<>();
        JsonNode array = node.path("parameters");
        if (array.isArray()) {
            for (JsonNode item : array) {
                parameters.add(new Parameter(text(item, "name"), text(item, "defaultValue")));
            }
        }
        JsonNode object = node.path("parameter");
        if (object.isObject()) {
            object.properties().forEach(entry ->
                    parameters.add(new Parameter(entry.getKey(), entry.getValue().asText())));
        }
        return new DataServiceDefinition(
                text(node, "serviceName"),
                text(node, "serviceVersion"),
                text(node, "category"),
                text(node, "serviceType"),
                text(node, "sql"),
                text(node, "description"),
                text(node, "searchPath"),
                text(node, "snapshotLimit"),
                parameters);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
