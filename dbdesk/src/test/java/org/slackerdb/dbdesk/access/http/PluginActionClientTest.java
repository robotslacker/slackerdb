package org.slackerdb.dbdesk.access.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件管理端点：HTTP 200 + retCode 的业务失败也不能当成功。
 */
class PluginActionClientTest {

    private static HttpServer server;
    private static ManagementClient client;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/plugin", PluginActionClientTest::handle);
        server.start();
        client = new ManagementClient("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(5));
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("load: 解析 pluginId，成功与业务失败都按 retCode")
    void loadParsesPluginId() throws Exception {
        PluginActionResult loaded = client.loadPlugin("demo.jar");
        assertTrue(loaded.success());
        assertEquals("demo", loaded.pluginId());
        assertEquals("Plugin loaded successfully", loaded.message());

        PluginActionResult duplicate = client.loadPlugin("duplicate.jar");
        assertFalse(duplicate.success());
        assertEquals("already loaded", duplicate.message());
    }

    @Test
    @DisplayName("unload/start/stop: 成功返回 retMsg")
    void lifecycleResults() throws Exception {
        assertTrue(client.unloadPlugin("demo").success());
        assertTrue(client.startPlugin("demo").success());
        assertTrue(client.stopPlugin("demo").success());
    }

    @Test
    @DisplayName("业务失败: HTTP 200 但 retCode=-1 时 success=false")
    void businessFailureIsNotSuccess() throws Exception {
        PluginActionResult missing = client.unloadPlugin("missing");
        assertFalse(missing.success());
        assertEquals("Plugin is not loaded", missing.message());
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.endsWith("/load")) {
            if (body.contains("duplicate")) {
                respond(exchange, -1, "already loaded", null);
            } else {
                respond(exchange, 0, "Plugin loaded successfully", "demo");
            }
            return;
        }
        if (body.contains("missing")) {
            respond(exchange, -1, "Plugin is not loaded", null);
            return;
        }
        if (path.endsWith("/unload")) {
            respond(exchange, 0, "Plugin unloaded successfully", null);
        } else if (path.endsWith("/start")) {
            respond(exchange, 0, "Plugin started successfully", null);
        } else if (path.endsWith("/stop")) {
            respond(exchange, 0, "Plugin stopped successfully", null);
        } else {
            respond(exchange, -1, "unknown endpoint", null);
        }
    }

    private static void respond(HttpExchange exchange, int retCode, String retMsg, String pluginId)
            throws IOException {
        StringBuilder json = new StringBuilder()
                .append("{\"retCode\":").append(retCode)
                .append(",\"retMsg\":\"").append(retMsg).append("\"");
        if (pluginId != null) {
            json.append(",\"pluginId\":\"").append(pluginId).append("\"");
        }
        json.append('}');
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
