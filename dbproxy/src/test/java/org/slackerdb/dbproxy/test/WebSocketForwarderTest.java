package org.slackerdb.dbproxy.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.server.ProxyInstance;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 转发测试：用真实 Javalin 冒充实例的 {@code /aichat}。
 *
 * <p>锁住三件事：握手能穿过代理、文本帧双向转发、实例未运行时以 1011 拒绝。</p>
 */
@Timeout(120)
class WebSocketForwarderTest {

    @TempDir
    static Path root;

    private static Javalin backend;
    private static ProxyInstance proxy;
    private static int proxyPortX;
    private static int backendPort;

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void init() throws Exception {
        backendPort = freePort();
        backend = Javalin.create();
        backend.unsafe.routes.get("/health", ctx -> ctx.json(Map.of("status", "UP")));
        backend.unsafe.routes.ws("/aichat", ws -> {
            ws.onConnect(ctx -> ctx.send("welcome"));
            ws.onMessage(ctx -> ctx.send("echo:" + ctx.message()));
        });
        backend.start("127.0.0.1", backendPort);

        writeInstance("demo", backendPort);
        writeInstance("stopped", freePort());

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData_Dir(root.toString());
        proxy = new ProxyInstance(configuration);
        proxy.start();
        proxyPortX = configuration.getPortX();

        awaitRunning();
    }

    @AfterAll
    static void tearDown() {
        if (proxy != null) {
            proxy.stop();
        }
        if (backend != null) {
            backend.stop();
        }
    }

    @Test
    @DisplayName("ws: text frames are forwarded in both directions")
    void forwardsTextFrames() throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocket socket = connect("/demo/aichat", messages, new AtomicInteger(), new CountDownLatch(1));

        String welcome = messages.poll(5, TimeUnit.SECONDS);
        assertEquals("welcome", welcome, "the backend's greeting must reach the client");

        socket.sendText("hello", true).join();
        String echo = messages.poll(5, TimeUnit.SECONDS);
        assertEquals("echo:hello", echo);

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    @DisplayName("ws: a stopped instance is rejected with close code 1011")
    void stoppedInstanceIsRejected() throws Exception {
        AtomicInteger closeCode = new AtomicInteger(-1);
        CountDownLatch closed = new CountDownLatch(1);

        connect("/stopped/aichat", new LinkedBlockingQueue<>(), closeCode, closed);

        assertTrue(closed.await(5, TimeUnit.SECONDS), "the proxy must close the websocket");
        assertEquals(1011, closeCode.get(), "close code must tell the client the database is not running");
    }

    // ------------------------------------------------------------------ helpers

    private static WebSocket connect(String path, BlockingQueue<String> messages,
                                     AtomicInteger closeCode, CountDownLatch closed) throws Exception {
        return CLIENT.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + proxyPortX + path), new WebSocket.Listener() {
                    private final AtomicBoolean open = new AtomicBoolean(false);

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        open.set(true);
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        messages.add(data.toString());
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closeCode.set(statusCode);
                        closed.countDown();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        closed.countDown();
                    }
                })
                .get(10, TimeUnit.SECONDS);
    }

    private static void awaitRunning() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = CLIENT.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxyPortX + "/admin/databases"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JSONObject body = JSON.parseObject(response.body());
                    boolean running = body.getJSONArray("databases").stream()
                            .map(item -> (JSONObject) item)
                            .anyMatch(item -> "demo".equals(item.getString("alias"))
                                    && "RUNNING".equals(item.getString("state")));
                    if (running) {
                        return;
                    }
                }
            } catch (Exception ignored) {
                // not ready yet
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("proxy/instance did not become ready");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void writeInstance(String alias, int mgmtPort) throws IOException {
        Path dir = root.resolve(alias);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("instance.conf"), """
                data=%s
                data_dir=data
                port=1
                port_x=%d
                pid=instance.pid
                log=logs/server.log
                bind=127.0.0.1
                """.formatted(alias, mgmtPort), StandardCharsets.UTF_8);
    }
}
