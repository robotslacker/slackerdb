package org.slackerdb.dbcli.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dbcli 的 HTTP 客户端：每个 {@code ScriptContext} 一个实例，内部按需创建
 * “跟随重定向”和“不跟随重定向”两个 {@link HttpClient}。
 *
 * <p>请求用 {@code sendAsync} 发出，主线程按 50ms 轮询；作业停止信号到达时取消 future。
 * 响应体有 16 MiB 上限；解码字符集取 {@code Content-Type} 的 {@code charset=}，缺省 UTF-8。</p>
 */
public final class ApiClient implements AutoCloseable {

    /** 响应体上限。 */
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    /** 停止轮询分片（与 _HOST 同量级）。 */
    private static final long STOP_POLL_MILLIS = 50L;

    private static final Pattern CHARSET = Pattern.compile(
            "(?i)charset\\s*=\\s*\"?([^\";]+)\"?");

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "dbcli-api");
        thread.setDaemon(true);
        return thread;
    });

    private HttpClient followClient;
    private HttpClient noFollowClient;

    /**
     * 执行一次请求。
     *
     * @param request       已解析请求
     * @param stopRequested 作业停止信号
     * @throws ApiException          请求级失败
     * @throws ApiCancelledException 被停止信号/线程中断取消
     */
    public ApiResponse send(ApiRequest request, BooleanSupplier stopRequested)
            throws ApiException, ApiCancelledException {
        long startedAt = System.nanoTime();
        long deadline = request.timeout().isZero()
                ? Long.MAX_VALUE
                : startedAt + request.timeout().toNanos();
        HttpRequest httpRequest;
        try {
            httpRequest = buildRequest(request);
        } catch (IllegalArgumentException invalidRequest) {
            throw new ApiException(messageOf(invalidRequest), invalidRequest);
        }
        CompletableFuture<HttpResponse<InputStream>> future = client(request.follow())
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response = await(future, deadline, stopRequested);
        String body = readBody(response, deadline, stopRequested);
        return new ApiResponse(response.statusCode(), headersOf(response), body, null);
    }

    private HttpRequest buildRequest(ApiRequest request) throws ApiException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri());
        if (!request.timeout().isZero()) {
            builder.timeout(request.timeout());
        }
        byte[] body = request.body();
        if (body.length == 0) {
            builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(request.method(), HttpRequest.BodyPublishers.ofByteArray(body));
        }

        Set<String> present = new java.util.HashSet<>();
        for (ApiRequest.Header header : request.headers()) {
            present.add(header.name().toLowerCase(Locale.ROOT));
        }
        // 默认头只在用户没给时加；给出显式值时保留用户的值。
        if (!present.contains("user-agent")) {
            builder.setHeader("User-Agent", "dbcli");
        }
        if (!present.contains("accept-encoding")) {
            builder.setHeader("Accept-Encoding", "identity");
        }
        for (ApiRequest.Header header : request.headers()) {
            try {
                builder.header(header.name(), header.value());
            } catch (IllegalArgumentException invalidHeader) {
                throw new ApiException("Header [" + header.name()
                        + "] is managed by the HTTP client.", invalidHeader);
            }
        }
        return builder.build();
    }

    private HttpResponse<InputStream> await(CompletableFuture<HttpResponse<InputStream>> future,
                                            long deadline, BooleanSupplier stopRequested)
            throws ApiException, ApiCancelledException {
        while (true) {
            if (stopRequested != null && stopRequested.getAsBoolean()) {
                future.cancel(true);
                throw new ApiCancelledException();
            }
            long waitMillis = STOP_POLL_MILLIS;
            if (deadline != Long.MAX_VALUE) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    future.cancel(true);
                    throw new ApiException("Request timed out.");
                }
                waitMillis = Math.min(waitMillis, Math.max(1L, remaining / 1_000_000L));
            }
            try {
                return future.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillWaiting) {
                // 继续轮询停止信号/截止时间
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new ApiCancelledException();
            } catch (ExecutionException failed) {
                throw new ApiException(messageOf(failed.getCause()), failed.getCause());
            }
        }
    }

    private String readBody(HttpResponse<InputStream> response, long deadline,
                            BooleanSupplier stopRequested)
            throws ApiException, ApiCancelledException {
        String encoding = firstHeader(response.headers(), "Content-Encoding").strip();
        if (!encoding.isBlank() && !"identity".equalsIgnoreCase(encoding)) {
            closeQuietly(response.body());
            throw new ApiException("Unsupported Content-Encoding [" + encoding + "].");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream stream = response.body()) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (stopRequested != null && stopRequested.getAsBoolean()) {
                    throw new ApiCancelledException();
                }
                if (deadline != Long.MAX_VALUE && System.nanoTime() > deadline) {
                    throw new ApiException("Request timed out.");
                }
                if (bytes.size() + read > MAX_BODY_BYTES) {
                    throw new ApiException("_API response body exceeds " + MAX_BODY_BYTES + " bytes.");
                }
                bytes.write(buffer, 0, read);
            }
        } catch (IOException readFailed) {
            throw new ApiException(messageOf(readFailed), readFailed);
        }
        String text = new String(bytes.toByteArray(), charsetOf(response.headers()));
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private synchronized HttpClient client(boolean follow) {
        if (follow) {
            if (followClient == null) {
                followClient = newClient(HttpClient.Redirect.NORMAL);
            }
            return followClient;
        }
        if (noFollowClient == null) {
            noFollowClient = newClient(HttpClient.Redirect.NEVER);
        }
        return noFollowClient;
    }

    private HttpClient newClient(HttpClient.Redirect redirect) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(redirect)
                .proxy(NoProxySelector.INSTANCE)
                .executor(executor)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    private static Map<String, List<String>> headersOf(HttpResponse<?> response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) ->
                headers.put(name, values == null ? List.of() : List.copyOf(values)));
        return headers;
    }

    private static Charset charsetOf(HttpHeaders headers) {
        String contentType = firstHeader(headers, "Content-Type");
        if (contentType.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        Matcher matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(matcher.group(1).strip());
        } catch (RuntimeException unsupported) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String firstHeader(HttpHeaders headers, String name) {
        return headers.firstValue(name).orElse("");
    }

    private static String messageOf(Throwable failure) {
        Throwable actual = failure;
        if (actual instanceof HttpTimeoutException) {
            return "Request timed out.";
        }
        String message = actual == null ? null : actual.getMessage();
        return message == null || message.isBlank()
                ? (actual == null ? "request failed" : actual.getClass().getSimpleName())
                : message;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
            // 已经在错误路径上，关闭失败不再覆盖真正原因
        }
    }

    /**
     * 不使用系统代理：避免 HTTP_PROXY/http.proxyHost 让同一脚本在不同机器上行为不同。
     */
    private static final class NoProxySelector extends ProxySelector {

        private static final NoProxySelector INSTANCE = new NoProxySelector();

        @Override
        public List<Proxy> select(URI uri) {
            return List.of();
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // 没有代理，不会走到这里
        }
    }

    @Override
    public synchronized void close() {
        if (followClient != null) {
            followClient.close();
            followClient = null;
        }
        if (noFollowClient != null) {
            noFollowClient.close();
            noFollowClient = null;
        }
        executor.shutdownNow();
    }
}
