package org.slackerdb.dbcli.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientTest {

    private HttpServer server;
    private String base;
    private String lastMethod;
    private String lastBody;
    private final Map<String, String> lastHeaders = new LinkedHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200,
                Map.of("Content-Type", "application/json"), "{\"status\":\"ok\"}"));
        server.createContext("/head", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/echo", exchange -> {
            lastMethod = exchange.getRequestMethod();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastHeaders.clear();
            exchange.getRequestHeaders().forEach((name, values) ->
                    lastHeaders.put(name.toLowerCase(), String.join(",", values)));
            respond(exchange, 200, Map.of(), "echo");
        });
        server.createContext("/missing", exchange -> respond(exchange, 404,
                Map.of("Content-Type", "text/plain"), "not found"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, Map.of(), "slow");
        });
        server.createContext("/gbk", exchange -> {
            byte[] body = "中文".getBytes(Charset.forName("GBK"));
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=GBK");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/gzip", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            byte[] body = "compressed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("GET 返回状态、头与响应体")
    void sendsGet() throws Exception {
        try (ApiClient client = new ApiClient()) {
            ApiResponse response = client.send(parse("GET " + base + "/ok"), () -> false);

            assertEquals(200, response.status());
            assertEquals("{\"status\":\"ok\"}", response.body());
            assertEquals("application/json", response.header("Content-Type"));
            assertNull(response.error());
        }
    }

    @Test
    @DisplayName("POST 发送方法、头与精确请求体")
    void sendsPost() throws Exception {
        try (ApiClient client = new ApiClient()) {
            ApiResponse response = client.send(parse(
                    "POST " + base + "/echo header='Content-Type: application/json' "
                            + "body='{\"a\":1}'"), () -> false);

            assertEquals(200, response.status());
            assertEquals("POST", lastMethod);
            assertEquals("{\"a\":1}", lastBody);
            assertEquals("application/json", lastHeaders.get("content-type"));
            assertEquals("dbcli", lastHeaders.get("user-agent"));
            assertEquals("identity", lastHeaders.get("accept-encoding"));
        }
    }

    @Test
    @DisplayName("HEAD 只取状态/头，不读取响应体")
    void sendsHead() throws Exception {
        try (ApiClient client = new ApiClient()) {
            ApiResponse response = client.send(parse("HEAD " + base + "/head"), () -> false);

            assertEquals(200, response.status());
            assertEquals("", response.body());
        }
    }

    @Test
    @DisplayName("默认跟随 302；follow=false 返回原始 302")
    void followsRedirectsByDefault() throws Exception {
        try (ApiClient client = new ApiClient()) {
            ApiResponse followed = client.send(parse("GET " + base + "/redirect"), () -> false);
            assertEquals(200, followed.status());
            assertEquals("{\"status\":\"ok\"}", followed.body());

            ApiResponse notFollowed = client.send(parse(
                    "GET " + base + "/redirect follow=false"), () -> false);
            assertEquals(302, notFollowed.status());
            assertEquals("/ok", notFollowed.header("Location"));
        }
    }

    @Test
    @DisplayName("超时是请求级错误")
    void timeoutIsAnError() {
        try (ApiClient client = new ApiClient()) {
            ApiException error = assertThrows(ApiException.class,
                    () -> client.send(parse("GET " + base + "/slow timeout=1"), () -> false));
            assertNotNull(error.getMessage());
        }
    }

    @Test
    @DisplayName("Content-Type 的 charset 决定解码；GBK 可用")
    void decodesCharset() throws Exception {
        try (ApiClient client = new ApiClient()) {
            ApiResponse response = client.send(parse("GET " + base + "/gbk"), () -> false);
            assertEquals("中文", response.body());
        }
    }

    @Test
    @DisplayName("不支持的 Content-Encoding 是请求级错误")
    void rejectsUnsupportedEncoding() {
        try (ApiClient client = new ApiClient()) {
            ApiException error = assertThrows(ApiException.class,
                    () -> client.send(parse("GET " + base + "/gzip"), () -> false));
            assertTrue(error.getMessage().contains("Unsupported Content-Encoding"), error.getMessage());
        }
    }

    private ApiRequest parse(String arguments) throws ApiParseException {
        return ApiRequestParser.parse(arguments, Path.of("."), name -> null);
    }

    private static void respond(HttpExchange exchange, int status, Map<String, String> headers,
                                String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
