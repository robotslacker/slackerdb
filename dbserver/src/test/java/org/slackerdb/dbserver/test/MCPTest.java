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
        // Register query_inventory service
        Map<String, Object> queryInventoryService = Map.of(
                "name", "query_inventory",
                "apiName", "query_inventory",
                "version", "1.0",
                "method", "GET",
                "description", "查询仓库库存水平，可以按仓库ID和产品ID过滤",
                "category", "database",
                "capabilities", java.util.List.of("query", "filter", "aggregate", "report"),
            "use_cases", java.util.List.of("inventory_check", "stock_analysis", "reorder_planning"),
            "parameters", java.util.List.of(
                Map.of("name", "warehouse_id", "type", "string", "required", false, "description", "仓库ID"),
                Map.of("name", "product_id", "type", "string", "required", false, "description", "产品ID")
            ),
            "examples", java.util.List.of(
                Map.of("user_query", "查看仓库A的库存", "parameters", Map.of("warehouse_id", "A")),
                Map.of("user_query", "查看产品P123在所有仓库的库存", "parameters", Map.of("product_id", "P123"))
            )
        );
        
        String requestBody = JSON.toJSONString(queryInventoryService);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp/registerMCPService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        
        JSONObject resp = JSON.parseObject(response.body());
        assertEquals(0, resp.getInteger("retCode"));
        assertEquals("Service registered successfully", resp.getString("retMsg"));
        
        System.out.println("TEST:: Registered query_inventory service");
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
        
        // 默认工具处理程序返回格式: {"status":"registered","tool":"echo","arguments":{"text":"Hello MCP"}}
        // 检查是否包含预期的结构
        assertTrue(result.containsKey("status"));
        assertEquals("registered", result.getString("status"));
        assertTrue(result.containsKey("tool"));
        assertEquals("echo", result.getString("tool"));
        assertTrue(result.containsKey("arguments"));
        JSONObject arguments = result.getJSONObject("arguments");
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
        String contents = resp.getJSONObject("result").getString("contents");
        assertEquals("Hello, MCP!", contents);
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
        assertEquals(-32601, resp.getJSONObject("error").getInteger("code"));
        assertEquals("Tool not found", resp.getJSONObject("error").getString("message"));
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
        assertEquals("Method not found", resp.getJSONObject("error").getString("message"));
    }

    @Test
    void testServicesList() throws Exception {
        String requestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 7,
                "method", "services/list"
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
        assertEquals(7, resp.getInteger("id"));
        assertTrue(resp.containsKey("result"));
        JSONArray services = resp.getJSONObject("result").getJSONArray("services");
        assertTrue(services != null && !services.isEmpty());
        
        // Check for the example query_inventory service
        boolean foundQueryInventory = false;
        for (int i = 0; i < services.size(); i++) {
            JSONObject service = services.getJSONObject(i);
            if ("query_inventory".equals(service.getString("name"))) {
                foundQueryInventory = true;
                assertEquals("查询仓库库存水平，可以按仓库ID和产品ID过滤", service.getString("description"));
                assertEquals("database", service.getString("category"));
                
                // Check capabilities
                JSONArray capabilities = service.getJSONArray("capabilities");
                assertTrue(capabilities != null && !capabilities.isEmpty());
                assertEquals(4, capabilities.size());
                
                // Check parameters
                JSONArray parameters = service.getJSONArray("parameters");
                assertTrue(parameters != null && !parameters.isEmpty());
                assertEquals(2, parameters.size());
                
                // Check examples
                JSONArray examples = service.getJSONArray("examples");
                assertTrue(examples != null && !examples.isEmpty());
                assertEquals(2, examples.size());
                break;
            }
        }
        assertTrue(foundQueryInventory, "query_inventory service not found in services/list");
    }

    @Test
    void testRegisterMCPService() throws Exception {
        // Register a new service via HTTP API
        Map<String, Object> serviceDef = Map.of(
                "name", "test_service",
                "apiName", "test_service",
                "version", "1.0",
                "method", "GET",
                "description", "A test service for unit testing",
                "category", "test",
                "capabilities", java.util.List.of("test capability 1", "test capability 2"),
                "use_cases", java.util.List.of("test use case 1", "test use case 2"),
                "parameters", java.util.List.of(
                        Map.of("name", "param1", "type", "string", "required", true, "description", "First parameter"),
                        Map.of("name", "param2", "type", "number", "required", false, "description", "Second parameter")
                ),
            "examples", java.util.List.of(
                Map.of("user_query", "How to use test service?", "parameters", Map.of("param1", "value1"))
            )
        );

        String requestBody = JSON.toJSONString(serviceDef);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp/registerMCPService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JSONObject resp = JSON.parseObject(response.body());
        assertEquals(0, resp.getInteger("retCode"));
        assertEquals("Service registered successfully", resp.getString("retMsg"));

        // Verify the service is now listed
        String listRequestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 8,
                "method", "services/list"
        ));

        HttpRequest listRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(listRequestBody))
                .build();

        HttpResponse<String> listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResponse.statusCode());

        JSONObject listResp = JSON.parseObject(listResponse.body());
        JSONArray services = listResp.getJSONObject("result").getJSONArray("services");
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
        // First register a service
        Map<String, Object> serviceDef = Map.of(
                "name", "temp_service",
                "apiName", "temp_service",
                "version", "1.0",
                "method", "GET",
                "description", "Temporary service for unregister test",
                "category", "temp",
                "capabilities", java.util.List.of("temp capability"),
                "use_cases", java.util.List.of("temp use case"),
                "parameters", java.util.List.of(),
                "examples", java.util.List.of()
        );

        String registerBody = JSON.toJSONString(serviceDef);
        HttpRequest registerRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp/registerMCPService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(registerBody))
                .build();

        HttpResponse<String> registerResponse = client.send(registerRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, registerResponse.statusCode());

        // Now unregister it
        String unregisterBody = JSON.toJSONString(Map.of("name", "temp_service"));
        HttpRequest unregisterRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp/unregisterMCPService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(unregisterBody))
                .build();

        HttpResponse<String> unregisterResponse = client.send(unregisterRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, unregisterResponse.statusCode());

        JSONObject unregisterResp = JSON.parseObject(unregisterResponse.body());
        assertEquals(0, unregisterResp.getInteger("retCode"));
        assertEquals("Service unregistered successfully", unregisterResp.getString("retMsg"));

        // Verify the service is no longer listed
        String listRequestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 9,
                "method", "services/list"
        ));

        HttpRequest listRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(listRequestBody))
                .build();

        HttpResponse<String> listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResponse.statusCode());

        JSONObject listResp = JSON.parseObject(listResponse.body());
        JSONArray services = listResp.getJSONObject("result").getJSONArray("services");
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
