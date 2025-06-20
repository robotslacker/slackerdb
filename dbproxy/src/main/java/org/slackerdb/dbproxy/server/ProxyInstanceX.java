package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.plugin.bundled.CorsPluginConfig;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class ProxyInstanceX {
    private final Logger logger;
    private Javalin managementApp = null;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ProxyInstanceX(Logger logger)
    {
        this.logger = logger;
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

    public static void forwardRequest(Context ctx, String targetBaseUrl) {
        try {
            // 构建目标URL
            String targetUrl = targetBaseUrl + ctx.path();
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
                            config.staticFiles.add("/web", Location.CLASSPATH);
                            // 支持跨域
                            config.bundledPlugins.enableCors(
                                    cors -> cors.addRule(CorsPluginConfig.CorsRule::anyHost));
                        }
                )
                .start(
                        serverConfiguration.getBindHost(),
                        serverConfiguration.getPortX()
                );

        // 需要在记录器之前添加的过滤器
        this.managementApp.before(ctx -> {
            // 设置请求开始时间作为属性
            ctx.attribute("startTime", System.currentTimeMillis());
        });

        // 处理转发请求
        this.managementApp.get(
                "/*",
                ctx ->
                {
                    int secondSlash = ctx.path().indexOf('/', 1);
                    String ctxPathPrefix = secondSlash == -1 ? ctx.path() : ctx.path().substring(0, secondSlash);
                    for (String alias : forwarderPathMappings.keySet()) {
                        if (ctxPathPrefix.equalsIgnoreCase(alias)) {
                            forwardRequest(ctx, forwarderPathMappings.get(alias));
                            return;
                        }
                    }
                    // 找不到按照404处理
                    ctx.html(Files.readString(Path.of(page404Resource.getURI())));
                }
        );
        this.managementApp.post(
                "/*",
                ctx ->
                {
                    int secondSlash = ctx.path().indexOf('/', 1);
                    String ctxPathPrefix = secondSlash == -1 ? ctx.path() : ctx.path().substring(0, secondSlash);
                    for (String alias : forwarderPathMappings.keySet()) {
                        if (ctxPathPrefix.equalsIgnoreCase(alias)) {
                            forwardRequest(ctx, forwarderPathMappings.get(alias));
                            return;
                        }
                    }
                    // 找不到按照404处理
                    ctx.html(Files.readString(Path.of(page404Resource.getURI())));
                }
        );
        this.managementApp.put(
                "/*",
                ctx ->
                {
                    int secondSlash = ctx.path().indexOf('/', 1);
                    String ctxPathPrefix = secondSlash == -1 ? ctx.path() : ctx.path().substring(0, secondSlash);
                    for (String alias : forwarderPathMappings.keySet()) {
                        if (ctxPathPrefix.equalsIgnoreCase(alias)) {
                            forwardRequest(ctx, forwarderPathMappings.get(alias));
                            return;
                        }
                    }
                    // 找不到按照404处理
                    ctx.html(Files.readString(Path.of(page404Resource.getURI())));
                }
        );
        this.managementApp.delete(
                "/*",
                ctx ->
                {
                    int secondSlash = ctx.path().indexOf('/', 1);
                    String ctxPathPrefix = secondSlash == -1 ? ctx.path() : ctx.path().substring(0, secondSlash);
                    for (String alias : forwarderPathMappings.keySet()) {
                        if (ctxPathPrefix.equalsIgnoreCase(alias)) {
                            forwardRequest(ctx, forwarderPathMappings.get(alias));
                            return;
                        }
                    }
                    // 找不到按照404处理
                    ctx.html(Files.readString(Path.of(page404Resource.getURI())));
                }
        );
        this.managementApp.patch(
                "/*",
                ctx ->
                {
                    int secondSlash = ctx.path().indexOf('/', 1);
                    String ctxPathPrefix = secondSlash == -1 ? ctx.path() : ctx.path().substring(0, secondSlash);
                    for (String alias : forwarderPathMappings.keySet()) {
                        if (ctxPathPrefix.equalsIgnoreCase(alias)) {
                            forwardRequest(ctx, forwarderPathMappings.get(alias));
                            return;
                        }
                    }
                    // 找不到按照404处理
                    ctx.html(Files.readString(Path.of(page404Resource.getURI())));
                }
        );

        // 在请求结束后记录响应信息
        this.managementApp.after(ctx -> {
            Long startTime = ctx.attribute("startTime");
            long duration = -1;
            if (startTime != null) {
                duration = System.currentTimeMillis() - startTime;
            }
            logger.trace("Response: {} {} from {} - Status: {} - Time: {}ms",
                    ctx.method(), ctx.path(), this.getClientIp(ctx), ctx.status(), duration);
        });

        // 自定义404假面
        this.managementApp.error(404, ctx -> ctx.html(Files.readString(Path.of(page404Resource.getURI()))));

        // 异常处理
        this.managementApp.exception(Exception.class, (e, ctx) -> {
            logger.error("Error occurred while processing request: {} {} - {}", ctx.method(), ctx.path(), e.getMessage());
            ctx.status(500).result("Internal Server Error");
        });

        logger.info("[SERVER][STARTUP    ] Management server listening on {}:{}.",
                serverConfiguration.getBindHost(), serverConfiguration.getPortX());
    }

    public void stop()
    {
        this.managementApp.stop();
    }
}
