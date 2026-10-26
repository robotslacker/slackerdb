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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * MCP 服务端。
 *
 * <p>职责分两层：本类负责"有哪些工具/资源/服务"（注册表 + 管理面 + 私有 AI Chat），
 * {@link McpProtocol} 负责"这些内容怎么按 2026-07-28 规范落到线上"，
 * {@link McpToolkit} 负责工具的实际执行（SQL 执行与结果物化），
 * {@link McpSessionStore} 负责跨调用状态的显式 handle。</p>
 *
 * <p>传输为 Streamable HTTP 的单一 POST 端点（{@code /mcp}，兼容别名 {@code /jsonrpc}）。
 * 旧的 GET/{@code /sse} 端点与 {@code ping}、{@code logging/setLevel} 方法
 * 已随 2026-07-28 规范移除；{@code initialize} 保留以兼容旧代客户端。</p>
 */
public class McpServer {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final Map<String, Service> services = new ConcurrentHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(
            4,                          // corePoolSize
            32,                         // maximumPoolSize
            60L,                        // keepAliveTime
            TimeUnit.SECONDS,           // keepAliveTime unit
            new LinkedBlockingQueue<>(1000),  // workQueue with bounded capacity
            new ThreadPoolExecutor.CallerRunsPolicy()  // rejection policy
    );
    private final Logger logger;
    private final String mcpConfigPath;
    private final String mcpLlmServer;
    private final String mcpLlmKey;
    private final Javalin managementApp;
    private final Object saveLock = new Object();
    private final int portX;
    private final String bindHost;

    /** 工具与资源的实际执行体。 */
    private final McpToolkit toolkit;
    /** 跨调用状态的显式 handle 表。 */
    private final McpSessionStore sessionStore;

    /**
     * 列表类结果的缓存建议时长。
     *
     * <p>工具/资源清单只在管理员注册或卸载时变化，给 30 秒既能让客户端缓存生效，
     * 又能让管理员的操作在可接受时间内被看见。</p>
     */
    private final long toolsTtlMs = 30_000L;
    private final long resourcesTtlMs = 30_000L;

    /** 内省类工具的返回行数上限（run_sql 不设上限）。 */
    private static final int MAX_ROWS_CEILING = 5000;
    /** 会话 handle 的空闲回收时间。 */
    private static final long SESSION_IDLE_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final int MAX_SESSIONS = 64;

    // WebSocket heartbeat support for keeping connections alive
    private final Map<WsContext, Long> activeChatConnections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean(false);

    /** 会话 handle 的空闲回收线程。 */
    private final ScheduledExecutorService sessionReaper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mcp-session-reaper");
                t.setDaemon(true);
                return t;
            });

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

        // 工具执行统一走 dbserver 的连接池——不新建旁路，池上限与会话约束同样生效。
        //
        // 借出的连接必须先把 schema 搜索路径钉回默认值：池是 pgwire / 数据服务 / MCP 共用的，
        // 连接会把上一个会话的 search_path 一起带过来（例如客户端在启动包里要求了
        // currentSchema=client_sp 的 pgwire 会话），而 run_sql 执行的是用户原始 SQL ——
        // 非限定名一旦按别人的 schema 解析，轻则表找不到，重则读到另一个同名表的错数据。
        final String mcpDefaultSchema = defaultSchema(dbInstance);
        this.sessionStore = new McpSessionStore(
                () -> openDefaultSchemaConnection(dbInstance, mcpDefaultSchema),
                logger,
                SESSION_IDLE_TIMEOUT_MS,
                MAX_SESSIONS);
        // MCP 是内部工具：不再施加只读、行数上限或语句超时；权限与
        // access_mode 由服务器本身决定，run_sql 可以正常执行写语句。
        this.toolkit = new McpToolkit(sessionStore, new McpToolkit.Config(
                MAX_ROWS_CEILING,
                mcpDefaultSchema), logger);
        sessionReaper.scheduleWithFixedDelay(() -> {
            try {
                sessionStore.reapIdle();
            } catch (Exception e) {
                logger.warn("[SERVER][MCP        ] session reaper failed", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 默认 schema。
     *
     * <p>用 DuckDB 的默认 schema {@code main}，<b>不是</b>配置里的 {@code data} 名：
     * 后者是 ATTACH 进来的 catalog 名，直接拿它当 schema 会查不到任何列。
     * 实测 {@code information_schema.columns} 里普通表落在 {@code main}。</p>
     */
    private static String defaultSchema(DBInstance dbInstance) {
        return "main";
    }

    /**
     * 从池里借一条连接，并把它的 schema 搜索路径钉回 MCP 的默认 schema。
     *
     * <p><b>为什么必须显式设</b>：MCP 与 pgwire、数据服务共用同一个连接池，池里的连接是复用的，
     * 而归还时的复位只做"回滚未提交事务 + 恢复 autoCommit"
     * （见 {@code DBDataSourcePool#resetConnectionForReuse}），<b>不碰 search_path</b>。
     * 于是上一个会话留在连接上的 schema 搜索路径会被 MCP 继承：会话照样成功、不报错、不打日志，
     * 但 {@code run_sql} 执行用户原始 SQL 时，非限定名按别人的 schema 解析——轻则表找不到，
     * 重则解析到另一个同名表、静默读到错数据。</p>
     *
     * <p>把"借到即规范化"收敛成这一个入口，{@link McpSessionStore#borrow()}（无 handle 的
     * 内省工具与 {@code run_sql}）与 handle 会话的建连都覆盖到了。</p>
     *
     * <p>规范化失败时必须把连接还回池里（句柄的 {@code close()} 语义就是归还），
     * 不能把槽位漏在手上。</p>
     */
    private static Connection openDefaultSchemaConnection(DBInstance dbInstance, String defaultSchema)
            throws SQLException {
        Connection connection = dbInstance.dbDataSourcePool.getConnection();
        boolean ready = false;
        try {
            applyDefaultSearchPath(connection, defaultSchema);
            ready = true;
            return connection;
        } finally {
            if (!ready) {
                connection.close();
            }
        }
    }

    /**
     * 把连接的 schema 搜索路径设成 {@code <当前 catalog>.<默认 schema>}。
     *
     * <p><b>为什么必须带 catalog 限定、而且必须是"当前 catalog"</b>：DuckDB 会跟着
     * {@code search_path} 第一项的 catalog 切换当前库（实测把 {@code memory} 写在最前面会让
     * 整个会话回到内存库，非限定名建表就落到内存库去了）。这里要的只是让 <b>schema 搜索路径</b>
     * 确定下来、不继承别人的残留，<b>不改变当前库</b>——所以用 {@code current_catalog()}
     * 把当前库原样带上。不加限定的写法（{@code set search_path = 'main'}）依赖 DuckDB
     * 未文档化的推断行为，不采用。</p>
     */
    private static void applyDefaultSearchPath(Connection connection, String defaultSchema)
            throws SQLException {
        String catalog;
        try (Statement probe = connection.createStatement();
             ResultSet rs = probe.executeQuery("select current_catalog()")) {
            catalog = rs.next() ? rs.getString(1) : null;
        }

        // catalog / schema 名都来自服务端自身（不是用户输入），仍做一次单引号转义以防万一
        String searchPath = (catalog == null || catalog.isBlank())
                ? defaultSchema
                : catalog + "." + defaultSchema;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("set search_path = '" + searchPath.replace("'", "''") + "'");
        }
    }

    public void run() throws Exception {
        loadConfiguredMcpDefinitions();
        registerMcpProtocolRoutes();
        registerMcpAdminRoutes();
        registerAiChatRoute();
    }

    /**
     * 启动时加载 {@code mcp_config} 指定的工具/资源定义。
     *
     * <p><b>为什么必须有这个方法</b>：配置项 {@code mcp_config} 在旧实现里
     * <b>只在保存/导出端点被读取</b>，启动路径完全没有加载它——
     * 也就是说 README 承诺的"启动时动态加载工具与资源"从未发生，
     * 注册表在每次重启后都是空的。这里补上。</p>
     *
     * <p>接受：单个 JSON 文件，或一个目录（加载其中所有 {@code *.mcp} 与 {@code *.service}）。
     * 加载失败不阻断启动（记警告继续），因为工具面不可用不应让数据库起不来。</p>
     */
    private void loadConfiguredMcpDefinitions() {
        if (mcpConfigPath == null || mcpConfigPath.isBlank()) {
            return;
        }
        File path = new File(mcpConfigPath);
        if (!path.exists()) {
            logger.warn("[SERVER][MCP    ] mcp_config [{}] does not exist; no tools or resources loaded.",
                    mcpConfigPath);
            return;
        }
        List<File> files = new ArrayList<>();
        if (path.isDirectory()) {
            File[] found = path.listFiles((dir, name) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.endsWith(".mcp") || lower.endsWith(".service");
            });
            if (found != null) {
                Arrays.sort(found, Comparator.comparing(File::getName));
                files.addAll(Arrays.asList(found));
            }
        } else {
            files.add(path);
        }
        if (files.isEmpty()) {
            logger.warn("[SERVER][MCP    ] mcp_config directory [{}] contains no *.mcp or *.service file.",
                    mcpConfigPath);
            return;
        }
        int toolCount = 0;
        int resourceCount = 0;
        for (File file : files) {
            JSONObject config = readConfigFile(file);
            if (config == null || config.isEmpty()) {
                continue;
            }
            JSONArray toolArray = config.getJSONArray("tools");
            if (toolArray != null) {
                for (int i = 0; i < toolArray.size(); i++) {
                    JSONObject def = toolArray.getJSONObject(i);
                    if (registerExternalTool(def, "config:" + file.getName())) {
                        toolCount++;
                    }
                }
            }
            JSONArray resourceArray = config.getJSONArray("resources");
            if (resourceArray != null) {
                for (int i = 0; i < resourceArray.size(); i++) {
                    JSONObject def = resourceArray.getJSONObject(i);
                    String uri = def.getString("uri");
                    String name = def.getString("name");
                    if (uri == null || uri.isBlank() || name == null || name.isBlank()) {
                        logger.warn("[SERVER][MCP    ] skipping resource without uri/name in {}",
                                file.getName());
                        continue;
                    }
                    if (resources.containsKey(uri)) {
                        logger.warn("[SERVER][MCP    ] duplicate resource [{}] in {}; skipped", uri,
                                file.getName());
                        continue;
                    }
                    addResource(uri, name,
                            def.getString("description"),
                            def.getString("mimeType"),
                            def.get("contents"),
                            def.getString("category"));
                    resourceCount++;
                }
            }
        }
        logger.info("[SERVER][MCP    ] loaded {} tool(s) and {} resource(s) from mcp_config [{}].",
                toolCount, resourceCount, mcpConfigPath);
    }

    /**
     * 注册一个外部工具定义（来自配置文件或 {@code /mcp/registerMCPTool}）。
     *
     * <p>支持 {@code handler} 字段：{@code builtin:query} 之类会<b>绑定到真实实现</b>，
     * 而不是像旧实现那样一律挂 echo 桩——旧实现从不读取 {@code handler}，
     * 于是配置里写的语义被静默丢弃。</p>
     *
     * @return 是否注册成功（重复或字段缺失返回 false）
     */
    private boolean registerExternalTool(JSONObject def, String source) {
        String name = def == null ? null : def.getString("name");
        if (name == null || name.isBlank()) {
            logger.warn("[SERVER][MCP    ] skipping tool without name in {}", source);
            return false;
        }
        String description = def.getString("description");
        if (description == null || description.isBlank()) {
            logger.warn("[SERVER][MCP    ] skipping tool [{}] in {}: description is required", name, source);
            return false;
        }
        if (isBuiltinTool(name)) {
            logger.warn("[SERVER][MCP    ] skipping tool [{}] in {}: the name is reserved by a built-in tool",
                    name, source);
            return false;
        }
        if (tools.containsKey(name)) {
            logger.warn("[SERVER][MCP    ] duplicate tool [{}] in {}; skipped", name, source);
            return false;
        }
        String handler = def.getString("handler");
        JSONObject inputSchema = def.getJSONObject("inputSchema");

        if (handler != null && handler.startsWith("builtin:")) {
            String target = handler.substring("builtin:".length()).trim();
            String mapped = mapLegacyBuiltin(target);
            McpToolkit.ToolSpec spec = findBuiltinSpec(mapped);
            if (spec == null) {
                logger.warn("[SERVER][MCP    ] tool [{}] in {} requests unknown builtin handler [{}]; skipped",
                        name, source, handler);
                return false;
            }
            // 内置工具带自己的 schema（含 outputSchema），不采用配置里的手写 schema，
            // 保证"声明"与"实际实现"始终一致；spec.name() 一并记下，保存时写回 handler
            boolean added = addTool(name, description, spec.inputSchema(), spec.outputSchema(), arguments -> {
                try {
                    return toolkit.call(spec.name(), JSONObject.from(arguments));
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }, def.getString("category"), spec.name(), false);
            if (added) {
                logger.info("[SERVER][MCP    ] tool [{}] from {} bound to builtin handler [{}]",
                        name, source, spec.name());
            }
            return added;
        }

        if (inputSchema == null) {
            logger.warn("[SERVER][MCP    ] skipping tool [{}] in {}: inputSchema is required "
                    + "when no builtin handler is given", name, source);
            return false;
        }
        // 无 handler：保留旧语义（回显入参）以便兼容既有配置。提示语不写进 description，
        // 而是在 tools/list 展示时拼接，这样 save→load 反复往返也不会把描述越滚越长。
        boolean added = addTool(name,
                description,
                inputSchema,
                null,
                arguments -> Map.of("status", "registered", "tool", name, "arguments", arguments),
                def.getString("category"), null, true);
        if (added) {
            logger.warn("[SERVER][MCP    ] tool [{}] from {} has no 'handler'; it will only echo arguments",
                    name, source);
        }
        return added;
    }

    /** 旧的示例配置里用过 {@code query} 这个名字，映射到现在的 {@code run_sql}。 */
    private static String mapLegacyBuiltin(String target) {
        return switch (target.toLowerCase(Locale.ROOT)) {
            case "query", "sql", "runquery" -> "run_sql";
            case "tables", "show_tables" -> "list_tables";
            case "describe" -> "describe_table";
            case "sample" -> "sample_rows";
            default -> target;
        };
    }

    private McpToolkit.ToolSpec findBuiltinSpec(String name) {
        for (McpToolkit.ToolSpec spec : toolkit.toolSpecs()) {
            if (spec.name().equals(name)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 注册 MCP 协议面（规范相关部分）。
     *
     * <p>相对旧实现的差异，逐条对应 2026-07-28：</p>
     * <ul>
     *   <li><b>GET/{@code /sse} 端点已移除</b>（2026-07-28 取消 GET；HTTP+SSE 自 2025-03-26 起弃用）。
     *       旧实现用 SSE 硬塞一个能力帧、且从不读取客户端消息，本身就是不合规的。</li>
     *   <li><b>{@code POST /mcp} 为规范端点</b>：Streamable HTTP 的单一 POST 端点，
     *       结果以 JSON 对象返回。</li>
     *   <li><b>{@code POST /jsonrpc} 保留为兼容别名</b>：已安装客户端/控制台都在用它。</li>
     *   <li><b>{@code server/discover} 已实现</b>（规范强制）。</li>
     *   <li><b>{@code initialize} 保留以兼容旧代</b>——规范明确要求向后兼容。</li>
     * </ul>
     */
    private void registerMcpProtocolRoutes() {
        // 规范端点：Streamable HTTP 单一 POST
        this.managementApp.unsafe.routes.post("/mcp", this::handleMcpPost);
        // 兼容别名：旧客户端与控制台
        this.managementApp.unsafe.routes.post("/jsonrpc", this::handleMcpPost);

        /*
          GET /mcp 必须回 405，不能回 200。

          2026-07-28 取消了 GET 端点，但已安装客户端的 Streamable HTTP 传输仍会在
          连接时先发一个 GET 想开 SSE 流，并且**只把 405 当作"服务端不提供 SSE 流"的
          正常信号**（其余非 2xx 会抛错，而 2xx 会被当成 SSE 流解析）。对官方
          @modelcontextprotocol/sdk 而言，返回 200 + JSON 会让它去解析一个不是
          SSE 的响应体，连接直接失败。

          所以这里刻意回 405 + Allow: POST：既符合规范方向，又让旧客户端能正常接上。
          server/discover 仍然可用，走 POST（这是规范要求的唯一方式）。
        */
        this.managementApp.unsafe.routes.get("/mcp", ctx -> {
            ctx.header("Allow", "POST");
            ctx.status(405).result("Method Not Allowed: this MCP endpoint is POST-only "
                    + "(the GET/SSE endpoint was removed in MCP 2026-07-28). "
                    + "Send a JSON-RPC POST with method 'server/discover' instead.");
        });
    }

    /**
     * 处理一次 MCP POST。
     *
     * <p>协议要点：</p>
     * <ol>
     *   <li>解析 JSON-RPC 信封，非法请求直接回标准错误码；</li>
     *   <li>从 {@code params._meta} 读协议版本与客户端能力（无状态代）；</li>
     *   <li>版本不匹配回 {@code UnsupportedProtocolVersionError}；</li>
     *   <li>结果统一带 {@code resultType}；列表/read 结果统一带 {@code ttlMs}/{@code cacheScope}。</li>
     * </ol>
     */
    private void handleMcpPost(io.javalin.http.Context ctx) {
        ctx.contentType("application/json");

        JSONObject request;
        try {
            String body = ctx.body();
            if (body == null || body.isBlank()) {
                ctx.result(JSON.toJSONString(errorEnvelope(null, McpProtocol.ERR_INVALID_REQUEST, "Empty request body")));
                return;
            }
            request = JSON.parseObject(body);
        } catch (Exception e) {
            ctx.result(JSON.toJSONString(errorEnvelope(null, McpProtocol.ERR_PARSE, "Parse error: " + e.getMessage())));
            return;
        }

        if (request == null) {
            ctx.result(JSON.toJSONString(errorEnvelope(null, McpProtocol.ERR_INVALID_REQUEST, "Request must be a JSON object")));
            return;
        }
        if (!"2.0".equals(request.getString("jsonrpc"))) {
            ctx.result(JSON.toJSONString(errorEnvelope(request.get("id"), McpProtocol.ERR_INVALID_REQUEST,
                    "Invalid Request: jsonrpc must be exactly '2.0'")));
            return;
        }
        String method = request.getString("method");
        if (method == null || method.isBlank()) {
            ctx.result(JSON.toJSONString(errorEnvelope(request.get("id"), McpProtocol.ERR_INVALID_REQUEST,
                    "Invalid Request: method must be a non-empty string")));
            return;
        }
        Object id = request.get("id");
        JSONObject params = request.getJSONObject("params");

        // 规范要求在 Streamable HTTP POST 上带 Mcp-Method / Mcp-Name。
        // 缺失时容忍（已安装客户端尚在过渡），仅在调试级别记录。
        String headerMethod = ctx.header("Mcp-Method");
        if (headerMethod == null && logger.isDebugEnabled()) {
            logger.debug("[SERVER][MCP] request without Mcp-Method header: {}", method);
        }

        McpProtocol.RequestMeta meta = McpProtocol.readMeta(params);
        if (meta.modernEra() && !McpProtocol.supportsVersion(meta.protocolVersion())) {
            ctx.result(JSON.toJSONString(new JSONObject() {{
                put("jsonrpc", "2.0");
                put("id", id);
                put("error", McpProtocol.unsupportedVersion(meta.protocolVersion()));
            }}));
            return;
        }

        try {
            JSONObject result = dispatch(method, params, meta);
            ctx.result(JSON.toJSONString(successEnvelope(id, result)));
        } catch (McpProtocol.McpParamException e) {
            ctx.result(JSON.toJSONString(errorEnvelope(id, McpProtocol.ERR_INVALID_PARAMS, e.getMessage())));
        } catch (UnsupportedOperationException e) {
            ctx.result(JSON.toJSONString(errorEnvelope(id, McpProtocol.ERR_METHOD_NOT_FOUND, e.getMessage())));
        } catch (Exception e) {
            logger.error("[SERVER][MCP] method {} failed", method, e);
            ctx.result(JSON.toJSONString(errorEnvelope(id, McpProtocol.ERR_INTERNAL,
                    "Internal error: " + e.getMessage())));
        }
    }

    /**
     * 协议方法分派。
     *
     * <p>旧代 {@code initialize} 与 {@code notifications/initialized} 在此处理，
     * 返回与 {@code server/discover} 同一份能力与身份，避免两份真相。</p>
     */
    private JSONObject dispatch(String method, JSONObject params, McpProtocol.RequestMeta meta) throws Exception {
        return switch (method) {
            case "server/discover" -> wrap(McpProtocol.complete(discoverPayload()));

            // 旧代握手：规范已移除，但已安装客户端仍在用，必须保留
            case "initialize" -> wrap(McpProtocol.complete(legacyInitializePayload(params)));
            case "notifications/initialized" -> wrap(McpProtocol.complete(new JSONObject()));
            // 规范已移除 ping / logging/setLevel：明确回复不支持，而不是静默吞掉
            case "ping" -> throw new UnsupportedOperationException(
                    "Method 'ping' was removed in MCP 2026-07-28; use server/discover for liveness.");
            case "logging/setLevel" -> throw new UnsupportedOperationException(
                    "Method 'logging/setLevel' was removed in MCP 2026-07-28; pass "
                            + McpProtocol.META_LOG_LEVEL + " in _meta per request instead.");

            case "tools/list" -> wrap(McpProtocol.complete(
                    McpProtocol.withCache(toolsListPayload(), toolsTtlMs, McpProtocol.SCOPE_PUBLIC)));
            case "tools/call" -> callTool(params);
            case "resources/list" -> wrap(McpProtocol.complete(
                    McpProtocol.withCache(resourcesListPayload(), resourcesTtlMs, McpProtocol.SCOPE_PUBLIC)));
            case "resources/templates/list" -> wrap(McpProtocol.complete(
                    McpProtocol.withCache(resourceTemplatesPayload(), resourcesTtlMs, McpProtocol.SCOPE_PUBLIC)));
            case "resources/read" -> readResource(params);
            case "prompts/list" -> wrap(McpProtocol.complete(
                    McpProtocol.withCache(promptsListPayload(), resourcesTtlMs, McpProtocol.SCOPE_PUBLIC)));
            case "prompts/get" -> getPrompt(params);

            default -> throw new UnsupportedOperationException("Method not found: " + method);
        };
    }

    /** {@link McpProtocol} 用 {@code Map} 构造信封，协议出口统一转成 fastjson 的 JSONObject。 */
    private static JSONObject wrap(Map<String, Object> map) {
        return JSONObject.from(map);
    }

    /** {@code server/discover} 的载荷：规范强制的"公布版本 / 能力 / 身份"。 */
    private JSONObject discoverPayload() {
        JSONObject payload = new JSONObject();
        payload.put("protocolVersions", new JSONArray(new ArrayList<Object>(McpProtocol.SUPPORTED_VERSIONS)));
        payload.put("protocolVersion", McpProtocol.PROTOCOL_VERSION);
        payload.put("serverInfo", McpProtocol.serverInfo());
        payload.put("capabilities", McpProtocol.capabilities());
        payload.put("instructions", instructions());
        payload.put("methods", new JSONArray(new ArrayList<Object>(McpProtocol.implementedMethods())));
        return payload;
    }

    /**
     * 旧代 {@code initialize} 的应答。
     *
     * <p>旧客户端会读 {@code protocolVersion} 并据此决定后续行为，因此这里必须回
     * <b>它请求的那个版本</b>（如果在我们支持范围内），而不是硬塞最新版本——
     * 否则旧客户端会以为服务端升级了、开始发新语义的请求。</p>
     *
     * <p>当客户端请求的版本我们完全不支持时，退回到<b>双方共同支持的最新版本</b>
     * （即 {@link McpProtocol#SUPPORTED_VERSIONS} 里最靠前的一个），而不是无条件回
     * 我们的首要版本：对只认识旧版本的客户端来说，一个它不认识的新版本号同样没法用。</p>
     */
    private JSONObject legacyInitializePayload(JSONObject params) {
        String requested = params == null ? null : params.getString("protocolVersion");
        String negotiated = McpProtocol.supportsVersion(requested)
                ? requested
                : McpProtocol.SUPPORTED_VERSIONS.get(0);
        JSONObject payload = new JSONObject();
        payload.put("protocolVersion", negotiated);
        payload.put("serverInfo", McpProtocol.serverInfo());
        payload.put("capabilities", McpProtocol.capabilities());
        payload.put("instructions", instructions());
        return payload;
    }

    /** 给模型的服务器级说明：把"优先用已注册服务"这条策略写在握手阶段。 */
    private String instructions() {
        return "SlackerDB exposes a DuckDB database. "
                + "Start by calling list_services: the registered data services are parameterized SQL exposed "
                + "as named, cached, versioned endpoints, and they are the curated queries for this database — "
                + "prefer a matching service over writing ad-hoc SQL. "
                + "If no service matches, inspect the schema with list_tables, then use describe_table or "
                + "sample_rows before writing SQL. "
                + "run_sql executes both read and write statements; permissions follow the server's access_mode. "
                + "By default all rows are returned; pass max_rows to cap the result. "
                + "(The same service list is also available as the resource slackerdb://services, for clients "
                + "that consume resources.)";
    }

    private JSONObject toolsListPayload() {
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (McpToolkit.ToolSpec spec : toolkit.toolSpecs()) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", spec.name());
            tool.put("description", spec.description());
            tool.put("inputSchema", spec.inputSchema());
            tool.put("outputSchema", spec.outputSchema());
            toolList.add(tool);
        }
        // 外部通过 /mcp/registerMCPTool 注册的工具（管理员自定义），同样按名排序
        for (Tool tool : tools.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name);
            item.put("description", tool.presentedDescription());
            item.put("inputSchema", tool.inputSchema);
            if (tool.outputSchema != null) {
                item.put("outputSchema", tool.outputSchema);
            }
            if (tool.category != null) {
                item.put("category", tool.category);
            }
            toolList.add(item);
        }
        // 确定性顺序：规范 SHOULD 要求，用于客户端缓存与提示词缓存命中
        toolList.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        JSONObject payload = new JSONObject();
        payload.put("tools", toolList);
        return payload;
    }

    /**
     * {@code tools/call}。
     *
     * <p>结果形状按规范给全：{@code content}（给人/模型看）+
     * {@code structuredContent}（给程序用）+ {@code isError}。</p>
     */
    private JSONObject callTool(JSONObject params) throws Exception {
        String toolName = McpProtocol.requireString(params, "name");
        JSONObject arguments = params.getJSONObject("arguments");

        JSONObject structured;
        if (isBuiltinTool(toolName)) {
            structured = toolkit.call(toolName, arguments);
        } else {
            Tool external = tools.get(toolName);
            if (external == null) {
                throw new McpProtocol.McpParamException("Unknown tool: " + toolName);
            }
            Map<String, Object> argsMap = arguments == null
                    ? Map.of()
                    : JSON.parseObject(arguments.toJSONString(), new TypeReference<Map<String, Object>>() {
            });
            Object handlerResult = external.handler.apply(argsMap);
            structured = handlerResult instanceof JSONObject obj
                    ? obj
                    : JSONObject.from(handlerResult == null ? Map.of() : handlerResult);
        }

        JSONObject payload = new JSONObject();
        payload.put("content", McpProtocol.jsonContent(structured));
        payload.put("structuredContent", structured);
        payload.put("isError", false);
        return wrap(McpProtocol.complete(payload));
    }

    private boolean isBuiltinTool(String name) {
        for (McpToolkit.ToolSpec spec : toolkit.toolSpecs()) {
            if (spec.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private JSONObject resourcesListPayload() {
        List<Map<String, Object>> resourceList = new ArrayList<>();
        for (McpToolkit.ResourceSpec spec : toolkit.resourceSpecs()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uri", spec.uri());
            item.put("name", spec.name());
            item.put("description", spec.description());
            item.put("mimeType", spec.mimeType());
            resourceList.add(item);
        }
        for (Resource res : resources.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uri", res.uri);
            item.put("name", res.name);
            item.put("description", res.description);
            item.put("mimeType", res.mimeType);
            if (res.category != null) {
                item.put("category", res.category);
            }
            resourceList.add(item);
        }
        resourceList.sort((a, b) -> String.valueOf(a.get("uri")).compareTo(String.valueOf(b.get("uri"))));
        JSONObject payload = new JSONObject();
        payload.put("resources", resourceList);
        return payload;
    }

    private JSONObject resourceTemplatesPayload() {
        JSONObject payload = new JSONObject();
        payload.put("resourceTemplates", toolkit.resourceTemplates());
        return payload;
    }

    /**
     * {@code resources/read}。
     *
     * <p>返回规范要求的 {@code contents} <b>数组</b>（含 uri/mimeType/text），
     * 而不是旧实现那样直接回一个裸值——裸值不符合 content-block 形状，
     * 客户端按规范解析会失败。</p>
     */
    private JSONObject readResource(JSONObject params) throws Exception {
        String uri = McpProtocol.requireString(params, "uri");

        JSONObject body;
        String mimeType = "application/json";
        // 先查已注册（配置/管理端点注册）的资源，再回退到内置命名空间。
        // 顺序不能反过来：内置读取器只认识 slackerdb:// 下的固定几个 URI，
        // 先按前缀路由会让"配置里声明了 slackerdb:// 资源"这种情况在列表里可见、
        // 一读却报 Unknown resource URI。
        Resource res = resources.get(uri);
        if (res != null) {
            mimeType = res.mimeType == null ? "text/plain" : res.mimeType;
            body = new JSONObject();
            body.put("value", res.contents);
        } else if (uri.startsWith("slackerdb://")) {
            body = toolkit.readResource(uri);
        } else {
            // 规范把"资源不存在"归到 -32602（旧的 -32002 已不再用于此）
            throw new McpProtocol.McpParamException("Resource not found: " + uri);
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", uri);
        content.put("mimeType", mimeType);
        content.put("text", body.toJSONString());

        JSONObject payload = new JSONObject();
        payload.put("contents", List.of(content));
        return wrap(McpProtocol.complete(
                McpProtocol.withCache(payload, resourcesTtlMs, McpProtocol.SCOPE_PUBLIC)));
    }

    private JSONObject promptsListPayload() {
        JSONObject payload = new JSONObject();
        payload.put("prompts", toolkit.prompts());
        return payload;
    }

    private JSONObject getPrompt(JSONObject params) throws Exception {
        String name = McpProtocol.requireString(params, "name");
        JSONObject arguments = params.getJSONObject("arguments");
        String topic = arguments == null ? null : arguments.getString("topic");
        return wrap(McpProtocol.complete(toolkit.prompt(name, topic)));
    }

    private static JSONObject successEnvelope(Object id, JSONObject result) {
        JSONObject envelope = new JSONObject();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("result", result);
        return envelope;
    }

    private static JSONObject errorEnvelope(Object id, int code, String message) {
        JSONObject envelope = new JSONObject();
        envelope.put("jsonrpc", "2.0");
        if (id != null) {
            envelope.put("id", id);
        }
        envelope.put("error", McpProtocol.error(code, message));
        return envelope;
    }

    /** 管理面端点：注册/加载/保存/导出。协议面无状态，这些是管理员操作，与规范无关。 */
    private void registerMcpAdminRoutes() {
        // Register MCP Tool endpoint
        this.managementApp.unsafe.routes.post("/mcp/registerMCPTool", ctx -> {
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
                // 内置工具名是保留的：撞名会让 tools/list 出现两条同名工具，且调用会被内置实现截走
                if (isBuiltinTool(name)) {
                    ctx.result(JSON.toJSONString(Map.of(
                        "retCode", -1,
                        "retMsg", "Rejected. [" + name + "] is reserved by a built-in tool; choose another name."
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
        this.managementApp.unsafe.routes.post("/mcp/unregisterMCPTool", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/registerMCPResource", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/unregisterMCPResource", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/loadMCPTool", ctx -> {
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
                        if (isBuiltinTool(name)) {
                            logger.warn("[MCP] Skipping tool [{}]: the name is reserved by a built-in tool", name);
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
                    if (isBuiltinTool(name)) {
                        ctx.result(JSON.toJSONString(Map.of(
                            "retCode", -1,
                            "retMsg", "Rejected. [" + name + "] is reserved by a built-in tool; choose another name."
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
        this.managementApp.unsafe.routes.post("/mcp/saveMCPTool", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/dumpMCPTool", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/loadMCPResource", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/saveMCPSource", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/dumpMCPSource", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/registerMCPService", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/unregisterMCPService", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/loadMCPService", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/saveMCPService", ctx -> {
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
        this.managementApp.unsafe.routes.post("/mcp/dumpMCPService", ctx -> {
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

    }

    /**
     * WebSocket AI Chat 端点：仅在配置了 {@code mcp_llm_server} 时启用。
     *
     * <p>与规范无关——这是本服务端私有的 NL→服务路由通道（模型不写 SQL，
     * 只在已注册的数据服务里选择）。保留原样。</p>
     */
    private void registerAiChatRoute() {
        // WebSocket AI Chat endpoint - only enabled when mcp_llm_server is configured
        if (mcpLlmServer != null && !mcpLlmServer.trim().isEmpty()) {
            logger.info("[SERVER][AI CHAT    ] Chat server listening on websocket /aichat. ");
            logger.info("[SERVER][AI CHAT    ] Chat llm server: {}", mcpLlmServer);

            this.managementApp.unsafe.routes.ws("/aichat", ws -> {
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
            toolArray.add(toolDefinition(tool));
        }
        return toolArray;
    }

    /**
     * 把一个工具写成配置里的声明形状。
     *
     * <p><b>必须写回 {@code handler}</b>：saveMCPTool / dumpMCPTool 写出的文件会被再次当作
     * {@code mcp_config} 加载，漏掉 handler 就会让内置绑定在重载后退化成回显桩；
     * 同理这里写的是<b>声明时的 description</b>，不带展示用的回显桩提示语，
     * 否则每保存一次描述就多一截。</p>
     */
    private JSONObject toolDefinition(Tool tool) {
        JSONObject toolObj = new JSONObject();
        toolObj.put("name", tool.name);
        toolObj.put("description", tool.description);
        toolObj.put("inputSchema", tool.inputSchema);
        if (tool.builtinHandler != null) {
            toolObj.put("handler", "builtin:" + tool.builtinHandler);
        }
        if (tool.category != null) {
            toolObj.put("category", tool.category);
        }
        return toolObj;
    }

    /**
     * Group tool definitions by category.
     * @return Map where key is category (or "uncategorized" if null), value is JSON array of tools in that category.
     */
    private Map<String, JSONArray> generateToolDefinitionsByCategory() {
        Map<String, JSONArray> grouped = new HashMap<>();
        for (Tool tool : tools.values()) {
            String category = tool.category != null ? tool.category : "uncategorized";
            JSONArray categoryArray = grouped.computeIfAbsent(category, k -> new JSONArray());
            categoryArray.add(toolDefinition(tool));
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
        addTool(name, description, inputSchema, null, handler, category, null, false);
    }

    /**
     * 注册工具，可带 {@code outputSchema}。
     *
     * <p>绑定到内置实现的工具会带上内置实现的真实 outputSchema——
     * "声明"与"实际返回"必须一致，否则客户端按 schema 解析会失败。</p>
     */
    public void addTool(String name, String description, JSONObject inputSchema, JSONObject outputSchema,
                        Function<Map<String,Object>, Object> handler, String category) {
        addTool(name, description, inputSchema, outputSchema, handler, category, null, false);
    }

    /**
     * 注册工具的完整入口。
     *
     * @param builtinHandler 绑定的内置实现名（如 {@code run_sql}），无绑定传 {@code null}；
     *                       保存/导出时据此写回 {@code "handler": "builtin:<name>"}，
     *                       使配置文件的保存-重载闭环不丢语义。
     * @param echoStub       是否为"声明时未指定 handler"的回显桩，用于在展示时补提示语。
     * @return 是否注册成功；名字已被内置工具占用时拒绝（内置名是保留的）
     */
    private boolean addTool(String name, String description, JSONObject inputSchema, JSONObject outputSchema,
                            Function<Map<String,Object>, Object> handler, String category,
                            String builtinHandler, boolean echoStub) {
        // 兜底：tools/list 是"内置工具 + 外部工具"两段拼接，外部工具撞上内置名会出现两条同名
        // 工具（调用还会被内置实现截走）。各入口已显式拒绝，这里再挡一次，防止新增调用点漏检。
        if (isBuiltinTool(name)) {
            logger.error("[SERVER][MCP    ] refusing to register [{}]: the name is reserved by a built-in tool",
                    name);
            return false;
        }
        tools.put(name, new Tool(name, description, inputSchema, outputSchema, handler, category,
                builtinHandler, echoStub));
        return true;
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
        // 会话 handle 持有连接池连接：必须显式释放，否则连接要等池的生命周期淘汰
        try {
            toolkit.shutdown();
        } catch (Exception e) {
            logger.warn("[SERVER][MCP] failed to close tool sessions", e);
        }
        sessionReaper.shutdownNow();
        // 心跳线程是非 daemon 的，不关会挂住 JVM 退出
        heartbeatExecutor.shutdownNow();
    }

    /**
     * 注入已注册数据服务的读取器（来自 {@link APIService}）。
     *
     * <p>刻意<b>不</b>在 MCP 侧再建一份服务注册表：语义层只有一份真相，
     * MCP 只是它的另一个视图。这正是"给 agent 开门，不新增第二条执行路径"的落地。</p>
     */
    public void setServiceSupplier(java.util.function.Supplier<List<Map<String, Object>>> supplier) {
        toolkit.setServicesSupplier(supplier);
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

    /** 无 handler 的工具在 tools/list 里必须自曝其短，而不是伪装成能干活。 */
    private static final String ECHO_STUB_NOTE = " (no handler configured: this tool only echoes its arguments.)";

    /**
     * 一个已注册的工具。
     *
     * <p>{@code builtinHandler} 记录"这个工具挂在哪个内置实现上"（如 {@code run_sql}），
     * 而不是只持有一个 lambda：saveMCPTool / dumpMCPTool 要把绑定关系原样写回配置文件，
     * 否则保存一次再重启，内置绑定就退化成回显桩了——lambda 本身无法序列化。</p>
     *
     * <p>{@code echoStub} 标记"声明时没有 handler"的条目：这类工具的"只会回显入参"提示在
     * <b>展示时</b>拼接（见 {@link #presentedDescription()}），不写进 description 本身，
     * 因此反复 save/load 不会让描述越滚越长。</p>
     */
    private record Tool(String name, String description, JSONObject inputSchema, JSONObject outputSchema,
                        Function<Map<String, Object>, Object> handler, String category,
                        String builtinHandler, boolean echoStub) {

        /** 面向客户端的描述：没挂 handler 的工具必须自曝其短。 */
        String presentedDescription() {
            return echoStub ? description + ECHO_STUB_NOTE : description;
        }
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
