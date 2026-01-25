package org.slackerdb.dbserver.service;

import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.*;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final String mcpLlmKey;
    private final Javalin managementApp;
    private final Object saveLock = new Object();
    private final int portX;
    private final String bindHost;

    // WebSocket heartbeat support for keeping connections alive
    private final Map<WsContext, Long> activeChatConnections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean(false);
    
    public McpServer(
            DBInstance dbInstance,
            Javalin managementApp,
            Logger logger
    ) {
        this.logger = logger;
        this.managementApp = managementApp;
        this.mcpConfigPath = dbInstance.serverConfiguration.getMcpConfig();
        this.mcpLlmServer = dbInstance.serverConfiguration.getMcpLlmServer();
        this.mcpLlmKey = dbInstance.serverConfiguration.getMcpLlmKey();
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
                        String json = JSON.toJSONString(config, JSONWriter.Feature.PrettyFormat);
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
                    String json = JSON.toJSONString(configObj, JSONWriter.Feature.PrettyFormat);
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
                String json = JSON.toJSONString(generateToolDefinitionsJsonArray(), JSONWriter.Feature.PrettyFormat);
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
                        String json = JSON.toJSONString(config, JSONWriter.Feature.PrettyFormat);
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
                    String json = JSON.toJSONString(configObj, JSONWriter.Feature.PrettyFormat);
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
                String json = JSON.toJSONString(generateResourceDefinitionsJsonArray(), JSONWriter.Feature.PrettyFormat);
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
                        String json = JSON.toJSONString(config, JSONWriter.Feature.PrettyFormat);
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
                    String json = JSON.toJSONString(configObj, JSONWriter.Feature.PrettyFormat);
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
                        
                        String message = ctx.message();
                        logger.trace(
                                "[SERVER][AI CHAT    ] AI Chat received message : {}", message);
                        
                        // Parse the incoming message
                        JSONObject messageNode = JSON.parseObject(message);
                        String messageType = messageNode.containsKey("type") ? messageNode.getString("type") : "chat";
                        String content = messageNode.containsKey("content") ? messageNode.getString("content") : "";

                        // 对话的消息类型可以是 chat, clear_history
                        if ("chat".equals(messageType) && !content.trim().isEmpty()) {
                            // 处理会话消息
                            processChatMessage(ctx, content);
                        }
                        else if ("clear_history".equals(messageType)) {
                            // 清空历史会话
                            ctx.attribute("confirmedInfo", null);
                            ctx.attribute("previousCandidateService", "");
                            ctx.send(
                                    """
                                        {
                                            "type": "info",
                                            "message": "Conversation history cleaned."
                                        }
                                    """
                            );
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
                    if (error instanceof WebSocketTimeoutException) {
                        // 如果是空闲超时错误，记录为调试信息而不是错误
                        logger.trace("[SERVER][AI CHAT    ] AI Chat WebSocket idle timeout (connection closed due to inactivity): {}", error.getMessage());
                    } else if (isConnectionInterruption(error)) {
                        // 连接中断是正常现象，不记录为错误
                        logger.trace("[SERVER][AI CHAT    ] AI Chat WebSocket connection interrupted: {}", error.getMessage());
                    } else {
                        // 其他错误仍然记录为错误
                        logger.error("[SERVER][AI CHAT    ] AI Chat WebSocket error:", error);
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
        return JSON.toJSONString(generateServiceDefinitionsJsonArray(), JSONWriter.Feature.PrettyFormat);
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
        String json = JSON.toJSONString(root, JSONWriter.Feature.PrettyFormat);
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
        String json = JSON.toJSONString(root, JSONWriter.Feature.PrettyFormat);
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
        String json = JSON.toJSONString(root, JSONWriter.Feature.PrettyFormat);
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
    private void processChatMessage(WsContext ctx, String userMessage) {
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

                // 填写系统提示词和用户提示词
                JSONObject userPrompt = new JSONObject();
                userPrompt.put("userQuery", userMessage);
                Map<String, Object> confirmedInfo = ctx.attribute("confirmedInfo");
                userPrompt.put("confirmedInfo", Objects.requireNonNullElse(confirmedInfo, ""));
                String previousCandidateService = ctx.attribute("previousCandidateService");
                userPrompt.put("previousCandidateService", Objects.requireNonNullElse(previousCandidateService, ""));

                // 大模型调用
                String llmResponse;
                JSONObject llmResponseJson;

                llmResponse = callLlmApi(service, host, port, model, generateSystemPrompt(), userPrompt.toJSONString());
                // 如果回应中包含了serviceName，则记录到Context中
                llmResponseJson = JSON.parseObject(llmResponse);
                if (llmResponseJson.containsKey("type") && llmResponseJson.getString("type").equals("error"))
                {
                    JSONObject feedbackJsonObj = new JSONObject();
                    feedbackJsonObj.put("type", "response");
                    feedbackJsonObj.put("content", llmResponseJson.getString("message"));
                    feedbackJsonObj.put("timestamp", System.currentTimeMillis());
                    ctx.send(feedbackJsonObj.toJSONString());
                    return;
                }
                // 判断API是否已经就绪
                boolean apiServiceReady = true;
                if (llmResponseJson.containsKey("service_name") && !llmResponseJson.getString("service_name").isEmpty())
                {
                    ctx.attribute("previousCandidateService", llmResponseJson.getString("service_name"));
                }
                else
                {
                    apiServiceReady = false;
                }
                if (llmResponseJson.containsKey("parameters"))
                {
                    JSONObject llmResponseParameterJson = llmResponseJson.getJSONObject("parameters");
                    Map<String, String> confirmedInfoFromResponse = new HashMap<>();
                    for (String key : llmResponseParameterJson.keySet())
                    {
                        if (!llmResponseParameterJson.getString(key).isEmpty())
                        {
                            confirmedInfoFromResponse.put(key, llmResponseParameterJson.getString(key));
                        }
                        else
                        {
                            apiServiceReady = false;
                        }
                    }
                    if (!confirmedInfoFromResponse.isEmpty())
                    {
                        ctx.attribute("confirmedInfo", confirmedInfoFromResponse);
                    }
                }

                if (!apiServiceReady)
                {
                    // 没有收集到足够的信息，要求用户补充信息
                    String feedback;
                    // 没有收集到足够的信息，要求用户补充信息
                    if (llmResponseJson.getString("userLanguage").equalsIgnoreCase("Chinese"))
                    {
                        feedback = """
                                    你可能需要补充信息来进行下一步操作.
                                
                                    当前已经确认的消息：
                                        候选服务： ${service_name}
                                        已知参数： ${parameters}
                                """;
                        feedback = feedback.replace("${service_name}", "\"" + llmResponseJson.getString("service_name") + "\"");
                        feedback = feedback.replace("${parameters}", llmResponseJson.getString("parameters"));
                        feedback = feedback.replace("\"\"", "\"缺失，待补充\"");
                    }
                    else
                    {
                        feedback = """
                                    You may need to provide additional information to continue ...
                                
                                    Currently confirmed message:
                                        Candidate service: ${service_name}
                                        parameters： {parameters}
                                """;
                        feedback = feedback.replace("${service_name}", "\"" + llmResponseJson.getString("service_name") + "\"");
                        feedback = feedback.replace("${parameters}", llmResponseJson.getString("parameters"));
                        feedback = feedback.replace("\"\"", "\"Missing, to be added\"");
                    }
                    JSONObject feedbackJsonObj = new JSONObject();
                    feedbackJsonObj.put("type", "response");
                    feedbackJsonObj.put("content", feedback);
                    feedbackJsonObj.put("timestamp", System.currentTimeMillis());
                    ctx.send(feedbackJsonObj.toJSONString());
                    return;
                }

                // 调用API
                String finalResponse;
                try {
                    // 调用服务API
                    String serviceName = llmResponseJson.containsKey("service_name") ? llmResponseJson.getString("service_name") : "";
                    JSONObject parametersNode = llmResponseJson.getJSONObject("parameters");
                    Map<String, Object> parameters = JSON.parseObject(JSON.toJSONString(parametersNode), new TypeReference<>() {});

                    // 调用服务
                    try {
                        // 调用指定的API Service: http://<bindHost>:<portX>/api/<apiVersion>/<serviceName>
                        String serviceResult = callServiceApi(serviceName, parameters);

                        if (llmResponseJson.getBoolean("summary")) {
                            // 根据serviceResult，发送给LLM，要求它进行自然语言的整理
                            try {
                                finalResponse = summarizeServiceResult(userMessage, serviceResult);
                            } catch (Exception e) {
                                logger.warn("[SERVER][AI CHAT    ] Failed to summarize service result, using raw result: {}", e.getMessage());
                                finalResponse = serviceResult;
                            }
                        }
                        else
                        {
                            finalResponse = serviceResult;
                        }
                    } catch (Exception e) {
                        logger.error("[SERVER][AI CHAT    ] Failed to call service API: {}",
                                e.getMessage(), e);
                        finalResponse = "Failed to call service '" + serviceName + "': " + e.getMessage();
                    }
                } catch (Exception e) {
                    // 如果解析失败，直接返回原始响应
                    logger.warn("[SERVER][AI CHAT    ] Failed to parse LLM response as JSON, using raw response: {}", e.getMessage());
                    finalResponse = llmResponse;
                }

                // 为用户做出回答
                String responseJson = JSON.toJSONString(Map.of(
                    "type", "response",
                    "content", finalResponse,
                    "timestamp", System.currentTimeMillis()
                ));
                ctx.send(responseJson);
            } catch (Exception e) {
                // Check if this is a ClosedChannelException (connection closed during send)
                Throwable cause = e;
                boolean isClosedChannel = false;
                while (cause != null) {
                    if (cause instanceof ClosedChannelException) {
                        isClosedChannel = true;
                        break;
                    }
                    cause = cause.getCause();
                }

                if (isClosedChannel) {
                    // Just log at debug level, don't send error to client (connection already closed)
                    logger.trace("[SERVER][AI CHAT    ] Connection closed while sending response to AI chat");
                } else {
                    logger.error("[SERVER][AI CHAT    ] Failed to process chat message.", e);
                    try {
                        ctx.send("{\"type\": \"error\", \"message\": \"Failed to get response from AI: " + e.getMessage() + "\"}");
                    } catch (Exception ignored) {}
                }
            }
        }, executor);
    }

    /**
     * LLM系统提示词.
     */
    private String generateSystemPrompt() {
        String prompt =
                """
You are a service candidate selector and parameter extractor.

Role and task:
- You are given a list of available services ("serviceInfo").
- Each service includes:
  - name, version, method
  - description
  - capabilities
  - use_cases
  - parameters (required / optional)
  - example user queries
- Your task is to select the single best candidate service for the user's query and extract parameters as completely as possible.

Rules:
1. You will receive a User Prompt in JSON format containing:
   - "userQuery": the latest question or request from the user.
   - "confirmedInfo": parameters already confirmed in previous interactions. May be empty if no prior confirmed parameters exist. Do NOT modify these values.
   - "previousCandidateService": the candidate service selected in the previous turn, for reference only. May be empty if no previous candidate exists.
   - "summaryPreference" (optional): if explicitly false, do NOT include summary; otherwise, summary defaults to true.

2. Respect the user's input:
   - If the user denies or changes any confirmedInfo or previousCandidateService field, follow the user's input.
   - If the user explicitly sets "summaryPreference" to false, set summary to false in output.
   - Do NOT override or assume values in confirmedInfo without explicit user input.

3. Candidate service selection:
   - Only one candidate service should be selected.
   - If the user's intent clearly matches a service in serviceInfo, you MUST select it.
   - If no service can possibly match the user's intent, leave selectedService empty.

4. Parameter extraction:
   - Extract all parameters as completely as possible.
   - If a parameter cannot be confidently determined, leave it empty.
   - Do NOT modify confirmedInfo.

5. Language:
   - Follow the user's input language exactly (Chinese input → Chinese output; English input → English output).
   - Do NOT mix languages.

6. Output:
   - Return strictly as a single JSON object, no explanations or markdown.
   - The output MUST include:
      1. A boolean field "summary" (true or false), following "summaryPreference" from User Prompt.
      2. A string field "userLanguage", which should be "Chinese" for Chinese input or "English" for English input.

Available services:
${servicesInfo}

User Prompt JSON structure:
{
  "userQuery": "...",
  "confirmedInfo": {...} (may be empty),
  "previousCandidateService": "..." (may be empty),
  "summaryPreference": true|false (optional)
}

Output format (JSON):
{
  "service_name": "string_or_empty",
  "parameters": {
    "param1": "value1_or_empty",
    "param2": "value2_or_empty",
    ...
  },
  "summary": true|false,
  "userLanguage": "Chinese"|"English"
}
    """;
        prompt = prompt.replace("${servicesInfo}", JSON.toJSONString(generateServiceDefinitionsJsonArray(), JSONWriter.Feature.PrettyFormat));
        return prompt;
    }
    
    /**
     * 调用大模型服务
     */
    private String callLlmApi(
            String service,
            String host,
            String port,
            String model,
            String systemPrompt,
            String userMessage
    ) throws IOException, InterruptedException {
        // LLM实现, 当前支持ollama和openai
        if ("ollama".equalsIgnoreCase(service)) {
            // Construct URL
            String url = "http://" + host + ":" + port + "/v1/chat/completions";
            
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
            requestBody.put("max_tokens", 2000);        // Increased for longer responses
            requestBody.put("top_p", 1);                // 保证概率分布不被截断，避免模型"放弃生成"
            requestBody.put("temperature", 0.3);        // 较低的温度有利于更确定性的路径选择
            
            String requestBodyJson = JSON.toJSONString(requestBody);
            
            // Create HTTP client and request
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson));
            
            // 如果配置了API密钥，添加到请求头
            if (mcpLlmKey != null && !mcpLlmKey.trim().isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + mcpLlmKey.trim());
            }
            
            HttpRequest request = requestBuilder.build();
            
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
                return """
                            {
                            "type": "error",
                            "message": "No response content found in LLM response"
                            }
                        """;
            } else {
                throw new IOException("LLM API request failed with status: " + response.statusCode() + ", body: " + response.body());
            }
        } else if ("openai".equalsIgnoreCase(service)) {
            // OpenAI API支持
            String url = "https://" + host + "/v1/chat/completions";
            
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
            requestBody.put("max_tokens", 2000);
            requestBody.put("temperature", 0.3);
            
            String requestBodyJson = JSON.toJSONString(requestBody);
            
            // Create HTTP client and request
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson));
            
            // OpenAI API需要API密钥
            if (mcpLlmKey != null && !mcpLlmKey.trim().isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + mcpLlmKey.trim());
            } else {
                throw new IOException("OpenAI API requires API key but mcp_llm_key is not configured");
            }
            
            HttpRequest request = requestBuilder.build();
            
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
                return """
                            {
                            "type": "error",
                            "message": "No response content found in LLM response"
                            }
                        """;
            } else {
                throw new IOException("OpenAI API request failed with status: " + response.statusCode() + ", body: " + response.body());
            }
        } else {
            // For other services, return a placeholder response
            return """
                            {
                            "type": "error",
                            "message": "LLM service ${service} is configured but not yet implemented. "
                            }
                        """.replace("${service}", service);
        }
    }
    
    /**
     * 调用指定的API服务
     * @param serviceName 服务名称
     * @param parameters 服务参数
     * @return 服务调用结果
     */
    private String callServiceApi(String serviceName, Map<String, Object> parameters) throws IOException, InterruptedException {
        Service service = this.services.get(serviceName);

        String apiName = service.apiName;
        String apiVersion = service.version;
        String apiMethod = service.method;

        // 构建基础API URL: http://<bindHost>:<portX>/api/<serviceVersion>/<serviceName>
        String baseUrl = "http://" + bindHost + ":" + portX + "/api/" + apiVersion + "/" + apiName;
        
        logger.trace("[SERVER][AI CHAT    ] Calling service API: {} with version: {}, method: {} and parameters: {}",
                     baseUrl, apiVersion, apiMethod, parameters);
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request;
        
        // 根据serviceMethod决定请求方式
        if ("GET".equalsIgnoreCase(apiMethod)) {
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
                String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
                String encodedValue = URLEncoder.encode(value.toString(), StandardCharsets.UTF_8);
                urlBuilder.append(encodedKey).append("=").append(encodedValue);
            }
        }
        
        return urlBuilder.toString();
    }
    
    public void shutdown() {
        executor.shutdown();
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
        Iterator<Map.Entry<WsContext, Long>> it = activeChatConnections.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WsContext, Long> entry = it.next();
            WsContext ctx = entry.getKey();
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
        if (error instanceof ClosedChannelException || error instanceof EOFException) {
            return true;
        }
        // 检查 SocketException 且消息包含 closed 或 reset
        if (error instanceof SocketException) {
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
}
