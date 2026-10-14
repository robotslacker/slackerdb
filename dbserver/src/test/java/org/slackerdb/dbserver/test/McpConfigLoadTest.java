package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mcp_config} 启动加载的契约测试。
 *
 * <p>这条能力此前<b>只存在于文档里</b>：配置项只在保存/导出端点被读取，启动路径从不加载，
 * 因此每次重启后注册表都是空的。这组用例把它钉住。</p>
 *
 * <p>同时验证 {@code handler: builtin:...} 会绑定到真实实现，而不是像旧实现那样
 * 一律挂成 echo 桩（旧代码从不读取 {@code handler} 字段，配置里写的语义被静默丢弃）。</p>
 */
public class McpConfigLoadTest {

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static DBInstance dbInstance;
    private static String baseUrl;
    private static Path tempConfigDir;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        tempConfigDir = Files.createTempDirectory("slackerdb-mcp-config");
        Path configFile = tempConfigDir.resolve("tools.mcp");
        Files.writeString(configFile, """
                {
                  "tools": [
                    {
                      "name": "cfg_run_sql",
                      "description": "Configured read-only SQL tool",
                      "handler": "builtin:query"
                    },
                    {
                      "name": "cfg_echo",
                      "description": "Configured echo tool with no handler",
                      "inputSchema": {
                        "type": "object",
                        "properties": { "text": { "type": "string" } },
                        "required": ["text"]
                      }
                    }
                  ],
                  "resources": [
                    {
                      "uri": "file:///configured.txt",
                      "name": "Configured resource",
                      "description": "Loaded from mcp_config at startup",
                      "mimeType": "text/plain",
                      "contents": "configured-at-startup"
                    },
                    {
                      "uri": "slackerdb://configured/reserved-prefix",
                      "name": "Configured resource in the reserved namespace",
                      "description": "Must stay readable even though slackerdb:// routes to builtin readers",
                      "mimeType": "text/plain",
                      "contents": "configured-reserved-prefix"
                    }
                  ]
                }
                """);

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setSqlHistory("OFF");
        configuration.setDataServiceHistory("OFF");
        configuration.setMcpConfig(configFile.toString());

        dbInstance = new DBInstance(configuration);
        dbInstance.start();
        assertEquals("RUNNING", dbInstance.instanceState.toUpperCase());
        baseUrl = "http://127.0.0.1:" + configuration.getPortX();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (dbInstance != null) {
            dbInstance.stop();
        }
        if (tempConfigDir != null) {
            try (var paths = Files.walk(tempConfigDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 临时目录清理失败不影响测试结论
                    }
                });
            }
        }
    }

    private JSONObject rpc(String method, JSONObject params) throws Exception {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        JSONObject meta = new JSONObject();
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        JSONObject effective = params == null ? new JSONObject() : params;
        effective.put("_meta", meta);
        request.put("params", effective);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toJSONString()))
                .build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return JSON.parseObject(response.body());
    }

    @Test
    @DisplayName("Tools and resources declared in mcp_config are loaded at startup")
    void configIsLoadedAtStartup() throws Exception {
        JSONArray tools = rpc("tools/list", new JSONObject()).getJSONObject("result").getJSONArray("tools");
        boolean foundConfiguredTool = false;
        boolean foundConfiguredBuiltin = false;
        for (int i = 0; i < tools.size(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            String name = tool.getString("name");
            if ("cfg_echo".equals(name)) {
                foundConfiguredTool = true;
                // 没有 handler 的工具必须自曝其短，而不是伪装成能干活
                assertTrue(tool.getString("description").contains("echoes its arguments"),
                        "a handler-less tool must say so in its description");
            }
            if ("cfg_run_sql".equals(name)) {
                foundConfiguredBuiltin = true;
                // builtin:query 映射到 run_sql，因此必须带上真正的 outputSchema
                assertNotNull(tool.getJSONObject("outputSchema"),
                        "a builtin-bound tool must expose the real outputSchema");
            }
        }
        assertTrue(foundConfiguredTool, "cfg_echo from mcp_config was not loaded");
        assertTrue(foundConfiguredBuiltin, "cfg_run_sql from mcp_config was not loaded");

        JSONArray resources = rpc("resources/list", new JSONObject())
                .getJSONObject("result").getJSONArray("resources");
        boolean foundConfiguredResource = false;
        for (int i = 0; i < resources.size(); i++) {
            if ("file:///configured.txt".equals(resources.getJSONObject(i).getString("uri"))) {
                foundConfiguredResource = true;
                break;
            }
        }
        assertTrue(foundConfiguredResource, "resource from mcp_config was not loaded");
    }

    @Test
    @DisplayName("A tool bound to builtin:query actually executes SQL")
    void builtinBoundToolExecutes() throws Exception {
        JSONObject arguments = new JSONObject();
        arguments.put("sql", "select 7 as seven");
        JSONObject params = new JSONObject();
        params.put("name", "cfg_run_sql");
        params.put("arguments", arguments);

        JSONObject envelope = rpc("tools/call", params);
        assertTrue(envelope.get("error") == null, "builtin-bound tool must execute: " + envelope);
        JSONObject structured = envelope.getJSONObject("result").getJSONObject("structuredContent");
        assertEquals(1, structured.getIntValue("row_count"));
        assertEquals(7, structured.getJSONArray("rows").getJSONArray(0).getIntValue(0));
    }

    @Test
    @DisplayName("Registered resources are readable, including under the reserved slackerdb:// namespace")
    void registeredResourcesAreReadable() throws Exception {
        assertReadableResource("file:///configured.txt", "configured-at-startup");
        // 曾经 slackerdb:// 一律先路由到内置读取器，配置里声明的该命名空间资源
        // 在列表里可见、一读却报 Unknown resource URI。
        assertReadableResource("slackerdb://configured/reserved-prefix", "configured-reserved-prefix");
        // 内置资源必须仍然可读，不能被上面的优先级压掉
        JSONObject builtin = rpc("resources/read", uriParams("slackerdb://services"));
        assertTrue(builtin.get("error") == null, "builtin resource must stay readable: " + builtin);
    }

    private void assertReadableResource(String uri, String expectedText) throws Exception {
        JSONObject envelope = rpc("resources/read", uriParams(uri));
        assertTrue(envelope.get("error") == null, "resource " + uri + " must be readable: " + envelope);
        JSONArray contents = envelope.getJSONObject("result").getJSONArray("contents");
        assertNotNull(contents, uri + " must return a contents array");
        assertEquals(1, contents.size());
        assertTrue(contents.getJSONObject(0).getString("text").contains(expectedText),
                "resource " + uri + " must return its configured contents: " + contents);
    }

    private static JSONObject uriParams(String uri) {
        JSONObject params = new JSONObject();
        params.put("uri", uri);
        return params;
    }
}
