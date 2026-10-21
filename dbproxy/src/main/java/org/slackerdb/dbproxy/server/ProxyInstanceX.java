package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slackerdb.dbproxy.BuildInfo;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Version;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class ProxyInstanceX {
    private final Logger logger;
    private final ProxyInstance proxyInstance;
    private Javalin managementApp = null;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 进程启动时间，供 {@code /status} 计算运行时长。 */
    private final long startedAt = System.currentTimeMillis();
    /** 管理口 / pgwire 口（{@code start()} 时记录，供能力声明使用）。 */
    private int managementPort = -1;
    private int pgPort = -1;

    public ProxyInstanceX(
            Logger logger,
            ProxyInstance proxyInstance
    )
    {
        this.logger = logger;
        this.proxyInstance = proxyInstance;
    }

    public ConcurrentHashMap<String, String> forwarderPathMappings = new ConcurrentHashMap<>();

    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "upgrade"
    );

    private static String[] createHeadersString(Context ctx) {
        return ctx.headerMap().entrySet().stream()
                .filter(e -> !RESTRICTED_HEADERS.contains(e.getKey().toLowerCase()))
                .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
                .toArray(String[]::new);
    }

    static URI resolveRedirectUrl(URI originalUri, String location) {
        URI locationUri = URI.create(location);
        if (locationUri.isAbsolute()) {
            return locationUri;
        }
        // 保留原始查询参数
        return originalUri.resolve(location).normalize();
    }

    public static void forwardRequest(Context ctx, String targetBaseUrl, String prefixToRemove) {
        try {
            // 构建目标URL，去掉匹配的前缀
            String path = ctx.path();
            if (prefixToRemove != null && path.startsWith(prefixToRemove)) {
                path = path.substring(prefixToRemove.length());
                // 确保路径以/开头
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
            }
            String targetUrl = targetBaseUrl + path;
            URI originalUri = URI.create(targetUrl);

            HttpRequest.BodyPublisher bodyPublisher = ctx.body().isEmpty() ?
                    HttpRequest.BodyPublishers.noBody() :
                    HttpRequest.BodyPublishers.ofByteArray(ctx.bodyAsBytes());

            // 构建转发请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .method(ctx.method().toString(), bodyPublisher)
                    .headers(createHeadersString(ctx))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            // 发送请求并获取响应
            HttpResponse<byte[]> response = CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.statusCode() == 302) {
                String location = response.headers()
                        .firstValue("Location")
                        .orElseThrow();
                URI newUri = resolveRedirectUrl(originalUri, location);
                HttpRequest newRequest = HttpRequest.newBuilder()
                        .uri(newUri)
                        .method(ctx.method().toString(), bodyPublisher)
                        .headers(createHeadersString(ctx))
                        .timeout(Duration.ofSeconds(15))
                        .build();
                response = CLIENT.send(newRequest, HttpResponse.BodyHandlers.ofByteArray());
            }

            // 回传响应
            ctx.status(response.statusCode());

            response.headers().map().forEach((k, v) ->
                    ctx.header(k, String.join(",", v)));
            ctx.result(response.body());
        } catch (Exception e) {
            ctx.status(500).result("Forwarding error: " + e.getMessage());
        }
    }

    private String getClientIp(Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        return (xff != null && !xff.isEmpty())
                ? xff.split(",")[0].trim()
                : ctx.ip();
    }

    /**
     * 代理自身的状态。
     *
     * <p>过去代理只有一个 {@code /*} 前缀转发器，平台看不到"代理活着吗、它在转发给谁"。
     * 这里如实报告已有信息；**后端健康状态目前没有采集**（{@code state} 恒为 {@code UNKNOWN}），
     * 需要时再加探测（已在契约里标为后续项）。</p>
     */
    private JSONObject buildStatusJson() {
        JSONObject status = new JSONObject();
        status.put("role", "proxy");
        status.put("version", readVersion());
        status.put("pid", ProcessHandle.current().pid());
        status.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000L);
        status.put("managementPort", managementPort);
        status.put("pgPort", pgPort);

        JSONArray backends = new JSONArray();
        for (java.util.Map.Entry<String, String> entry : forwarderPathMappings.entrySet()) {
            JSONObject backend = new JSONObject();
            backend.put("alias", entry.getKey());
            backend.put("target", entry.getValue());
            backend.put("state", "UNKNOWN");
            backends.add(backend);
        }
        status.put("backends", backends);
        status.put("backendCount", backends.size());
        return status;
    }

    /**
     * 代理的能力声明（与 {@code dbserver} 用同一信封，客户端只需实现一次解析）。
     */
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

        // 前缀转发是代理的独有能力：客户端要"通过代理管多后端"时必须知道别名规则
        JSONObject forwarding = new JSONObject();
        forwarding.put("enabled", true);
        forwarding.put("prefixMode", "alias");
        capabilities.put("managementForwarding", forwarding);

        JSONObject contracts = new JSONObject();
        contracts.put("managementApi", org.slackerdb.common.api.ApiVersion.MANAGEMENT);
        capabilities.put("contracts", contracts);
        return capabilities;
    }

    /** 代理版本号（构建期生成的 {@code META-INF/build-info.properties}）；读不到时是 {@code unknown}。 */
    private String readVersion() {
        return BuildInfo.version();
    }

    public void start(ServerConfiguration serverConfiguration)
    {
        // 关闭Javalin, 如果不是在trace下
        Logger javalinLogger = (Logger) LoggerFactory.getLogger("io.javalin.Javalin");
        Logger jettyLogger = (Logger) LoggerFactory.getLogger("org.eclipse.jetty");

        ClassPathResource page404Resource = new ClassPathResource("web/404.html");

        if (!this.logger.getLevel().equals(Level.TRACE)) {
            javalinLogger.setLevel(Level.OFF);
            jettyLogger.setLevel(Level.OFF);
        }
        this.managementApp = Javalin
                .create(config->
                        {
                            // 添加静态文件
                            config.staticFiles.add(staticFiles -> {
                                staticFiles.hostedPath = "/web";
                                staticFiles.directory = "/web";
                                staticFiles.location = io.javalin.http.staticfiles.Location.CLASSPATH;
                            });
                            // 支持跨域
                            config.bundledPlugins.enableCors(
                                    cors -> cors.addRule(corsConfig -> {
                                        corsConfig.anyHost();
                                    }));
                        }
                )
                .start(
                        serverConfiguration.getBindHost(),
                        serverConfiguration.getPortX()
                );

        // 需要在记录器之前添加的过滤器
        this.managementApp.unsafe.routes.before(ctx -> {
            // 设置请求开始时间作为属性
            ctx.attribute("startTime", System.currentTimeMillis());
        });

        // 平台契约：代理自身的状态与能力。
        // 必须在下面的 /* 通配转发之前注册，否则会被前缀转发吞掉（见 ProxyApiTest 的断言）。
        this.managementPort = serverConfiguration.getPortX();
        this.pgPort = serverConfiguration.getPort();
        this.managementApp.unsafe.routes.get("/status", ctx -> ctx.json(buildStatusJson()));
        this.managementApp.unsafe.routes.get("/capabilities", ctx -> ctx.json(buildCapabilitiesJson()));

        // 处理转发请求
        this.managementApp.unsafe.routes.get(
                "/*",
                ctx ->
                {
                    int secondSlash = ctx.path().indexOf('/', 1);
                    String ctxPathPrefix = secondSlash == -1 ? ctx.path() : ctx.path().substring(0, secondSlash);
                    for (String alias : forwarderPathMappings.keySet()) {
                        if (ctxPathPrefix.equalsIgnoreCase(alias)) {
                            forwardRequest(ctx, forwarderPathMappings.get(alias), alias);
                            return;
                        }
                    }
                    // 找不到按照404处理
                    ctx.html(Files.readString(Path.of(page404Resource.getURI())));
                }
        );
        this.managementApp.unsafe.routes.post(
                "/*",
                ctx ->
                {
                    int secondSlash = ctx.path().indexOf('/', 1);
                    String ctxPathPrefix = secondSlash == -1 ? ctx.path() : ctx.path().substring(0, secondSlash);
                    for (String alias : forwarderPathMappings.keySet()) {
                        if (ctxPathPrefix.equalsIgnoreCase(alias)) {
                            forwardRequest(ctx, forwarderPathMappings.get(alias), alias);
                            return;
                        }
                    }
                    // 找不到按照404处理
                    ctx.html(Files.readString(Path.of(page404Resource.getURI())));
                }
        );
        this.managementApp.unsafe.routes.put(
                "/*",
                ctx ->
                {
                    int secondSlash = ctx.path().indexOf('/', 1);
                    String ctxPathPrefix = secondSlash == -1 ? ctx.path() : ctx.path().substring(0, secondSlash);
                    for (String alias : forwarderPathMappings.keySet()) {
                        if (ctxPathPrefix.equalsIgnoreCase(alias)) {
                            forwardRequest(ctx, forwarderPathMappings.get(alias), alias);
                            return;
                        }
                    }
                    // 找不到按照404处理
                    ctx.html(Files.readString(Path.of(page404Resource.getURI())));
                }
        );
        this.managementApp.unsafe.routes.delete(
                "/*",
                ctx ->
                {
                    int secondSlash = ctx.path().indexOf('/', 1);
                    String ctxPathPrefix = secondSlash == -1 ? ctx.path() : ctx.path().substring(0, secondSlash);
                    for (String alias : forwarderPathMappings.keySet()) {
                        if (ctxPathPrefix.equalsIgnoreCase(alias)) {
                            forwardRequest(ctx, forwarderPathMappings.get(alias), alias);
                            return;
                        }
                    }
                    // 找不到按照404处理
                    ctx.html(Files.readString(Path.of(page404Resource.getURI())));
                }
        );
        this.managementApp.unsafe.routes.patch(
                "/*",
                ctx ->
                {
                    int secondSlash = ctx.path().indexOf('/', 1);
                    String ctxPathPrefix = secondSlash == -1 ? ctx.path() : ctx.path().substring(0, secondSlash);
                    for (String alias : forwarderPathMappings.keySet()) {
                        if (ctxPathPrefix.equalsIgnoreCase(alias)) {
                            forwardRequest(ctx, forwarderPathMappings.get(alias), alias);
                            return;
                        }
                    }
                    // 找不到按照404处理
                    ctx.html(Files.readString(Path.of(page404Resource.getURI())));
                }
        );

        // 在请求结束后记录响应信息
        this.managementApp.unsafe.routes.after(ctx -> {
            Long startTime = ctx.attribute("startTime");
            long duration = -1;
            if (startTime != null) {
                duration = System.currentTimeMillis() - startTime;
            }
            logger.trace("Response: {} {} from {} - Status: {} - Time: {}ms",
                    ctx.method(), ctx.path(), this.getClientIp(ctx), ctx.status(), duration);
        });

        // 自定义404假面
        this.managementApp.unsafe.routes.error(404, ctx -> ctx.html(Files.readString(Path.of(page404Resource.getURI()))));

        // 异常处理
        this.managementApp.unsafe.routes.exception(Exception.class, (e, ctx) -> {
            logger.error("Error occurred while processing request: {} {} - {}", ctx.method(), ctx.path(), e.getMessage());
            ctx.status(500).result("Internal Server Error");
        });

        logger.info("[PROXY][STARTUP    ] Management server listening on {}:{}.",
                serverConfiguration.getBindHost(), serverConfiguration.getPortX());
    }

    public void stop()
    {
        this.managementApp.stop();
    }
}
