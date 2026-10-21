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
                "嵌入式服务端应已启动");
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
    @DisplayName("/capabilities 声明契约版本与能力开关，且不携带敏感参数")
    void capabilitiesDeclaresContractAndFeatures() throws Exception {
        HttpResponse<String> response = get("/capabilities");
        assertEquals(200, response.statusCode(), "GET /capabilities 应为 200");

        JSONObject capabilities = JSONObject.parseObject(response.body());
        assertEquals(1, capabilities.getIntValue("apiVersion"), "管理 API 契约版本应为 1");

        JSONObject features = capabilities.getJSONObject("features");
        assertNotNull(features, "必须声明 features");
        assertTrue(features.getBooleanValue("dataService"), "数据服务能力应可用");
        assertTrue(features.getBooleanValue("sqlRepl"), "SQL REPL 能力应可用");

        assertNotNull(capabilities.getJSONObject("ports"), "必须声明端口");
        assertTrue(capabilities.containsKey("plugins"), "必须包含插件列表（可为空）");
        assertEquals("2026-07-28", capabilities.getJSONObject("contracts").getString("mcp"),
                "MCP 协议版本应与服务端实现一致");

        assertFalse(response.body().contains("mcpLlmKey"),
                "能力声明不得携带敏感参数");
    }

    @Test
    @DisplayName("/health 是轻量存活检查且不依赖 /status 的重逻辑")
    void healthReportsUp() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = get("/health");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(200, response.statusCode(), "GET /health 应为 200");
        JSONObject health = JSONObject.parseObject(response.body());
        assertEquals("UP", health.getString("status"));
        assertEquals("UP", health.getString("database"));
        assertTrue(elapsedMillis < 1000,
                "健康检查应轻量（实测 " + elapsedMillis + "ms）");
    }

    @Test
    @DisplayName("杀掉不存在的会话返回统一错误信封")
    void killUnknownSessionReturnsEnvelope() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/sessions/999999/kill"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode(), "未知会话应返回 404");
        JSONObject body = JSONObject.parseObject(response.body());
        assertEquals(404, body.getIntValue("retCode"));
        assertEquals("NOT_FOUND", body.getString("errorClass"),
                "错误信封必须带 errorClass，客户端才能区分错误类别");
    }

    @Test
    @DisplayName("/status.sessions 能辨识来源（type / applicationName），并可被 HTTP 接口终止")
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
            assertNotNull(sessionId, "/status.sessions 应能按 applicationName 找到这条连接");

            // 通过 HTTP 终止它（UI 走这条路径，而不是自定义 TCP 管理协议）
            HttpResponse<String> killResponse = CLIENT.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/sessions/" + sessionId + "/kill"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, killResponse.statusCode(),
                    "终止存在的会话应返回 200，实际响应：" + killResponse.body());
            assertTrue(JSONObject.parseObject(killResponse.body()).getBooleanValue("killed"));
        }
    }

    /** 在 /status.sessions 里按 applicationName 找会话 id；找不到返回 null。 */
    private static Integer findSessionIdByApplicationName(String applicationName) throws Exception {
        HttpResponse<String> response = get("/status");
        assertEquals(200, response.statusCode());
        JSONArray sessions = JSONObject.parseObject(response.body()).getJSONArray("sessions");
        assertNotNull(sessions, "/status 必须包含 sessions 数组");
        Integer found = null;
        for (int i = 0; i < sessions.size(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            assertEquals("pgwire", session.getString("type"), "会话类型应显式声明");
            assertTrue(session.containsKey("applicationName"), "会话应包含 applicationName 字段");
            if (applicationName.equals(session.getString("applicationName"))) {
                found = session.getInteger("id");
            }
        }
        if (found == null) {
            fail("未在 /status.sessions 中找到 applicationName=[" + applicationName + "]；实际内容："
                    + sessions.toJSONString());
        }
        return found;
    }
}
