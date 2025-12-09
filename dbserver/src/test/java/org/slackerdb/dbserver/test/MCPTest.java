package org.slackerdb.dbserver.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

/**
 * Integration tests for SimpleMcpServer MCP endpoints.
 * Starts an embedded server on a random port before all tests.
 */
public class MCPTest {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient client = HttpClient.newHttpClient();

    static int dbPort;
    static int dbPortX;
    static String baseUrl;
    static DBInstance dbInstance ;

    @BeforeAll
    static void initAll() throws ServerException {
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
        String requestBody = mapper.writeValueAsString(Map.of(
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

        JsonNode resp = mapper.readTree(response.body());
        assertEquals("2.0", resp.get("jsonrpc").asText());
        assertEquals(1, resp.get("id").asInt());
        assertTrue(resp.has("result"));
        JsonNode tools = resp.get("result").get("tools");
        assertTrue(tools.isArray());
        // At least the echo tool should be present
        boolean foundEcho = false;
        for (JsonNode tool : tools) {
            if ("echo".equals(tool.get("name").asText())) {
                foundEcho = true;
                assertEquals("Echo back the input text", tool.get("description").asText());
                assertTrue(tool.has("inputSchema"));
                break;
            }
        }
        assertTrue(foundEcho, "Echo tool not found in tools/list");
    }

    @Test
    void testToolsCall() throws Exception {
        String requestBody = mapper.writeValueAsString(Map.of(
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

        JsonNode resp = mapper.readTree(response.body());
        assertEquals("2.0", resp.get("jsonrpc").asText());
        assertEquals(2, resp.get("id").asInt());
        assertTrue(resp.has("result"));
        JsonNode result = resp.get("result");
        assertEquals("Hello MCP", result.get("echo").asText());
    }

    @Test
    void testResourcesList() throws Exception {
        String requestBody = mapper.writeValueAsString(Map.of(
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

        JsonNode resp = mapper.readTree(response.body());
        assertEquals("2.0", resp.get("jsonrpc").asText());
        assertEquals(3, resp.get("id").asInt());
        assertTrue(resp.has("result"));
        JsonNode resources = resp.get("result").get("resources");
        assertTrue(resources.isArray());
        boolean foundExample = false;
        for (JsonNode res : resources) {
            if ("file:///example.txt".equals(res.get("uri").asText())) {
                foundExample = true;
                assertEquals("Example File", res.get("name").asText());
                assertEquals("A simple text file for demonstration", res.get("description").asText());
                assertEquals("text/plain", res.get("mimeType").asText());
                break;
            }
        }
        assertTrue(foundExample, "Example resource not found in resources/list");
    }

    @Test
    void testResourcesRead() throws Exception {
        String requestBody = mapper.writeValueAsString(Map.of(
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

        JsonNode resp = mapper.readTree(response.body());
        assertEquals("2.0", resp.get("jsonrpc").asText());
        assertEquals(4, resp.get("id").asInt());
        assertTrue(resp.has("result"));
        JsonNode contents = resp.get("result").get("contents");
        assertEquals("Hello, MCP!", contents.asText());
    }

    @Test
    void testInvalidToolCall() throws Exception {
        String requestBody = mapper.writeValueAsString(Map.of(
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

        JsonNode resp = mapper.readTree(response.body());
        assertEquals("2.0", resp.get("jsonrpc").asText());
        assertEquals(5, resp.get("id").asInt());
        assertTrue(resp.has("error"));
        assertEquals(-32601, resp.get("error").get("code").asInt());
        assertEquals("Tool not found", resp.get("error").get("message").asText());
    }

    @Test
    void testInvalidMethod() throws Exception {
        String requestBody = mapper.writeValueAsString(Map.of(
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

        JsonNode resp = mapper.readTree(response.body());
        assertEquals("2.0", resp.get("jsonrpc").asText());
        assertEquals(6, resp.get("id").asInt());
        assertTrue(resp.has("error"));
        assertEquals(-32601, resp.get("error").get("code").asInt());
        assertEquals("Method not found", resp.get("error").get("message").asText());
    }
}
