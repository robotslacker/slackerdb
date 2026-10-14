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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具注册表的两条回环契约。
 *
 * <p><b>1) 保存-重载不能丢内置绑定。</b> {@code Tool} 此前只持有一个 lambda，不记录"挂在哪个内置
 * 实现上"，于是 {@code saveMCPTool} / {@code dumpMCPTool} 写出的文件里没有 {@code handler}：
 * 在控制台点一次保存、再重启，内置绑定就退化成回显桩（而 schema 仍是内置那份，声明与行为反而更不一致）。
 * 这里走完整闭环：配置 → 启动 → 保存 → 读回文件 → 用保存后的文件重启 → 别名仍能真的执行 SQL。</p>
 *
 * <p><b>2) 内置工具名是保留的。</b> {@code tools/list} 是"内置 + 外部"两段拼接，外部工具若与内置
 * 同名会出现两条同名工具（调用还会被内置实现截走）。配置声明、{@code registerMCPTool}、
 * {@code loadMCPTool} 三条入口都必须拒绝。</p>
 *
 * <p>顺带钉住描述文本的幂等性：无 handler 工具的"只会回显入参"提示改为展示时拼接，
 * 不再写进 description，因此反复 save/load 不会越滚越长。</p>
 */
public class McpToolRoundTripTest {

    private static final String ECHO_NOTE = "only echoes its arguments";

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static Path tempDir;
    private static Path configFile;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        tempDir = Files.createTempDirectory("slackerdb-mcp-roundtrip");
        configFile = tempDir.resolve("tools.mcp");
        Files.writeString(configFile, """
                {
                  "tools": [
                    {
                      "name": "rt_run_sql",
                      "description": "Configured read-only SQL tool",
                      "category": "database",
                      "handler": "builtin:query"
                    },
                    {
                      "name": "rt_echo",
                      "description": "Configured echo tool with no handler",
                      "inputSchema": {
                        "type": "object",
                        "properties": { "text": { "type": "string" } },
                        "required": ["text"]
                      }
                    },
                    {
                      "name": "list_tables",
                      "description": "Declaration that steals a builtin name and must be skipped",
                      "inputSchema": { "type": "object" }
                    },
                    {
                      "name": "top_tables",
                      "description": "Configured alias of the builtin list_tables",
                      "handler": "builtin:list_tables"
                    }
                  ],
                  "resources": []
                }
                """);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (tempDir != null) {
            try (var paths = Files.walk(tempDir)) {
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

    private record Running(DBInstance instance, String baseUrl) {
    }

    private static Running start(Path configPath) throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setSqlHistory("OFF");
        configuration.setDataServiceHistory("OFF");
        configuration.setMcpConfig(configPath.toString());

        DBInstance instance = new DBInstance(configuration);
        instance.start();
        assertEquals("RUNNING", instance.instanceState.toUpperCase());
        return new Running(instance, "http://127.0.0.1:" + configuration.getPortX());
    }

    private static JSONObject post(Running running, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(running.baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "POST " + path + " -> " + response.body());
        return JSON.parseObject(response.body());
    }

    private static JSONObject rpc(Running running, String method, JSONObject params) throws Exception {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        JSONObject meta = new JSONObject();
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        JSONObject effective = params == null ? new JSONObject() : params;
        effective.put("_meta", meta);
        request.put("params", effective);
        return post(running, "/mcp", request.toJSONString());
    }

    private static JSONArray listTools(Running running) throws Exception {
        return rpc(running, "tools/list", new JSONObject()).getJSONObject("result").getJSONArray("tools");
    }

    private static JSONObject findTool(JSONArray tools, String name) {
        for (int i = 0; i < tools.size(); i++) {
            if (name.equals(tools.getJSONObject(i).getString("name"))) {
                return tools.getJSONObject(i);
            }
        }
        return null;
    }

    private static JSONObject findToolInFile(JSONArray tools, String name) {
        return findTool(tools, name);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    private static void assertNoDuplicateNames(JSONArray tools) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < tools.size(); i++) {
            names.add(tools.getJSONObject(i).getString("name"));
        }
        assertEquals(tools.size(), names.size(), "tools/list must not contain duplicate names: " + names);
    }

    private static JSONObject toolCall(String name, String sql) {
        JSONObject arguments = new JSONObject();
        arguments.put("sql", sql);
        JSONObject params = new JSONObject();
        params.put("name", name);
        params.put("arguments", arguments);
        return params;
    }

    @Test
    @DisplayName("A save/restart cycle keeps builtin bindings, and builtin names stay reserved")
    void saveRestartAndReservedNames() throws Exception {
        // ---------- 阶段 1：配置声明的撞名条目被跳过，内置工具完好 ----------
        Running first = start(configFile);
        try {
            JSONArray tools = listTools(first);
            assertNoDuplicateNames(tools);

            JSONObject builtinListTables = findTool(tools, "list_tables");
            assertNotNull(builtinListTables, "the builtin list_tables must be present");
            assertNotNull(builtinListTables.getJSONObject("outputSchema"),
                    "the entry reusing the builtin name must be skipped, leaving the real builtin: "
                            + builtinListTables);

            assertNotNull(findTool(tools, "top_tables").getJSONObject("outputSchema"),
                    "a configured alias must be bound to its builtin");
            assertTrue(findTool(tools, "rt_echo").getString("description").contains(ECHO_NOTE),
                    "a handler-less tool must self-report in tools/list");

            // ---------- 阶段 2：管理端点同样拒绝保留名 ----------
            JSONObject registerBody = new JSONObject();
            registerBody.put("name", "sample_rows");
            registerBody.put("description", "Trying to reuse a builtin name");
            registerBody.put("inputSchema", new JSONObject().fluentPut("type", "object"));
            JSONObject registerResp = post(first, "/mcp/registerMCPTool", registerBody.toJSONString());
            assertEquals(-1, registerResp.getIntValue("retCode"),
                    "registerMCPTool must reject a builtin name: " + registerResp);
            assertTrue(registerResp.getString("retMsg").contains("reserved"),
                    "the rejection must say the name is reserved: " + registerResp);

            JSONObject loadBody = new JSONObject();
            loadBody.put("name", "run_sql");
            loadBody.put("description", "Trying to reuse a builtin name");
            loadBody.put("inputSchema", new JSONObject().fluentPut("type", "object"));
            JSONObject loadResp = post(first, "/mcp/loadMCPTool", loadBody.toJSONString());
            assertEquals(-1, loadResp.getIntValue("retCode"),
                    "loadMCPTool must reject a builtin name: " + loadResp);

            assertNoDuplicateNames(listTools(first));

            // ---------- 阶段 3：落盘 ----------
            JSONObject saveResp = post(first, "/mcp/saveMCPTool", "{}");
            assertEquals(0, saveResp.getIntValue("retCode"), "saveMCPTool failed: " + saveResp);
        } finally {
            first.instance().stop();
        }

        // ---------- 阶段 4：文件里必须带着 handler，且不含展示用的提示语 ----------
        JSONArray savedTools = JSON.parseObject(Files.readString(configFile)).getJSONArray("tools");

        JSONObject savedSql = findToolInFile(savedTools, "rt_run_sql");
        assertNotNull(savedSql, "rt_run_sql disappeared from the saved file");
        assertEquals("builtin:run_sql", savedSql.getString("handler"),
                "the saved file must carry the resolved builtin binding: " + savedSql);
        assertFalse(savedSql.getString("description").contains(ECHO_NOTE),
                "a builtin-bound tool must not be relabelled as an echo stub: " + savedSql);

        JSONObject savedEcho = findToolInFile(savedTools, "rt_echo");
        assertNotNull(savedEcho, "rt_echo disappeared from the saved file");
        assertNull(savedEcho.getString("handler"), "a handler-less tool must stay handler-less: " + savedEcho);
        assertEquals(0, countOccurrences(savedEcho.getString("description"), ECHO_NOTE),
                "the echo note belongs to the presentation, not to the saved declaration: " + savedEcho);

        assertNull(findToolInFile(savedTools, "list_tables"),
                "a declaration that was skipped at load must not be resurrected by save");

        // ---------- 阶段 5：用保存后的文件重启，绑定必须还在且真的能干活 ----------
        Running second = start(configFile);
        try {
            JSONObject restarted = findTool(listTools(second), "rt_run_sql");
            assertNotNull(restarted, "rt_run_sql was not loaded after the restart");
            assertNotNull(restarted.getJSONObject("outputSchema"),
                    "after a save/restart cycle the tool must still be bound to its builtin, not an echo stub");
            assertEquals(1, countOccurrences(findTool(listTools(second), "rt_echo")
                            .getString("description"), ECHO_NOTE),
                    "the echo note must not accumulate across save/load cycles");

            JSONObject envelope = rpc(second, "tools/call", toolCall("rt_run_sql", "select 7 as seven"));
            assertTrue(envelope.get("error") == null, "the alias must still execute after a restart: " + envelope);
            JSONObject structured = envelope.getJSONObject("result").getJSONObject("structuredContent");
            assertEquals(1, structured.getIntValue("row_count"));
            assertEquals(7, structured.getJSONArray("rows").getJSONArray(0).getIntValue(0));
        } finally {
            second.instance().stop();
        }
    }
}
