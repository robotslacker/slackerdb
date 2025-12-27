package org.slackerdb.dbserver.mcpservice;

import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.TypeReference;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A minimal MCP (Model Context Protocol) server implementation.
 * Provides SSE for streaming, JSON-RPC over HTTP for tools and resources.
 */
public class McpServer {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final Map<String, Service> services = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Logger logger;
    private final String mcpConfigPath;
    private final String mcpLlmServer;
    private final Javalin managementApp;
    private final Object saveLock = new Object();
    private final int portX;
    private final String bindHost;
    
    // Conversation history management
    private final Map<String, ConversationSession> conversationSessions = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY_MESSAGES = 20; // Maximum number of messages to keep in history
    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes session timeout
    
    // WebSocket heartbeat support for keeping connections alive
    private final Map<io.javalin.websocket.WsContext, Long> activeChatConnections = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService heartbeatExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean heartbeatStarted = new java.util.concurrent.atomic.AtomicBoolean(false);
    
    public McpServer(
            DBInstance dbInstance,
            Javalin managementApp,
            Logger logger
    ) {
        this.logger = logger;
        this.managementApp = managementApp;
        this.mcpConfigPath = dbInstance.serverConfiguration.getMcpConfig();
        this.mcpLlmServer = dbInstance.serverConfiguration.getMcpLlmServer();
        this.portX = dbInstance.serverConfiguration.getPortX();
        this.bindHost = dbInstance.serverConfiguration.getBindHost();
    }

    public void run() throws Exception {
        // SSE endpoint for MCP protocol
        this.managementApp.sse("/sse", client -> {
            try {
                Map<String, Object> capabilities = new HashMap<>();
                // 主流扩展：补充list/describe操作，更符合MCP标准
                capabilities.put("tools", Map.of(
                        "list", true,          // 列出所有可用工具
                        "listChanged", true,   // 列出变更的工具
                        "describe", true,      // 描述工具的参数/返回值
                        "call", true           // 调用工具
                ));

                // 主流扩展：补充list/write操作，覆盖完整的资源读写能力
                capabilities.put("resources", Map.of(
                        "list", true,
                        "listChanged", true,
                        "read", true,
                        "write", true,         // 支持写入/修改资源
                        "describe", true
                ));

                // 主流扩展：补充list/invoke操作，完善服务调用能力
                capabilities.put("services", Map.of(
                        "list", true,
                        "listChanged", true,
                        "invoke", true         // 通用的服务调用（替代仅listChanged）
                ));

                if (mcpLlmServer != null && !mcpLlmServer.trim().isEmpty()) {
                    Map<String, Object> experimental = new HashMap<>();
                    experimental.put("aiChat", true);
                    capabilities.put("experimental", experimental);
                }

                client.sendEvent(
                        "message",
                        JSON.toJSONString(
                                Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "result",
                                        Map.of(
                                                "protocolVersion", "2024-11-05",
                                                "capabilities", capabilities,
                                                "serverInfo", Map.of(
                                                        "name", "SlackerDB-MCP-Server",
                                                        "version", "1.0.0"
                                                )
                                        )
                                )
                        )
                );
            } catch (Exception e) {
                logger.error("[SERVER][MCP] SSE error: ", e);
            }
        });

        // JSON-RPC over HTTP endpoint for MCP
        this.managementApp.post("/jsonrpc", ctx -> {
            JSONObject request;
            try {
                request = JSON.parseObject(ctx.body());
            } catch (Exception e) {
                // Parse error
                ctx.result(JSON.toJSONString(Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -32700, "message", "Parse error: " + e.getMessage())
                )));
                return;
            }

            // Validate jsonrpc version
            String jsonrpcValue = request.getString("jsonrpc");
            if (!"2.0".equals(jsonrpcValue)) {
                ctx.result(JSON.toJSONString(Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -32600, "message", "Invalid Request: jsonrpc must be exactly '2.0'")
                )));
                return;
            }

            // Validate method
            String method = request.getString("method");
            if (method == null) {
                ctx.result(JSON.toJSONString(Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -32600, "message", "Invalid Request: method must be a string")
                )));
                return;
            }

            JSONObject params = request.getJSONObject("params");
            Object id = request.get("id"); // may be null, number, string, etc.

            switch (method) {
                case "tools/list" -> {
                    List<Map<String, Object>> toolList = new ArrayList<>();
                    for (Tool tool : tools.values()) {
                        toolList.add(Map.of(
                                "name", tool.name,
                                "description", tool.description,
                                "inputSchema", tool.inputSchema,
                                "category", tool.category
                        ));
                    }
                    ctx.result(JSON.toJSONString(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", Map.of("tools", toolList)
                    )));
                }
                case "tools/call" -> {
                    // Validate params
                    if (params == null) {
                        ctx.result(JSON.toJSONString(Map.of(
                                "jsonrpc", "2.0",
                                "id", id,
                                "error", Map.of("code", -32600, "message", "Invalid Request: params must be an object")
                        )));
                        return;
                    }
                    String toolName = params.getString("name");
                    JSONObject argumentsNode = params.getJSONObject("arguments");
                    if (toolName == null) {
                        ctx.result(JSON.toJSONString(Map.of(
                                "jsonrpc", "2.0",
                                "id", id,
                                "error", Map.of("code", -32600, "message", "Invalid Request: params.name must be a string")
                        )));
                        return;
                    }
                    Tool tool = tools.get(toolName);
                    if (tool == null) {
                        ctx.result(JSON.toJSONString(Map.of(
                                "jsonrpc", "2.0",
                                "id", id,
                                "error", Map.of("code", -32601, "message", "Tool not found")
                        )));
                        return;
                    }
                    Map<String, Object> argsMap;
                    if (argumentsNode == null) {
                        argsMap = Map.of();
                    } else {
                        try {
                            argsMap = JSON.parseObject(argumentsNode.toJSONString(), new TypeReference<>() {
                            });
                        } catch (Exception e) {
                            ctx.result(JSON.toJSONString(Map.of(
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
                        ctx.result(JSON.toJSONString(Map.of(
                                "jsonrpc", "2.0",
                                "id", id,
                                "error", Map.of("code", -32603, "message", "Internal error: " + e.getMessage())
                        )));
                        return;
                    }
                    ctx.result(JSON.toJSONString(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", result
                    )));
                }
                case "resources/list" -> {
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
                    ctx.result(JSON.toJSONString(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", Map.of("resources", resourceList)
                    )));
                }
                case "resources/read" -> {
                    // Validate params
                    if (params == null) {
                        ctx.result(JSON.toJSONString(Map.of(
                                "jsonrpc", "2.0",
                                "id", id,
                                "error", Map.of("code", -32600, "message", "Invalid Request: params must be an object")
                        )));
                        return;
                    }
                    String uri = params.getString("uri");
                    if (uri == null) {
                        ctx.result(JSON.toJSONString(Map.of(
                                "jsonrpc", "2.0",
                                "id", id,
                                "error", Map.of("code", -32600, "message", "Invalid Request: params.uri must be a string")
                        )));
                        return;
                    }
                    Resource res = resources.get(uri);
                    if (res == null) {
                        ctx.result(JSON.toJSONString(Map.of(
                                "jsonrpc", "2.0",
                                "id", id,
                                "error", Map.of("code", -32601, "message", "Resource not found")
                        )));
                        return;
                    }
                    ctx.result(JSON.toJSONString(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", Map.of("contents", res.contents)
                    )));
                }
                case "services/list" -> {
                    List<Map<String, Object>> serviceList = new ArrayList<>();
                    for (Service service : services.values()) {
                        Map<String, Object> serviceObj = new HashMap<>();
                        serviceObj.put("name", service.name);
                        serviceObj.put("description", service.description);
                        if (service.category != null) {
                            serviceObj.put("category", service.category);
                        }
                        serviceObj.put("capabilities", service.capabilities);
                        serviceObj.put("use_cases", service.useCases);

                        // Convert parameters
                        List<Map<String, Object>> paramList = new ArrayList<>();
                        for (ServiceParameter param : service.parameters) {
                            Map<String, Object> paramObj = new HashMap<>();
                            paramObj.put("name", param.name);
                            paramObj.put("type", param.type);
                            paramObj.put("required", param.required);
                            paramObj.put("description", param.description);
                            paramList.add(paramObj);
                        }
                        serviceObj.put("parameters", paramList);

                        // Convert examples
                        List<Map<String, Object>> exampleList = new ArrayList<>();
                        for (ServiceExample ex : service.examples) {
                            Map<String, Object> exampleObj = new HashMap<>();
                            exampleObj.put("user_query", ex.userQuery);
                            exampleObj.put("parameters", ex.parameters);
                            exampleList.add(exampleObj);
                        }
                        serviceObj.put("examples", exampleList);

                        serviceList.add(serviceObj);
                    }
                    ctx.result(JSON.toJSONString(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", Map.of("services", serviceList)
                    )));
                }
                default -> ctx.result(JSON.toJSONString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32601, "message", "Method not found")
                )));
            }
        });

        // Register MCP Tool endpoint
        this.managementApp.post("/mcp/registerMCPTool", ctx -> {
            try {
                JSONObject body = JSON.parseObject(ctx.body());
                String name = body.getString("name");
                String description = body.getString("description");
                JSONObject inputSchema = body.getJSONObject("inputSchema");
                String category = body.containsKey("category") ? body.getString("category") : null;
                if (name == null || name.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. name is missed."
                    )));
                    return;
                }
                if (description == null || description.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. description is missed."
                    )));
                    return;
                }
                if (inputSchema == null) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. inputSchema is missed."
                    )));
                    return;
                }
                if (tools.containsKey(name)) {
                    ctx.result(JSON.toJSONString(Map.of(
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
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", 0,
                    "retMsg", "Tool registered successfully"
                )));
            } catch (Exception e) {
                logger.error("[MCP] Failed to register tool", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to register tool: " + e.getMessage()
                )));
            }
        });

        // Unregister MCP Tool endpoint
        this.managementApp.post("/mcp/unregisterMCPTool", ctx -> {
            try {
                JSONObject body = JSON.parseObject(ctx.body());
                String name = body.getString("name");
                if (name == null || name.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. name is missed."
                    )));
                    return;
                }
                if (tools.containsKey(name)) {
                    tools.remove(name);
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", 0,
                        "retMsg", "Tool unregistered successfully"
                    )));
                } else {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. [" + name + "] is not registered."
                    )));
                }
            } catch (Exception e) {
                logger.error("[MCP] Failed to unregister tool", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to unregister tool: " + e.getMessage()
                )));
            }
        });

        // Register MCP Resource endpoint
        this.managementApp.post("/mcp/registerMCPResource", ctx -> {
            try {
                JSONObject body = JSON.parseObject(ctx.body());
                String uri = body.getString("uri");
                String name = body.getString("name");
                String description = body.getString("description");
                String mimeType = body.getString("mimeType");
                Object contents = body.get("contents");
                String category = body.containsKey("category") ? body.getString("category") : null;
                if (uri == null || uri.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. uri is missed."
                    )));
                    return;
                }
                if (name == null || name.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. name is missed."
                    )));
                    return;
                }
                if (description == null || description.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. description is missed."
                    )));
                    return;
                }
                if (mimeType == null || mimeType.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. mimeType is missed."
                    )));
                    return;
                }
                if (contents == null) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. contents is missed."
                    )));
                    return;
                }
                if (resources.containsKey(uri)) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. [" + uri + "] is already registered."
                    )));
                    return;
                }
                addResource(uri, name, description, mimeType, contents, category);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", 0,
                    "retMsg", "Resource registered successfully"
                )));
            } catch (Exception e) {
                logger.error("[MCP] Failed to register resource", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to register resource: " + e.getMessage()
                )));
            }
        });

        // Unregister MCP Resource endpoint
        this.managementApp.post("/mcp/unregisterMCPResource", ctx -> {
            try {
                JSONObject body = JSON.parseObject(ctx.body());
                String uri = body.getString("uri");
                if (uri == null || uri.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. uri is missed."
                    )));
                    return;
                }
                if (resources.containsKey(uri)) {
                    resources.remove(uri);
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", 0,
                        "retMsg", "Resource unregistered successfully"
                    )));
                } else {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. [" + uri + "] is not registered."
                    )));
                }
            } catch (Exception e) {
                logger.error("[MCP] Failed to unregister resource", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to unregister resource: " + e.getMessage()
                )));
            }
        });

        // Load MCP Tool definitions from JSON
        this.managementApp.post("/mcp/loadMCPTool", ctx -> {
            try {
                Object parsed = JSON.parse(ctx.body());
                if (parsed instanceof JSONArray bodyArray) {
                    for (int i = 0; i < bodyArray.size(); i++) {
                        JSONObject toolDef = bodyArray.getJSONObject(i);
                        String name = toolDef.getString("name");
                        String description = toolDef.getString("description");
                        JSONObject inputSchema = toolDef.getJSONObject("inputSchema");
                        String category = toolDef.containsKey("category") ? toolDef.getString("category") : null;
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
                } else if (parsed instanceof JSONObject body) {
                    // Single object
                    String name = body.getString("name");
                    String description = body.getString("description");
                    JSONObject inputSchema = body.getJSONObject("inputSchema");
                    String category = body.containsKey("category") ? body.getString("category") : null;
                    if (name == null || name.trim().isEmpty() || description == null || description.trim().isEmpty() || inputSchema == null) {
                        ctx.result(JSON.toJSONString(Map.of(
                            "retCode", -1,
                            "retMsg", "Rejected. Missing required fields."
                        )));
                        return;
                    }
                    if (tools.containsKey(name)) {
                        ctx.result(JSON.toJSONString(Map.of(
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
                } else {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. Invalid JSON format."
                    )));
                    return;
                }
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", 0,
                    "retMsg", "Tools loaded successfully"
                )));
            } catch (Exception e) {
                logger.error("[MCP] Failed to load tools", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to load tools: " + e.getMessage()
                )));
            }
        });

        // Save MCP Tool definitions to file
        this.managementApp.post("/mcp/saveMCPTool", ctx -> {
            try {
                if (mcpConfigPath == null || mcpConfigPath.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. mcpConfigPath is not configured."
                    )));
                    return;
                }
                File configFile = new File(mcpConfigPath);
                if (configFile.isFile()) {
                    // Single file: read existing config, update tools, keep resources
                    synchronized (saveLock) {
                        JSONObject config = readConfigFile(configFile);
                        config.put("tools", generateToolDefinitionsJsonArray());
                        // keep existing resources if any
                        String json = JSON.toJSONString(config, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
                        Files.write(configFile.toPath(), json.getBytes());
                        logger.info("[MCP] Saved {} tools to {}", tools.size(), configFile.getAbsolutePath());
                    }
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", 0,
                        "retMsg", "Tools saved successfully",
                        "savedPath", configFile.getAbsolutePath()
                    )));
                } else if (configFile.isDirectory()) {
                    // Directory: group by category, each category a file named <category>.service
                    Map<String, JSONArray> byCategory = generateToolDefinitionsByCategory();
                    int totalFiles = 0;
                    int totalTools = 0;
                    for (Map.Entry<String, JSONArray> entry : byCategory.entrySet()) {
                        String category = entry.getKey();
                        JSONArray toolsArray = entry.getValue();
                        updateToolsInCategoryFile(configFile, category, toolsArray);
                        totalFiles++;
                        totalTools += toolsArray.size();
                        logger.debug("[MCP] Updated tools in category '{}' with {} tools", category, toolsArray.size());
                    }
                    logger.info("[MCP] Saved {} tools ({} categories) to directory {}", totalTools, totalFiles, configFile.getAbsolutePath());
                    ctx.result(JSON.toJSONString(Map.of(
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
                    JSONObject configObj = new JSONObject();
                    configObj.put("tools", generateToolDefinitionsJsonArray());
                    configObj.put("resources", new JSONArray());
                    String json = JSON.toJSONString(configObj, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
                    Files.write(configFile.toPath(), json.getBytes());
                    logger.info("[MCP] Created new file and saved {} tools to {}", tools.size(), configFile.getAbsolutePath());
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", 0,
                        "retMsg", "Tools saved successfully (new file created)",
                        "savedPath", configFile.getAbsolutePath()
                    )));
                }
            } catch (Exception e) {
                logger.error("[MCP] Failed to save tools", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to save tools: " + e.getMessage()
                )));
            }
        });

        // Dump MCP Tool definitions as JSON (download)
        this.managementApp.post("/mcp/dumpMCPTool", ctx -> {
            try {
                String json = JSON.toJSONString(generateToolDefinitionsJsonArray(), com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
                ctx.header("Content-Type", "application/json");
                ctx.header("Content-Disposition", "attachment; filename=\"mcp_tools.json\"");
                ctx.result(json);
                logger.info("[MCP] Dumped {} tools for download", tools.size());
            } catch (Exception e) {
                logger.error("[MCP] Failed to dump tools", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to generate JSON: " + e.getMessage()
                )));
            }
        });

        // Load MCP Resource definitions from JSON
        this.managementApp.post("/mcp/loadMCPResource", ctx -> {
            try {
                Object parsed = JSON.parse(ctx.body());
                if (parsed instanceof JSONArray bodyArray) {
                    for (int i = 0; i < bodyArray.size(); i++) {
                        JSONObject resDef = bodyArray.getJSONObject(i);
                        String uri = resDef.getString("uri");
                        String name = resDef.getString("name");
                        String description = resDef.getString("description");
                        String mimeType = resDef.getString("mimeType");
                        Object contents = resDef.get("contents");
                        String category = resDef.containsKey("category") ? resDef.getString("category") : null;
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
                } else if (parsed instanceof JSONObject body) {
                    // Single object
                    String uri = body.getString("uri");
                    String name = body.getString("name");
                    String description = body.getString("description");
                    String mimeType = body.getString("mimeType");
                    Object contents = body.get("contents");
                    String category = body.containsKey("category") ? body.getString("category") : null;
                    if (uri == null || uri.trim().isEmpty() || name == null || name.trim().isEmpty() ||
                            description == null || description.trim().isEmpty() || mimeType == null || mimeType.trim().isEmpty() || contents == null) {
                        ctx.result(JSON.toJSONString(Map.of(
                            "retCode", -1,
                            "retMsg", "Rejected. Missing required fields."
                        )));
                        return;
                    }
                    if (resources.containsKey(uri)) {
                        ctx.result(JSON.toJSONString(Map.of(
                            "retCode", -1,
                            "retMsg", "Rejected. [" + uri + "] is already registered."
                        )));
                        return;
                    }
                    addResource(uri, name, description, mimeType, contents, category);
                } else {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. Invalid JSON format."
                    )));
                    return;
                }
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", 0,
                    "retMsg", "Resources loaded successfully"
                )));
            } catch (Exception e) {
                logger.error("[MCP] Failed to load resources", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to load resources: " + e.getMessage()
                )));
            }
        });

        // Save MCP Resource definitions to file (saveMCPSource)
        this.managementApp.post("/mcp/saveMCPSource", ctx -> {
            try {
                if (mcpConfigPath == null || mcpConfigPath.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. mcpConfigPath is not configured."
                    )));
                    return;
                }
                File configFile = new File(mcpConfigPath);
                if (configFile.isFile()) {
                    // Single file: read existing config, update resources, keep tools
                    synchronized (saveLock) {
                        JSONObject config = readConfigFile(configFile);
                        config.put("resources", generateResourceDefinitionsJsonArray());
                        // keep existing tools if any
                        String json = JSON.toJSONString(config, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
                        Files.write(configFile.toPath(), json.getBytes());
                        logger.info("[MCP] Saved {} resources to {}", resources.size(), configFile.getAbsolutePath());
                    }
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", 0,
                        "retMsg", "Resources saved successfully",
                        "savedPath", configFile.getAbsolutePath()
                    )));
                } else if (configFile.isDirectory()) {
                    // Directory: group by category, each category a file named <category>.service
                    Map<String, JSONArray> byCategory = generateResourceDefinitionsByCategory();
                    int totalFiles = 0;
                    int totalResources = 0;
                    for (Map.Entry<String, JSONArray> entry : byCategory.entrySet()) {
                        String category = entry.getKey();
                        JSONArray resourcesArray = entry.getValue();
                        updateResourcesInCategoryFile(configFile, category, resourcesArray);
                        totalFiles++;
                        totalResources += resourcesArray.size();
                        logger.debug("[MCP] Updated resources in category '{}' with {} resources", category, resourcesArray.size());
                    }
                    logger.info("[MCP] Saved {} resources ({} categories) to directory {}", totalResources, totalFiles, configFile.getAbsolutePath());
                    ctx.result(JSON.toJSONString(Map.of(
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
                    JSONObject configObj = new JSONObject();
                    configObj.put("tools", new JSONArray());
                    configObj.put("resources", generateResourceDefinitionsJsonArray());
                    String json = JSON.toJSONString(configObj, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
                    Files.write(configFile.toPath(), json.getBytes());
                    logger.info("[MCP] Created new file and saved {} resources to {}", resources.size(), configFile.getAbsolutePath());
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", 0,
                        "retMsg", "Resources saved successfully (new file created)",
                        "savedPath", configFile.getAbsolutePath()
                    )));
                }
            } catch (Exception e) {
                logger.error("[MCP] Failed to save resources", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to save resources: " + e.getMessage()
                )));
            }
        });

        // Dump MCP Resource definitions as JSON (dumpMCPSource)
        this.managementApp.post("/mcp/dumpMCPSource", ctx -> {
            try {
                String json = JSON.toJSONString(generateResourceDefinitionsJsonArray(), com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
                ctx.header("Content-Type", "application/json");
                ctx.header("Content-Disposition", "attachment; filename=\"mcp_resources.json\"");
                ctx.result(json);
                logger.info("[MCP] Dumped {} resources for download", resources.size());
            } catch (Exception e) {
                logger.error("[MCP] Failed to dump resources", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to generate JSON: " + e.getMessage()
                )));
            }
        });

        // Register MCP Service endpoint
        this.managementApp.post("/mcp/registerMCPService", ctx -> {
            try {
                JSONObject body = JSON.parseObject(ctx.body());
                String name = body.getString("name");
                String apiName = body.getString("apiName");
                String version = body.getString("version");
                String method = body.getString("method");
                String description = body.getString("description");
                String category = body.containsKey("category") ? body.getString("category") : null;
                
                // Parse capabilities
                List<String> capabilities = new ArrayList<>();
                JSONArray capabilitiesNode = body.getJSONArray("capabilities");
                if (capabilitiesNode != null) {
                    for (int i = 0; i < capabilitiesNode.size(); i++) {
                        capabilities.add(capabilitiesNode.getString(i));
                    }
                }
                
                // Parse use cases
                List<String> useCases = new ArrayList<>();
                JSONArray useCasesNode = body.getJSONArray("use_cases");
                if (useCasesNode != null) {
                    for (int i = 0; i < useCasesNode.size(); i++) {
                        useCases.add(useCasesNode.getString(i));
                    }
                }
                
                // Parse parameters
                List<ServiceParameter> parameters = new ArrayList<>();
                JSONArray parametersNode = body.getJSONArray("parameters");
                if (parametersNode != null) {
                    for (int i = 0; i < parametersNode.size(); i++) {
                        JSONObject param = parametersNode.getJSONObject(i);
                        String paramName = param.getString("name");
                        String paramType = param.getString("type");
                        boolean paramRequired = param.containsKey("required") && param.getBoolean("required");
                        String paramDescription = param.containsKey("description") ? param.getString("description") : "";
                        parameters.add(new ServiceParameter(paramName, paramType, paramRequired, paramDescription));
                    }
                }
                
                // Parse examples
                List<ServiceExample> examples = new ArrayList<>();
                JSONArray examplesNode = body.getJSONArray("examples");
                if (examplesNode != null) {
                    for (int i = 0; i < examplesNode.size(); i++) {
                        JSONObject ex = examplesNode.getJSONObject(i);
                        String userQuery = ex.getString("user_query");
                        Map<String, Object> params = JSON.parseObject(JSON.toJSONString(ex.get("parameters")), new TypeReference<>() {
                        });
                        examples.add(new ServiceExample(userQuery, params));
                    }
                }
                
                addService(name, apiName, version, method, description, category, capabilities, useCases, parameters, examples);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", 0,
                    "retMsg", "Service registered successfully"
                )));
            } catch (Exception e) {
                logger.error("[MCP] Failed to register service", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to register service: " + e.getMessage()
                )));
            }
        });

        // Unregister MCP Service endpoint
        this.managementApp.post("/mcp/unregisterMCPService", ctx -> {
            try {
                JSONObject body = JSON.parseObject(ctx.body());
                String name = body.getString("name");
                if (name == null || name.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. name is missed."
                    )));
                    return;
                }
                if (services.containsKey(name)) {
                    services.remove(name);
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", 0,
                        "retMsg", "Service unregistered successfully"
                    )));
                } else {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. [" + name + "] is not registered."
                    )));
                }
            } catch (Exception e) {
                logger.error("[MCP] Failed to unregister service", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to unregister service: " + e.getMessage()
                )));
            }
        });

        // Load MCP Service definitions from JSON
        this.managementApp.post("/mcp/loadMCPService", ctx -> {
            try {
                Object parsed = JSON.parse(ctx.body());
                if (parsed instanceof JSONArray bodyArray) {
                    for (int i = 0; i < bodyArray.size(); i++) {
                        JSONObject serviceDef = bodyArray.getJSONObject(i);
                        String name = serviceDef.getString("name");
                        String apiName = serviceDef.getString("apiName");
                        String version = serviceDef.getString("version");
                        String method = serviceDef.getString("method");
                        String description = serviceDef.getString("description");
                        String category = serviceDef.containsKey("category") ? serviceDef.getString("category") : null;
                        
                        // Parse capabilities
                        List<String> capabilities = new ArrayList<>();
                        JSONArray capabilitiesNode = serviceDef.getJSONArray("capabilities");
                        if (capabilitiesNode != null) {
                            for (int j = 0; j < capabilitiesNode.size(); j++) {
                                capabilities.add(capabilitiesNode.getString(j));
                            }
                        }
                        
                        // Parse use cases
                        List<String> useCases = new ArrayList<>();
                        JSONArray useCasesNode = serviceDef.getJSONArray("use_cases");
                        if (useCasesNode != null) {
                            for (int j = 0; j < useCasesNode.size(); j++) {
                                useCases.add(useCasesNode.getString(j));
                            }
                        }
                        
                        // Parse parameters
                        List<ServiceParameter> parameters = new ArrayList<>();
                        JSONArray parametersNode = serviceDef.getJSONArray("parameters");
                        if (parametersNode != null) {
                            for (int j = 0; j < parametersNode.size(); j++) {
                                JSONObject param = parametersNode.getJSONObject(j);
                                String paramName = param.getString("name");
                                String paramType = param.getString("type");
                                boolean paramRequired = param.containsKey("required") && param.getBoolean("required");
                                String paramDescription = param.containsKey("description") ? param.getString("description") : "";
                                parameters.add(new ServiceParameter(paramName, paramType, paramRequired, paramDescription));
                            }
                        }
                        
                        // Parse examples
                        List<ServiceExample> examples = new ArrayList<>();
                        JSONArray examplesNode = serviceDef.getJSONArray("examples");
                        if (examplesNode != null) {
                            for (int j = 0; j < examplesNode.size(); j++) {
                                JSONObject ex = examplesNode.getJSONObject(j);
                                String userQuery = ex.getString("user_query");
                                Map<String, Object> params = JSON.parseObject(JSON.toJSONString(ex.get("parameters")), new TypeReference<>() {
                                });
                                examples.add(new ServiceExample(userQuery, params));
                            }
                        }
                        
                        if (name == null || name.trim().isEmpty() || description == null || description.trim().isEmpty()) {
                            logger.warn("[MCP] Skipping invalid service definition: missing required fields");
                            continue;
                        }
                        if (services.containsKey(name)) {
                            logger.warn("[MCP] Service already exists, skipping: {}", name);
                            continue;
                        }
                        
                        addService(name, apiName, version, method, description, category, capabilities, useCases, parameters, examples);
                    }
                } else if (parsed instanceof JSONObject body) {
                    // Single object
                    String name = body.getString("name");
                    String apiName = body.getString("apiName");
                    String version = body.getString("version");
                    String method = body.getString("method");
                    String description = body.getString("description");
                    String category = body.containsKey("category") ? body.getString("category") : null;
                    
                    // Parse capabilities
                    List<String> capabilities = new ArrayList<>();
                    JSONArray capabilitiesNode = body.getJSONArray("capabilities");
                    if (capabilitiesNode != null) {
                        for (int i = 0; i < capabilitiesNode.size(); i++) {
                            capabilities.add(capabilitiesNode.getString(i));
                        }
                    }
                    
                    // Parse use cases
                    List<String> useCases = new ArrayList<>();
                    JSONArray useCasesNode = body.getJSONArray("use_cases");
                    if (useCasesNode != null) {
                        for (int i = 0; i < useCasesNode.size(); i++) {
                            useCases.add(useCasesNode.getString(i));
                        }
                    }
                    
                    // Parse parameters
                    List<ServiceParameter> parameters = new ArrayList<>();
                    JSONArray parametersNode = body.getJSONArray("parameters");
                    if (parametersNode != null) {
                        for (int i = 0; i < parametersNode.size(); i++) {
                            JSONObject param = parametersNode.getJSONObject(i);
                            String paramName = param.getString("name");
                            String paramType = param.getString("type");
                            boolean paramRequired = param.containsKey("required") && param.getBoolean("required");
                            String paramDescription = param.containsKey("description") ? param.getString("description") : "";
                            parameters.add(new ServiceParameter(paramName, paramType, paramRequired, paramDescription));
                        }
                    }
                    
                    // Parse examples
                    List<ServiceExample> examples = new ArrayList<>();
                    JSONArray examplesNode = body.getJSONArray("examples");
                    if (examplesNode != null) {
                        for (int i = 0; i < examplesNode.size(); i++) {
                            JSONObject ex = examplesNode.getJSONObject(i);
                            String userQuery = ex.getString("user_query");
                            Map<String, Object> params = JSON.parseObject(JSON.toJSONString(ex.get("parameters")), new TypeReference<>() {
                            });
                            examples.add(new ServiceExample(userQuery, params));
                        }
                    }
                    
                    if (name == null || name.trim().isEmpty() || description == null || description.trim().isEmpty()) {
                        ctx.result(JSON.toJSONString(Map.of(
                            "retCode", -1,
                            "retMsg", "Rejected. Missing required fields."
                        )));
                        return;
                    }
                    if (services.containsKey(name)) {
                        ctx.result(JSON.toJSONString(Map.of(
                            "retCode", -1,
                            "retMsg", "Rejected. [" + name + "] is already registered."
                        )));
                        return;
                    }
                    
                    addService(name, apiName, version, method, description, category, capabilities, useCases, parameters, examples);
                } else {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. Invalid JSON format."
                    )));
                    return;
                }
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", 0,
                    "retMsg", "Services loaded successfully"
                )));
            } catch (Exception e) {
                logger.error("[MCP] Failed to load services", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to load services: " + e.getMessage()
                )));
            }
        });

        // Save MCP Service definitions to file
        this.managementApp.post("/mcp/saveMCPService", ctx -> {
            try {
                if (mcpConfigPath == null || mcpConfigPath.trim().isEmpty()) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. mcpConfigPath is not configured."
                    )));
                    return;
                }
                File configFile = new File(mcpConfigPath);
                if (configFile.isFile()) {
                    // Single file: read existing config, update services, keep tools and resources
                    synchronized (saveLock) {
                        JSONObject config = readConfigFile(configFile);
                        config.put("services", generateServiceDefinitionsJsonArray());
                        // keep existing tools and resources if any
                        String json = JSON.toJSONString(config, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
                        Files.write(configFile.toPath(), json.getBytes());
                        logger.info("[MCP] Saved {} services to {}", services.size(), configFile.getAbsolutePath());
                    }
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", 0,
                        "retMsg", "Services saved successfully",
                        "savedPath", configFile.getAbsolutePath()
                    )));
                } else if (configFile.isDirectory()) {
                    // Directory: group by category, each category a file named <category>.service
                    Map<String, JSONArray> byCategory = generateServiceDefinitionsByCategory();
                    int totalFiles = 0;
                    int totalServices = 0;
                    for (Map.Entry<String, JSONArray> entry : byCategory.entrySet()) {
                        String category = entry.getKey();
                        JSONArray servicesArray = entry.getValue();
                        updateServicesInCategoryFile(configFile, category, servicesArray);
                        totalFiles++;
                        totalServices += servicesArray.size();
                        logger.debug("[MCP] Updated services in category '{}' with {} services", category, servicesArray.size());
                    }
                    logger.info("[MCP] Saved {} services ({} categories) to directory {}", totalServices, totalFiles, configFile.getAbsolutePath());
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", 0,
                        "retMsg", "Services saved successfully",
                        "savedPath", configFile.getAbsolutePath(),
                        "categories", totalFiles,
                        "services", totalServices
                    )));
                } else {
                    // Path does not exist, try to create as a file
                    if (configFile.getParentFile() != null && !configFile.getParentFile().exists()) {
                        var ignored = configFile.getParentFile().mkdirs();
                    }
                    // New file: write services, empty tools and resources
                    JSONObject configObj = new JSONObject();
                    configObj.put("tools", new JSONArray());
                    configObj.put("resources", new JSONArray());
                    configObj.put("services", generateServiceDefinitionsJsonArray());
                    String json = JSON.toJSONString(configObj, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
                    Files.write(configFile.toPath(), json.getBytes());
                    logger.info("[MCP] Created new file and saved {} services to {}", services.size(), configFile.getAbsolutePath());
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", 0,
                        "retMsg", "Services saved successfully (new file created)",
                        "savedPath", configFile.getAbsolutePath()
                    )));
                }
            } catch (Exception e) {
                logger.error("[MCP] Failed to save services", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to save services: " + e.getMessage()
                )));
            }
        });

        // Dump MCP Service definitions as JSON (download)
        this.managementApp.post("/mcp/dumpMCPService", ctx -> {
            try {
                String json = dumpServicesAsJson();
                ctx.header("Content-Type", "application/json");
                ctx.header("Content-Disposition", "attachment; filename=\"mcp_services.json\"");
                ctx.result(json);
                logger.info("[MCP] Dumped {} services for download", services.size());
            } catch (Exception e) {
                logger.error("[MCP] Failed to dump services", e);
                ctx.result(JSON.toJSONString(Map.of(
                    "retCode", -1,
                    "retMsg", "Failed to generate JSON: " + e.getMessage()
                )));
            }
        });

        // WebSocket AI Chat endpoint - only enabled when mcp_llm_server is configured
        if (mcpLlmServer != null && !mcpLlmServer.trim().isEmpty()) {
            logger.info("[SERVER][AI CHAT    ] Chat server listening on websocket /aichat. ");
            logger.info("[SERVER][AI CHAT    ] Chat llm server: {}", mcpLlmServer);

            this.managementApp.ws("/aichat", ws -> {
                ws.onConnect(ctx -> {
                    String sid = UUID.randomUUID().toString();
                    ctx.attribute("sessionId", sid);
                    activeChatConnections.put(ctx, System.currentTimeMillis());
                    startHeartbeatIfNeeded();
                    logger.trace("[SERVER][AI CHAT    ] AI Chat WebSocket connected: {}", sid);
                    ctx.send(
                            """
                                {
                                    "type": "connected",
                                    "message": "AI Chat WebSocket connected. Send your messages in JSON format."
                                }
                            """
                    );
                });

                ws.onMessage(ctx -> {
                    try {
                        // 处理ping/pong心跳消息
                        if (ctx.message().equals("ping")) {
                            ctx.send("pong");
                            return;
                        }
                        
                        String sessionId = ctx.attribute("sessionId");
                        if (sessionId == null) {
                            sessionId = UUID.randomUUID().toString();
                            ctx.attribute("sessionId", sessionId);
                        }
                        
                        String message = ctx.message();
                        logger.trace(
                                "[SERVER][AI CHAT    ] AI Chat received message from session {}: {}",
                                sessionId, message);
                        
                        // Parse the incoming message
                        JSONObject messageNode = JSON.parseObject(message);
                        String messageType = messageNode.containsKey("type") ? messageNode.getString("type") : "chat";
                        String content = messageNode.containsKey("content") ? messageNode.getString("content") : "";

                        // 对话的消息类型可以是 chat, clear_history, get_history
                        if ("chat".equals(messageType) && !content.trim().isEmpty()) {
                            // 处理会话消息
                            processChatMessage(ctx, sessionId, content);
                        }
                        else if ("clear_history".equals(messageType)) {
                            // 清空历史会话
                            clearConversationHistory(sessionId);
                            ctx.send(
                                    """
                                        {
                                            "type": "info",
                                            "message": "Conversation history cleaned."
                                        }
                                    """
                            );
                        }
                        else if ("get_history".equals(messageType)) {
                            // 返回之前的历史会话信息
                            List<Map<String, String>> history = getConversationHistory(sessionId);
                            ctx.send(
                                    JSON.toJSONString(
                                            Map.of(

                                "type", "history",
                                "history", history,
                                "sessionId", sessionId
                            )));
                        } else {
                            ctx.send(
                                    """
                                        {
                                            "type": "error",
                                            "message": "Invalid message format. Supported types: 'chat', 'clear_history', 'get_history'"
                                        }
                                    """
                            );
                        }
                    } catch (Exception e) {
                        logger.error("[SERVER][AI CHAT    ] AI Chat message processing error", e);
                        try {
                            ctx.send(
                                    """
                                        {
                                            "type": "error",
                                            "message": "Failed to process message: ${message}"
                                        }
                                    """.replace("${message}", e.getMessage())
                            );
                        } catch (Exception ignored) {}
                    }
                });

                ws.onClose(ctx -> {
                    activeChatConnections.remove(ctx);
                    logger.trace("[SERVER][AI CHAT    ] AI Chat WebSocket disconnected: {}",
                            Objects.requireNonNull(ctx.attribute("sessionId")).toString());
                });

                ws.onError(ctx -> {
                    activeChatConnections.remove(ctx);
                    Throwable error = ctx.error();
                    // 检查是否是WebSocket空闲超时错误
                    if (error instanceof org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException) {
                        // 如果是空闲超时错误，记录为调试信息而不是错误
                        logger.trace("[SERVER][AI CHAT    ] AI Chat WebSocket idle timeout (connection closed due to inactivity): {}", error.getMessage());
                    } else if (isConnectionInterruption(error)) {
                        // 连接中断是正常现象，不记录为错误
                        logger.trace("[SERVER][AI CHAT    ] AI Chat WebSocket connection interrupted: {}", error.getMessage());
                    } else {
                        // 其他错误仍然记录为错误
                        logger.error("[MCP] AI Chat WebSocket error:", error);
                    }
                });
            });
        }
    }

    /**
     * Generate a JSON array of all registered tool definitions.
     */
    private JSONArray generateToolDefinitionsJsonArray() {
        JSONArray toolArray = new JSONArray();
        for (Tool tool : tools.values()) {
            JSONObject toolObj = new JSONObject();
            toolObj.put("name", tool.name);
            toolObj.put("description", tool.description);
            toolObj.put("inputSchema", tool.inputSchema);
            if (tool.category != null) {
                toolObj.put("category", tool.category);
            }
            toolArray.add(toolObj);
        }
        return toolArray;
    }

    /**
     * Group tool definitions by category.
     * @return Map where key is category (or "uncategorized" if null), value is JSON array of tools in that category.
     */
    private Map<String, JSONArray> generateToolDefinitionsByCategory() {
        Map<String, JSONArray> grouped = new HashMap<>();
        for (Tool tool : tools.values()) {
            String category = tool.category != null ? tool.category : "uncategorized";
            JSONObject toolObj = new JSONObject();
            toolObj.put("name", tool.name);
            toolObj.put("description", tool.description);
            toolObj.put("inputSchema", tool.inputSchema);
            if (tool.category != null) {
                toolObj.put("category", tool.category);
            }
            JSONArray categoryArray = grouped.computeIfAbsent(category, k -> new JSONArray());
            categoryArray.add(toolObj);
        }
        return grouped;
    }

    /**
     * Generate a JSON array of all registered resource definitions.
     */
    private JSONArray generateResourceDefinitionsJsonArray() {
        JSONArray resourceArray = new JSONArray();
        for (Resource res : resources.values()) {
            JSONObject resObj = new JSONObject();
            resObj.put("uri", res.uri);
            resObj.put("name", res.name);
            resObj.put("description", res.description);
            resObj.put("mimeType", res.mimeType);
            resObj.put("contents", res.contents);
            if (res.category != null) {
                resObj.put("category", res.category);
            }
            resourceArray.add(resObj);
        }
        return resourceArray;
    }

    /**
     * Group resource definitions by category.
     * @return Map where key is category (or "uncategorized" if null), value is JSON array of resources in that category.
     */
    private Map<String, JSONArray> generateResourceDefinitionsByCategory() {
        Map<String, JSONArray> grouped = new HashMap<>();
        for (Resource res : resources.values()) {
            String category = res.category != null ? res.category : "uncategorized";
            JSONObject resObj = new JSONObject();
            resObj.put("uri", res.uri);
            resObj.put("name", res.name);
            resObj.put("description", res.description);
            resObj.put("mimeType", res.mimeType);
            resObj.put("contents", res.contents);
            if (res.category != null) {
                resObj.put("category", res.category);
            }
            JSONArray categoryArray = grouped.computeIfAbsent(category, k -> new JSONArray());
            categoryArray.add(resObj);
        }
        return grouped;
    }
    /**
     * Generate a JSON array of all registered service definitions.
     */
    private JSONArray generateServiceDefinitionsJsonArray() {
        JSONArray serviceArray = new JSONArray();
        for (Service service : services.values()) {
            JSONObject serviceObj = new JSONObject();
            serviceObj.put("name", service.name);
            serviceObj.put("version", service.version);
            serviceObj.put("method", service.method);
            serviceObj.put("description", service.description);
            if (service.category != null) {
                serviceObj.put("category", service.category);
            }
            serviceObj.put("capabilities", service.capabilities);
            serviceObj.put("use_cases", service.useCases);
            
            // Convert parameters
            JSONArray paramArray = new JSONArray();
            for (ServiceParameter param : service.parameters) {
                JSONObject paramObj = new JSONObject();
                paramObj.put("name", param.name);
                paramObj.put("type", param.type);
                paramObj.put("required", param.required);
                paramObj.put("description", param.description);
                paramArray.add(paramObj);
            }
            serviceObj.put("parameters", paramArray);
            
            // Convert examples
            JSONArray exampleArray = new JSONArray();
            for (ServiceExample ex : service.examples) {
                JSONObject exampleObj = new JSONObject();
                exampleObj.put("user_query", ex.userQuery);
                exampleObj.put("parameters", ex.parameters);
                exampleArray.add(exampleObj);
            }
            serviceObj.put("examples", exampleArray);
            
            serviceArray.add(serviceObj);
        }
        return serviceArray;
    }

    /**
     * Group service definitions by category.
     * @return Map where key is category (or "uncategorized" if null), value is JSON array of services in that category.
     */
    private Map<String, JSONArray> generateServiceDefinitionsByCategory() {
        Map<String, JSONArray> grouped = new HashMap<>();
        for (Service service : services.values()) {
            String category = service.category != null ? service.category : "uncategorized";
            JSONObject serviceObj = new JSONObject();
            serviceObj.put("name", service.name);
            serviceObj.put("description", service.description);
            if (service.category != null) {
                serviceObj.put("category", service.category);
            }
            serviceObj.put("capabilities", service.capabilities);
            serviceObj.put("use_cases", service.useCases);
            
            // Convert parameters
            JSONArray paramArray = new JSONArray();
            for (ServiceParameter param : service.parameters) {
                JSONObject paramObj = new JSONObject();
                paramObj.put("name", param.name);
                paramObj.put("type", param.type);
                paramObj.put("required", param.required);
                paramObj.put("description", param.description);
                paramArray.add(paramObj);
            }
            serviceObj.put("parameters", paramArray);
            
            // Convert examples
            JSONArray exampleArray = new JSONArray();
            for (ServiceExample ex : service.examples) {
                JSONObject exampleObj = new JSONObject();
                exampleObj.put("user_query", ex.userQuery);
                exampleObj.put("parameters", ex.parameters);
                exampleArray.add(exampleObj);
            }
            serviceObj.put("examples", exampleArray);
            
            JSONArray categoryArray = grouped.computeIfAbsent(category, k -> new JSONArray());
            categoryArray.add(serviceObj);
        }
        return grouped;
    }

    /**
     * Dump all services as a JSON string.
     */
    private String dumpServicesAsJson() {
        return JSON.toJSONString(generateServiceDefinitionsJsonArray(), com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
    }

    /**
     * Read existing config file (if exists) and return a JSONObject representing the whole config.
     * The config is expected to be a JSON object with optional "tools" and "resources" fields.
     * If the file does not exist or is invalid, returns an empty object.
     */
    private JSONObject readConfigFile(File configFile) {
        if (!configFile.exists() || configFile.length() == 0) {
            return new JSONObject();
        }
        try {
            // Read file content as string
            String content = Files.readString(configFile.toPath());
            Object parsed = JSON.parse(content);
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            } else if (parsed instanceof JSONArray) {
                // Legacy format: assume it's an array of tools (or resources?)
                // We'll treat it as tools array and wrap into an object.
                JSONObject obj = new JSONObject();
                obj.put("tools", parsed);
                return obj;
            } else {
                logger.warn("[MCP] Config file has unexpected format, treating as empty");
                return new JSONObject();
            }
        } catch (Exception e) {
            logger.warn("[MCP] Failed to parse config file, treating as empty", e);
            return new JSONObject();
        }
    }

    /**
     * Update the tools section in a category .service file.
     * Reads existing file (if any), merges tools, keeps resources unchanged.
     * Writes back as a JSON object with "tools" and "resources" fields.
     */
    private void updateToolsInCategoryFile(File configDir, String category, JSONArray newTools) throws IOException {
        String safeCategory = category.replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeCategory + ".service";
        File targetFile = new File(configDir, fileName);
        JSONObject root = readConfigFile(targetFile);
        root.put("tools", newTools);
        // Ensure resources field exists (if missing, create empty array)
        if (!root.containsKey("resources")) {
            root.put("resources", new JSONArray());
        }
        String json = JSON.toJSONString(root, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
        Files.write(targetFile.toPath(), json.getBytes());
        logger.debug("[MCP] Updated tools in category '{}' to {}", category, targetFile.getAbsolutePath());
    }

    /**
     * Update the resources section in a category .service file.
     */
    private void updateResourcesInCategoryFile(File configDir, String category, JSONArray newResources) throws IOException {
        String safeCategory = category.replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeCategory + ".service";
        File targetFile = new File(configDir, fileName);
        JSONObject root = readConfigFile(targetFile);
        root.put("resources", newResources);
        if (!root.containsKey("tools")) {
            root.put("tools", new JSONArray());
        }
        String json = JSON.toJSONString(root, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
        Files.write(targetFile.toPath(), json.getBytes());
        logger.debug("[MCP] Updated resources in category '{}' to {}", category, targetFile.getAbsolutePath());
    }

    /**
     * Update the services section in a category .service file.
     */
    private void updateServicesInCategoryFile(File configDir, String category, JSONArray newServices) throws IOException {
        String safeCategory = category.replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeCategory + ".service";
        File targetFile = new File(configDir, fileName);
        JSONObject root = readConfigFile(targetFile);
        root.put("services", newServices);
        // Ensure tools and resources fields exist
        if (!root.containsKey("tools")) {
            root.put("tools", new JSONArray());
        }
        if (!root.containsKey("resources")) {
            root.put("resources", new JSONArray());
        }
        String json = JSON.toJSONString(root, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
        Files.write(targetFile.toPath(), json.getBytes());
        logger.debug("[MCP] Updated services in category '{}' to {}", category, targetFile.getAbsolutePath());
    }

    public void addTool(String name, String description, JSONObject inputSchema, Function<Map<String,Object>, Object> handler, String category) {
        tools.put(name, new Tool(name, description, inputSchema, handler, category));
    }

    public void addResource(String uri, String name, String description, String mimeType, Object contents, String category) {
        resources.put(uri, new Resource(uri, name, description, mimeType, contents, category));
    }

    public void addService(String name, String apiName, String version, String method, String description, String category, List<String> capabilities,
                           List<String> useCases, List<ServiceParameter> parameters, List<ServiceExample> examples) {
        services.put(name, new Service(name, apiName, version, method, description, category, capabilities, useCases, parameters, examples));
    }

    /**
     * 处理用户会话消息
     */
    private void processChatMessage(WsContext ctx, String sessionId, String userMessage) {
        CompletableFuture.runAsync(() -> {
            try {
                // 解析 mcp_llm_server 配置: <service>:<ip>:<port>:<model>
                String[] parts = mcpLlmServer.split(":", 4);
                if (parts.length < 4) {
                    ctx.send(
                            """
                                {
                                    "type": "error",
                                    "message": "Invalid mcp_llm_server configuration format. Expected <service>:<ip>:<port>:<model>"
                                }
                            """);
                    return;
                }
                String service = parts[0];
                String host = parts[1];
                String port = parts[2];
                String model = parts[3];

                // 获取用户会话历史
                List<Map<String, String>> conversationHistory = getConversationHistory(sessionId);

                // 大模型调用
                String llmResponse;
                if (conversationHistory.isEmpty()) {
                    // 首次调用
                    llmResponse = callLlmApi(service, host, port, model, userMessage);
                } else {
                    // 考虑上下文以及历史信息
                    llmResponse = callLlmApiWithHistory(service, host, port, model, userMessage, conversationHistory);
                }

                // 解析LLM响应，判断决策类型
                String finalResponse;
                boolean shouldRecordToHistory = true;

                try {
                    JSONObject llmResponseJson = JSON.parseObject(llmResponse);
                    String decision = llmResponseJson.containsKey("decision") ? llmResponseJson.getString("decision") : "";
                    logger.trace(JSON.toJSONString(llmResponseJson, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat));
                    if ("call_service".equals(decision)) {
                        // 调用服务API
                        String serviceName = llmResponseJson.containsKey("service_name") ? llmResponseJson.getString("service_name") : "";
                        String apiName = llmResponseJson.containsKey("api_name") ? llmResponseJson.getString("api_name") : "";
                        String serviceVersion = llmResponseJson.containsKey("service_version") ? llmResponseJson.getString("service_version") : "";
                        String serviceMethod = llmResponseJson.containsKey("service_method") ? llmResponseJson.getString("service_method") : "";
                        JSONObject parametersNode = llmResponseJson.getJSONObject("parameters");
                        Map<String, Object> parameters = JSON.parseObject(JSON.toJSONString(parametersNode), new TypeReference<>() {
                        });

                        // 调用服务
                        logger.trace("[SERVER][AI CHAT    ] Invoking service: {} with parameters: {}", apiName, parameters);

                        try {
                            // 调用指定的API Service: http://<bindHost>:<portX>/api/<apiVersion>/<serviceName>
                            String serviceResult = callServiceApi(apiName, serviceVersion, serviceMethod, parameters);

                            // 根据serviceResult，发送给LLM，要求它进行自然语言的整理
                            try {
                                finalResponse = summarizeServiceResult(userMessage, serviceResult);
                            } catch (Exception e) {
                                logger.warn("[SERVER][AI CHAT    ] Failed to summarize service result, using raw result: {}", e.getMessage());
                                finalResponse = serviceResult;
                            }
                        } catch (Exception e) {
                            logger.error("[SERVER][AI CHAT    ] Failed to call service API: {}",
                                    e.getMessage(), e);
                            finalResponse = "Failed to call service '" + serviceName + "': " + e.getMessage();
                        }
                    } else if ("need_clarification".equals(decision)) {
                        // 需要澄清，直接返回澄清问题
                        finalResponse = llmResponseJson.containsKey("direct_answer") ? llmResponseJson.getString("direct_answer") : llmResponse;
                    } else if ("direct_answer".equals(decision)) {
                        // 直接回答，不记录后续的setSummarizedHistory
                        finalResponse = llmResponseJson.containsKey("direct_answer") ? llmResponseJson.getString("direct_answer") : llmResponse;

                        // 不记录后续的setSummarizedHistory
                        shouldRecordToHistory = false;
                    } else {
                        // 未知决策类型，直接返回原始响应
                        finalResponse = llmResponse;
                    }
                } catch (Exception e) {
                    // 如果解析失败，直接返回原始响应
                    logger.warn("[MCP] Failed to parse LLM response as JSON, using raw response: {}", e.getMessage());
                    finalResponse = llmResponse;
                }

                // 将本轮会话信息加入到历史信息中
                addMessageToHistory(sessionId, "user", userMessage);
                addMessageToHistory(sessionId, "assistant", finalResponse);

                // 整理每一轮的会话，作为下次会话的上下文
                if (shouldRecordToHistory) {
                    String summarizedHistory = summarizeConversationHistory(sessionId, userMessage, finalResponse);
                    ConversationSession session = conversationSessions.get(sessionId);
                    if (session != null) {
                        session.setSummarizedHistory(summarizedHistory);
                    }
                }

                // 为用户做出回答
                String responseJson = JSON.toJSONString(Map.of(
                    "type", "response",
                    "content", finalResponse,
                    "timestamp", System.currentTimeMillis(),
                    "sessionId", sessionId
                ));
                ctx.send(responseJson);
            } catch (Exception e) {
                // Check if this is a ClosedChannelException (connection closed during send)
                Throwable cause = e;
                boolean isClosedChannel = false;
                while (cause != null) {
                    if (cause instanceof java.nio.channels.ClosedChannelException) {
                        isClosedChannel = true;
                        break;
                    }
                    cause = cause.getCause();
                }

                if (isClosedChannel) {
                    // Just log at debug level, don't send error to client (connection already closed)
                    logger.trace("[SERVER][AI CHAT    ] Connection closed while sending response to AI chat for session {}", sessionId);
                } else {
                    logger.error("[SERVER][AI CHAT    ] Failed to process chat message for session {}", sessionId, e);
                    try {
                        ctx.send("{\"type\": \"error\", \"message\": \"Failed to get response from AI: " + e.getMessage() + "\"}");
                    } catch (Exception ignored) {}
                }
            }
        }, executor);
    }

    /**
     * Generate the system prompt with service information.
     */
    private String generateSystemPrompt(String servicesInfo, String userQuery) {
        String prompt =
                """
                        You are a service routing decision engine.

                        You are given a list of available services ("serviceInfo"). Each service describes:
                            - its purpose and usage conditions (description),
                            - the specific actions it can perform (capabilities),
                            - the situations where it should be used (use_cases),
                            - service name, service version and service method
                            - required and optional parameters,
                            - and example user queries that justify calling the service.

                        Your task is to analyze the user query and the available services, and decide whether:
                            - to call a service,
                            - to ask the user for clarification,
                            - or to answer directly.

                        How to interpret serviceInfo:
                            - A service should be considered ONLY if the user's intent clearly matches the service description and use_cases.
                            - Capabilities indicate what the service can do, not what it should always be used for.
                            - Examples illustrate valid routing scenarios; if the user's query is semantically similar, the service may be applicable.
                            - Do NOT call a service if the user's request can be answered using general knowledge, reasoning, or conversation.
                            - Do NOT infer or fabricate missing parameters. If required parameters are missing or ambiguous, request clarification instead.

                        Available services:
                            ${servicesInfo}
                
                        User query:
                            "${userQuery}"

                        Language rule:
                            - The output language MUST follow the user's input language.
                            - If the user asks in Chinese, respond in Chinese.
                            - If the user asks in English, respond in English.
                            - Do NOT mix languages.

                        Decision rules:
                            1. Determine the user's intent.
                            2. Decide whether any available service can accurately fulfill this intent.
                            3. Call a service ONLY if:
                               - the service clearly matches the intent, and
                               - all required parameters can be confidently inferred from the user query.
                            4. If required parameters are missing or ambiguous, request clarification.
                            5. For greetings, small talk, or general knowledge questions, answer directly without calling any service.
                
                        Output format:
                            You MUST return a single valid JSON object and NOTHING ELSE.

                        JSON schema:
                        {
                          "decision": "call_service | need_clarification | direct_answer",
                          "direct_answer": "",
                          "service_name": "",
                          "api_name" : "",
                          "service_version": "",
                          "service_method": "GET",
                          "parameters": {"parameter1":"value1","parameter2":"value2",...}
                        }
                
                        Field rules:
                        - If decision = "direct_answer":
                          - direct_answer MUST be non-empty
                          - service_name MUST be empty
                          - api_name MUST be empty
                          - service_version MUST be empty
                          - service_method MUST be empty
                          - parameters MUST be empty
                        - If decision = "call_service":
                          - service_name MUST be non-empty
                          - api_name MUST be non-empty
                          - service_version MUST be non-empty
                          - service_method MUST be non-empty
                          - parameters MUST contain all required parameters
                          - direct_answer MUST be empty
                        - If decision = "need_clarification":
                          - direct_answer MUST contain a clarification question to ask the user
                          - service_name MUST be empty
                          - api_name MUST be non-empty
                          - service_version MUST be empty
                          - service_version MUST be empty
                          - parameters MUST be empty

                        Important:
                        - Do NOT explain your reasoning.
                        - Do NOT include markdown.
                        - Do NOT output anything other than the JSON object.

                """;
        prompt = prompt.replace("${servicesInfo}", servicesInfo);
        prompt = prompt.replace("${userQuery}", userQuery);
        return prompt;
    }
    
    /**
     * Generate the system prompt with service information and conversation history context.
     * This enhanced prompt includes conversation history to provide better context for multi-turn conversations.
     */
    private String generateSystemPromptWithHistory(List<Map<String, String>> conversationHistory) {
        // Build structured history context in the required format
        StringBuilder historyContext = new StringBuilder();
        
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            // Extract structured information from conversation history
            List<String> confirmedFacts = new ArrayList<>();
            List<String> openContext = new ArrayList<>();
            String currentStatus = "No action has been executed yet.";
            
            // Analyze conversation history to extract structured information
            for (Map<String, String> msg : conversationHistory) {
                String role = msg.get("role");
                String content = msg.get("content");
                
                if ("user".equals(role)) {
                    // Extract potential facts from user messages
                    extractInformationFromUserMessage(content, confirmedFacts, openContext);
                } else if ("assistant".equals(role)) {
                    // Extract status from assistant responses
                    if (content.contains("executed") || content.contains("query") || content.contains("result")) {
                        currentStatus = "Previous action has been executed. Check the response for details.";
                    }
                }
            }
            
            // Build the structured history context
            historyContext.append("Conversation history context (structured format):\n\n");
            
            // Confirmed facts section
            if (!confirmedFacts.isEmpty()) {
                historyContext.append("Confirmed facts:\n");
                for (String fact : confirmedFacts) {
                    historyContext.append("- ").append(fact).append("\n");
                }
                historyContext.append("\n");
            } else {
                historyContext.append("Confirmed facts: None confirmed yet.\n\n");
            }
            
            // Open context section
            if (!openContext.isEmpty()) {
                historyContext.append("Open context:\n");
                for (String context : openContext) {
                    historyContext.append("- ").append(context).append("\n");
                }
                historyContext.append("\n");
            } else {
                historyContext.append("Open context: No open context identified.\n\n");
            }
            
            // Status section
            historyContext.append("Current status: ").append(currentStatus).append("\n\n");
            
        } else {
            historyContext.append("Conversation history context (structured format):\n\n");
            historyContext.append("Confirmed facts: No previous conversation.\n\n");
            historyContext.append("Open context: No open context.\n\n");
            historyContext.append("Current status: New conversation started.\n\n");
        }

        String prompt = """
            You are a service routing decision engine.
            
            Your responsibility is to decide whether to:
            - call a service,
            - ask the user for clarification,
            - or answer directly.
            
            You must follow all rules strictly.
            
            Global rules:
            - You MUST output a single valid JSON object and nothing else.
            - You MUST NOT explain your reasoning.
            - You MUST NOT include markdown or extra text.
            - The output language MUST follow the user's latest input language.
            - Do NOT mix languages.
            
            Decision rules:
            1. Identify the user's intent.
            2. Determine whether any available service can accurately fulfill the intent.
            3. Call a service ONLY if:
               - the service clearly matches the intent, and
               - all required parameters can be confidently inferred.
            4. If required parameters are missing or ambiguous, request clarification.
            5. For greetings, small talk, or general knowledge, answer directly.
            
            ────────────────────
            CONVERSATION HISTORY CONTEXT (READ-ONLY):
            The following information is provided in a structured format for context.
            Use it to understand what has been discussed and what needs to be done.
            
            ${historyContext}
            ────────────────────
            
            Current task:
            Based on the user's latest query and the available services,
            make a decision and produce the JSON output.
            
            Important: When analyzing the conversation history context:
            - "Confirmed facts" are things that have been explicitly stated or agreed upon
            - "Open context" represents user preferences or intentions that haven't been acted upon
            - "Current status" indicates whether previous actions have been executed
            
            Use this structured context to make better decisions about whether to:
            - Use confirmed facts as parameters for service calls
            - Address open context in your response
            - Check current status to avoid repeating completed actions
            """;
        prompt = prompt.replace("${historyContext}", historyContext);
        return prompt;
    }
    
    /**
     * Extract structured information from user messages.
     * This is a simplified implementation that should be enhanced based on actual use cases.
     */
    private void extractInformationFromUserMessage(String content, List<String> confirmedFacts, List<String> openContext) {
        // Simple pattern matching for demonstration
        // In a real implementation, this would use NLP or more sophisticated parsing
        
        // Convert to lowercase for case-insensitive matching
        String lowerContent = content.toLowerCase();
        
        // Look for table names - improved pattern matching
        // Pattern: "from the X table" or "from X table" or "table X"
        if (lowerContent.matches(".*from\\s+(?:the\\s+)?(\\w+)\\s+table.*")) {
            String tableMatch = content.replaceAll("(?i).*from\\s+(?:the\\s+)?(\\w+)\\s+table.*", "$1");
            if (tableMatch.length() > 1 && !tableMatch.equalsIgnoreCase("the")) {
                confirmedFacts.add("Table name: " + tableMatch);
            }
        } else if (lowerContent.matches(".*table\\s+(\\w+).*")) {
            String tableMatch = content.replaceAll("(?i).*table\\s+(\\w+).*", "$1");
            if (tableMatch.length() > 1) {
                confirmedFacts.add("Table name: " + tableMatch);
            }
        }
        
        // Look for row limits - improved pattern matching
        // Pattern: "last X" or "limit X" or "show X" or "get X"
        if (lowerContent.matches(".*last\\s+(\\d+).*")) {
            String limitMatch = content.replaceAll("(?i).*last\\s+(\\d+).*", "$1");
            confirmedFacts.add("Row limit: " + limitMatch);
        } else if (lowerContent.matches(".*limit\\s+(\\d+).*")) {
            String limitMatch = content.replaceAll("(?i).*limit\\s+(\\d+).*", "$1");
            confirmedFacts.add("Row limit: " + limitMatch);
        } else if (lowerContent.matches(".*show\\s+(\\d+).*")) {
            String limitMatch = content.replaceAll("(?i).*show\\s+(\\d+).*", "$1");
            confirmedFacts.add("Row limit: " + limitMatch);
        } else if (lowerContent.matches(".*get\\s+(\\d+).*")) {
            String limitMatch = content.replaceAll("(?i).*get\\s+(\\d+).*", "$1");
            confirmedFacts.add("Row limit: " + limitMatch);
        }
        
        // Look for ordering preferences
        if (lowerContent.matches(".*order.*by.*(desc|descending).*")) {
            openContext.add("The user wants the result ordered by time descending");
        } else if (lowerContent.matches(".*order.*by.*(asc|ascending).*")) {
            openContext.add("The user wants the result ordered by time ascending");
        } else if (lowerContent.contains("descending") || lowerContent.contains("latest") ||
                   lowerContent.contains("recent") || lowerContent.contains("newest")) {
            openContext.add("The user wants the result ordered by time descending");
        } else if (lowerContent.contains("ascending") || lowerContent.contains("oldest")) {
            openContext.add("The user wants the result ordered by time ascending");
        }
        
        // Look for general preferences
        if (lowerContent.contains("want") && lowerContent.contains("result")) {
            openContext.add("The user wants to see results");
        }
        
        // If no specific patterns matched, add a generic context
        if (openContext.isEmpty() && content.length() > 10) {
            // Extract key phrases for context
            String[] words = content.split("\\s+");
            if (words.length > 3) {
                String intent = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(5, words.length)));
                openContext.add("User intent: " + intent + (words.length > 5 ? "..." : ""));
            } else {
                openContext.add("User intent: " + content);
            }
        }
        
        // Remove duplicates
        Set<String> factsSet = new LinkedHashSet<>(confirmedFacts);
        confirmedFacts.clear();
        confirmedFacts.addAll(factsSet);
        
        Set<String> contextSet = new LinkedHashSet<>(openContext);
        openContext.clear();
        openContext.addAll(contextSet);
    }
    
    /**
     * 根据会话历史来调用大模型服务
     */
    private String callLlmApiWithHistory(String service, String host, String port, String model,
                                         String userMessage, List<Map<String, String>> conversationHistory)
                                         throws IOException, InterruptedException {
        // LLM实现, 当前只支持ollama
        if ("ollama".equalsIgnoreCase(service)) {
            // Construct URL
            String url = "http://" + host + ":" + port + "/v1/chat/completions";
            
            // Generate services info and system prompt
            String systemPrompt = generateSystemPromptWithHistory(conversationHistory);
            
            // Build messages list with system prompt, conversation history, and current user message
            List<Map<String, String>> messages = new ArrayList<>();
            
            // Add system prompt
            messages.add(Map.of("role", "system", "content", systemPrompt));
            
            // Add conversation history (excluding the current user message which will be added separately)
            for (Map<String, String> historyMsg : conversationHistory) {
                // Skip the current user message if it's already in history (it will be added at the end)
                if (!(historyMsg.get("role").equals("user") && historyMsg.get("content").equals(userMessage))) {
                    messages.add(Map.of("role", historyMsg.get("role"), "content", historyMsg.get("content")));
                }
            }
            
            // Add current user message
            messages.add(Map.of("role", "user", "content", userMessage));
            
            // Prepare request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 2000); // Increased for longer responses
            requestBody.put("temperature", 0.3); // Lower temperature for more deterministic routing decisions
            
            String requestBodyJson = JSON.toJSONString(requestBody);
            
            // Create HTTP client and request
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JSONObject responseJson = JSON.parseObject(response.body());
                JSONArray choices = responseJson.getJSONArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JSONObject message = choices.getJSONObject(0).getJSONObject("message");
                    if (message != null && message.containsKey("content")) {
                        return message.getString("content");
                    }
                }
                return "No response content found in LLM response";
            } else {
                throw new IOException("LLM API request failed with status: " + response.statusCode() + ", body: " + response.body());
            }
        } else {
            // For other services, return a placeholder response
            return "LLM service '" + service + "' is configured but not yet implemented. User message: " + userMessage;
        }
    }
    
    /**
     * 调用大模型服务（第一次调用，用户历史不存在时）
     */
    private String callLlmApi(String service, String host, String port, String model,
                              String userMessage) throws IOException, InterruptedException {
        // LLM实现, 当前只支持ollama
        if ("ollama".equalsIgnoreCase(service)) {
            // Construct URL
            String url = "http://" + host + ":" + port + "/v1/chat/completions";
            
            // Generate services info
            String servicesInfo = JSON.toJSONString(generateServiceDefinitionsJsonArray(), com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);

            // Generate system prompt using generateSystemPrompt method
            String systemPrompt = generateSystemPrompt(servicesInfo, userMessage);

            // Build messages list with system prompt and current user message
            List<Map<String, String>> messages = new ArrayList<>();
            
            // Add system prompt
            messages.add(Map.of("role", "system", "content", systemPrompt));
            
            // Add current user message
            messages.add(Map.of("role", "user", "content", userMessage));
            
            // Prepare request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 2000); // Increased for longer responses
            requestBody.put("temperature", 0.3); // Lower temperature for more deterministic routing decisions
            
            String requestBodyJson = JSON.toJSONString(requestBody);
            
            // Create HTTP client and request
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JSONObject responseJson = JSON.parseObject(response.body());
                JSONArray choices = responseJson.getJSONArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JSONObject message = choices.getJSONObject(0).getJSONObject("message");
                    if (message != null && message.containsKey("content")) {
                        return message.getString("content");
                    }
                }
                return "No response content found in LLM response";
            } else {
                throw new IOException("LLM API request failed with status: " + response.statusCode() + ", body: " + response.body());
            }
        } else {
            // For other services, return a placeholder response
            return "LLM service '" + service + "' is configured but not yet implemented. User message: " + userMessage;
        }
    }
    
    /**
     * 调用指定的API服务
     * @param apiName 服务名称
     * @param parameters 服务参数
     * @return 服务调用结果
     */
    private String callServiceApi(String apiName, String serviceVersion, String serviceMethod,
                                  Map<String, Object> parameters) throws IOException, InterruptedException {
        // 构建基础API URL: http://<bindHost>:<portX>/api/<serviceVersion>/<serviceName>
        String baseUrl = "http://" + bindHost + ":" + portX + "/api/" + serviceVersion + "/" + apiName;
        
        logger.trace("[SERVER][AI CHAT    ] Calling service API: {} with method: {} and parameters: {}",
                     baseUrl, serviceMethod, parameters);
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request;
        
        // 根据serviceMethod决定请求方式
        if ("GET".equalsIgnoreCase(serviceMethod)) {
            // GET请求：参数作为路径的一部分
            String urlWithParams = buildUrlWithParameters(baseUrl, parameters);
            request = HttpRequest.newBuilder()
                .uri(URI.create(urlWithParams))
                .header("Content-Type", "application/json")
                .GET()
                .build();
        } else {
            // 默认使用POST请求：参数作为请求体
            String requestBodyJson = JSON.toJSONString(parameters);

            request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
        }
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            // 尝试解析响应为JSON
            try {
                JSONObject responseJson = JSON.parseObject(response.body());
                // 如果响应包含"result"字段，返回该字段的值
                if (responseJson.containsKey("result")) {
                    return responseJson.getString("result");
                }
                // 否则返回整个响应体
                return response.body();
            } catch (Exception e) {
                // 如果解析失败，返回原始响应
                logger.warn("[SERVER][AI CHAT    ] Failed to parse service API response as JSON: {}", e.getMessage());
                return response.body();
            }
        } else {
            logger.error("[SERVER][AI CHAT    ] Service API [{}] request failed with status: {}, body: {}",
                    baseUrl, response.statusCode(), response.body());
            throw new IOException("Service API request failed with status: " + response.statusCode() + ", body: " + response.body());
        }
    }
    
    /**
     * 为GET请求构建包含参数的URL
     * @param baseUrl 基础URL
     * @param parameters 参数映射
     * @return 包含参数的完整URL
     */
    private String buildUrlWithParameters(String baseUrl, Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return baseUrl;
        }
        
        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        
        // 检查URL是否已经包含查询参数
        boolean hasQuery = baseUrl.contains("?");
        
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value != null) {
                if (!hasQuery) {
                    urlBuilder.append("?");
                    hasQuery = true;
                } else {
                    urlBuilder.append("&");
                }
                
                // URL编码参数
                String encodedKey = java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8);
                String encodedValue = java.net.URLEncoder.encode(value.toString(), java.nio.charset.StandardCharsets.UTF_8);
                urlBuilder.append(encodedKey).append("=").append(encodedValue);
            }
        }
        
        return urlBuilder.toString();
    }
    
    public void shutdown() {
        executor.shutdown();
        // Clean up expired sessions
        cleanupExpiredSessions();
    }
    
    /**
     * Clean up expired conversation sessions.
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ConversationSession>> it = conversationSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConversationSession> entry = it.next();
            if (now - entry.getValue().getLastActivityTime() > SESSION_TIMEOUT_MS) {
                it.remove();
                logger.debug("[MCP] Cleaned up expired conversation session: {}", entry.getKey());
            }
        }
    }
    
    /**
     * 创建新的用户会话
     */
    private ConversationSession getOrCreateSession(String sessionId) {
        return conversationSessions.compute(sessionId, (key, existing) -> {
            if (existing == null || System.currentTimeMillis() - existing.getLastActivityTime() > SESSION_TIMEOUT_MS) {
                logger.debug("[SERVER][AI CHAT    ] Creating new conversation session: {}", sessionId);
                return new ConversationSession();
            }
            existing.updateActivityTime();
            return existing;
        });
    }
    
    /**
     * 记录消息的回答历史
     */
    private void addMessageToHistory(String sessionId, String role, String content) {
        ConversationSession session = getOrCreateSession(sessionId);
        session.addMessage(role, content);
        logger.trace("[SERVER][AI CHAT    ] Added message to session {}: {}: {}", sessionId, role,
                     content.length() > 50 ? content.substring(0, 50) + "..." : content);
    }
    
    /**
     * 查询用户会话历史
     */
    private List<Map<String, String>> getConversationHistory(String sessionId) {
        ConversationSession session = conversationSessions.get(sessionId);
        if (session == null) {
            return new ArrayList<>();
        }
        return session.getMessages();
    }
    
    /**
     * 清空用户会话历史
     */
    private void clearConversationHistory(String sessionId) {
        conversationSessions.remove(sessionId);
        logger.trace("[SERVER][AI CHAT    ] Cleared conversation history for session: {}", sessionId);
    }
    
    /**
     * 总结会话历史
     * 返回新的历史总结信息
     */
    private String summarizeConversationHistory(String sessionId, String latestUserInput, String latestAssistantResponse) throws Exception {
        ConversationSession session = conversationSessions.get(sessionId);
        String previousHistoryContext = session.getSummarizedHistory();
        if (previousHistoryContext == null || previousHistoryContext.trim().isEmpty()) {
            previousHistoryContext = "No previous history context.";
        }

        // 构建总结prompt
        String prompt = """
                You are a conversation state summarization engine.
        
                Your task is to update the conversation history context based on:
                1. The previous history context
                2. The latest user input
                3. The latest assistant response
        
                The goal is to produce a concise, factual, and structured history context
                that can be used safely in future decision-making.
        
                ────────────────────
                Previous history context:
                ${previousHistoryContext}
                ────────────────────
        
                Latest user input:
                "${latestUserInput}"
        
                Latest assistant response:
                ${latestAssistantResponse}
        
                ────────────────────
                Rules (CRITICAL):
                - The history context represents FACTS and STATE, not dialogue.
                - ONLY include information that is explicitly stated or clearly confirmed.
                - DO NOT infer or assume execution unless it is explicitly stated as completed.
                - DO NOT describe intentions, plans, or future actions unless they are still pending.
                - DO NOT invent results, parameters, or outcomes.
                - If execution failed, was cancelled, or is unclear, state it explicitly.
                - If information is ambiguous, omit it rather than guessing.
        
                Formatting rules:
                - Use clear sections such as:
                  - Confirmed facts
                  - Completed actions
                  - Last result summary
                  - Open context
                  - Current state
                - Omit empty sections.
                - Be concise and avoid repetition.
                - Do NOT include timestamps, IDs, or conversational phrasing.
        
                Output rules:
                - Output ONLY the updated history context.
                - Do NOT include explanations, markdown, or extra text.
                """
            .replace("${previousHistoryContext}", previousHistoryContext)
            .replace("${latestUserInput}", latestUserInput)
            .replace("${latestAssistantResponse}", latestAssistantResponse);

        // 解析大模型服务器配置
        String[] parts = this.mcpLlmServer.split(":", 4);
        String service = parts[0];
        String host = parts[1];
        String port = parts[2];
        String model = parts[3];

        if ("ollama".equalsIgnoreCase(service)) {
            String url = "http://" + host + ":" + port + "/v1/chat/completions";

            // 构建请求
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", "You are a conversation state summarization engine. Follow the user's instructions precisely."));
            messages.add(Map.of("role", "user", "content", prompt));

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 1000);
            requestBody.put("temperature", 0.1); // 低温度以获得更确定性的总结

            String requestBodyJson = JSON.toJSONString(requestBody);

            // 发送HTTP请求
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject responseJson = JSON.parseObject(response.body());
                JSONArray choices = responseJson.getJSONArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JSONObject message = choices.getJSONObject(0).getJSONObject("message");
                    if (message != null && message.containsKey("content")) {
                        String content = message.getString("content");
                        content = content.trim();
                        return content;
                    }
                }
                throw new IOException("[SERVER][AI CHAT    ] Summarization API request failed. unexpected message: " + response.body());
            } else {
                throw new IOException("[SERVER][AI CHAT    ] Summarization API request failed with status: " + response.statusCode());
            }
        }
        return previousHistoryContext;
    }
    
    /**
     * 启动心跳调度（如果需要）
     */
    private void startHeartbeatIfNeeded() {
        if (heartbeatStarted.compareAndSet(false, true)) {
            heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, 10, 10, TimeUnit.SECONDS);
            logger.trace("[SERVER][AI CHAT    ] WebSocket heartbeat started (interval 10s)");
        }
    }
    
    /**
     * 发送心跳ping到所有活动连接
     */
    private void sendHeartbeat() {
        Iterator<Map.Entry<io.javalin.websocket.WsContext, Long>> it = activeChatConnections.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<io.javalin.websocket.WsContext, Long> entry = it.next();
            io.javalin.websocket.WsContext ctx = entry.getKey();
            try {
                ctx.sendPing();
            } catch (Exception e) {
                // 发送失败，连接可能已关闭，从映射中移除
                it.remove();
            }
        }
    }
    
    /**
     * 检查是否为连接中断异常
     */
    private boolean isConnectionInterruption(Throwable error) {
        if (error == null) {
            return false;
        }
        // 检查异常类型是否为常见的连接中断异常
        if (error instanceof java.nio.channels.ClosedChannelException || error instanceof java.io.EOFException) {
            return true;
        }
        // 检查 SocketException 且消息包含 closed 或 reset
        if (error instanceof java.net.SocketException) {
            String msg = error.getMessage();
            if (msg != null) {
                msg = msg.toLowerCase();
                if (msg.contains("closed") || msg.contains("reset") || msg.contains("broken pipe")) {
                    return true;
                }
            }
        }
        String msg = error.getMessage();
        if (msg == null) {
            return false;
        }
        msg = msg.toLowerCase();
        return  msg.contains("connection reset") ||
                msg.contains("closed") ||
                msg.contains("interrupted") ||
                msg.contains("broken pipe") ||
                msg.contains("connection abort") ||
                msg.contains("stream closed") ||
                msg.contains("connection idle timeout") ;
    }

    /**
     * 将服务执行结果转换为自然语言响应
     * @param userQuery 用户的原始查询
     * @param serviceResult 服务执行结果（JSON格式）
     * @return 自然语言响应
     */
    private String summarizeServiceResult(String userQuery, String serviceResult) throws IOException, InterruptedException {
        // 解析大模型服务器配置
        String[] parts = this.mcpLlmServer.split(":", 4);
        String service = parts[0];
        String host = parts[1];
        String port = parts[2];
        String model = parts[3];

        // 如果不支持的服务，返回原始结果
        if ("ollama".equalsIgnoreCase(service)) {
            String url = "http://" + host + ":" + port + "/v1/chat/completions";
            
            // 构建提示词
            String prompt = """
                    You are a response generator.

                    Your task is to convert structured service execution results into a natural language response for the end user.

                    User query:
                    "${userQuery}"

                    Service execution result (JSON):
                    ${serviceResult}

                    Rules:
                    - Use ONLY the information explicitly present in the provided result data.
                    - Do NOT add, infer, or assume any information that is not in the data.
                    - If the dataset is empty, clearly state that no results were found.
                    - If multiple rows exist, summarize them clearly.
                    - The response must be concise, clear, and user-friendly.
                    - Respond in the same language as the user's original query.
                    """
                .replace("${userQuery}", userQuery)
                .replace("${serviceResult}", serviceResult);
            
            // 构建请求消息
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", "You are a response generator. Convert structured service execution results into natural language responses."));
            messages.add(Map.of("role", "user", "content", prompt));
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 2000);
            requestBody.put("temperature", 0.3);
            
            String requestBodyJson = JSON.toJSONString(requestBody);
            
            // 发送HTTP请求
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 如果API调用失败，返回原始结果
            if (response.statusCode() == 200) {
                JSONObject responseJson = JSON.parseObject(response.body());
                JSONArray choices = responseJson.getJSONArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JSONObject message = choices.getJSONObject(0).getJSONObject("message");
                    if (message != null && message.containsKey("content")) {
                        String content = message.getString("content");
                        content = content.trim();
                        return content;
                    }
                }
                logger.warn("[SERVER][AI CHAT    ] Failed to extract content from LLM response for service result summarization");
            } else {
                logger.error("[SERVER][AI CHAT    ] Service result summarization API request failed with status: {}, body: {}",
                    response.statusCode(), response.body());
            }
        } else {
            logger.warn("[SERVER][AI CHAT    ] LLM service '{}' is not supported for service result summarization", service);
        }
        return serviceResult; // 如果无法提取内容，返回原始结果
    }

    private record Tool(String name, String description, JSONObject inputSchema,
                        Function<Map<String, Object>, Object> handler, String category) {
    }

    private record Resource(String uri, String name, String description, String mimeType, Object contents,
                            String category) {
    }

    private record Service(
            String name,
            String apiName,
            String version,
            String method,
            String description,
            String category,
            List<String> capabilities,
            List<String> useCases,
            List<ServiceParameter> parameters,
            List<ServiceExample> examples
    ) {}

    public record ServiceParameter(
            String name,
            String type,
            boolean required,
            String description
    ) {}

    public record ServiceExample(String userQuery, Map<String, Object> parameters) {}
    
    /**
     * Represents a conversation session with message history.
     */
    private static class ConversationSession {
        private final List<Map<String, String>> messages;
        private String summarizedHistory;
        private long lastActivityTime;
        
        public ConversationSession() {
            this.messages = new ArrayList<>();
            this.summarizedHistory = "";
            this.lastActivityTime = System.currentTimeMillis();
        }
        
        public void addMessage(String role, String content) {
            // Limit the number of messages to avoid excessive memory usage
            if (messages.size() >= MAX_HISTORY_MESSAGES) {
                messages.remove(0); // Remove oldest message
            }
            messages.add(Map.of("role", role, "content", content));
            updateActivityTime();
        }
        
        public List<Map<String, String>> getMessages() {
            return new ArrayList<>(messages); // Return a copy
        }
        
        public String getSummarizedHistory() {
            return summarizedHistory;
        }
        
        public void setSummarizedHistory(String summarizedHistory) {
            this.summarizedHistory = summarizedHistory;
            updateActivityTime();
        }
        
        public void updateActivityTime() {
            this.lastActivityTime = System.currentTimeMillis();
        }
        
        public long getLastActivityTime() {
            return lastActivityTime;
        }
    }
}
