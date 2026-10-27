package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import org.slackerdb.dbproxy.BuildInfo;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.instance.BackendView;
import org.slackerdb.dbproxy.instance.InstanceController;
import org.slackerdb.dbproxy.instance.InstanceException;
import org.slackerdb.dbproxy.instance.InstanceSpec;
import org.slackerdb.dbproxy.instance.ObservedInstance;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 代理的管理口（{@code port_x}）。
 *
 * <p>路由分三层，注册顺序即优先级：</p>
 * <ol>
 *   <li>代理自身：{@code /status}、{@code /capabilities}；</li>
 *   <li>实例管理：{@code /admin/databases...}（list/create/start/shutdown/restart/destroy/logs）；</li>
 *   <li>透传：{@code /<alias>/**} → 该实例的管理口（{@link ManagementForwarder}）。</li>
 * </ol>
 */
public class ProxyInstanceX {
    private final Logger logger;
    private final ProxyInstance proxyInstance;
    private Javalin managementApp = null;

    /** 进程启动时间，供 {@code /status} 计算运行时长。 */
    private final long startedAt = System.currentTimeMillis();
    /** 管理口 / pgwire 口（{@code start()} 时记录，供能力声明使用）。 */
    private int managementPort = -1;
    private int pgPort = -1;

    public ProxyInstanceX(Logger logger, ProxyInstance proxyInstance) {
        this.logger = logger;
        this.proxyInstance = proxyInstance;
    }

    /**
     * 前缀转发映射：{@code "/alias" → "http://host:port"}。
     *
     * <p>托管实例的映射由扫描结果实时推导，不写这里；这张表只保留"外部登记"的旧通道
     * （测试与将来的手工登记），保持既有行为不变。</p>
     */
    public ConcurrentHashMap<String, String> forwarderPathMappings = new ConcurrentHashMap<>();

    private String getClientIp(Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        return (xff != null && !xff.isEmpty())
                ? xff.split(",")[0].trim()
                : ctx.ip();
    }

    // ------------------------------------------------------------------ 状态 / 能力

    private List<BackendView> safeBackends(boolean fresh) {
        try {
            return proxyInstance.listBackends(fresh);
        } catch (RuntimeException runtimeException) {
            logger.warn("[PROXY][INSTANCE   ] Failed to list backends: {}",
                    runtimeException.getMessage());
            return List.of();
        }
    }

    private JSONObject instanceJson(BackendView backend) {
        JSONObject json = new JSONObject();
        json.put("alias", backend.alias());
        json.put("source", backend.source().wireName());
        json.put("manageable", backend.manageable());
        json.put("name", backend.dataName());
        json.put("state", backend.state().name());
        json.put("pid", backend.pid());
        json.put("pgPort", backend.pgPort());
        json.put("mgmtPort", backend.mgmtPort());
        json.put("health", backend.healthText());
        json.put("uptimeSeconds", backend.uptimeSeconds());
        json.put("error", backend.error());
        json.put("dir", backend.dir());
        json.put("target", backend.target());
        return json;
    }

    private JSONObject instanceJson(ObservedInstance instance) {
        return instanceJson(BackendView.management(instance));
    }

    private JSONObject buildStatusJson() {
        JSONObject status = new JSONObject();
        status.put("role", "proxy");
        status.put("version", readVersion());
        status.put("pid", ProcessHandle.current().pid());
        status.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000L);
        status.put("managementPort", managementPort);
        status.put("pgPort", pgPort);
        status.put("instanceManagementEnabled", proxyInstance.isInstanceManagementEnabled());

        JSONArray backends = new JSONArray();
        for (Map.Entry<String, String> entry : forwarderPathMappings.entrySet()) {
            JSONObject backend = new JSONObject();
            backend.put("alias", entry.getKey());
            backend.put("target", entry.getValue());
            backend.put("state", "UNKNOWN");
            backends.add(backend);
        }
        status.put("backends", backends);
        status.put("backendCount", backends.size());

        JSONArray databases = new JSONArray();
        for (BackendView backend : safeBackends(false)) {
            databases.add(instanceJson(backend));
        }
        status.put("databases", databases);
        return status;
    }

    private JSONObject buildCapabilitiesJson() {
        JSONObject capabilities = new JSONObject();
        capabilities.put("apiVersion", org.slackerdb.common.api.ApiVersion.MANAGEMENT);

        JSONObject proxy = new JSONObject();
        proxy.put("version", readVersion());
        proxy.put("pid", ProcessHandle.current().pid());
        capabilities.put("proxy", proxy);

        JSONObject ports = new JSONObject();
        ports.put("pg", pgPort);
        ports.put("management", managementPort);
        ports.put("managementEnabled", true);
        capabilities.put("ports", ports);

        JSONObject forwarding = new JSONObject();
        forwarding.put("enabled", true);
        forwarding.put("prefixMode", "alias");
        capabilities.put("managementForwarding", forwarding);

        JSONObject provisioning = new JSONObject();
        provisioning.put("enabled", proxyInstance.isInstanceManagementEnabled());
        provisioning.put("mode", "process");
        provisioning.put("adminBase", "/admin");
        provisioning.put("features", List.of("list", "detail", "create", "start", "shutdown",
                "restart", "destroy", "logs"));
        // 客户端据此知道状态里会出现哪些来源；external 不可治理
        provisioning.put("backendSources", List.of("management", "external"));
        capabilities.put("provisioning", provisioning);

        JSONObject contracts = new JSONObject();
        contracts.put("managementApi", org.slackerdb.common.api.ApiVersion.MANAGEMENT);
        capabilities.put("contracts", contracts);
        return capabilities;
    }

    private String readVersion() {
        return BuildInfo.version();
    }

    // ------------------------------------------------------------------ 管理 API

    private InstanceController controller() {
        InstanceController controller = proxyInstance.getInstanceController();
        if (controller == null) {
            throw new InstanceException(InstanceException.UNAVAILABLE,
                    "Instance management is not enabled. Set data_dir in the proxy configuration.");
        }
        return controller;
    }

    private void admin(Context ctx, Supplier<Object> action) {
        try {
            ctx.json(action.get());
        } catch (InstanceException instanceException) {
            JSONObject body = new JSONObject();
            body.put("retCode", instanceException.status());
            body.put("retMsg", instanceException.getMessage());
            ctx.status(instanceException.status()).json(body);
        } catch (RuntimeException runtimeException) {
            logger.error("[PROXY][INSTANCE   ] Admin request failed: {} {}",
                    ctx.method(), ctx.path(), runtimeException);
            JSONObject body = new JSONObject();
            body.put("retCode", 500);
            body.put("retMsg", "Internal error: " + runtimeException.getMessage());
            ctx.status(500).json(body);
        }
    }

    private void registerAdminRoutes() {
        var routes = this.managementApp.unsafe.routes;

        routes.get("/admin/databases", ctx -> {
            try {
                boolean probe = !"false".equalsIgnoreCase(ctx.queryParam("probe"));

                JSONObject body = new JSONObject();
                body.put("instanceManagementEnabled", proxyInstance.isInstanceManagementEnabled());
                JSONObject storage = new JSONObject();
                storage.put("dataDir", proxyInstance.getInstanceDataDir() == null
                        ? null : proxyInstance.getInstanceDataDir().toString());
                body.put("storage", storage);
                JSONArray databases = new JSONArray();
                for (BackendView backend : safeBackends(probe)) {
                    databases.add(instanceJson(backend));
                }
                body.put("databases", databases);
                ctx.json(body);
            } catch (RuntimeException runtimeException) {
                logger.error("[PROXY][INSTANCE   ] Failed to list instances.", runtimeException);
                JSONObject body = new JSONObject();
                body.put("retCode", 500);
                body.put("retMsg", "Failed to scan instances: " + runtimeException.getMessage());
                ctx.status(500).json(body);
            }
        });

        // 详情：内部实例（带解析后的 conf）与外部后端（只有地址）都能查到
        routes.get("/admin/databases/{alias}", ctx -> admin(ctx, () -> {
            String alias = ctx.pathParam("alias");
            ObservedInstance managed = proxyInstance.findManaged(alias);
            if (managed != null) {
                JSONObject json = instanceJson(managed);
                json.put("conf", managed.conf() == null ? null : managed.conf().asLowercaseMap());
                return json;
            }
            PostgresProxyTarget external = proxyInstance.findExternal(alias);
            if (external != null) {
                return instanceJson(BackendView.external(alias, external.host, external.port,
                        external.portX, external.database));
            }
            throw new InstanceException(InstanceException.NOT_FOUND,
                    "Database [" + alias + "] does not exist.");
        }));

        routes.get("/admin/databases/{alias}/logs", ctx -> {
            try {
                String alias = ctx.pathParam("alias");
                if (proxyInstance.isExternal(alias)) {
                    throw new InstanceException(InstanceException.CONFLICT,
                            externalOnlyMessage(alias, "logs"));
                }
                int lines = 200;
                String rawLines = ctx.queryParam("lines");
                if (rawLines != null && !rawLines.isBlank()) {
                    try {
                        lines = Integer.parseInt(rawLines.trim());
                    } catch (NumberFormatException nfe) {
                        throw new InstanceException(InstanceException.BAD_REQUEST,
                                "lines must be an integer.");
                    }
                }
                String text = controller().logs(alias, lines);
                ctx.contentType("text/plain; charset=utf-8").result(text);
            } catch (InstanceException instanceException) {
                JSONObject body = new JSONObject();
                body.put("retCode", instanceException.status());
                body.put("retMsg", instanceException.getMessage());
                ctx.status(instanceException.status()).json(body);
            }
        });

        routes.post("/admin/databases", ctx -> admin(ctx, () -> {
            JSONObject request;
            try {
                request = JSONObject.parseObject(ctx.body());
            } catch (RuntimeException parseError) {
                throw new InstanceException(InstanceException.BAD_REQUEST,
                        "Invalid JSON body: " + parseError.getMessage());
            }
            if (request == null || request.getString("alias") == null) {
                throw new InstanceException(InstanceException.BAD_REQUEST, "alias is required.");
            }

            Map<String, String> overrides = new LinkedHashMap<>();
            JSONObject conf = request.getJSONObject("conf");
            if (conf != null) {
                conf.forEach((key, value) -> overrides.put(key, value == null ? "" : String.valueOf(value)));
            }
            boolean start = !request.containsKey("start") || Boolean.TRUE.equals(request.getBoolean("start"));

            ObservedInstance created = controller().create(new InstanceSpec(
                    request.getString("alias"),
                    request.getString("name"),
                    start,
                    overrides));

            // 内部实例优先：同名外部注册必须立刻让位
            proxyInstance.pruneShadowedExternal(created.alias());

            JSONObject json = instanceJson(created);
            json.put("conf", created.conf() == null ? null : created.conf().asLowercaseMap());
            ctx.status(201);
            return json;
        }));

        routes.post("/admin/databases/{alias}/start",
                ctx -> handleLifecycle(ctx, "start"));
        routes.post("/admin/databases/{alias}/shutdown",
                ctx -> handleLifecycle(ctx, "shutdown"));
        routes.post("/admin/databases/{alias}/restart",
                ctx -> handleLifecycle(ctx, "restart"));

        routes.delete("/admin/databases/{alias}", ctx -> admin(ctx, () -> {
            String alias = ctx.pathParam("alias");
            if (proxyInstance.isExternal(alias)) {
                throw new InstanceException(InstanceException.CONFLICT,
                        externalOnlyMessage(alias, "destroy"));
            }
            controller().destroy(alias);
            JSONObject body = new JSONObject();
            body.put("alias", alias);
            body.put("destroyed", true);
            return body;
        }));
    }

    /** 外部后端只转发、不可治理；所有生命周期入口都用这句话拒绝。 */
    private static String externalOnlyMessage(String alias, String action) {
        return "Backend [" + alias + "] is external (registered via multicast) and has no local instance "
                + "directory. Only data forwarding is available; [" + action + "] is not supported.";
    }

    private void handleLifecycle(Context ctx, String action) {
        admin(ctx, () -> {
            String alias = ctx.pathParam("alias");
            if (proxyInstance.isExternal(alias)) {
                throw new InstanceException(InstanceException.CONFLICT,
                        externalOnlyMessage(alias, action));
            }
            InstanceController controller = controller();
            ObservedInstance observed = switch (action) {
                case "start" -> controller.start(alias);
                case "shutdown" -> controller.shutdown(alias);
                case "restart" -> controller.restart(alias);
                default -> throw new InstanceException(InstanceException.BAD_REQUEST,
                        "Unknown lifecycle action [" + action + "].");
            };
            JSONObject json = instanceJson(observed);
            json.put("conf", observed.conf() == null ? null : observed.conf().asLowercaseMap());
            return json;
        });
    }

    // ------------------------------------------------------------------ 启动

    public void start(ServerConfiguration serverConfiguration) {
        Logger javalinLogger = (Logger) LoggerFactory.getLogger("io.javalin.Javalin");
        Logger jettyLogger = (Logger) LoggerFactory.getLogger("org.eclipse.jetty");

        ClassPathResource page404Resource = new ClassPathResource("web/404.html");

        if (!this.logger.getLevel().equals(Level.TRACE)) {
            javalinLogger.setLevel(Level.OFF);
            jettyLogger.setLevel(Level.OFF);
        }
        this.managementApp = Javalin
                .create(config -> {
                    config.staticFiles.add(staticFiles -> {
                        staticFiles.hostedPath = "/web";
                        staticFiles.directory = "/web";
                        staticFiles.location = io.javalin.http.staticfiles.Location.CLASSPATH;
                    });
                    config.bundledPlugins.enableCors(cors -> cors.addRule(corsConfig -> corsConfig.anyHost()));
                })
                .start(serverConfiguration.getBindHost(), serverConfiguration.getPortX());

        this.managementApp.unsafe.routes.before(ctx ->
                ctx.attribute("startTime", System.currentTimeMillis()));

        // 自身状态与能力：必须在通配转发之前注册，否则会被 /{alias}/** 吞掉
        this.managementPort = serverConfiguration.getPortX();
        this.pgPort = serverConfiguration.getPort();
        this.managementApp.unsafe.routes.get("/status", ctx -> ctx.json(buildStatusJson()));
        this.managementApp.unsafe.routes.get("/capabilities", ctx -> ctx.json(buildCapabilitiesJson()));

        registerAdminRoutes();

        // WebSocket 升级请求走不了 HTTP 通配路由，单独挂一条双向管道（当前只有 /aichat）
        WebSocketForwarder.register(proxyInstance, this.managementApp);

        // 透传：所有方法走同一个实现，避免 5 段复制粘贴带来的行为漂移
        for (HandlerType type : List.of(HandlerType.GET, HandlerType.POST, HandlerType.PUT,
                HandlerType.PATCH, HandlerType.DELETE, HandlerType.HEAD, HandlerType.OPTIONS)) {
            this.managementApp.unsafe.routes.addHttpHandler(type, "/*",
                    ctx -> ManagementForwarder.handle(proxyInstance, ctx));
        }

        this.managementApp.unsafe.routes.after(ctx -> {
            Long startTime = ctx.attribute("startTime");
            long duration = -1;
            if (startTime != null) {
                duration = System.currentTimeMillis() - startTime;
            }
            logger.trace("Response: {} {} from {} - Status: {} - Time: {}ms",
                    ctx.method(), ctx.path(), this.getClientIp(ctx), ctx.status(), duration);
        });

        // 404 兜底页面。注意：转发层会主动写 JSON 404，这里不能覆盖已有的响应体
        // （Javalin 的 error handler 对 handler 主动设置的 404 也会触发）。
        this.managementApp.unsafe.routes.error(404, ctx -> {
            String existing = ctx.result();
            if (existing != null && !existing.isEmpty()) {
                return;
            }
            ctx.html(readResource(page404Resource));
        });

        this.managementApp.unsafe.routes.exception(Exception.class, (e, ctx) -> {
            logger.error("Error occurred while processing request: {} {} - {}",
                    ctx.method(), ctx.path(), e.getMessage());
            ctx.status(500).result("Internal Server Error");
        });

        logger.info("[PROXY][STARTUP    ] Management server listening on {}:{}.",
                serverConfiguration.getBindHost(), serverConfiguration.getPortX());
        logger.info("[PROXY][STARTUP    ] Instance management is {}.",
                proxyInstance.isInstanceManagementEnabled() ? "enabled" : "disabled (data_dir is not set)");
    }

    private static String readResource(ClassPathResource resource) {
        try {
            return Files.readString(Path.of(resource.getURI()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<html><body><h1>404 Not Found</h1></body></html>";
        }
    }

    public void stop() {
        if (this.managementApp != null) {
            this.managementApp.stop();
            this.managementApp = null;
        }
    }
}
