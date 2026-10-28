package org.slackerdb.dbdesk.access.mcp;

import org.slackerdb.dbdesk.i18n.LocalizedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 管理客户端：协议面走 {@code POST /mcp} 的 JSON-RPC，管理面走 {@code /mcp/*}。
 *
 * <p>这里刻意不使用旧 Web Console 的 {@code /jsonrpc} 别名；{@code /mcp} 是规范端点。
 * 资源注销使用 {@code /mcp/unregisterMCPResource}，不复制旧控制台的拼写错误。</p>
 */
public final class McpAdminClient implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public McpAdminClient(String baseUrl) {
        this(baseUrl, DEFAULT_TIMEOUT);
    }

    public McpAdminClient(String baseUrl, Duration timeout) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    // ------------------------------------------------------------- 协议面

    public List<McpTool> tools() throws McpException {
        JsonNode result = jsonRpc("tools/list", Map.of());
        List<McpTool> tools = new ArrayList<>();
        JsonNode array = result.path("tools");
        if (array.isArray()) {
            for (JsonNode item : array) {
                tools.add(new McpTool(
                        text(item, "name"),
                        text(item, "description"),
                        object(item.path("inputSchema")),
                        text(item, "category")));
            }
        }
        return List.copyOf(tools);
    }

    public List<McpResource> resources() throws McpException {
        JsonNode result = jsonRpc("resources/list", Map.of());
        List<McpResource> resources = new ArrayList<>();
        JsonNode array = result.path("resources");
        if (array.isArray()) {
            for (JsonNode item : array) {
                resources.add(new McpResource(
                        text(item, "uri"),
                        text(item, "name"),
                        text(item, "description"),
                        text(item, "mimeType"),
                        object(item.path("contents")),
                        text(item, "category")));
            }
        }
        return List.copyOf(resources);
    }

    /**
     * MCP 服务清单：规范里没有自定义 {@code services/list}，服务通过资源
     * {@code slackerdb://services} 暴露。
     */
    public List<McpService> services() throws McpException {
        JsonNode result = jsonRpc("resources/read", Map.of("uri", "slackerdb://services"));
        JsonNode contents = result.path("contents");
        List<McpService> services = new ArrayList<>();
        if (contents.isArray() && !contents.isEmpty()) {
            String text = text(contents.get(0), "text");
            if (text != null && !text.isBlank()) {
                JsonNode payload;
                try {
                    payload = json.readTree(text);
                } catch (RuntimeException e) {
                    throw new McpException(e, "error.mcp.invalidServiceListJson");
                }
                JsonNode array = payload.path("services");
                if (array.isArray()) {
                    for (JsonNode item : array) {
                        services.add(new McpService(
                                text(item, "name"),
                                text(item, "apiName"),
                                text(item, "version"),
                                text(item, "method"),
                                text(item, "description"),
                                text(item, "category"),
                                stringList(item.path("capabilities")),
                                stringList(item.path("use_cases")),
                                object(item.path("parameters")),
                                object(item.path("examples"))));
                    }
                }
            }
        }
        return List.copyOf(services);
    }

    // ------------------------------------------------------------- 管理面

    public void registerTool(McpTool tool) throws McpException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", tool.name());
        body.put("description", tool.description());
        body.put("inputSchema", tool.inputSchema());
        body.put("category", tool.category());
        requireOk(postJson("/mcp/registerMCPTool", body), "error.mcp.registerTool");
    }

    public void unregisterTool(String name) throws McpException {
        requireOk(postJson("/mcp/unregisterMCPTool", Map.of("name", name)), "error.mcp.unregisterTool");
    }

    public void registerResource(McpResource resource) throws McpException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uri", resource.uri());
        body.put("name", resource.name());
        body.put("description", resource.description());
        body.put("mimeType", resource.mimeType());
        body.put("contents", resource.contents());
        body.put("category", resource.category());
        requireOk(postJson("/mcp/registerMCPResource", body), "error.mcp.registerResource");
    }

    public void unregisterResource(String uri) throws McpException {
        requireOk(postJson("/mcp/unregisterMCPResource", Map.of("uri", uri)), "error.mcp.unregisterResource");
    }

    public void registerService(McpService service) throws McpException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", service.name());
        body.put("apiName", service.apiName());
        body.put("version", service.version());
        body.put("method", service.method());
        body.put("description", service.description());
        body.put("category", service.category());
        body.put("capabilities", service.capabilities());
        body.put("use_cases", service.useCases());
        body.put("parameters", service.parameters());
        body.put("examples", service.examples());
        requireOk(postJson("/mcp/registerMCPService", body), "error.mcp.registerService");
    }

    public void unregisterService(String name) throws McpException {
        requireOk(postJson("/mcp/unregisterMCPService", Map.of("name", name)), "error.mcp.unregisterService");
    }

    public void loadTools(String rawJson) throws McpException {
        requireOk(postRaw("/mcp/loadMCPTool", rawJson), "error.mcp.loadTools");
    }

    public void loadResources(String rawJson) throws McpException {
        requireOk(postRaw("/mcp/loadMCPResource", rawJson), "error.mcp.loadResources");
    }

    public void loadServices(String rawJson) throws McpException {
        requireOk(postRaw("/mcp/loadMCPService", rawJson), "error.mcp.loadServices");
    }

    public SaveResult saveTools() throws McpException {
        return save("/mcp/saveMCPTool", "error.mcp.saveTools");
    }

    public SaveResult saveResources() throws McpException {
        return save("/mcp/saveMCPSource", "error.mcp.saveResources");
    }

    public SaveResult saveServices() throws McpException {
        return save("/mcp/saveMCPService", "error.mcp.saveServices");
    }

    public byte[] dumpTools() throws McpException {
        return dump("/mcp/dumpMCPTool");
    }

    public byte[] dumpResources() throws McpException {
        return dump("/mcp/dumpMCPSource");
    }

    public byte[] dumpServices() throws McpException {
        return dump("/mcp/dumpMCPService");
    }

    // ---------------------------------------------------------------- 传输

    private JsonNode jsonRpc(String method, Object params) throws McpException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.put("params", params);
        JsonNode root = postJson("/mcp", body);
        JsonNode error = root.path("error");
        if (error.isObject()) {
            throw new McpException("error.mcp.jsonRpc", method, fallbackMessage(error));
        }
        return root.path("result");
    }

    private SaveResult save(String path, String actionKey) throws McpException {
        JsonNode root = postJson(path, Map.of());
        requireOk(root, actionKey);
        return new SaveResult(text(root, "savedPath"), text(root, "retMsg"));
    }

    private byte[] dump(String path) throws McpException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = sendBytes(request);
        requireOkStatus(response.statusCode(), response.body());
        return response.body();
    }

    private JsonNode postJson(String path, Object body) throws McpException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8))
                .build();
        return sendJson(request);
    }

    private JsonNode postRaw(String path, String body) throws McpException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
                .build();
        return sendJson(request);
    }

    private JsonNode sendJson(HttpRequest request) throws McpException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new McpException(e, "error.mcp.unreachable", baseUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(e, "error.mcp.interrupted");
        }
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() / 100 != 2) {
            throw error(response.statusCode(), body);
        }
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            throw new McpException(e, "error.mcp.invalidJson", abbreviate(body));
        }
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest request) throws McpException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new McpException(e, "error.mcp.unreachable", baseUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(e, "error.mcp.interrupted");
        }
    }

    private void requireOk(JsonNode root, String actionKey) throws McpException {
        int retCode = root.path("retCode").isNumber() ? root.path("retCode").asInt() : 0;
        if (retCode != 0) {
            throw new McpException(actionKey, fallbackMessage(root));
        }
    }

    private void requireOkStatus(int status, byte[] body) throws McpException {
        if (status / 100 == 2) {
            return;
        }
        throw error(status, body == null ? "" : new String(body, StandardCharsets.UTF_8));
    }

    private McpException error(int status, String body) {
        try {
            JsonNode root = json.readTree(body);
            String detail = fallbackMessage(root);
            if (detail != null && !detail.isBlank()) {
                return new McpException("error.mcp.httpStatusWithDetail", status, detail);
            }
        } catch (RuntimeException ignored) {
            if (body != null && !body.isBlank()) {
                return new McpException("error.mcp.httpStatusWithDetail", status, abbreviate(body));
            }
        }
        return new McpException("error.mcp.httpStatus", status);
    }

    private static String fallbackMessage(JsonNode root) {
        String message = text(root, "retMsg");
        return message == null ? text(root, "message") : message;
    }

    private String writeJson(Object value) throws McpException {
        try {
            return json.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new McpException(e, "error.mcp.serializeFailed");
        }
    }

    private Object object(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return json.convertValue(node, Object.class);
        } catch (RuntimeException e) {
            return node.toString();
        }
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String abbreviate(String body) {
        String singleLine = body.replaceAll("\\s+", " ");
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "…";
    }

    @Override
    public void close() {
        http.close();
    }

    /** 保存结果。 */
    public record SaveResult(String savedPath, String message) {
    }

    /**
     * MCP 管理失败：携带 i18n key 与参数（{@link LocalizedException}），
     * 由界面层用 {@code I18n.message(e)} 按当前语言渲染；{@code getMessage()} 只是 key。
     */
    public static class McpException extends LocalizedException {
        public McpException(String messageKey, Object... messageArgs) {
            super(messageKey, messageArgs);
        }

        public McpException(Throwable cause, String messageKey, Object... messageArgs) {
            super(cause, messageKey, messageArgs);
        }
    }
}
