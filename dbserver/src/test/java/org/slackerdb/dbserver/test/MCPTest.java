package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.TimeZone;

import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SimpleMcpServer MCP endpoints.
 * Starts an embedded server on a random port before all tests.
 */
public class MCPTest {
    private static final HttpClient client = HttpClient.newHttpClient();

    static int dbPort;
    static int dbPortX;
    static String baseUrl;
    static DBInstance dbInstance ;

    @BeforeAll
    static void initAll() throws Exception {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setPortX(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        serverConfiguration.setDataServiceHistory("ON");
        dbPort = serverConfiguration.getPort();
        dbPortX  = serverConfiguration.getPortX();

        // 初始化数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        baseUrl = "http://127.0.0.1:" + dbPortX;

        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
        System.out.println("TEST:: Server started successful ...");

        // 注册测试所需的工具、资源和服务
        // 服务器启动时不会带有任何示例，所有需要用到的tool、resource均需要在测试准备的时候通过registertool、registerresource等添加
        registerTestTools();
        registerTestResources();
        registerTestServices();
    }

    private static void registerTestTools() throws Exception {
        // Register echo tool
        Map<String, Object> echoTool = Map.of(
            "name", "echo",
            "description", "Echo back the input text",
            "inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "text", Map.of(
                        "type", "string",
                        "description", "The text to echo back"
                    )
                ),
                "required", java.util.List.of("text")
            ),
            "category", "utility"
        );
        
        String requestBody = JSON.toJSONString(echoTool);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp/registerMCPTool"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        
        JSONObject resp = JSON.parseObject(response.body());
        assertEquals(0, resp.getInteger("retCode"));
        assertEquals("Tool registered successfully", resp.getString("retMsg"));
        
        System.out.println("TEST:: Registered echo tool");
    }
    
    private static void registerTestResources() throws Exception {
        // Register example.txt resource
        Map<String, Object> exampleResource = Map.of(
            "uri", "file:///example.txt",
            "name", "Example File",
            "description", "A simple text file for demonstration",
            "mimeType", "text/plain",
            "contents", "Hello, MCP!",
            "category", "example"
        );
        
        String requestBody = JSON.toJSONString(exampleResource);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp/registerMCPResource"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        
        JSONObject resp = JSON.parseObject(response.body());
        assertEquals(0, resp.getInteger("retCode"));
        assertEquals("Resource registered successfully", resp.getString("retMsg"));
        
        System.out.println("TEST:: Registered example.txt resource");
    }
    
    private static void registerTestServices() throws Exception {
        /*
          注册到真正的数据服务注册表（/api/registerService），而不是 MCP 自有的那一份。
          MCP 侧的 slackerdb://services 资源现在直接读 APIService，只保留一份真相：
          agent 看到的服务与 HTTP 数据服务永远一致。
        */
        Map<String, Object> queryInventoryService = Map.of(
                "serviceName", "query_inventory",
                "serviceVersion", "1.0",
                "serviceType", "GET",
                "sql", "SELECT 1 AS warehouse_id, 2 AS product_id",
                "description", "查询仓库库存水平，可以按仓库ID和产品ID过滤",
                "category", "database",
                "parameters", java.util.List.of(
                        Map.of("name", "warehouse_id", "defaultValue", "A"),
                        Map.of("name", "product_id", "defaultValue", "P123")
                )
        );

        String requestBody = JSON.toJSONString(queryInventoryService);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/registerService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JSONObject resp = JSON.parseObject(response.body());
        assertEquals(0, resp.getInteger("retCode"), "registerService failed: " + response.body());

        System.out.println("TEST:: Registered query_inventory data service");
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("TEST:: Will shutdown server ...");
        System.out.println("TEST:: Active sessions : " +  dbInstance.activeSessions);
        dbInstance.stop();
        System.out.println("TEST:: Server stopped successful.");
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    @Test
    void testToolsList() throws Exception {
        String requestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list"
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JSONObject resp = JSON.parseObject(response.body());
        assertEquals("2.0", resp.getString("jsonrpc"));
        assertEquals(1, resp.getInteger("id"));
        assertTrue(resp.containsKey("result"));
        JSONArray tools = resp.getJSONObject("result").getJSONArray("tools");
        assertTrue(tools != null && !tools.isEmpty());
        // At least the echo tool should be present
        boolean foundEcho = false;
        for (int i = 0; i < tools.size(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            if ("echo".equals(tool.getString("name"))) {
                foundEcho = true;
                assertEquals("Echo back the input text", tool.getString("description"));
                assertTrue(tool.containsKey("inputSchema"));
                break;
            }
        }
        assertTrue(foundEcho, "Echo tool not found in tools/list");
    }

    @Test
    void testToolsCall() throws Exception {
        String requestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/call",
                "params", Map.of(
                        "name", "echo",
                        "arguments", Map.of("text", "Hello MCP")
                )
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JSONObject resp = JSON.parseObject(response.body());
        assertEquals("2.0", resp.getString("jsonrpc"));
        assertEquals(2, resp.getInteger("id"));
        assertTrue(resp.containsKey("result"));
        JSONObject result = resp.getJSONObject("result");

        // 2026-07-28 起 tools/call 必须返回 {content, structuredContent, isError}，
        // 不能像旧实现那样直接把 handler 的裸对象当 result 返回。
        assertEquals("complete", result.getString("resultType"));
        assertEquals(Boolean.FALSE, result.getBoolean("isError"));
        assertNotNull(result.getJSONArray("content"));
        JSONObject structured = result.getJSONObject("structuredContent");
        assertNotNull(structured);
        // 由配置文件注册、没有 builtin handler 的工具仍然回显入参（保持兼容）
        assertEquals("registered", structured.getString("status"));
        assertEquals("echo", structured.getString("tool"));
        JSONObject arguments = structured.getJSONObject("arguments");
        assertTrue(arguments.containsKey("text"));
        assertEquals("Hello MCP", arguments.getString("text"));
    }

    @Test
    void testResourcesList() throws Exception {
        String requestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 3,
                "method", "resources/list"
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JSONObject resp = JSON.parseObject(response.body());
        assertEquals("2.0", resp.getString("jsonrpc"));
        assertEquals(3, resp.getInteger("id"));
        assertTrue(resp.containsKey("result"));
        JSONArray resources = resp.getJSONObject("result").getJSONArray("resources");
        assertTrue(resources != null && !resources.isEmpty());
        boolean foundExample = false;
        for (int i = 0; i < resources.size(); i++) {
            JSONObject res = resources.getJSONObject(i);
            if ("file:///example.txt".equals(res.getString("uri"))) {
                foundExample = true;
                assertEquals("Example File", res.getString("name"));
                assertEquals("A simple text file for demonstration", res.getString("description"));
                assertEquals("text/plain", res.getString("mimeType"));
                break;
            }
        }
        assertTrue(foundExample, "Example resource not found in resources/list");
    }

    @Test
    void testResourcesRead() throws Exception {
        String requestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 4,
                "method", "resources/read",
                "params", Map.of("uri", "file:///example.txt")
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JSONObject resp = JSON.parseObject(response.body());
        assertEquals("2.0", resp.getString("jsonrpc"));
        assertEquals(4, resp.getInteger("id"));
        assertTrue(resp.containsKey("result"));
        // 2026-07-28：resources/read 必须返回 contents 数组（content-block 形状），
        // 旧实现直接回一个裸值，按规范解析的客户端会失败。
        JSONArray contents = resp.getJSONObject("result").getJSONArray("contents");
        assertNotNull(contents);
        assertEquals(1, contents.size());
        JSONObject block = contents.getJSONObject(0);
        assertEquals("file:///example.txt", block.getString("uri"));
        assertEquals("text/plain", block.getString("mimeType"));
        assertTrue(block.getString("text").contains("Hello, MCP!"),
                "content block text must carry the stored value, got: " + block.getString("text"));
    }

    @Test
    void testInvalidToolCall() throws Exception {
        String requestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 5,
                "method", "tools/call",
                "params", Map.of(
                        "name", "nonexistent",
                        "arguments", Map.of()
                )
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); // JSON-RPC error still returns 200

        JSONObject resp = JSON.parseObject(response.body());
        assertEquals("2.0", resp.getString("jsonrpc"));
        assertEquals(5, resp.getInteger("id"));
        assertTrue(resp.containsKey("error"));
        // 2026-07-28：未知工具属于"入参不合法"（-32602），
        // 旧的 -32601 是"方法不存在"，两者不再混用。
        assertEquals(-32602, resp.getJSONObject("error").getInteger("code"));
        assertTrue(resp.getJSONObject("error").getString("message").contains("Unknown tool"));
    }

    @Test
    void testInvalidMethod() throws Exception {
        String requestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 6,
                "method", "unknown.method"
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JSONObject resp = JSON.parseObject(response.body());
        assertEquals("2.0", resp.getString("jsonrpc"));
        assertEquals(6, resp.getInteger("id"));
        assertTrue(resp.containsKey("error"));
        assertEquals(-32601, resp.getJSONObject("error").getInteger("code"));
        assertTrue(resp.getJSONObject("error").getString("message").startsWith("Method not found"),
                "message should name the missing method, got: " + resp.getJSONObject("error").getString("message"));
    }

    @Test
    void testServicesList() throws Exception {
        JSONArray services = readServicesResource();
        assertTrue(services != null && !services.isEmpty());

        // Check for the example query_inventory service
        boolean foundQueryInventory = false;
        for (int i = 0; i < services.size(); i++) {
            JSONObject service = services.getJSONObject(i);
            if ("query_inventory".equals(service.getString("name"))) {
                foundQueryInventory = true;
                assertEquals("查询仓库库存水平，可以按仓库ID和产品ID过滤", service.getString("description"));
                assertEquals("database", service.getString("category"));

                // Check parameters
                JSONArray parameters = service.getJSONArray("parameters");
                assertTrue(parameters != null && !parameters.isEmpty());
                assertEquals(2, parameters.size());

                // 服务必须给出调用端点，模型无需猜
                assertNotNull(service.getString("endpoint"));
                // SQL 文本刻意不下发
                assertFalse(service.containsKey("sql"), "service SQL must not be exposed to the agent");
                break;
            }
        }
        assertTrue(foundQueryInventory, "query_inventory service not found in slackerdb://services");
    }

    /**
     * 读 {@code slackerdb://services} 资源，取回服务数组。
     *
     * <p>2026-07-28 把自定义的 {@code services/list} 方法去掉了：服务清单是
     * <b>可读上下文</b>，属于 resources 而不是私有方法。控制台也改读这个资源。</p>
     */
    private static JSONArray readServicesResource() throws Exception {
        String requestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 7,
                "method", "resources/read",
                "params", Map.of("uri", "slackerdb://services")
        ));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JSONObject resp = JSON.parseObject(response.body());
        assertTrue(resp.containsKey("result"), "resources/read must return a result: " + response.body());
        JSONArray contents = resp.getJSONObject("result").getJSONArray("contents");
        assertTrue(contents != null && contents.size() == 1);
        String text = contents.getJSONObject(0).getString("text");
        return JSON.parseObject(text).getJSONArray("services");
    }

    @Test
    void testRegisterMCPService() throws Exception {
        // 注册走真正的数据服务注册表，MCP 只是它的一个视图
        Map<String, Object> serviceDef = new java.util.HashMap<>();
        serviceDef.put("serviceName", "test_service");
        serviceDef.put("serviceVersion", "1.0");
        serviceDef.put("serviceType", "GET");
        serviceDef.put("sql", "SELECT 1");
        serviceDef.put("description", "A test service for unit testing");
        serviceDef.put("category", "test");
        serviceDef.put("parameters", java.util.List.of(
                Map.of("name", "param1", "defaultValue", "value1"),
                Map.of("name", "param2", "defaultValue", "value2")
        ));

        String requestBody = JSON.toJSONString(serviceDef);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/registerService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JSONObject resp = JSON.parseObject(response.body());
        assertEquals(0, resp.getInteger("retCode"), "registerService failed: " + response.body());

        // Verify the service is now listed through the MCP resource
        JSONArray services = readServicesResource();
        boolean foundTestService = false;
        for (int i = 0; i < services.size(); i++) {
            JSONObject service = services.getJSONObject(i);
            if ("test_service".equals(service.getString("name"))) {
                foundTestService = true;
                assertEquals("A test service for unit testing", service.getString("description"));
                assertEquals("test", service.getString("category"));
                break;
            }
        }
        assertTrue(foundTestService, "test_service not found after registration");
    }

    @Test
    void testUnregisterMCPService() throws Exception {
        // First register a service in the real data-service registry
        Map<String, Object> serviceDef = Map.of(
                "serviceName", "temp_service",
                "serviceVersion", "1.0",
                "serviceType", "GET",
                "sql", "SELECT 1",
                "description", "Temporary service for unregister test",
                "category", "temp",
                "parameters", java.util.List.of()
        );

        String registerBody = JSON.toJSONString(serviceDef);
        HttpRequest registerRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/registerService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(registerBody))
                .build();

        HttpResponse<String> registerResponse = client.send(registerRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, registerResponse.statusCode());

        // Now unregister it
        Map<String, Object> unregisterDef = Map.of(
                "serviceName", "temp_service",
                "serviceVersion", "1.0",
                "serviceType", "GET"
        );
        String unregisterBody = JSON.toJSONString(unregisterDef);
        HttpRequest unregisterRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/unRegisterService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(unregisterBody))
                .build();

        HttpResponse<String> unregisterResponse = client.send(unregisterRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, unregisterResponse.statusCode());

        // Verify the service is no longer listed through the MCP resource
        JSONArray services = readServicesResource();
        boolean foundTempService = false;
        for (int i = 0; i < services.size(); i++) {
            JSONObject service = services.getJSONObject(i);
            if ("temp_service".equals(service.getString("name"))) {
                foundTempService = true;
                break;
            }
        }
        assertFalse(foundTempService, "temp_service should not be found after unregistration");
    }
}
