package org.slackerdb.dbserver.mcpservice;

import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final String mcpConfigPath;
    private final Javalin managementApp;
    private final Object saveLock = new Object();

    public McpServer(
            DBInstance dbInstance,
            Javalin managementApp,
            Logger logger
    ) {
        this.logger = logger;
        this.managementApp = managementApp;
        this.mcpConfigPath = dbInstance.serverConfiguration.getMcpConfig();
    }

    public void run() throws Exception {
        // Example tool: Echo
        ObjectMapper localMapper = new ObjectMapper();
        addTool("echo", "Echo back the input text",
                localMapper.createObjectNode().put("type", "object").set("properties",
                        localMapper.createObjectNode().set("text", localMapper.createObjectNode().put("type", "string"))),
                arguments -> Map.of("echo", arguments.get("text")),
                "example");

        // Example resource: a simple text file
        addResource("file:///example.txt", "Example File",
                "A simple text file for demonstration", "text/plain",
                "Hello, MCP!", "example");

        // SSE endpoint for MCP protocol
        this.managementApp.sse("/sse", client -> {
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
                logger.warn("SSE error: ", e);
            }
        });

        // JSON-RPC over HTTP endpoint for MCP
        this.managementApp.post("/jsonrpc", ctx -> {
            JsonNode request;
            try {
                request = mapper.readTree(ctx.body());
            } catch (Exception e) {
                // Parse error
                ctx.result(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -32700, "message", "Parse error: " + e.getMessage())
                )));
                return;
            }

            // Validate jsonrpc version
            JsonNode jsonrpcNode = request.get("jsonrpc");
            if (jsonrpcNode == null || !"2.0".equals(jsonrpcNode.asText())) {
                ctx.result(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -32600, "message", "Invalid Request: jsonrpc must be exactly '2.0'")
                )));
                return;
            }

            // Validate method
            JsonNode methodNode = request.get("method");
            if (methodNode == null || !methodNode.isTextual()) {
                ctx.result(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -32600, "message", "Invalid Request: method must be a string")
                )));
                return;
            }
            String method = methodNode.asText();

            JsonNode params = request.get("params");
            JsonNode id = request.get("id"); // may be null, number, string, etc.

            if ("tools/list".equals(method)) {
                List<Map<String, Object>> toolList = new ArrayList<>();
                for (Tool tool : tools.values()) {
                    toolList.add(Map.of(
                        "name", tool.name,
                        "description", tool.description,
                        "inputSchema", tool.inputSchema,
                        "category", tool.category
                    ));
                }
                ctx.result(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("tools", toolList)
                )));
            } else if ("tools/call".equals(method)) {
                // Validate params
                if (params == null || !params.isObject()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32600, "message", "Invalid Request: params must be an object")
                    )));
                    return;
                }
                JsonNode nameNode = params.get("name");
                JsonNode argumentsNode = params.get("arguments");
                if (nameNode == null || !nameNode.isTextual()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32600, "message", "Invalid Request: params.name must be a string")
                    )));
                    return;
                }
                String toolName = nameNode.asText();
                Tool tool = tools.get(toolName);
                if (tool == null) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32601, "message", "Tool not found")
                    )));
                    return;
                }
                Map<String, Object> argsMap;
                if (argumentsNode == null || argumentsNode.isNull()) {
                    argsMap = Map.of();
                } else {
                    try {
                        argsMap = mapper.convertValue(argumentsNode, new TypeReference<>() {});
                    } catch (Exception e) {
                        ctx.result(mapper.writeValueAsString(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "error", Map.of("code", -32602, "message", "Invalid params: arguments must be a valid object")
                        )));
                        return;
                    }
                }
                CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> tool.handler.apply(argsMap), executor);
                Object result;
                try {
                    result = future.get();
                } catch (Exception e) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32603, "message", "Internal error: " + e.getMessage())
                    )));
                    return;
                }
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
                        "mimeType", res.mimeType,
                        "category", res.category
                    ));
                }
                ctx.result(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("resources", resourceList)
                )));
            } else if ("resources/read".equals(method)) {
                // Validate params
                if (params == null || !params.isObject()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32600, "message", "Invalid Request: params must be an object")
                    )));
                    return;
                }
                JsonNode uriNode = params.get("uri");
                if (uriNode == null || !uriNode.isTextual()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32600, "message", "Invalid Request: params.uri must be a string")
                    )));
                    return;
                }
                String uri = uriNode.asText();
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

        // Register MCP Tool endpoint
        this.managementApp.post("/mcp/registerMCPTool", ctx -> {
            try {
                JsonNode body = mapper.readTree(ctx.body());
                String name = body.get("name").asText();
                String description = body.get("description").asText();
                JsonNode inputSchema = body.get("inputSchema");
                String category = body.has("category") ? body.get("category").asText() : null;
                if (name == null || name.trim().isEmpty()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. name is missed."
                    )));
                    return;
                }
                if (description == null || description.trim().isEmpty()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. description is missed."
                    )));
                    return;
                }
                if (inputSchema == null || inputSchema.isNull()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. inputSchema is missed."
                    )));
                    return;
                }
                if (tools.containsKey(name)) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. [" + name + "] is already registered."
                    )));
                    return;
                }
                // For simplicity, we create a dummy handler that returns the input arguments
                Function<Map<String, Object>, Object> handler = arguments -> Map.of(
                    "status", "registered",
                    "tool", name,
                    "arguments", arguments
                );
                addTool(name, description, inputSchema, handler, category);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", 0,
                    "retMsg", "Tool registered successfully"
                )));
            } catch (Exception e) {
                logger.error("[MCP] Failed to register tool", e);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to register tool: " + e.getMessage()
                )));
            }
        });

        // Unregister MCP Tool endpoint
        this.managementApp.post("/mcp/unregisterMCPTool", ctx -> {
            try {
                JsonNode body = mapper.readTree(ctx.body());
                String name = body.get("name").asText();
                if (name == null || name.trim().isEmpty()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. name is missed."
                    )));
                    return;
                }
                if (tools.containsKey(name)) {
                    tools.remove(name);
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", 0,
                        "retMsg", "Tool unregistered successfully"
                    )));
                } else {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. [" + name + "] is not registered."
                    )));
                }
            } catch (Exception e) {
                logger.error("[MCP] Failed to unregister tool", e);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to unregister tool: " + e.getMessage()
                )));
            }
        });

        // Register MCP Resource endpoint
        this.managementApp.post("/mcp/registerMCPResource", ctx -> {
            try {
                JsonNode body = mapper.readTree(ctx.body());
                String uri = body.get("uri").asText();
                String name = body.get("name").asText();
                String description = body.get("description").asText();
                String mimeType = body.get("mimeType").asText();
                Object contents = body.get("contents");
                String category = body.has("category") ? body.get("category").asText() : null;
                if (uri == null || uri.trim().isEmpty()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. uri is missed."
                    )));
                    return;
                }
                if (name == null || name.trim().isEmpty()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. name is missed."
                    )));
                    return;
                }
                if (description == null || description.trim().isEmpty()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. description is missed."
                    )));
                    return;
                }
                if (mimeType == null || mimeType.trim().isEmpty()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. mimeType is missed."
                    )));
                    return;
                }
                if (contents == null) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. contents is missed."
                    )));
                    return;
                }
                if (resources.containsKey(uri)) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. [" + uri + "] is already registered."
                    )));
                    return;
                }
                addResource(uri, name, description, mimeType, contents, category);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", 0,
                    "retMsg", "Resource registered successfully"
                )));
            } catch (Exception e) {
                logger.error("[MCP] Failed to register resource", e);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to register resource: " + e.getMessage()
                )));
            }
        });

        // Unregister MCP Resource endpoint
        this.managementApp.post("/mcp/unregisterMCPResource", ctx -> {
            try {
                JsonNode body = mapper.readTree(ctx.body());
                String uri = body.get("uri").asText();
                if (uri == null || uri.trim().isEmpty()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. uri is missed."
                    )));
                    return;
                }
                if (resources.containsKey(uri)) {
                    resources.remove(uri);
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", 0,
                        "retMsg", "Resource unregistered successfully"
                    )));
                } else {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. [" + uri + "] is not registered."
                    )));
                }
            } catch (Exception e) {
                logger.error("[MCP] Failed to unregister resource", e);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to unregister resource: " + e.getMessage()
                )));
            }
        });

        // Load MCP Tool definitions from JSON
        this.managementApp.post("/mcp/loadMCPTool", ctx -> {
            try {
                JsonNode body = mapper.readTree(ctx.body());
                if (body.isArray()) {
                    for (JsonNode toolDef : body) {
                        String name = toolDef.get("name").asText();
                        String description = toolDef.get("description").asText();
                        JsonNode inputSchema = toolDef.get("inputSchema");
                        String category = toolDef.has("category") ? toolDef.get("category").asText() : null;
                        if (name == null || name.trim().isEmpty() || description == null || description.trim().isEmpty() || inputSchema == null) {
                            logger.warn("[MCP] Skipping invalid tool definition: missing required fields");
                            continue;
                        }
                        if (tools.containsKey(name)) {
                            logger.warn("[MCP] Tool already exists, skipping: {}", name);
                            continue;
                        }
                        // Use dummy handler as in registerMCPTool
                        Function<Map<String, Object>, Object> handler = arguments -> Map.of(
                            "status", "loaded",
                            "tool", name,
                            "arguments", arguments
                        );
                        addTool(name, description, inputSchema, handler, category);
                    }
                } else {
                    // Single object
                    String name = body.get("name").asText();
                    String description = body.get("description").asText();
                    JsonNode inputSchema = body.get("inputSchema");
                    String category = body.has("category") ? body.get("category").asText() : null;
                    if (name == null || name.trim().isEmpty() || description == null || description.trim().isEmpty() || inputSchema == null) {
                        ctx.result(mapper.writeValueAsString(Map.of(
                            "retCode", -1,
                            "retMsg", "Rejected. Missing required fields."
                        )));
                        return;
                    }
                    if (tools.containsKey(name)) {
                        ctx.result(mapper.writeValueAsString(Map.of(
                            "retCode", -1,
                            "retMsg", "Rejected. [" + name + "] is already registered."
                        )));
                        return;
                    }
                    Function<Map<String, Object>, Object> handler = arguments -> Map.of(
                        "status", "loaded",
                        "tool", name,
                        "arguments", arguments
                    );
                    addTool(name, description, inputSchema, handler, category);
                }
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", 0,
                    "retMsg", "Tools loaded successfully"
                )));
            } catch (Exception e) {
                logger.error("[MCP] Failed to load tools", e);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to load tools: " + e.getMessage()
                )));
            }
        });

        // Save MCP Tool definitions to file
        this.managementApp.post("/mcp/saveMCPTool", ctx -> {
            try {
                if (mcpConfigPath == null || mcpConfigPath.trim().isEmpty()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. mcpConfigPath is not configured."
                    )));
                    return;
                }
                File configFile = new File(mcpConfigPath);
                if (configFile.isFile()) {
                    // Single file: read existing config, update tools, keep resources
                    synchronized (saveLock) {
                        JsonNode config = readConfigFile(configFile);
                        ObjectNode configObj = (ObjectNode) config;
                        configObj.set("tools", generateToolDefinitionsJsonArray());
                        // keep existing resources if any
                        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configObj);
                        Files.write(configFile.toPath(), json.getBytes());
                        logger.info("[MCP] Saved {} tools to {}", tools.size(), configFile.getAbsolutePath());
                    }
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", 0,
                        "retMsg", "Tools saved successfully",
                        "savedPath", configFile.getAbsolutePath()
                    )));
                } else if (configFile.isDirectory()) {
                    // Directory: group by category, each category a file named <category>.service
                    Map<String, JsonNode> byCategory = generateToolDefinitionsByCategory();
                    int totalFiles = 0;
                    int totalTools = 0;
                    for (Map.Entry<String, JsonNode> entry : byCategory.entrySet()) {
                        String category = entry.getKey();
                        JsonNode toolsArray = entry.getValue();
                        updateToolsInCategoryFile(configFile, category, toolsArray);
                        totalFiles++;
                        totalTools += toolsArray.size();
                        logger.debug("[MCP] Updated tools in category '{}' with {} tools", category, toolsArray.size());
                    }
                    logger.info("[MCP] Saved {} tools ({} categories) to directory {}", totalTools, totalFiles, configFile.getAbsolutePath());
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", 0,
                        "retMsg", "Tools saved successfully",
                        "savedPath", configFile.getAbsolutePath(),
                        "categories", totalFiles,
                        "tools", totalTools
                    )));
                } else {
                    // Path does not exist, try to create as a file
                    if (configFile.getParentFile() != null && !configFile.getParentFile().exists()) {
                        var ignored = configFile.getParentFile().mkdirs();
                    }
                    // New file: write both tools and empty resources
                    ObjectNode configObj = mapper.createObjectNode();
                    configObj.set("tools", generateToolDefinitionsJsonArray());
                    configObj.set("resources", mapper.createArrayNode());
                    String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configObj);
                    Files.write(configFile.toPath(), json.getBytes());
                    logger.info("[MCP] Created new file and saved {} tools to {}", tools.size(), configFile.getAbsolutePath());
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", 0,
                        "retMsg", "Tools saved successfully (new file created)",
                        "savedPath", configFile.getAbsolutePath()
                    )));
                }
            } catch (Exception e) {
                logger.error("[MCP] Failed to save tools", e);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to save tools: " + e.getMessage()
                )));
            }
        });

        // Dump MCP Tool definitions as JSON (download)
        this.managementApp.post("/mcp/dumpMCPTool", ctx -> {
            try {
                String json = dumpToolsAsJson();
                ctx.header("Content-Type", "application/json");
                ctx.header("Content-Disposition", "attachment; filename=\"mcp_tools.json\"");
                ctx.result(json);
                logger.info("[MCP] Dumped {} tools for download", tools.size());
            } catch (Exception e) {
                logger.error("[MCP] Failed to dump tools", e);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to generate JSON: " + e.getMessage()
                )));
            }
        });

        // Load MCP Resource definitions from JSON
        this.managementApp.post("/mcp/loadMCPResource", ctx -> {
            try {
                JsonNode body = mapper.readTree(ctx.body());
                if (body.isArray()) {
                    for (JsonNode resDef : body) {
                        String uri = resDef.get("uri").asText();
                        String name = resDef.get("name").asText();
                        String description = resDef.get("description").asText();
                        String mimeType = resDef.get("mimeType").asText();
                        Object contents = resDef.get("contents");
                        String category = resDef.has("category") ? resDef.get("category").asText() : null;
                        if (uri == null || uri.trim().isEmpty() || name == null || name.trim().isEmpty() ||
                                description == null || description.trim().isEmpty() || mimeType == null || mimeType.trim().isEmpty() || contents == null) {
                            logger.warn("[MCP] Skipping invalid resource definition: missing required fields");
                            continue;
                        }
                        if (resources.containsKey(uri)) {
                            logger.warn("[MCP] Resource already exists, skipping: {}", uri);
                            continue;
                        }
                        addResource(uri, name, description, mimeType, contents, category);
                    }
                } else {
                    // Single object
                    String uri = body.get("uri").asText();
                    String name = body.get("name").asText();
                    String description = body.get("description").asText();
                    String mimeType = body.get("mimeType").asText();
                    Object contents = body.get("contents");
                    String category = body.has("category") ? body.get("category").asText() : null;
                    if (uri == null || uri.trim().isEmpty() || name == null || name.trim().isEmpty() ||
                            description == null || description.trim().isEmpty() || mimeType == null || mimeType.trim().isEmpty() || contents == null) {
                        ctx.result(mapper.writeValueAsString(Map.of(
                            "retCode", -1,
                            "retMsg", "Rejected. Missing required fields."
                        )));
                        return;
                    }
                    if (resources.containsKey(uri)) {
                        ctx.result(mapper.writeValueAsString(Map.of(
                            "retCode", -1,
                            "retMsg", "Rejected. [" + uri + "] is already registered."
                        )));
                        return;
                    }
                    addResource(uri, name, description, mimeType, contents, category);
                }
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", 0,
                    "retMsg", "Resources loaded successfully"
                )));
            } catch (Exception e) {
                logger.error("[MCP] Failed to load resources", e);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to load resources: " + e.getMessage()
                )));
            }
        });

        // Save MCP Resource definitions to file (saveMCPSource)
        this.managementApp.post("/mcp/saveMCPSource", ctx -> {
            try {
                if (mcpConfigPath == null || mcpConfigPath.trim().isEmpty()) {
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. mcpConfigPath is not configured."
                    )));
                    return;
                }
                File configFile = new File(mcpConfigPath);
                if (configFile.isFile()) {
                    // Single file: read existing config, update resources, keep tools
                    synchronized (saveLock) {
                        JsonNode config = readConfigFile(configFile);
                        ObjectNode configObj = (ObjectNode) config;
                        configObj.set("resources", generateResourceDefinitionsJsonArray());
                        // keep existing tools if any
                        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configObj);
                        Files.write(configFile.toPath(), json.getBytes());
                        logger.info("[MCP] Saved {} resources to {}", resources.size(), configFile.getAbsolutePath());
                    }
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", 0,
                        "retMsg", "Resources saved successfully",
                        "savedPath", configFile.getAbsolutePath()
                    )));
                } else if (configFile.isDirectory()) {
                    // Directory: group by category, each category a file named <category>.service
                    Map<String, JsonNode> byCategory = generateResourceDefinitionsByCategory();
                    int totalFiles = 0;
                    int totalResources = 0;
                    for (Map.Entry<String, JsonNode> entry : byCategory.entrySet()) {
                        String category = entry.getKey();
                        JsonNode resourcesArray = entry.getValue();
                        updateResourcesInCategoryFile(configFile, category, resourcesArray);
                        totalFiles++;
                        totalResources += resourcesArray.size();
                        logger.debug("[MCP] Updated resources in category '{}' with {} resources", category, resourcesArray.size());
                    }
                    logger.info("[MCP] Saved {} resources ({} categories) to directory {}", totalResources, totalFiles, configFile.getAbsolutePath());
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", 0,
                        "retMsg", "Resources saved successfully",
                        "savedPath", configFile.getAbsolutePath(),
                        "categories", totalFiles,
                        "resources", totalResources
                    )));
                } else {
                    // Path does not exist, try to create as a file
                    if (configFile.getParentFile() != null && !configFile.getParentFile().exists()) {
                        var ignored = configFile.getParentFile().mkdirs();
                    }
                    // New file: write both resources and empty tools
                    ObjectNode configObj = mapper.createObjectNode();
                    configObj.set("tools", mapper.createArrayNode());
                    configObj.set("resources", generateResourceDefinitionsJsonArray());
                    String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configObj);
                    Files.write(configFile.toPath(), json.getBytes());
                    logger.info("[MCP] Created new file and saved {} resources to {}", resources.size(), configFile.getAbsolutePath());
                    ctx.result(mapper.writeValueAsString(Map.of(
                        "retCode", 0,
                        "retMsg", "Resources saved successfully (new file created)",
                        "savedPath", configFile.getAbsolutePath()
                    )));
                }
            } catch (Exception e) {
                logger.error("[MCP] Failed to save resources", e);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to save resources: " + e.getMessage()
                )));
            }
        });

        // Dump MCP Resource definitions as JSON (dumpMCPSource)
        this.managementApp.post("/mcp/dumpMCPSource", ctx -> {
            try {
                String json = dumpResourcesAsJson();
                ctx.header("Content-Type", "application/json");
                ctx.header("Content-Disposition", "attachment; filename=\"mcp_resources.json\"");
                ctx.result(json);
                logger.info("[MCP] Dumped {} resources for download", resources.size());
            } catch (Exception e) {
                logger.error("[MCP] Failed to dump resources", e);
                ctx.result(mapper.writeValueAsString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to generate JSON: " + e.getMessage()
                )));
            }
        });
    }

    /**
     * Generate a JSON array of all registered tool definitions.
     */
    private JsonNode generateToolDefinitionsJsonArray() {
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (Tool tool : tools.values()) {
            Map<String, Object> toolObj = new HashMap<>();
            toolObj.put("name", tool.name);
            toolObj.put("description", tool.description);
            toolObj.put("inputSchema", tool.inputSchema);
            if (tool.category != null) {
                toolObj.put("category", tool.category);
            }
            toolList.add(toolObj);
        }
        return mapper.valueToTree(toolList);
    }

    /**
     * Group tool definitions by category.
     * @return Map where key is category (or "uncategorized" if null), value is JSON array of tools in that category.
     */
    private Map<String, JsonNode> generateToolDefinitionsByCategory() {
        Map<String, List<Map<String, Object>>> grouped = new HashMap<>();
        for (Tool tool : tools.values()) {
            String category = tool.category != null ? tool.category : "uncategorized";
            Map<String, Object> toolObj = new HashMap<>();
            toolObj.put("name", tool.name);
            toolObj.put("description", tool.description);
            toolObj.put("inputSchema", tool.inputSchema);
            if (tool.category != null) {
                toolObj.put("category", tool.category);
            }
            grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(toolObj);
        }
        Map<String, JsonNode> result = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), mapper.valueToTree(entry.getValue()));
        }
        return result;
    }

    /**
     * Dump all tools as a JSON string.
     */
    private String dumpToolsAsJson() throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(generateToolDefinitionsJsonArray());
    }

    /**
     * Generate a JSON array of all registered resource definitions.
     */
    private JsonNode generateResourceDefinitionsJsonArray() {
        List<Map<String, Object>> resourceList = new ArrayList<>();
        for (Resource res : resources.values()) {
            Map<String, Object> resObj = new HashMap<>();
            resObj.put("uri", res.uri);
            resObj.put("name", res.name);
            resObj.put("description", res.description);
            resObj.put("mimeType", res.mimeType);
            resObj.put("contents", res.contents);
            if (res.category != null) {
                resObj.put("category", res.category);
            }
            resourceList.add(resObj);
        }
        return mapper.valueToTree(resourceList);
    }

    /**
     * Group resource definitions by category.
     * @return Map where key is category (or "uncategorized" if null), value is JSON array of resources in that category.
     */
    private Map<String, JsonNode> generateResourceDefinitionsByCategory() {
        Map<String, List<Map<String, Object>>> grouped = new HashMap<>();
        for (Resource res : resources.values()) {
            String category = res.category != null ? res.category : "uncategorized";
            Map<String, Object> resObj = new HashMap<>();
            resObj.put("uri", res.uri);
            resObj.put("name", res.name);
            resObj.put("description", res.description);
            resObj.put("mimeType", res.mimeType);
            resObj.put("contents", res.contents);
            if (res.category != null) {
                resObj.put("category", res.category);
            }
            grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(resObj);
        }
        Map<String, JsonNode> result = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), mapper.valueToTree(entry.getValue()));
        }
        return result;
    }

    /**
     * Dump all resources as a JSON string.
     */
    private String dumpResourcesAsJson() throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(generateResourceDefinitionsJsonArray());
    }

    /**
     * Read existing config file (if exists) and return a JsonNode representing the whole config.
     * The config is expected to be a JSON object with optional "tools" and "resources" fields.
     * If the file does not exist or is invalid, returns an empty object.
     */
    private JsonNode readConfigFile(File configFile) {
        if (!configFile.exists() || configFile.length() == 0) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode root = mapper.readTree(configFile);
            if (root.isObject()) {
                return root;
            } else if (root.isArray()) {
                // Legacy format: assume it's an array of tools (or resources?)
                // We'll treat it as tools array and wrap into an object.
                ObjectNode obj = mapper.createObjectNode();
                obj.set("tools", root);
                return obj;
            } else {
                logger.warn("[MCP] Config file has unexpected format, treating as empty");
                return mapper.createObjectNode();
            }
        } catch (Exception e) {
            logger.warn("[MCP] Failed to parse config file, treating as empty", e);
            return mapper.createObjectNode();
        }
    }

    /**
     * Update the tools section in a category .service file.
     * Reads existing file (if any), merges tools, keeps resources unchanged.
     * Writes back as a JSON object with "tools" and "resources" fields.
     */
    private void updateToolsInCategoryFile(File configDir, String category, JsonNode newTools) throws IOException {
        String safeCategory = category.replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeCategory + ".service";
        File targetFile = new File(configDir, fileName);
        JsonNode root = readConfigFile(targetFile);
        ObjectNode rootObj;
        if (root.isObject()) {
            rootObj = (ObjectNode) root;
        } else {
            // If root is array or something else, treat as empty object
            rootObj = mapper.createObjectNode();
        }
        rootObj.set("tools", newTools);
        // Ensure resources field exists (if missing, create empty array)
        if (!rootObj.has("resources")) {
            rootObj.set("resources", mapper.createArrayNode());
        }
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootObj);
        Files.write(targetFile.toPath(), json.getBytes());
        logger.debug("[MCP] Updated tools in category '{}' to {}", category, targetFile.getAbsolutePath());
    }

    /**
     * Update the resources section in a category .service file.
     */
    private void updateResourcesInCategoryFile(File configDir, String category, JsonNode newResources) throws IOException {
        String safeCategory = category.replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeCategory + ".service";
        File targetFile = new File(configDir, fileName);
        JsonNode root = readConfigFile(targetFile);
        ObjectNode rootObj;
        if (root.isObject()) {
            rootObj = (ObjectNode) root;
        } else {
            rootObj = mapper.createObjectNode();
        }
        rootObj.set("resources", newResources);
        if (!rootObj.has("tools")) {
            rootObj.set("tools", mapper.createArrayNode());
        }
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootObj);
        Files.write(targetFile.toPath(), json.getBytes());
        logger.debug("[MCP] Updated resources in category '{}' to {}", category, targetFile.getAbsolutePath());
    }

    public void addTool(String name, String description, JsonNode inputSchema, Function<Map<String,Object>, Object> handler, String category) {
        tools.put(name, new Tool(name, description, inputSchema, handler, category));
    }

    public void addResource(String uri, String name, String description, String mimeType, Object contents, String category) {
        resources.put(uri, new Resource(uri, name, description, mimeType, contents, category));
    }

    public void shutdown() {
        executor.shutdown();
    }

    private static class Tool {
        final String name;
        final String description;
        final JsonNode inputSchema;
        final Function<Map<String,Object>, Object> handler;
        final String category;

        Tool(String name, String description, JsonNode inputSchema, Function<Map<String,Object>, Object> handler, String category) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.handler = handler;
            this.category = category;
        }
    }

    private static class Resource {
        final String uri;
        final String name;
        final String description;
        final String mimeType;
        final Object contents;
        final String category;

        Resource(String uri, String name, String description, String mimeType, Object contents, String category) {
            this.uri = uri;
            this.name = name;
            this.description = description;
            this.mimeType = mimeType;
            this.contents = contents;
            this.category = category;
        }
    }
}
