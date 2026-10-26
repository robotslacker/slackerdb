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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 2026-07-28 协议契约测试。
 *
 * <p>这些断言直接对应规范条款，用来防止"文档说支持、实现其实没有"再次发生。
 * 覆盖：无状态请求、强制的 server/discover、双代兼容、resultType、
 * 缓存契约、确定性 tools/list、只读强制、行数上限、显式会话 handle、
 * 以及被移除方法/端点的明确拒绝。</p>
 */
public class McpProtocolTest {

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static DBInstance dbInstance;
    private static String baseUrl;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setSqlHistory("OFF");
        configuration.setDataServiceHistory("OFF");

        dbInstance = new DBInstance(configuration);
        dbInstance.start();
        assertEquals("RUNNING", dbInstance.instanceState.toUpperCase());
        baseUrl = "http://127.0.0.1:" + configuration.getPortX();

        // 造点数据，供 list_tables / describe_table / sample_rows 使用
        try (var conn = dbInstance.dbDataSourcePool.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("create or replace table mcp_probe(id integer, name varchar)");
            stmt.execute("insert into mcp_probe values (1, 'alpha'), (2, 'beta'), (3, 'gamma')");
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    // ------------------------------------------------------------ 工具方法

    /** 发一次 MCP 请求。{@code withMeta} 控制是否带无状态代的 _meta。 */
    private JSONObject rpc(String method, JSONObject params, boolean withMeta) throws Exception {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        JSONObject effective = params == null ? new JSONObject() : params;
        if (withMeta) {
            JSONObject meta = new JSONObject();
            meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
            effective.put("_meta", meta);
        }
        request.put("params", effective);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Mcp-Method", method)
                .POST(HttpRequest.BodyPublishers.ofString(request.toJSONString()))
                .build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "MCP POST must answer 200 with a JSON-RPC envelope");
        return JSON.parseObject(response.body());
    }

    private JSONObject callTool(String tool, JSONObject arguments) throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", tool);
        params.put("arguments", arguments == null ? new JSONObject() : arguments);
        JSONObject envelope = rpc("tools/call", params, true);
        assertNullValue(envelope.getJSONObject("error"), "tools/call returned an error: " + envelope);
        return envelope.getJSONObject("result");
    }


    // ------------------------------------------------------------ 规范条款

    @Test
    @DisplayName("server/discover is implemented and advertises versions, capabilities and identity")
    void discoverIsMandatory() throws Exception {
        JSONObject envelope = rpc("server/discover", new JSONObject(), true);
        assertNullValue(envelope.getJSONObject("error"), "server/discover must not error: " + envelope);
        JSONObject result = envelope.getJSONObject("result");
        assertNotNull(result, "result missing");

        assertTrue(result.getJSONArray("protocolVersions").contains("2026-07-28"),
                "2026-07-28 must be advertised");
        assertEquals("2026-07-28", result.getString("protocolVersion"));
        assertNotNull(result.getJSONObject("serverInfo").getString("name"));
        assertNotNull(result.getJSONObject("capabilities").getJSONObject("tools"));
        assertNotNull(result.getString("instructions"), "instructions guide the model; must be present");
    }

    @Test
    @DisplayName("Unsupported _meta protocol version returns UnsupportedProtocolVersionError")
    void unsupportedVersionIsRejected() throws Exception {
        JSONObject params = new JSONObject();
        JSONObject meta = new JSONObject();
        meta.put("io.modelcontextprotocol/protocolVersion", "1999-01-01");
        params.put("_meta", meta);

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 7);
        request.put("method", "tools/list");
        request.put("params", params);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toJSONString()))
                .build();
        JSONObject envelope = JSON.parseObject(
                client.send(httpRequest, HttpResponse.BodyHandlers.ofString()).body());

        assertNotNull(envelope.getJSONObject("error"), "must be an error envelope");
        assertEquals(-32020, envelope.getJSONObject("error").getIntValue("code"),
                "MCP reserves -32020 for unsupported protocol version");
    }

    @Test
    @DisplayName("Stateless: tools/list works with no prior initialize handshake")
    void statelessWithoutInitialize() throws Exception {
        JSONObject envelope = rpc("tools/list", new JSONObject(), true);
        assertNullValue(envelope.getJSONObject("error"), "tools/list must work without initialize: " + envelope);
        JSONObject result = envelope.getJSONObject("result");
        assertEquals("complete", result.getString("resultType"), "resultType is required");
        assertFalse(result.getJSONArray("tools").isEmpty(), "built-in tools must be advertised");
    }

    @Test
    @DisplayName("Dual era: legacy initialize handshake is still accepted and echoes a supported version")
    void legacyInitializeStillWorks() throws Exception {
        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2024-11-05");
        JSONObject envelope = rpc("initialize", params, false);
        assertNullValue(envelope.getJSONObject("error"), "legacy initialize must not error: " + envelope);
        JSONObject result = envelope.getJSONObject("result");
        assertEquals("2024-11-05", result.getString("protocolVersion"),
                "must echo the client's version so old clients do not switch semantics");
        assertNotNull(result.getJSONObject("capabilities"));
        assertEquals("complete", result.getString("resultType"));
    }

    @Test
    @DisplayName("Every result carries resultType")
    void resultTypeIsAlwaysPresent() throws Exception {
        for (String method : new String[]{"tools/list", "resources/list", "resources/templates/list", "prompts/list"}) {
            JSONObject envelope = rpc(method, new JSONObject(), true);
            assertNullValue(envelope.getJSONObject("error"), method + " errored: " + envelope);
            assertEquals("complete", envelope.getJSONObject("result").getString("resultType"),
                    method + " must set resultType");
        }
    }

    @Test
    @DisplayName("List and read results carry the caching contract (ttlMs + cacheScope)")
    void cachingContractOnListAndRead() throws Exception {
        for (String method : new String[]{"tools/list", "resources/list", "resources/templates/list", "prompts/list"}) {
            JSONObject result = rpc(method, new JSONObject(), true).getJSONObject("result");
            assertNotNull(result.get("ttlMs"), method + " must carry ttlMs");
            assertTrue(result.getLongValue("ttlMs") >= 0, method + " ttlMs must be >= 0");
            assertTrue(java.util.List.of("public", "private").contains(result.getString("cacheScope")),
                    method + " must carry cacheScope public|private, got " + result.getString("cacheScope"));
        }
        JSONObject params = new JSONObject();
        params.put("uri", "slackerdb://schema/tables");
        JSONObject readResult = rpc("resources/read", params, true).getJSONObject("result");
        assertNotNull(readResult.get("ttlMs"), "resources/read must carry ttlMs");
        assertNotNull(readResult.get("cacheScope"), "resources/read must carry cacheScope");
    }

    @Test
    @DisplayName("tools/list is deterministic and exposes outputSchema")
    void toolsListIsDeterministicWithOutputSchema() throws Exception {
        JSONArray first = rpc("tools/list", new JSONObject(), true).getJSONObject("result").getJSONArray("tools");
        JSONArray second = rpc("tools/list", new JSONObject(), true).getJSONObject("result").getJSONArray("tools");
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.getJSONObject(i).getString("name"), second.getJSONObject(i).getString("name"),
                    "tools/list order must be deterministic (index " + i + ")");
        }
        // 确定性排序：按名字升序
        for (int i = 1; i < first.size(); i++) {
            String previous = first.getJSONObject(i - 1).getString("name");
            String current = first.getJSONObject(i).getString("name");
            assertTrue(previous.compareTo(current) <= 0,
                    "tools must be sorted by name: " + previous + " before " + current);
        }
        // 真实实现而非 echo 桩
        boolean hasOutputSchema = false;
        for (int i = 0; i < first.size(); i++) {
            JSONObject tool = first.getJSONObject(i);
            assertNotNull(tool.getString("description"));
            assertNotNull(tool.getJSONObject("inputSchema"));
            if ("run_sql".equals(tool.getString("name"))) {
                assertNotNull(tool.getJSONObject("outputSchema"), "run_sql must declare outputSchema");
                hasOutputSchema = true;
            }
        }
        assertTrue(hasOutputSchema, "run_sql tool must be present as a real built-in");
    }

    @Test
    @DisplayName("tools/call returns content plus structuredContent")
    void toolCallReturnsStructuredContent() throws Exception {
        JSONObject result = callTool("run_sql", sqlArgs("select 1 as one, 'x' as two"));
        assertEquals(Boolean.FALSE, result.getBoolean("isError"));
        assertNotNull(result.getJSONArray("content"), "content is required");
        JSONObject structured = result.getJSONObject("structuredContent");
        assertNotNull(structured, "structuredContent must be present");
        assertEquals(1, structured.getIntValue("row_count"));
        assertEquals("complete", result.getString("resultType"));
    }

    @Test
    @DisplayName("Write statements are allowed (MCP is an internal tool)")
    void writeStatementsAreAllowed() throws Exception {
        callTool("run_sql", sqlArgs("drop table if exists mcp_write_probe"));
        callTool("run_sql", sqlArgs("create table mcp_write_probe(a int)"));
        callTool("run_sql", sqlArgs("insert into mcp_write_probe values (1), (2)"));
        JSONObject result = callTool("run_sql", sqlArgs("select count(*) as c from mcp_write_probe"));
        assertEquals(2, result.getJSONObject("structuredContent").getJSONArray("rows")
                .getJSONArray(0).getIntValue(0), "write statements must take effect");
        callTool("run_sql", sqlArgs("drop table mcp_write_probe"));
    }

    @Test
    @DisplayName("Row cap is applied and reported via truncated")
    void rowCapIsApplied() throws Exception {
        JSONObject args = new JSONObject();
        args.put("sql", "select * from range(1000)");
        args.put("max_rows", 10);
        JSONObject result = callTool("run_sql", args);
        JSONObject structured = result.getJSONObject("structuredContent");
        assertEquals(10, structured.getIntValue("row_count"));
        assertTrue(structured.getBooleanValue("truncated"), "truncated must be reported");
    }

    @Test
    @DisplayName("Session handles carry state across calls and can be closed")
    void sessionHandleCarriesState() throws Exception {
        JSONObject opened = callTool("open_session", new JSONObject()).getJSONObject("structuredContent");
        String handle = opened.getString("handle");
        assertNotNull(handle, "open_session must mint a handle");
        assertFalse(opened.getBooleanValue("read_only"), "MCP no longer enforces read-only");

        // 临时表跨调用存活——这正是"显式 handle"要解决的问题
        JSONObject args = new JSONObject();
        args.put("sql", "create temp table mcp_tmp as select 42 as answer");
        args.put("handle", handle);
        callTool("run_sql", args);

        // 同一会话里查询仍然可用
        JSONObject queryArgs = new JSONObject();
        queryArgs.put("sql", "select 5 as five");
        queryArgs.put("handle", handle);
        JSONObject queried = callTool("run_sql", queryArgs).getJSONObject("structuredContent");
        assertEquals(1, queried.getIntValue("row_count"));

        JSONObject closed = callTool("close_session", handleArgs(handle)).getJSONObject("structuredContent");
        assertTrue(closed.getBooleanValue("closed"), "close_session must report the handle closed");

        // 关闭后再用必须明确报错，而不是悄悄新建一个
        JSONObject reuse = rpc("tools/call", toolParams("run_sql", queryArgs), true);
        assertNotNull(reuse.getJSONObject("error"), "a closed handle must be rejected");
    }

    @Test
    @DisplayName("Schema introspection tools work against real tables")
    void introspectionTools() throws Exception {
        JSONObject tables = callTool("list_tables", new JSONObject())
                .getJSONObject("structuredContent");
        assertTrue(tables.getIntValue("row_count") > 0, "list_tables must find tables");

        JSONObject describeArgs = new JSONObject();
        describeArgs.put("table", "mcp_probe");
        JSONObject described = callTool("describe_table", describeArgs).getJSONObject("structuredContent");
        assertEquals(2, described.getIntValue("row_count"), "mcp_probe has two columns");

        JSONObject sampleArgs = new JSONObject();
        sampleArgs.put("table", "mcp_probe");
        sampleArgs.put("limit", 2);
        JSONObject sampled = callTool("sample_rows", sampleArgs).getJSONObject("structuredContent");
        assertEquals(2, sampled.getIntValue("row_count"));
    }

    @Test
    @DisplayName("list_tables returns the tables array its outputSchema declares")
    void listTablesMatchesDeclaredShape() throws Exception {
        // 曾经返回的是通用结果网格（columns/rows/…），而 outputSchema 声明的是
        // tables 数组，于是按 schema 读 structuredContent.tables 只会拿到 undefined。
        JSONObject structured = callTool("list_tables", new JSONObject()).getJSONObject("structuredContent");
        JSONArray tables = structured.getJSONArray("tables");
        assertNotNull(tables, "list_tables must return the declared 'tables' array: " + structured);
        assertTrue(tables.size() > 0, "list_tables must find the tables this test created");

        boolean foundProbe = false;
        for (int i = 0; i < tables.size(); i++) {
            JSONObject table = tables.getJSONObject(i);
            assertNotNull(table.getString("schema"), "each entry must carry schema: " + table);
            assertNotNull(table.getString("name"), "each entry must carry name: " + table);
            assertNotNull(table.getString("type"), "each entry must carry type: " + table);
            if ("mcp_probe".equalsIgnoreCase(table.getString("name"))) {
                foundProbe = true;
            }
        }
        assertTrue(foundProbe, "mcp_probe must appear in list_tables: " + tables);
    }

    @Test
    @DisplayName("Every tool's structuredContent satisfies its declared outputSchema")
    void structuredContentMatchesDeclaredOutputSchema() throws Exception {
        // outputSchema 声明 additionalProperties: false，而客户端（例如官方 MCP SDK）
        // 会在 tools/list 之后据此校验 structuredContent：多一个未声明字段就会让调用
        // 以 InvalidParams 失败。这里用同一条契约把每个内置工具的载荷钉住。
        JSONArray tools = rpc("tools/list", new JSONObject(), true).getJSONObject("result").getJSONArray("tools");
        Map<String, JSONObject> schemas = new HashMap<>();
        for (int i = 0; i < tools.size(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            JSONObject outputSchema = tool.getJSONObject("outputSchema");
            if (outputSchema != null) {
                schemas.put(tool.getString("name"), outputSchema);
            }
        }

        JSONObject noArgs = new JSONObject();
        callAndAssertSchema(schemas, "list_tables", noArgs);
        callAndAssertSchema(schemas, "list_services", noArgs);

        JSONObject opened = callTool("open_session", new JSONObject());
        assertSchema(schemas.get("open_session"), opened.getJSONObject("structuredContent"), "open_session");

        callAndAssertSchema(schemas, "run_sql", sqlArgs("select 1 as one, 'x' as two"));
        callAndAssertSchema(schemas, "describe_table", tableArgs("mcp_probe"));
        callAndAssertSchema(schemas, "sample_rows", tableArgs("mcp_probe"));

        // 没有结果集的分支（写语句）必须返回同一组字段，否则又会出现"声明与实际不一致"。
        // 写操作需要显式可写会话，用完即关。
        JSONObject writable = callTool("open_session", readOnlyArgs(false));
        assertSchema(schemas.get("open_session"), writable.getJSONObject("structuredContent"),
                "open_session(read_only=false)");
        callAndAssertSchema(schemas, "run_sql", writableSqlArgs(writable.getJSONObject("structuredContent")
                .getString("handle")));

        callAndAssertSchema(schemas, "close_session", handleArgs(
                opened.getJSONObject("structuredContent").getString("handle")));
        JSONObject closed = callTool("close_session", handleArgs(
                writable.getJSONObject("structuredContent").getString("handle")));
        assertSchema(schemas.get("close_session"), closed.getJSONObject("structuredContent"), "close_session");
    }

    /** 调用一个工具，并把它的 structuredContent 与声明的 outputSchema 对账。 */
    private void callAndAssertSchema(Map<String, JSONObject> schemas, String tool, JSONObject arguments)
            throws Exception {
        JSONObject result = callTool(tool, arguments);
        JSONObject declared = schemas.get(tool);
        if (declared == null) {
            return;
        }
        assertSchema(declared, result.getJSONObject("structuredContent"), tool);
    }

    /**
     * 按 outputSchema 校验一个结构化结果。
     *
     * <p>只覆盖本服务端实际使用的词汇：type/required/additionalProperties/properties/enum。
     * 非对象或缺少 schema 的结果由调用方先行判定。</p>
     */
    private static void assertSchema(JSONObject schema, Object payload, String tool) {
        assertNotNull(schema, tool + " must declare an outputSchema");
        if (!(payload instanceof JSONObject)) {
            assertTrue(scalarTypeMatches("object", payload),
                    tool + ": outputSchema says object, payload is " + describeType(payload));
            return;
        }
        JSONObject object = (JSONObject) payload;
        JSONObject properties = schema.getJSONObject("properties");
        JSONArray required = schema.getJSONArray("required");
        if (required != null) {
            for (int i = 0; i < required.size(); i++) {
                String name = required.getString(i);
                assertTrue(object.containsKey(name),
                        tool + ": required field '" + name + "' is missing from " + object.keySet());
            }
        }
        if (schema.getBooleanValue("additionalProperties", true) == false && properties != null) {
            for (String name : object.keySet()) {
                assertTrue(properties.containsKey(name),
                        tool + ": field '" + name + "' is not declared in outputSchema "
                                + properties.keySet());
            }
        }
        if (properties != null) {
            for (String name : object.keySet()) {
                JSONObject property = properties.getJSONObject(name);
                if (property != null) {
                    assertProperty(property, object.get(name), tool + "." + name);
                }
            }
        }
    }

    private static void assertProperty(JSONObject property, Object value, String path) {
        if (value == null) {
            return;
        }
        String type = property.getString("type");
        if (type != null) {
            assertTrue(scalarTypeMatches(type, value),
                    path + ": expected " + type + " but was " + describeType(value) + " (" + value + ")");
        }
        JSONArray allowed = property.getJSONArray("enum");
        if (allowed != null) {
            assertTrue(allowed.contains(value),
                    path + ": value '" + value + "' is outside the declared enum " + allowed);
        }
    }

    private static boolean scalarTypeMatches(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof JSONObject;
            case "array" -> value instanceof JSONArray || value instanceof java.util.List;
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof java.math.BigInteger;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> true;
        };
    }

    private static String describeType(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    @Test
    @DisplayName("resources/read returns a spec-shaped contents array")
    void resourcesReadShape() throws Exception {
        JSONObject params = new JSONObject();
        params.put("uri", "slackerdb://services");
        JSONObject result = rpc("resources/read", params, true).getJSONObject("result");
        JSONArray contents = result.getJSONArray("contents");
        assertNotNull(contents, "contents must be an array");
        assertEquals(1, contents.size());
        JSONObject item = contents.getJSONObject(0);
        assertEquals("slackerdb://services", item.getString("uri"));
        assertNotNull(item.getString("mimeType"));
        assertNotNull(item.getString("text"), "content block must carry text");
    }

    @Test
    @DisplayName("Unknown resource maps to -32602 (not the retired -32002)")
    void unknownResourceUsesInvalidParams() throws Exception {
        JSONObject params = new JSONObject();
        params.put("uri", "slackerdb://does-not-exist");
        JSONObject envelope = rpc("resources/read", params, true);
        assertNotNull(envelope.getJSONObject("error"));
        assertEquals(-32602, envelope.getJSONObject("error").getIntValue("code"));
    }

    @Test
    @DisplayName("Removed methods are refused explicitly instead of being silently ignored")
    void removedMethodsAreRefused() throws Exception {
        for (String removed : new String[]{"ping", "logging/setLevel"}) {
            JSONObject envelope = rpc(removed, new JSONObject(), true);
            assertNotNull(envelope.getJSONObject("error"), removed + " must be refused");
        }
    }

    @Test
    @DisplayName("The GET/SSE endpoint from earlier revisions is gone, and GET /mcp answers 405")
    void sseEndpointRemoved() throws Exception {
        // 已移除的旧路径
        HttpRequest sseRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/sse"))
                .GET()
                .build();
        HttpResponse<String> sseResponse = client.send(sseRequest, HttpResponse.BodyHandlers.ofString());
        assertTrue(sseResponse.statusCode() == 404 || sseResponse.statusCode() == 405,
                "GET /sse must no longer be served, got " + sseResponse.statusCode());

        // GET /mcp 必须是 405：已安装客户端的 Streamable HTTP 传输在连接时会先 GET 一次
        // 想开 SSE 流，且只把 405 当作"服务端不提供 SSE 流"的正常信号。返回 200 会让它
        // 去解析一个不是 SSE 的响应体，连接直接失败。
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .GET()
                .build();
        HttpResponse<String> getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, getResponse.statusCode(),
                "GET /mcp must answer 405 so older SDK clients treat it as 'no SSE stream'");
        assertNotNull(getResponse.headers().firstValue("Allow").orElse(null),
                "a 405 should advertise the allowed method");
    }

    @Test
    @DisplayName("list_services exposes the curated services as a tool (for clients that skip resources)")
    void listServicesTool() throws Exception {
        // 注册一个服务，然后确认它能通过工具看到
        JSONObject serviceDef = new JSONObject();
        serviceDef.put("serviceName", "protocol_test_service");
        serviceDef.put("serviceVersion", "1.0");
        serviceDef.put("serviceType", "GET");
        serviceDef.put("sql", "SELECT 1");
        serviceDef.put("description", "Service registered by McpProtocolTest");
        serviceDef.put("category", "protocol-test");

        HttpRequest register = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/registerService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(serviceDef.toJSONString()))
                .build();
        assertEquals(200, client.send(register, HttpResponse.BodyHandlers.ofString()).statusCode());

        JSONObject structured = callTool("list_services", new JSONObject()).getJSONObject("structuredContent");
        assertTrue(structured.getIntValue("row_count") > 0, "list_services must return the registered services");

        JSONArray services = structured.getJSONArray("services");
        boolean found = false;
        for (int i = 0; i < services.size(); i++) {
            JSONObject service = services.getJSONObject(i);
            if ("protocol_test_service".equals(service.getString("name"))) {
                found = true;
                assertNotNull(service.getString("endpoint"), "each service must carry its endpoint");
                break;
            }
        }
        assertTrue(found, "the registered service must appear in list_services");

        // 分类过滤
        JSONObject filteredArgs = new JSONObject();
        filteredArgs.put("category", "protocol-test");
        JSONObject filtered = callTool("list_services", filteredArgs).getJSONObject("structuredContent");
        assertEquals(1, filtered.getIntValue("row_count"), "category filter must narrow the list");
    }

    @Test
    @DisplayName("Introspection tools advertise that services are the preferred path")
    void instructionsPointAtServices() throws Exception {
        JSONObject result = rpc("server/discover", new JSONObject(), true).getJSONObject("result");
        String instructions = result.getString("instructions");
        assertTrue(instructions.contains("list_services"),
                "instructions must tell the model to start from list_services: " + instructions);
        assertTrue(instructions.contains("slackerdb://services"),
                "instructions should still name the resource for clients that consume resources");
    }

    // ------------------------------------------------------------ 参数构造小工具

    private static JSONObject sqlArgs(String sql) {
        JSONObject args = new JSONObject();
        args.put("sql", sql);
        return args;
    }

    private static JSONObject handleArgs(String handle) {
        JSONObject args = new JSONObject();
        args.put("handle", handle);
        return args;
    }

    private static JSONObject tableArgs(String table) {
        JSONObject args = new JSONObject();
        args.put("table", table);
        return args;
    }

    private static JSONObject readOnlyArgs(boolean readOnly) {
        JSONObject args = new JSONObject();
        args.put("read_only", readOnly);
        return args;
    }

    /** 在可写会话里执行一条没有结果集的语句，用于覆盖 run_sql 的 update 分支。 */
    private static JSONObject writableSqlArgs(String handle) {
        JSONObject args = new JSONObject();
        args.put("handle", handle);
        args.put("sql", "create temp table mcp_schema_probe as select 1 as x");
        return args;
    }

    private static JSONObject toolParams(String name, JSONObject arguments) {
        JSONObject params = new JSONObject();
        params.put("name", name);
        params.put("arguments", arguments);
        return params;
    }

    private static void assertNullValue(Object value, String message) {
        assertTrue(value == null, message);
    }
}
