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
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发行包里 {@code conf/mcp_config_example.service} 的守卫测试。
 *
 * <p><b>为什么需要它</b>：这份样例是对外发布的（deployment.xml 收进 conf/），
 * 但它此前含有三类"启动加载器从不读取"的死内容——顶层 {@code services}、顶层
 * {@code metadata}、资源里的 {@code filePath}；而文档提到的内置工具
 * （{@code list_tables}/{@code sample_rows} 等）又因为<b>本来就无需声明</b>而不在
 * 样例里出现，于是样例与文档看起来对不上。这次把样例改成"写进去的必须真的生效"，
 * 用这组用例钉住：</p>
 *
 * <ul>
 *   <li>样例里声明的每一条都真的注册进了 tools/resources；</li>
 *   <li>带 {@code handler} 的条目确实绑定到内置实现（带真实 outputSchema、能执行）；</li>
 *   <li>内置工具即使未被声明也必须始终在列表里；</li>
 *   <li>列表里没有重名条目（外部工具名与内置名撞车会在 tools/list 里出现两条同名工具）。</li>
 * </ul>
 *
 * <p>与 {@link McpConfigLoadTest} 的分工：那个用临时配置验证加载器契约，
 * 这个验证随发行包发布的真实样例文件。</p>
 */
public class McpExampleConfigTest {

    private static final Path EXAMPLE_CONFIG = Path.of("conf", "mcp_config_example.service");

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static DBInstance dbInstance;
    private static String baseUrl;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        assertTrue(Files.isRegularFile(EXAMPLE_CONFIG),
                "example config not found at " + EXAMPLE_CONFIG.toAbsolutePath()
                        + "; this test must run with the dbserver module as working directory");

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setSqlHistory("OFF");
        configuration.setDataServiceHistory("OFF");
        configuration.setMcpConfig(EXAMPLE_CONFIG.toString());

        dbInstance = new DBInstance(configuration);
        dbInstance.start();
        assertEquals("RUNNING", dbInstance.instanceState.toUpperCase());
        baseUrl = "http://127.0.0.1:" + configuration.getPortX();
    }

    @AfterAll
    static void tearDownAll() {
        if (dbInstance != null) {
            dbInstance.stop();
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

    private JSONArray listTools() throws Exception {
        return rpc("tools/list", new JSONObject()).getJSONObject("result").getJSONArray("tools");
    }

    private JSONObject findTool(JSONArray tools, String name) {
        for (int i = 0; i < tools.size(); i++) {
            if (name.equals(tools.getJSONObject(i).getString("name"))) {
                return tools.getJSONObject(i);
            }
        }
        return null;
    }

    @Test
    @DisplayName("Every entry declared in the shipped example config is really registered")
    void exampleEntriesAreRegistered() throws Exception {
        JSONArray tools = listTools();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < tools.size(); i++) {
            names.add(tools.getJSONObject(i).getString("name"));
        }

        JSONObject echo = findTool(tools, "echo");
        assertNotNull(echo, "tool 'echo' declared in the example was not registered");
        assertTrue(echo.getString("description").contains("echoes its arguments"),
                "a tool declared without 'handler' must say so in its description: " + echo);

        // 带 handler 的条目必须绑定到内置实现：证据是拿到了内置实现的真实 outputSchema
        for (String alias : new String[]{"query", "top_tables", "peek_rows"}) {
            JSONObject tool = findTool(tools, alias);
            assertNotNull(tool, "tool '" + alias + "' declared in the example was not registered");
            assertNotNull(tool.getJSONObject("outputSchema"),
                    "tool '" + alias + "' must be bound to a builtin implementation, not an echo stub");
        }

        // 内置工具不需要（也不应该需要）在配置里声明
        for (String builtin : new String[]{"run_sql", "list_tables", "sample_rows"}) {
            assertTrue(names.contains(builtin),
                    "builtin tool '" + builtin + "' must be present without being declared: " + names);
        }

        assertEquals(tools.size(), names.size(),
                "tools/list must not contain duplicate names: " + names);

        JSONArray resources = rpc("resources/list", new JSONObject())
                .getJSONObject("result").getJSONArray("resources");
        boolean found = false;
        for (int i = 0; i < resources.size(); i++) {
            if ("docs://mcp/config-example".equals(resources.getJSONObject(i).getString("uri"))) {
                found = true;
                break;
            }
        }
        assertTrue(found, "resource declared in the example was not registered");
    }

    @Test
    @DisplayName("An alias declared in the shipped example really executes its builtin")
    void exampleAliasExecutesBuiltin() throws Exception {
        JSONObject arguments = new JSONObject();
        arguments.put("sql", "select 42 as answer");
        JSONObject params = new JSONObject();
        params.put("name", "query");
        params.put("arguments", arguments);

        JSONObject envelope = rpc("tools/call", params);
        assertTrue(envelope.get("error") == null, "alias 'query' must execute: " + envelope);
        JSONObject structured = envelope.getJSONObject("result").getJSONObject("structuredContent");
        assertEquals(1, structured.getIntValue("row_count"));
        assertEquals(42, structured.getJSONArray("rows").getJSONArray(0).getIntValue(0));
    }

    @Test
    @DisplayName("The resource declared in the shipped example is readable")
    void exampleResourceIsReadable() throws Exception {
        JSONObject params = new JSONObject();
        params.put("uri", "docs://mcp/config-example");
        JSONObject envelope = rpc("resources/read", params);
        assertTrue(envelope.get("error") == null, "example resource must be readable: " + envelope);
        JSONArray contents = envelope.getJSONObject("result").getJSONArray("contents");
        assertEquals(1, contents.size());
        assertTrue(contents.getJSONObject(0).getString("text").contains("nothing is read from disk"),
                "resource must serve its own contents field: " + contents);
    }
}
