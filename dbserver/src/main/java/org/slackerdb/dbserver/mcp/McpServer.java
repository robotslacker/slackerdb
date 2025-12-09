package org.slackerdb.dbserver.mcp;

import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A minimal MCP (Model Context Protocol) server implementation.
 * Provides SSE for streaming, JSON-RPC over HTTP for tools and resources.
 */
public class McpServer {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Logger logger;

    public McpServer(Logger logger) {
        this.logger = logger;
    }

    public void run(Javalin app, Connection conn) throws Exception {
        // Example tool: Echo
        ObjectMapper localMapper = new ObjectMapper();
        addTool("echo", "Echo back the input text",
                localMapper.createObjectNode().put("type", "object").set("properties",
                        localMapper.createObjectNode().set("text", localMapper.createObjectNode().put("type", "string"))),
                arguments -> Map.of("echo", arguments.get("text")));

        // Example resource: a simple text file
        addResource("file:///example.txt", "Example File",
                "A simple text file for demonstration", "text/plain",
                "Hello, MCP!");

        // SSE endpoint for MCP protocol
        app.sse("/sse", client -> {
            try {
                client.sendEvent("message", mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "result", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of()
                    )
                )));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // JSON-RPC over HTTP endpoint for MCP
        app.post("/jsonrpc", ctx -> {
            JsonNode request = mapper.readTree(ctx.body());
            String method = request.get("method").asText();
            JsonNode params = request.get("params");
            JsonNode id = request.get("id");

            if ("tools/list".equals(method)) {
                List<Map<String, Object>> toolList = new ArrayList<>();
                for (Tool tool : tools.values()) {
                    toolList.add(Map.of(
                        "name", tool.name,
                        "description", tool.description,
                        "inputSchema", tool.inputSchema
                    ));
                }
                ctx.result(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("tools", toolList)
                )));
            } else if ("tools/call".equals(method)) {
                String toolName = params.get("name").asText();
                JsonNode arguments = params.get("arguments");
                Tool tool = tools.get(toolName);
                if (tool == null) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32601, "message", "Tool not found")
                    )));
                    return;
                }
                Map<String, Object> argsMap = mapper.convertValue(arguments, new TypeReference<>() {});
                CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> tool.handler.apply(argsMap), executor);
                Object result = future.get();
                ctx.result(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", result
                )));
            } else if ("resources/list".equals(method)) {
                List<Map<String, Object>> resourceList = new ArrayList<>();
                for (Resource res : resources.values()) {
                    resourceList.add(Map.of(
                        "uri", res.uri,
                        "name", res.name,
                        "description", res.description,
                        "mimeType", res.mimeType
                    ));
                }
                ctx.result(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("resources", resourceList)
                )));
            } else if ("resources/read".equals(method)) {
                String uri = params.get("uri").asText();
                Resource res = resources.get(uri);
                if (res == null) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32601, "message", "Resource not found")
                    )));
                    return;
                }
                ctx.result(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("contents", res.contents)
                )));
            } else {
                ctx.result(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "error", Map.of("code", -32601, "message", "Method not found")
                )));
            }
        });
    }

    public void addTool(String name, String description, JsonNode inputSchema, Function<Map<String,Object>, Object> handler) {
        tools.put(name, new Tool(name, description, inputSchema, handler));
    }

    public void addResource(String uri, String name, String description, String mimeType, Object contents) {
        resources.put(uri, new Resource(uri, name, description, mimeType, contents));
    }

    private static class Tool {
        final String name;
        final String description;
        final JsonNode inputSchema;
        final Function<Map<String,Object>, Object> handler;

        Tool(String name, String description, JsonNode inputSchema, Function<Map<String,Object>, Object> handler) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.handler = handler;
        }
    }

    private static class Resource {
        final String uri;
        final String name;
        final String description;
        final String mimeType;
        final Object contents;

        Resource(String uri, String name, String description, String mimeType, Object contents) {
            this.uri = uri;
            this.name = name;
            this.description = description;
            this.mimeType = mimeType;
            this.contents = contents;
        }
    }
}
