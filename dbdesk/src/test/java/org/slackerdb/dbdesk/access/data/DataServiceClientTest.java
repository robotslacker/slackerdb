package org.slackerdb.dbdesk.access.data;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataServiceClient} 的注册/列表契约：服务级 {@code filter} 必须随注册请求发出，
 * 列表回读时也要保留；空 filter 不发字段。
 */
class DataServiceClientTest {

    private static HttpServer server;
    private static DataServiceClient client;
    private static final AtomicReference<String> REGISTER_BODY = new AtomicReference<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/registerService", exchange -> {
            REGISTER_BODY.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"retCode\":0,\"retMsg\":\"successful.\"}");
        });
        server.createContext("/api/listRegisteredService", exchange -> respond(exchange, 200, """
                [
                  {"serviceName":"filterListed","serviceVersion":"1.0","serviceType":"GET",
                   "sql":"select 1","filter":"id = 1","parameter":{}},
                  {"serviceName":"plain","serviceVersion":"1.0","serviceType":"GET",
                   "sql":"select 2","parameter":{}}
                ]
                """));
        server.start();
        client = new DataServiceClient("http://127.0.0.1:" + server.getAddress().getPort(),
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
    void registerSendsServiceLevelFilter() throws Exception {
        client.register(definition("status IN ('paid','shipped') ORDER BY created_at DESC LIMIT 100"));

        String body = REGISTER_BODY.get();
        assertNotNull(body);
        assertTrue(body.contains(
                        "\"filter\":\"status IN ('paid','shipped') ORDER BY created_at DESC LIMIT 100\""),
                body);
    }

    @Test
    void registerOmitsBlankFilter() throws Exception {
        client.register(definition("   "));

        assertFalse(REGISTER_BODY.get().contains("\"filter\""), REGISTER_BODY.get());
    }

    @Test
    void registerTrimsFilterWhitespace() throws Exception {
        client.register(definition("  id = 1  "));

        assertTrue(REGISTER_BODY.get().contains("\"filter\":\"id = 1\""), REGISTER_BODY.get());
    }

    @Test
    void listReadsServiceLevelFilter() throws Exception {
        List<DataServiceDefinition> services = client.list();

        assertEquals(2, services.size());
        assertEquals("id = 1", services.get(0).filter());
        assertNull(services.get(1).filter(), "未配置 filter 的服务回读为 null");
    }

    private static DataServiceDefinition definition(String filter) {
        return new DataServiceDefinition(
                "filterDemo", "1.0", "orders", "GET", "select 1", "demo",
                null, filter, null, null, List.of());
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
