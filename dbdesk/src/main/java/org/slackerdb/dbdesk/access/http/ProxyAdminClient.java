package org.slackerdb.dbdesk.access.http;

import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dbproxy 实例管理客户端（{@code /admin/databases...}）。
 *
 * <p>只认识代理的管理 API，不掺实例级 dbserver 的能力。传输、超时、错误信封复用
 * {@link ManagementClient} 的包内方法，避免两套 HttpClient。</p>
 */
public final class ProxyAdminClient implements AutoCloseable {

    private static final Duration LIST_FRESH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LIST_CACHED_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DETAIL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MUTATION_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(35);

    private final ManagementClient transport;

    public ProxyAdminClient(String baseUrl, Duration timeout) {
        this.transport = new ManagementClient(baseUrl, timeout);
    }

    /** 列表；{@code probe=true} 让代理实时探测每个实例，{@code false} 读代理缓存。 */
    public List<ClusterInstance> databases(boolean probe) throws ManagementException {
        JsonNode root = transport.getJson("/admin/databases?probe=" + probe,
                probe ? LIST_FRESH_TIMEOUT : LIST_CACHED_TIMEOUT);
        JsonNode array = root.path("databases");
        List<ClusterInstance> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                result.add(parse(item));
            }
        }
        result.sort(Comparator.comparing(ClusterInstance::alias,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return List.copyOf(result);
    }

    /** 详情；内部实例的 {@code conf} 也会返回。 */
    public ClusterInstance detail(String alias) throws ManagementException {
        JsonNode root = transport.getJson("/admin/databases/" + encodePathSegment(alias), DETAIL_TIMEOUT);
        return parse(root);
    }

    /** 创建实例；默认创建后立即启动。 */
    public ClusterInstance create(String alias, String name, boolean start) throws ManagementException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("alias", alias);
        if (name != null && !name.isBlank()) {
            body.put("name", name.strip());
        }
        body.put("start", start);
        JsonNode root = transport.postJson("/admin/databases", body, MUTATION_TIMEOUT);
        return parse(root);
    }

    public ClusterInstance start(String alias) throws ManagementException {
        JsonNode root = transport.postJson("/admin/databases/" + encodePathSegment(alias) + "/start",
                Map.of(), MUTATION_TIMEOUT);
        return parse(root);
    }

    public ClusterInstance shutdown(String alias) throws ManagementException {
        JsonNode root = transport.postJson("/admin/databases/" + encodePathSegment(alias) + "/shutdown",
                Map.of(), STOP_TIMEOUT);
        return parse(root);
    }

    /** 销毁内部实例；外部实例由服务端返回 409。 */
    public void destroy(String alias) throws ManagementException {
        transport.deleteJson("/admin/databases/" + encodePathSegment(alias), MUTATION_TIMEOUT);
    }

    private static ClusterInstance parse(JsonNode node) {
        Integer pid = node.path("pid").isNumber() ? node.path("pid").asInt() : null;
        Long uptime = node.path("uptimeSeconds").isNumber() ? node.path("uptimeSeconds").asLong() : null;
        Map<String, String> conf = new LinkedHashMap<>();
        JsonNode confNode = node.path("conf");
        if (confNode.isObject()) {
            confNode.properties().forEach(entry -> conf.put(entry.getKey(),
                    entry.getValue().isNull() ? "" : entry.getValue().asText()));
        }
        return new ClusterInstance(
                text(node, "alias"),
                text(node, "source"),
                bool(node, "manageable"),
                text(node, "name"),
                text(node, "state"),
                pid,
                integer(node, "pgPort", -1),
                integer(node, "mgmtPort", -1),
                text(node, "health"),
                uptime,
                text(node, "error"),
                text(node, "dir"),
                text(node, "target"),
                conf);
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : fallback;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isBoolean() && value.asBoolean();
    }

    @Override
    public void close() {
        transport.close();
    }
}
