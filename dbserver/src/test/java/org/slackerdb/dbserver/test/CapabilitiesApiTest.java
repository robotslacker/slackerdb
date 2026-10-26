package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.TimeZone;

import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 平台契约端点的集成测试：{@code /capabilities}、{@code /health}、{@code POST /sessions/{id}/kill}
 * 以及 {@code /status.sessions[]} 的来源可辨识字段。
 *
 * <p>这些端点是"控制台/CLI 不靠探测端点猜能力"的基础，属于对平台承诺的一部分，必须锁死。</p>
 */
public class CapabilitiesApiTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    static int dbPort;
    static int dbPortX;
    static String baseUrl;
    static DBInstance dbInstance;

    @BeforeAll
    static void initAll() throws Exception {
        // 与其它端到端测试一致：固定 UTC，避免时区参与断言
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setPortX(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPort = serverConfiguration.getPort();
        dbPortX = serverConfiguration.getPortX();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        baseUrl = "http://127.0.0.1:" + dbPortX;
        assertTrue(dbInstance.instanceState.equalsIgnoreCase("RUNNING"),
                "the embedded server should be running");
    }

    @AfterAll
    static void shutdown() {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("/capabilities declares contract version and feature switches without leaking sensitive parameters")
    void capabilitiesDeclaresContractAndFeatures() throws Exception {
        HttpResponse<String> response = get("/capabilities");
        assertEquals(200, response.statusCode(), "GET /capabilities should return 200");

        JSONObject capabilities = JSONObject.parseObject(response.body());
        assertEquals(1, capabilities.getIntValue("apiVersion"), "management API contract version should be 1");

        JSONObject features = capabilities.getJSONObject("features");
        assertNotNull(features, "features must be declared");
        assertTrue(features.getBooleanValue("dataService"), "the data service capability should be available");
        assertTrue(features.getBooleanValue("sqlRepl"), "the SQL REPL capability should be available");
        assertTrue(features.getBooleanValue("backupTasks"), "async backup tasks should be declared");
        assertTrue(features.getBooleanValue("logTail"), "log tail support should be declared");
        assertFalse(features.getBooleanValue("aiChat"), "AI chat is disabled unless mcp_llm_server is configured");

        assertNotNull(capabilities.getJSONObject("ports"), "ports must be declared");
        assertTrue(capabilities.containsKey("plugins"), "the plugin list must be present (may be empty)");
        assertEquals("2026-07-28", capabilities.getJSONObject("contracts").getString("mcp"),
                "the MCP protocol version must match the server implementation");

        assertFalse(response.body().contains("mcpLlmKey"),
                "the capability declaration must not carry sensitive parameters");
    }

    @Test
    @DisplayName("/health is a lightweight liveness check and does not depend on the heavy /status path")
    void healthReportsUp() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = get("/health");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(200, response.statusCode(), "GET /health should return 200");
        JSONObject health = JSONObject.parseObject(response.body());
        assertEquals("UP", health.getString("status"));
        assertEquals("UP", health.getString("database"));
        assertTrue(elapsedMillis < 1000,
                "the health check should stay lightweight (measured " + elapsedMillis + "ms)");
    }

    @Test
    @DisplayName("killing a non-existent session returns the unified error envelope")
    void killUnknownSessionReturnsEnvelope() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/sessions/999999/kill"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode(), "an unknown session should return 404");
        JSONObject body = JSONObject.parseObject(response.body());
        assertEquals(404, body.getIntValue("retCode"));
        assertEquals("NOT_FOUND", body.getString("errorClass"),
                "the error envelope must carry errorClass so clients can tell error categories apart");
    }

    @Test
    @DisplayName("/status.sessions identifies the origin (type / applicationName) and the session can be killed over HTTP")
    void statusSessionsAreIdentifiableAndKillable() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem", "", "")) {
            // 显式上报来源：pgjdbc 系驱动用 SET application_name 上报，而这条语句会被
            // SQLReplacer 丢弃（DuckDB 不支持），因此服务端必须自己截留 —— 这正是本用例要锁的行为。
            try (Statement statement = connection.createStatement()) {
                statement.execute("set application_name = 'capabilities-api-test'");
            }

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("select 42 as answer")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt("answer"));
            }

            Integer sessionId = findSessionIdByApplicationName("capabilities-api-test");
            assertNotNull(sessionId, "/status.sessions should find this connection by applicationName");

            // 通过 HTTP 终止它（UI 走这条路径，而不是自定义 TCP 管理协议）
            HttpResponse<String> killResponse = CLIENT.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/sessions/" + sessionId + "/kill"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, killResponse.statusCode(),
                    "killing an existing session should return 200, actual response: " + killResponse.body());
            assertTrue(JSONObject.parseObject(killResponse.body()).getBooleanValue("killed"));
        }
    }

    /** 在 /status.sessions 里按 applicationName 找会话 id；找不到返回 null。 */
    private static Integer findSessionIdByApplicationName(String applicationName) throws Exception {
        HttpResponse<String> response = get("/status");
        assertEquals(200, response.statusCode());
        JSONArray sessions = JSONObject.parseObject(response.body()).getJSONArray("sessions");
        assertNotNull(sessions, "/status must contain the sessions array");
        Integer found = null;
        for (int i = 0; i < sessions.size(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            assertEquals("pgwire", session.getString("type"), "the session type must be declared explicitly");
            assertTrue(session.containsKey("applicationName"), "the session must carry the applicationName field");
            if (applicationName.equals(session.getString("applicationName"))) {
                found = session.getInteger("id");
            }
        }
        if (found == null) {
            fail("applicationName=[" + applicationName + "] not found in /status.sessions; actual content: "
                    + sessions.toJSONString());
        }
        return found;
    }

    @Test
    @DisplayName("legacy web console entry points are gone; the management port stays API-only")
    void webConsoleUiIsGone() throws Exception {
        for (String path : new String[] {"/", "/console.html", "/help", "/web/console.js"}) {
            HttpResponse<String> response = get(path);
            assertEquals(404, response.statusCode(),
                    path + " should no longer be served, actual body: " + response.body());
            assertTrue(response.body().contains("retCode"),
                    path + " should answer with the JSON error envelope, actual body: " + response.body());
        }
    }

    // ========== 契约夹具（W0）：字段只增不减 ==========
    private static void assertContainsAll(Object expected, Object actual, String path,
                                          java.util.List<String> missing) {
        if (!(expected instanceof JSONObject eo) || !(actual instanceof JSONObject ao)) {
            return;
        }
        for (String key : eo.keySet()) {
            if (key.startsWith("_")) {
                continue;
            }
            String where = path.isEmpty() ? key : path + "." + key;
            if (!ao.containsKey(key)) {
                missing.add(where);
                continue;
            }
            assertContainsAll(eo.get(key), ao.get(key), where, missing);
        }
    }

    private static JSONObject fixture(String name) throws Exception {
        java.nio.file.Path path = java.nio.file.Paths.get("..", "dbdesk-docs", "contract", name);
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(path),
                "no contract fixture (module only checkout?), skipping");
        return JSONObject.parseObject(
                java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("contract fixture: /capabilities and /health fields only grow, never shrink")
    void endpointsMatchFixtures() throws Exception {
        JSONObject capabilities = JSONObject.parseObject(get("/capabilities").body());
        JSONObject health = JSONObject.parseObject(get("/health").body());

        java.util.List<String> missing = new java.util.ArrayList<>();
        assertContainsAll(fixture("capabilities.v1.json"), capabilities, "", missing);
        assertContainsAll(fixture("health.v1.json"), health, "", missing);

        assertTrue(missing.isEmpty(), "the actual response is missing contract fields (breaking change): " + missing);
    }
}
