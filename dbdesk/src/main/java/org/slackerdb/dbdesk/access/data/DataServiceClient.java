package org.slackerdb.dbdesk.access.data;

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
 * 数据服务注册表的管理客户端（管理口 {@code /api/*}）。
 *
 * <p>与视图分离：这里只做 HTTP 与 JSON，不依赖 Vaadin。服务端列表接口的
 * {@code parameter}/{@code parameters} 差异在 {@link DataServiceDefinition} 里吸收。</p>
 */
public final class DataServiceClient implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public DataServiceClient(String baseUrl) {
        this(baseUrl, DEFAULT_TIMEOUT);
    }

    public DataServiceClient(String baseUrl, Duration timeout) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public List<DataServiceDefinition> list() throws DataServiceException {
        JsonNode root = getJson("/api/listRegisteredService");
        JsonNode array = root.isArray() ? root : root.path("services");
        List<DataServiceDefinition> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                result.add(DataServiceDefinition.fromJson(item));
            }
        }
        return List.copyOf(result);
    }

    public void register(DataServiceDefinition definition) throws DataServiceException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceName", definition.serviceName());
        body.put("serviceVersion", definition.serviceVersion());
        body.put("category", definition.category());
        body.put("serviceType", definition.serviceType());
        body.put("sql", definition.sql());
        body.put("description", definition.description());
        body.put("searchPath", definition.searchPath());
        body.put("snapshotLimit", definition.snapshotLimit());
        body.put("parameters", writeJson(definition.parameters()));
        requireOk(postJson("/api/registerService", body), "error.dataService.register");
    }

    public void unregister(String name, String version, String type) throws DataServiceException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceName", name);
        body.put("serviceVersion", version);
        body.put("serviceType", type);
        requireOk(postJson("/api/unRegisterService", body), "error.dataService.unregister");
    }

    public void load(String rawJson) throws DataServiceException {
        requireOk(postRaw("/api/loadRegisterService", rawJson), "error.dataService.load");
    }

    public SaveResult save() throws DataServiceException {
        JsonNode root = postJson("/api/saveRegisterService", Map.of());
        requireOk(root, "error.dataService.save");
        return new SaveResult(text(root, "savedPath"), text(root, "retMsg"));
    }

    public byte[] download() throws DataServiceException {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/downloadRegisteredService"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = sendBytes(request);
        requireOkStatus(response.statusCode(), response.body());
        return response.body();
    }

    // ---------------------------------------------------------------- 传输

    private JsonNode getJson(String path) throws DataServiceException {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).timeout(timeout).GET().build();
        return sendJson(request);
    }

    private JsonNode postJson(String path, Object body) throws DataServiceException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8))
                .build();
        return sendJson(request);
    }

    private JsonNode postRaw(String path, String body) throws DataServiceException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
                .build();
        return sendJson(request);
    }

    private JsonNode sendJson(HttpRequest request) throws DataServiceException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DataServiceException(e, "error.dataService.unreachable", baseUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataServiceException(e, "error.dataService.interrupted");
        }
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() / 100 != 2) {
            throw error(response.statusCode(), body);
        }
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            throw new DataServiceException(e, "error.dataService.invalidJson", abbreviate(body));
        }
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest request) throws DataServiceException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new DataServiceException(e, "error.dataService.unreachable", baseUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataServiceException(e, "error.dataService.interrupted");
        }
    }

    private void requireOk(JsonNode root, String actionKey) throws DataServiceException {
        int retCode = root.path("retCode").isNumber() ? root.path("retCode").asInt() : 0;
        if (retCode != 0) {
            throw new DataServiceException(actionKey, fallbackMessage(root));
        }
    }

    private void requireOkStatus(int status, byte[] body) throws DataServiceException {
        if (status / 100 == 2) {
            return;
        }
        throw error(status, body == null ? "" : new String(body, StandardCharsets.UTF_8));
    }

    private DataServiceException error(int status, String body) {
        try {
            JsonNode root = json.readTree(body);
            String detail = fallbackMessage(root);
            if (detail != null && !detail.isBlank()) {
                return new DataServiceException("error.dataService.httpStatusWithDetail", status, detail);
            }
        } catch (RuntimeException ignored) {
            if (body != null && !body.isBlank()) {
                return new DataServiceException("error.dataService.httpStatusWithDetail", status, abbreviate(body));
            }
        }
        return new DataServiceException("error.dataService.httpStatus", status);
    }

    private static String fallbackMessage(JsonNode root) {
        String message = text(root, "retMsg");
        return message == null ? text(root, "error") : message;
    }

    private String writeJson(Object value) throws DataServiceException {
        try {
            return json.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new DataServiceException(e, "error.dataService.serializeFailed");
        }
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

    /** 保存结果：服务端返回的落盘路径。 */
    public record SaveResult(String savedPath, String message) {
    }

    /**
     * 数据服务管理失败：携带 i18n key 与参数（{@link LocalizedException}），
     * 由界面层用 {@code I18n.message(e)} 按当前语言渲染；{@code getMessage()} 只是 key。
     */
    public static class DataServiceException extends LocalizedException {
        public DataServiceException(String messageKey, Object... messageArgs) {
            super(messageKey, messageArgs);
        }

        public DataServiceException(Throwable cause, String messageKey, Object... messageArgs) {
            super(cause, messageKey, messageArgs);
        }
    }
}
