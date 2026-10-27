package org.slackerdb.dbproxy.server;

import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 管理口的前缀转发：{@code /<alias>/**} → 实例管理口。
 *
 * <p>与旧实现的区别（都是"多实例 + dbDesk 走代理"暴露出来的真问题）：</p>
 * <ul>
 *   <li>保留 query string（旧实现直接丢了，{@code /logfile?lines=100} 会变成 {@code /logfile}）；</li>
 *   <li>请求体/响应体全部流式，不再把整个备份文件读进堆内存；</li>
 *   <li>全方法透传（含 HEAD/OPTIONS），不再每个方法复制一段；</li>
 *   <li>不再自动跟随 302（跟随会重发一次性请求体）；</li>
 *   <li>错误按 404/502/503/504 区分，并回 JSON 信封。</li>
 * </ul>
 */
public final class ManagementForwarder {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** RFC 7230 hop-by-hop 头 + 必须由本端重算的头。 */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade",
            "host", "content-length", "expect");

    /** 这些路径可能是大文件/长连接，不设整体超时（只保留 5s 建连超时）。 */
    private static final List<String> STREAMING_MARKERS = List.of(
            "/download", "/upload", "/logfile", "/viewlog", "/backup");

    private ManagementForwarder() {
    }

    public static void handle(ProxyInstance proxy, Context ctx) {
        String path = ctx.path();
        if (path == null || !path.startsWith("/") || path.length() <= 1) {
            jsonError(ctx, 404, "Nothing is served at [" + path + "]. Use /<database>/<path> or /admin/...", null);
            return;
        }

        int slash = path.indexOf('/', 1);
        String segment = slash == -1 ? path : path.substring(0, slash);
        String alias = segment.substring(1);
        String remainder = slash == -1 ? "" : path.substring(slash);

        ProxyInstance.ManagementTarget target = proxy.resolveManagementTarget(alias);
        if (target == null || (target.baseUrl() == null && target.state() == null)) {
            jsonError(ctx, 404, "Database [" + alias + "] does not exist.", null);
            return;
        }
        if (target.baseUrl() == null) {
            JSONObject details = new JSONObject();
            details.put("alias", alias);
            details.put("state", target.state() == null ? "UNKNOWN" : target.state().name());
            jsonError(ctx, 503, "Database [" + alias + "] is not running (state="
                    + (target.state() == null ? "UNKNOWN" : target.state().name()) + ").", details);
            return;
        }

        String targetUrl = target.baseUrl() + remainder;
        String query = ctx.queryString();
        if (query != null && !query.isEmpty()) {
            targetUrl = targetUrl + "?" + query;
        }

        try {
            forward(ctx, targetUrl);
        } catch (HttpTimeoutException timeoutException) {
            jsonError(ctx, 504, "Management request to database [" + alias + "] timed out.", null);
        } catch (IOException ioException) {
            jsonError(ctx, 502, "Management request to database [" + alias + "] failed: "
                    + ioException.getMessage(), null);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            jsonError(ctx, 502, "Management request to database [" + alias + "] was interrupted.", null);
        }
    }

    private static void forward(Context ctx, String targetUrl) throws IOException, InterruptedException {
        HandlerType method = ctx.method();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targetUrl))
                .method(method.name(), bodyPublisher(ctx, method));

        for (Map.Entry<String, String> header : ctx.headerMap().entrySet()) {
            if (HOP_BY_HOP.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            builder.header(header.getKey(), header.getValue());
        }
        if (!isStreamingPath(targetUrl)) {
            builder.timeout(DEFAULT_TIMEOUT);
        }

        HttpResponse<InputStream> response = CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

        ctx.status(response.statusCode());
        response.headers().map().forEach((name, values) -> {
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                ctx.res().addHeader(name, value);
            }
        });

        int status = response.statusCode();
        if (method == HandlerType.HEAD || status == 204 || status == 304) {
            response.body().close();
            return;
        }
        try (InputStream in = response.body();
             OutputStream out = ctx.res().getOutputStream()) {
            in.transferTo(out);
            out.flush();
        }
    }

    private static HttpRequest.BodyPublisher bodyPublisher(Context ctx, HandlerType method) {
        boolean mayHaveBody = method == HandlerType.POST || method == HandlerType.PUT
                || method == HandlerType.PATCH || method == HandlerType.DELETE;
        if (!mayHaveBody) {
            return HttpRequest.BodyPublishers.noBody();
        }
        long contentLength = ctx.req().getContentLengthLong();
        if (contentLength == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofInputStream(ctx::bodyInputStream);
    }

    private static boolean isStreamingPath(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String marker : STREAMING_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static void jsonError(Context ctx, int status, String message, JSONObject details) {
        JSONObject body = new JSONObject();
        body.put("retCode", status);
        body.put("retMsg", message);
        if (details != null) {
            body.putAll(details);
        }
        ctx.status(status).json(body);
    }
}
