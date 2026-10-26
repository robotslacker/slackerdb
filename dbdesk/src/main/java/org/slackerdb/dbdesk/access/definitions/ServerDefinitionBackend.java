package org.slackerdb.dbdesk.access.definitions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务端后端：走管理口 {@code /definitions/{kind}}（契约见 dbdesk-docs/m5-kickoff.md）。
 *
 * <p>错误语义转成异常，让调用方（页面）能给出人话提示：</p>
 * <ul>
 *   <li>409 → {@link ConflictException}（并发修改或重名，提示刷新/改名）；</li>
 *   <li>404 → 返回空/不抛（删除不存在的定义是幂等语义）；</li>
 *   <li>其它非 2xx → {@link IllegalStateException}，带服务端的 {@code retMsg}。</li>
 * </ul>
 */
public final class ServerDefinitionBackend implements DefinitionBackend {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 并发冲突（If-Match 不匹配 / 重名）。 */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private final String baseUrl;
    private final HttpClient http;

    public ServerDefinitionBackend(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public String name() {
        return "server";
    }

    @Override
    public List<Map<String, Object>> list(String kind) {
        HttpResponse<String> response = send("GET", "/definitions/" + encode(kind), null, null);
        if (response.statusCode() == 404) {
            return new ArrayList<>();
        }
        requireOk(response, "list " + kind);
        List<Map<String, Object>> items = new ArrayList<>();
        JsonNode root = JSON.readTree(response.body());
        for (JsonNode node : root.path("items")) {
            items.add(JSON.convertValue(node, Map.class));
        }
        return items;
    }

    @Override
    public Optional<Map<String, Object>> find(String kind, String id) {
        HttpResponse<String> response = send("GET", "/definitions/" + encode(kind) + "/" + encode(id), null, null);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireOk(response, "find " + kind + "/" + id);
        return Optional.of(JSON.convertValue(JSON.readTree(response.body()), Map.class));
    }

    @Override
    public Map<String, Object> save(String kind, Map<String, Object> definition) {
        String id = definition.get("id") == null ? "" : String.valueOf(definition.get("id"));
        boolean create = id.isBlank();
        String path = create
                ? "/definitions/" + encode(kind)
                : "/definitions/" + encode(kind) + "/" + encode(id);
        Map<String, Object> body = new LinkedHashMap<>(definition);
        HttpResponse<String> response = send(create ? "POST" : "PUT", path,
                JSON.writeValueAsString(body), create ? null : idempotencyHeader(kind, id, definition));
        if (!create && response.statusCode() == 404) {
            // upsert 语义：调用方给了 id 但服务端还没有（从文件迁上来的常见情况）→ 改用创建
            response = send("POST", "/definitions/" + encode(kind),
                    JSON.writeValueAsString(body), null);
        }
        if (response.statusCode() == 409) {
            throw new ConflictException(messageOf(response));
        }
        requireOk(response, (create ? "create " : "update ") + kind);
        return JSON.convertValue(JSON.readTree(response.body()), Map.class);
    }

    @Override
    public boolean delete(String kind, String id) {
        HttpResponse<String> response = send("DELETE", "/definitions/" + encode(kind) + "/" + encode(id), null, null);
        if (response.statusCode() == 404) {
            return false;
        }
        requireOk(response, "delete " + kind + "/" + id);
        return true;
    }

    /**
     * 乐观并发用 {@code updatedAt} 做 If-Match。调用方传进来的定义里通常带着它
     * （从服务端读出来的原样回传），没带就让服务端按"无条件更新"处理。
     */
    private String idempotencyHeader(String kind, String id, Map<String, Object> definition) {
        Object updatedAt = definition.get("updatedAt");
        return updatedAt == null || String.valueOf(updatedAt).isBlank() ? null : String.valueOf(updatedAt);
    }

    private HttpResponse<String> send(String method, String path, String body, String ifMatch) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("定义服务不可达（" + baseUrl + "）：" + e.getMessage(), e);
        }
    }

    private static void requireOk(HttpResponse<String> response, String what) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        throw new IllegalStateException(what + " 失败（HTTP " + response.statusCode() + "）：" + messageOf(response));
    }

    private static String messageOf(HttpResponse<String> response) {
        try {
            JsonNode root = JSON.readTree(response.body());
            String retMsg = root.path("retMsg").asString("");
            if (!retMsg.isBlank()) {
                return retMsg;
            }
        } catch (Exception ignored) {
            // 非 JSON 响应：退回原文
        }
        String body = response.body();
        return body == null || body.isBlank() ? ("HTTP " + response.statusCode()) : body;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
