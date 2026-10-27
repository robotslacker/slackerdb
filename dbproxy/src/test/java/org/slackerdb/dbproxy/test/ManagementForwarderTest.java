package org.slackerdb.dbproxy.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.server.ProxyInstance;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理口转发（P0）的端到端测试。
 *
 * <p>用真实的 {@link HttpServer} 冒充实例管理口，不启动 dbserver：这里要锁的是代理的转发语义
 * （query 保留、请求体透传、大响应流式、错误码），与实例本身无关。</p>
 */
class ManagementForwarderTest {

    private static final int BIG_BYTES = 4 * 1024 * 1024;

    @TempDir
    static Path root;

    private static HttpServer backend;
    private static ProxyInstance proxy;
    private static int proxyPortX;
    private static int backendPort;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @BeforeAll
    static void init() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
        backend.createContext("/status", exchange -> respond(exchange, 200, "ok"));
        backend.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JSONObject json = new JSONObject();
            json.put("method", exchange.getRequestMethod());
            json.put("query", exchange.getRequestURI().getRawQuery());
            json.put("length", body.length);
            respond(exchange, 200, json.toString());
        });
        backend.createContext("/big", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, BIG_BYTES);
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 'x');
            try (OutputStream out = exchange.getResponseBody()) {
                int remaining = BIG_BYTES;
                while (remaining > 0) {
                    int size = Math.min(chunk.length, remaining);
                    out.write(chunk, 0, size);
                    remaining -= size;
                }
            }
            exchange.close();
        });
        backend.start();
        backendPort = backend.getAddress().getPort();

        int closedPort = freePort();
        writeInstance("demo", backendPort);
        writeInstance("stopped", closedPort);

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData_Dir(root.toString());
        proxy = new ProxyInstance(configuration);
        proxy.start();
        proxyPortX = configuration.getPortX();

        awaitReady();
    }

    @AfterAll
    static void tearDown() {
        if (proxy != null) {
            proxy.stop();
        }
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    @DisplayName("forward: the query string survives the proxy (it used to be dropped)")
    void queryStringIsPreserved() throws Exception {
        HttpResponse<String> response = get("/demo/echo?a=1&b=two");

        assertEquals(200, response.statusCode());
        JSONObject body = JSON.parseObject(response.body());
        assertEquals("a=1&b=two", body.getString("query"));
    }

    @Test
    @DisplayName("forward: POST bodies are streamed through")
    void requestBodyIsForwarded() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/demo/echo?m=post"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("payload"))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JSONObject body = JSON.parseObject(response.body());
        assertEquals("POST", body.getString("method"));
        assertEquals(7, body.getIntValue("length"));
    }

    @Test
    @DisplayName("forward: large responses are streamed instead of buffered")
    void largeResponseIsStreamed() throws Exception {
        HttpResponse<byte[]> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(base() + "/demo/big")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertEquals(BIG_BYTES, response.body().length);
    }

    @Test
    @DisplayName("forward: HEAD is passed through without a body")
    void headIsSupported() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/demo/status"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    @DisplayName("forward: an unknown alias is a JSON 404, not an empty page")
    void unknownAliasIsNotFound() throws Exception {
        HttpResponse<String> response = get("/nope/status");

        assertEquals(404, response.statusCode());
        assertEquals(404, JSON.parseObject(response.body()).getIntValue("retCode"));
    }

    @Test
    @DisplayName("forward: a stopped instance is a JSON 503 that names its state")
    void stoppedInstanceIsUnavailable() throws Exception {
        HttpResponse<String> response = get("/stopped/status");

        assertEquals(503, response.statusCode());
        JSONObject body = JSON.parseObject(response.body());
        assertEquals(503, body.getIntValue("retCode"));
        assertEquals("STOPPED", body.getString("state"));
    }

    @Test
    @DisplayName("admin: /admin/databases lists observed state without any catalog file")
    void adminListExposesObservedState() throws Exception {
        HttpResponse<String> response = get("/admin/databases");

        assertEquals(200, response.statusCode());
        JSONObject body = JSON.parseObject(response.body());
        assertTrue(body.getBooleanValue("instanceManagementEnabled"));
        assertTrue(body.getJSONArray("databases").size() >= 2);
        assertNotNull(body.getJSONObject("storage").getString("dataDir"));

        boolean demoRunning = body.getJSONArray("databases").stream()
                .map(item -> (JSONObject) item)
                .anyMatch(item -> "demo".equals(item.getString("alias")) && "RUNNING".equals(item.getString("state")));
        boolean stopped = body.getJSONArray("databases").stream()
                .map(item -> (JSONObject) item)
                .anyMatch(item -> "stopped".equals(item.getString("alias")) && "STOPPED".equals(item.getString("state")));
        assertTrue(demoRunning, "demo should be observed as RUNNING through /health");
        assertTrue(stopped, "stopped should be observed as STOPPED");
    }

    @Test
    @DisplayName("capabilities: provisioning is declared so clients can discover create/destroy")
    void capabilitiesDeclareProvisioning() throws Exception {
        HttpResponse<String> response = get("/capabilities");

        assertEquals(200, response.statusCode());
        JSONObject body = JSON.parseObject(response.body());
        assertEquals("alias", body.getJSONObject("managementForwarding").getString("prefixMode"));
        JSONObject provisioning = body.getJSONObject("provisioning");
        assertNotNull(provisioning);
        assertTrue(provisioning.getBooleanValue("enabled"));
        assertEquals("process", provisioning.getString("mode"));
    }

    @Test
    @DisplayName("status: the proxy reports every database alongside its own state")
    void statusIncludesDatabases() throws Exception {
        HttpResponse<String> response = get("/status");

        assertEquals(200, response.statusCode());
        JSONObject body = JSON.parseObject(response.body());
        assertEquals("proxy", body.getString("role"));
        assertTrue(body.getBooleanValue("instanceManagementEnabled"));
        assertEquals(2, body.getJSONArray("databases").size());
    }

    // ------------------------------------------------------------------ helpers

    private static String base() {
        return "http://127.0.0.1:" + proxyPortX;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void awaitReady() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (get("/status").statusCode() == 200) {
                    // 首次扫描是异步的，等快照里出现两个实例
                    JSONObject body = JSON.parseObject(get("/admin/databases").body());
                    if (body.getJSONArray("databases").size() >= 2) {
                        return;
                    }
                }
            } catch (Exception ignored) {
                // 端口还没起来
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("proxy management endpoint did not become ready");
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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
