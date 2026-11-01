package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code max_execution_time} 的端到端回归：服务器 GLOBAL、服务定义、请求头三层优先级，
 * 连接池归还复位，backend 内部豁免，以及 MCP 侧的字段暴露。
 */
public class MaxExecutionTimeTest {

    /** 约 1.5-3 秒的查询：足够被毫秒级超时打断，又能在“不限制”时跑完。 */
    static final String SLOW_SQL = "SELECT count(*) AS v FROM range(5000000000)";

    static int dbPortX;
    static DBInstance dbInstance;

    @BeforeAll
    static void initAll() throws ServerException {
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setPortX(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        serverConfiguration.setDataServiceHistory("OFF");
        serverConfiguration.setMax_execution_time(150);
        dbPortX = serverConfiguration.getPortX();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
        assertEquals("RUNNING", dbInstance.instanceState);
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
        assertEquals("IDLE", dbInstance.instanceState);
    }

    // ------------------------------------------------------------------ 配置 / 内部豁免

    @Test
    void configParameterParsesAndValidates() throws Exception {
        assertEquals(0L, new ServerConfiguration().getMax_execution_time(), "默认应为 0（不限制）");

        Path configFile = Files.createTempFile("max_execution_time", ".conf");
        try {
            Files.writeString(configFile, "max_execution_time=300\n");
            ServerConfiguration parsed = new ServerConfiguration();
            parsed.loadConfigurationFile(configFile.toString());
            assertEquals(300L, parsed.getMax_execution_time());
        } finally {
            Files.deleteIfExists(configFile);
        }

        ServerConfiguration invalid = new ServerConfiguration();
        assertThrows(ServerException.class, () -> invalid.setMax_execution_time("-1"));
        assertThrows(ServerException.class, () -> invalid.setMax_execution_time("not-a-number"));
    }

    /** 内部执行豁免：启动脚本跑在 backend 连接上，即使它远比 GLOBAL 慢也不能被中断。 */
    @Test
    void startupScriptIsExemptFromGlobalLimit() throws Exception {
        Path script = Files.createTempFile("met_slow_startup", ".sql");
        Path configFile = Files.createTempFile("met_script_config", ".conf");
        DBInstance scripted = null;
        try {
            Files.writeString(script, "SELECT count(*) FROM range(2000000000);\n");
            Files.writeString(configFile, "max_execution_time=50\n");

            ServerConfiguration configuration = new ServerConfiguration();
            configuration.loadConfigurationFile(configFile.toString());
            configuration.setPort(0);
            configuration.setPortX(0);
            configuration.setData("mem");
            configuration.setLog_level("INFO");
            configuration.setSqlHistory("OFF");
            configuration.setStartup_script(script.toString());

            scripted = new DBInstance(configuration);
            scripted.start(); // 50ms GLOBAL 下若脚本不豁免，这里会因超时启动失败
            assertEquals("0", setting(scripted.backendSysConnection),
                    "backend 连接必须始终豁免");
        } finally {
            if (scripted != null) {
                scripted.stop();
            }
            Files.deleteIfExists(script);
            Files.deleteIfExists(configFile);
        }
    }

    @Test
    void backendIsExemptWhilePoolSeesGlobal() throws Exception {
        assertEquals("0", setting(dbInstance.backendSysConnection),
                "backend 连接必须始终豁免（0）");
        assertEquals("150", poolSetting(),
                "连接池连接应看到服务器 GLOBAL 设置");
    }

    // ------------------------------------------------------------------ 服务级覆盖

    @Test
    void serverGlobalAppliesWhenServiceHasNoOverride() throws Exception {
        register("metGlobal", SLOW_SQL, null);

        JSONObject response = getJson("/api/1.0/metGlobal");
        assertEquals(-1, response.getIntValue("retCode"), "没有覆盖时应跟随 GLOBAL：" + response);
        assertTrue(response.getString("retMsg").contains("maximum execution time"),
                "错误信息应来自 DuckDB：" + response);

        // 超时之后连接可继续复用
        register("metFast", "SELECT 1 AS v", null);
        assertEquals(0, getJson("/api/1.0/metFast").getIntValue("retCode"));
    }

    @Test
    void serviceDefinitionOverridesGlobal() throws Exception {
        // 0 = 该服务显式不限制，覆盖 GLOBAL 150ms
        register("metUnlimited", SLOW_SQL, 0L);
        JSONObject unlimited = getJson("/api/1.0/metUnlimited");
        assertEquals(0, unlimited.getIntValue("retCode"), unlimited.toJSONString());
        assertEquals(5_000_000_000L,
                unlimited.getJSONObject("data").getJSONArray("dataset").getJSONArray(0).getLongValue(0));

        // 更小的值同样覆盖 GLOBAL
        register("metTiny", SLOW_SQL, 20L);
        JSONObject tiny = getJson("/api/1.0/metTiny");
        assertEquals(-1, tiny.getIntValue("retCode"), "服务定义 20ms 应覆盖 GLOBAL：" + tiny);
        assertTrue(tiny.getString("retMsg").contains("maximum execution time"), tiny.toJSONString());
    }

    // ------------------------------------------------------------------ 请求头覆盖

    @Test
    void requestHeaderOverridesServiceAndGlobal() throws Exception {
        // 服务显式 20ms，请求头 60s → 成功
        register("metHeaderBig", SLOW_SQL, 20L);
        JSONObject big = getJson("/api/1.0/metHeaderBig", "maxExecutionTime", "60000");
        assertEquals(0, big.getIntValue("retCode"), "请求头应覆盖服务定义：" + big);

        // 服务不限制，请求头 20ms → 超时
        register("metHeaderSmall", SLOW_SQL, 0L);
        JSONObject small = getJson("/api/1.0/metHeaderSmall", "maxExecutionTime", "20");
        assertEquals(-1, small.getIntValue("retCode"), "请求头 20ms 应覆盖服务的 0：" + small);

        // 请求头 0 → 本次不限制（服务/全局都大于 0）
        register("metHeaderZero", SLOW_SQL, 20L);
        JSONObject zero = getJson("/api/1.0/metHeaderZero", "maxExecutionTime", "0");
        assertEquals(0, zero.getIntValue("retCode"), "请求头 0 应表示本次不限制：" + zero);
    }

    @Test
    void invalidHeaderIsRejected() throws Exception {
        register("metHeaderInvalid", "SELECT 1 AS v", null);

        JSONObject text = getJson("/api/1.0/metHeaderInvalid", "maxExecutionTime", "abc");
        assertEquals(-1, text.getIntValue("retCode"), text.toJSONString());
        assertTrue(text.getString("retMsg").contains("Invalid maxExecutionTime"), text.toJSONString());

        JSONObject negative = getJson("/api/1.0/metHeaderInvalid", "maxExecutionTime", "-5");
        assertEquals(-1, negative.getIntValue("retCode"), negative.toJSONString());
        assertTrue(negative.getString("retMsg").contains("Invalid maxExecutionTime"), negative.toJSONString());
    }

    // ------------------------------------------------------------------ 注册校验 / 回显

    @Test
    void registrationValidatesAndEchoesTheField() throws Exception {
        JSONObject negative = postJson("/api/registerService", serviceJson("metNeg", SLOW_SQL, -1L));
        assertEquals(-1, negative.getIntValue("retCode"), negative.toJSONString());
        assertTrue(negative.getString("retMsg").contains("maxExecutionTime"), negative.toJSONString());

        JSONObject malformed = postJson("/api/registerService", serviceJson("metBad", SLOW_SQL, "abc"));
        assertEquals(-1, malformed.getIntValue("retCode"), malformed.toJSONString());

        register("metEcho", "SELECT 1 AS v", 250L);
        JSONArray listed = JSONArray.parseArray(
                HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder().uri(new URI("http://127.0.0.1:" + dbPortX + "/api/listRegisteredService"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body());
        boolean found = false;
        for (int i = 0; i < listed.size(); i++) {
            JSONObject item = listed.getJSONObject(i);
            if ("metEcho".equals(item.getString("serviceName"))) {
                assertEquals(250L, item.getLongValue("maxExecutionTime"));
                found = true;
            }
        }
        assertTrue(found, "listRegisteredService 应回显 maxExecutionTime：" + listed);

        // dump 里也要带上，保证保存/重新加载不丢字段
        String dump = postRaw("/api/downloadRegisteredService", "{}").body();
        assertTrue(dump.contains("\"maxExecutionTime\":250"), "dump 应保留 maxExecutionTime：" + dump);
    }

    // ------------------------------------------------------------------ 连接池复位

    @Test
    void poolReleaseRestoresGlobal() throws Exception {
        try (Connection connection = dbInstance.dbDataSourcePool.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION max_execution_time=777");
            assertEquals("777", setting(connection));
        }
        assertEquals("150", poolSetting(), "连接归还后必须回到 GLOBAL");
    }

    // ------------------------------------------------------------------ MCP 暴露

    @Test
    void mcpListServicesExposesMaxExecutionTime() throws Exception {
        register("metMcp", "SELECT 1 AS v", 123L);

        JSONObject params = new JSONObject();
        params.put("name", "list_services");
        params.put("arguments", new JSONObject());
        JSONObject meta = new JSONObject();
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        params.put("_meta", meta);

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        request.put("params", params);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Mcp-Method", "tools/call")
                .POST(HttpRequest.BodyPublishers.ofString(request.toJSONString()))
                .build();
        JSONObject envelope = JSON.parseObject(
                HttpClient.newHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString()).body());
        assertNotNull(envelope.getJSONObject("result"), "MCP 调用失败：" + envelope);

        JSONArray services = envelope.getJSONObject("result")
                .getJSONObject("structuredContent").getJSONArray("services");
        boolean found = false;
        for (int i = 0; i < services.size(); i++) {
            JSONObject service = services.getJSONObject(i);
            if ("metMcp".equals(service.getString("name"))) {
                assertEquals(123L, service.getLongValue("max_execution_time"),
                        "MCP list_services 应暴露服务级超时：" + service);
                found = true;
            }
        }
        assertTrue(found, "list_services 应包含 metMcp：" + services);
    }

    @Test
    void mcpRunSqlSupportsPerCallMaxExecutionTime() throws Exception {
        // 1. 20ms 覆盖 → 慢查询被中断，MCP 返回错误
        JSONObject timeout = mcpCallTool("run_sql", args(
                "sql", SLOW_SQL,
                "maxExecutionTime", 20));
        assertNotNull(timeout.getJSONObject("error"), "20ms 覆盖应中断慢查询：" + timeout);
        assertTrue(timeout.getJSONObject("error").getString("message").contains("maximum execution time"),
                timeout.toJSONString());

        // 2. 0 = 本次不限制 → 成功
        JSONObject unlimited = mcpCallTool("run_sql", args(
                "sql", SLOW_SQL,
                "maxExecutionTime", 0));
        JSONObject unlimitedContent = unlimited.getJSONObject("result").getJSONObject("structuredContent");
        assertEquals(1, unlimitedContent.getIntValue("row_count"), unlimited.toJSONString());
        assertEquals(5_000_000_000L,
                unlimitedContent.getJSONArray("rows").getJSONArray(0).getLongValue(0));

        // 3. 带 handle：单次覆盖后必须恢复会话原值（GLOBAL=150）
        JSONObject opened = mcpCallTool("open_session", new JSONObject());
        String handle = opened.getJSONObject("result").getJSONObject("structuredContent").getString("handle");
        try {
            JSONObject tiny = mcpCallTool("run_sql", args(
                    "sql", SLOW_SQL,
                    "maxExecutionTime", 20,
                    "handle", handle));
            assertNotNull(tiny.getJSONObject("error"), tiny.toJSONString());

            JSONObject probe = mcpCallTool("run_sql", args(
                    "sql", "SELECT current_setting('max_execution_time') AS v",
                    "handle", handle));
            JSONArray row = probe.getJSONObject("result").getJSONObject("structuredContent")
                    .getJSONArray("rows").getJSONArray(0);
            assertEquals(150L, row.getLongValue(0),
                    "单次覆盖后会话值应恢复为 GLOBAL：" + probe);
        } finally {
            mcpCallTool("close_session", args("handle", handle));
        }

        // 4. 非法值 → 参数错误
        JSONObject invalid = mcpCallTool("run_sql", args(
                "sql", "SELECT 1",
                "maxExecutionTime", -1));
        assertEquals(-32602, invalid.getJSONObject("error").getIntValue("code"), invalid.toJSONString());
    }

    // ------------------------------------------------------------------ helpers

    static JSONObject args(Object... keyValues) {
        JSONObject arguments = new JSONObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            arguments.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return arguments;
    }

    /** 调用一个 MCP 工具，返回完整 JSON-RPC 信封（成功在 result，失败在 error）。 */
    static JSONObject mcpCallTool(String name, JSONObject arguments) throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", name);
        params.put("arguments", arguments == null ? new JSONObject() : arguments);
        JSONObject meta = new JSONObject();
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        params.put("_meta", meta);

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        request.put("params", params);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Mcp-Method", "tools/call")
                .POST(HttpRequest.BodyPublishers.ofString(request.toJSONString()))
                .build();
        return JSON.parseObject(
                HttpClient.newHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString()).body());
    }

    static String setting(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT current_setting('max_execution_time')")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    static String poolSetting() throws SQLException {
        Connection connection = dbInstance.dbDataSourcePool.getConnection();
        try {
            return setting(connection);
        } finally {
            dbInstance.dbDataSourcePool.releaseConnection(connection);
        }
    }

    static String serviceJson(String name, String sql, Object maxExecutionTime) {
        JSONObject body = new JSONObject();
        body.put("serviceName", name);
        body.put("serviceVersion", "1.0");
        body.put("serviceType", "GET");
        body.put("category", "met");
        body.put("searchPath", "main");
        body.put("sql", sql);
        if (maxExecutionTime != null) {
            body.put("maxExecutionTime", maxExecutionTime);
        }
        return body.toJSONString();
    }

    static void register(String name, String sql, Object maxExecutionTime) throws Exception {
        JSONObject response = postJson("/api/registerService", serviceJson(name, sql, maxExecutionTime));
        assertEquals(0, response.getIntValue("retCode"),
                "注册 " + name + " 失败：" + response);
    }

    static JSONObject getJson(String path, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + path))
                .GET()
                .header("Content-Type", "application/json");
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return JSON.parseObject(HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    static JSONObject postJson(String path, String body) throws Exception {
        return JSON.parseObject(postRaw(path, body).body());
    }

    static HttpResponse<String> postRaw(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "POST " + path + " -> " + response.body());
        return response;
    }
}
