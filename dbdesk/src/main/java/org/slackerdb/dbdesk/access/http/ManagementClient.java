package org.slackerdb.dbdesk.access.http;

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
 * dbserver 管理口的客户端（契约 S-1 / S-2 / S-3 / S-4）。
 *
 * <p><b>这一层不认识 Vaadin，也不认识 JDBC</b>：只发 HTTP、只回 record。视图拿到的是
 * {@link Capabilities}/{@link Health}/{@link ServerStatus}/{@link SessionInfo}/{@link BackupInfo}，
 * 不是 JSON。M1 起用 ArchUnit 守这条边界。</p>
 *
 * <p><b>字段容忍</b>：严格按契约"字段只增不减"解析 —— 缺字段给默认值、未知字段直接忽略
 * （不绑定 POJO 就是为了这个）。</p>
 */
public final class ManagementClient implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ManagementClient(String baseUrl) {
        this(baseUrl, DEFAULT_TIMEOUT);
    }

    public ManagementClient(String baseUrl, Duration timeout) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------- 契约端点

    /** S-1：能力声明。启动时**只调一次**，之后菜单/按钮都由它决定。 */
    public Capabilities capabilities() throws ManagementException {
        JsonNode root = getJson("/capabilities");
        JsonNode server = root.path("server");
        JsonNode ports = root.path("ports");
        JsonNode features = root.path("features");

        Capabilities.Features featureSet = new Capabilities.Features(
                bool(features, "dataService"),
                bool(features, "mcp"),
                bool(features, "plugins"),
                bool(features, "sqlRepl"),
                bool(features, "copyOut"),
                bool(features, "sqlHistory"),
                bool(features, "apiHistory"),
                bool(features, "definitions"),
                bool(features, "aiChat"),
                bool(features, "backupTasks"),
                bool(features, "logTail"));

        List<Capabilities.Plugin> plugins = new ArrayList<>();
        JsonNode pluginArray = root.path("plugins");
        if (pluginArray.isArray()) {
            for (JsonNode plugin : pluginArray) {
                JsonNode contributes = plugin.path("contributes");
                boolean etl = bool(contributes.path("capabilities"), "etl") || bool(contributes, "etl");
                plugins.add(new Capabilities.Plugin(text(plugin, "id"), text(plugin, "state"), etl));
            }
        }

        Map<String, String> contracts = new LinkedHashMap<>();
        JsonNode contractNode = root.path("contracts");
        if (contractNode.isObject()) {
            contractNode.properties().forEach(entry ->
                    contracts.put(entry.getKey(), entry.getValue().asText()));
        }

        JsonNode provisioningNode = root.path("provisioning");
        Capabilities.Provisioning provisioning = null;
        if (provisioningNode.isObject()) {
            provisioning = new Capabilities.Provisioning(
                    bool(provisioningNode, "enabled"),
                    text(provisioningNode, "mode"),
                    text(provisioningNode, "adminBase"),
                    stringList(provisioningNode.path("features")),
                    stringList(provisioningNode.path("backendSources")));
        }

        return new Capabilities(
                integer(root, "apiVersion", 0),
                text(server, "name"),
                text(server, "version"),
                longValue(server, "pid"),
                integer(ports, "pg", 0),
                integer(ports, "management", 0),
                bool(ports, "managementEnabled"),
                text(root, "accessMode"),
                featureSet,
                List.copyOf(plugins),
                Map.copyOf(contracts),
                text(root, "role"),
                provisioning,
                root.path("managementForwarding").isObject());
    }

    /**
     * 该地址是不是 dbProxy（而不是 dbserver）。
     *
     * <p>优先看 {@code role=proxy}；老代理没有该字段时，用 {@code provisioning} /
     * {@code managementForwarding} 是否存在兜底。</p>
     */
    public boolean isProxy() throws ManagementException {
        return capabilities().isProxy();
    }

    /** S-3：轻量存活（状态栏 5 s 轮询用）。 */
    public Health health() throws ManagementException {
        JsonNode root = getJson("/health");
        return new Health(
                text(root, "status"),
                text(root, "database"),
                text(root, "management"),
                text(root, "instanceState"),
                longValue(root, "ts"));
    }

    /** S-2：会话列表（只含 pgwire；WS/REPL 会话尚未纳入）。 */
    public List<SessionInfo> sessions() throws ManagementException {
        return status().sessions();
    }

    /**
     * S-2 的完整状态快照：server/database/parameters/usage/extensions/plugins/sessions。
     *
     * <p>比 {@link #sessions()} 重，供"实例与状态"页手动或低频刷新，不要放进 5 s 轮询。</p>
     */
    public ServerStatus status() throws ManagementException {
        JsonNode root = getJson("/status");

        JsonNode serverNode = root.path("server");
        ServerStatus.Server server = new ServerStatus.Server(
                text(serverNode, "status"),
                text(serverNode, "version"),
                text(serverNode, "build"),
                longValue(serverNode, "pid"),
                text(serverNode, "now"),
                text(serverNode, "bootTime"),
                text(serverNode, "runTime"));

        JsonNode databaseNode = root.path("database");
        ServerStatus.Database database = new ServerStatus.Database(
                text(databaseNode, "version"),
                text(databaseNode, "size"),
                text(databaseNode, "memoryUsage"),
                text(databaseNode, "walSize"));

        List<SessionInfo> sessions = new ArrayList<>();
        JsonNode sessionArray = root.path("sessions");
        if (sessionArray.isArray()) {
            for (JsonNode item : sessionArray) {
                sessions.add(session(item));
            }
        }

        List<ServerStatus.PluginInfo> plugins = new ArrayList<>();
        JsonNode pluginArray = root.path("plugins");
        if (pluginArray.isArray()) {
            for (JsonNode item : pluginArray) {
                plugins.add(new ServerStatus.PluginInfo(
                        text(item, "id"),
                        text(item, "filename"),
                        text(item, "filepath"),
                        text(item, "state"),
                        text(item, "version"),
                        text(item, "description"),
                        text(item, "mountTime")));
            }
        }

        return new ServerStatus(
                server,
                database,
                objectMap(root.path("parameters")),
                objectMap(root.path("usage")),
                stringList(root.path("extensions")),
                List.copyOf(plugins),
                List.copyOf(sessions),
                text(root, "error"));
    }

    /**
     * S-4：终止会话。
     *
     * @return {@code true} 表示服务端确认终止；{@code false} 表示它说没杀成
     * @throws ManagementException 404（会话不存在）/ 连不上 / 5xx
     */
    public boolean killSession(long sessionId) throws ManagementException {
        HttpRequest request = HttpRequest.newBuilder(uri("/sessions/" + sessionId + "/kill"))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        JsonNode root = send(request);
        return bool(root, "killed");
    }

    // ------------------------------------------------------------- 运维端点

    /** {@code GET /logfile?lines=N}；{@code lines <= 0} 表示取全文。 */
    public String logText(int lines) throws ManagementException {
        String path = lines > 0 ? "/logfile?lines=" + lines : "/logfile";
        HttpRequest request = HttpRequest.newBuilder(uri(path)).timeout(timeout).GET().build();
        return sendRaw(request);
    }

    /** {@code POST /backups}：启动异步备份任务。 */
    public BackupInfo startBackup(String backupTag) throws ManagementException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("backupTag", backupTag);
        JsonNode root = postJson("/backups", body, timeout);
        return new BackupInfo(
                text(root, "id"),
                backupTag,
                text(root, "status"),
                null,
                0,
                null,
                null,
                text(root, "error"));
    }

    /** {@code GET /backups/{id}}。 */
    public BackupInfo backup(String id) throws ManagementException {
        JsonNode root = getJson("/backups/" + id);
        return backupInfo(root);
    }

    /** {@code GET /backups}。 */
    public List<BackupInfo> backups() throws ManagementException {
        JsonNode root = getJson("/backups");
        List<BackupInfo> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode item : root) {
                result.add(backupInfo(item));
            }
        }
        return List.copyOf(result);
    }

    /** {@code GET /backups/{id}/download}：已完成的备份文件。 */
    public byte[] downloadBackup(String id) throws ManagementException {
        HttpRequest request = HttpRequest.newBuilder(uri("/backups/" + id + "/download"))
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();
        return sendBytes(request);
    }

    // ------------------------------------------------------------- 插件管理

    /**
     * {@code POST /plugin/load}：加载插件，但不自动启动。
     *
     * @param filename 插件 JAR 文件名（相对 {@code plugins_dir}）或完整路径
     */
    public PluginActionResult loadPlugin(String filename) throws ManagementException {
        return pluginAction("/plugin/load", Map.of("filename", filename));
    }

    /** {@code POST /plugin/unload}：停止并卸载插件；不删除 JAR。 */
    public PluginActionResult unloadPlugin(String pluginId) throws ManagementException {
        return pluginAction("/plugin/unload", Map.of("plugid", pluginId));
    }

    /** {@code POST /plugin/start}：启动已加载的插件。 */
    public PluginActionResult startPlugin(String pluginId) throws ManagementException {
        return pluginAction("/plugin/start", Map.of("plugid", pluginId));
    }

    /** {@code POST /plugin/stop}：停止运行中的插件。 */
    public PluginActionResult stopPlugin(String pluginId) throws ManagementException {
        return pluginAction("/plugin/stop", Map.of("plugid", pluginId));
    }

    private PluginActionResult pluginAction(String path, Map<String, ?> body) throws ManagementException {
        JsonNode root = postJson(path, body, Duration.ofSeconds(15));
        boolean success = integer(root, "retCode", -1) == 0;
        String message = text(root, "retMsg");
        if (message == null || message.isBlank()) {
            message = success ? "OK" : "插件操作失败";
        }
        return new PluginActionResult(success, message, text(root, "pluginId"));
    }

    // ---------------------------------------------------------------- 传输

    /**
     * 包内通用 JSON GET：代理管理客户端复用这里的传输、错误信封与超时处理，
     * 不再复制一套 HttpClient。
     */
    JsonNode getJson(String path) throws ManagementException {
        return getJson(path, timeout);
    }

    JsonNode getJson(String path, Duration requestTimeout) throws ManagementException {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).timeout(requestTimeout).GET().build();
        return send(request);
    }

    JsonNode postJson(String path, Object body, Duration requestTimeout) throws ManagementException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    JsonNode deleteJson(String path, Duration requestTimeout) throws ManagementException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(requestTimeout)
                .DELETE()
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) throws ManagementException {
        String body = sendRaw(request);
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new ManagementException("管理口返回的不是合法 JSON（" + request.uri() + "）："
                    + abbreviate(body), e);
        }
    }

    private String sendRaw(HttpRequest request) throws ManagementException {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ManagementException("调用管理口失败：" + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManagementException("调用管理口被中断：" + request.uri(), e);
        }

        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() / 100 != 2) {
            throw errorFrom(response.statusCode(), body);
        }
        return body;
    }

    private byte[] sendBytes(HttpRequest request) throws ManagementException {
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ManagementException("调用管理口失败：" + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManagementException("调用管理口被中断：" + request.uri(), e);
        }
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : new String(response.body(), StandardCharsets.UTF_8);
            throw errorFrom(response.statusCode(), body);
        }
        return response.body();
    }

    private ManagementException errorFrom(int status, String body) {
        int retCode = status;
        String retMsg = null;
        try {
            JsonNode root = objectMapper.readTree(body);
            retCode = integer(root, "retCode", status);
            retMsg = text(root, "retMsg");
            if (retMsg == null) {
                retMsg = text(root, "error");
            }
        } catch (RuntimeException ignored) {
            // 错误体不是 JSON（例如代理返回 HTML）——保留 HTTP 状态码就够定位了
            if (body != null && !body.isBlank()) {
                retMsg = abbreviate(body);
            }
        }
        return new ManagementException("管理口返回 " + status, status, retCode, retMsg);
    }

    private String writeJson(Object value) throws ManagementException {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new ManagementException("无法序列化请求体", e);
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

    private static String abbreviate(String body) {
        String singleLine = body.replaceAll("\\s+", " ");
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "…";
    }

    // ----------------------------------------------------------- JSON 小工具

    private static SessionInfo session(JsonNode item) {
        return new SessionInfo(
                longValue(item, "id"),
                text(item, "type"),
                text(item, "connectedTime"),
                text(item, "clientIp"),
                text(item, "applicationName"),
                text(item, "database"),
                text(item, "status"),
                text(item, "executingFunction"),
                text(item, "executingSql"));
    }

    private static BackupInfo backupInfo(JsonNode item) {
        return new BackupInfo(
                text(item, "id"),
                text(item, "backupTag"),
                text(item, "status"),
                text(item, "filename"),
                longValue(item, "sizeBytes"),
                text(item, "startedAt"),
                text(item, "finishedAt"),
                text(item, "error"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, Map.class);
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

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : fallback;
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : 0L;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isBoolean() && value.asBoolean();
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
