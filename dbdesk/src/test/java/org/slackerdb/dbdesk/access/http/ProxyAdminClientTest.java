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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbproxy 管理 API 客户端：列表/详情/创建/启停/销毁与错误信封。
 */
class ProxyAdminClientTest {

    private static HttpServer server;
    private static ProxyAdminClient client;
    private static final AtomicReference<String> LAST_CREATE_BODY = new AtomicReference<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/databases", ProxyAdminClientTest::handle);
        server.start();
        client = new ProxyAdminClient("http://127.0.0.1:" + server.getAddress().getPort(),
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
    @DisplayName("list: 解析字段、按别名排序、外部实例不可治理")
    void listParsesAndSorts() throws Exception {
        List<ClusterInstance> instances = client.databases(true);

        assertEquals(3, instances.size());
        assertEquals("alpha", instances.get(0).alias());
        assertEquals("sales", instances.get(1).alias());
        assertEquals("zeta", instances.get(2).alias());

        ClusterInstance external = instances.get(2);
        assertTrue(external.external());
        assertFalse(external.manageable());
        assertNull(external.pid());
        assertEquals(-1, external.mgmtPort());
        assertEquals("host:5500", external.target());

        ClusterInstance sales = instances.get(1);
        assertTrue(sales.manageable());
        assertEquals(Integer.valueOf(123), sales.pid());
        assertEquals(4409, sales.pgPort());
        assertEquals("STOPPED", sales.stateText());
    }

    @Test
    @DisplayName("detail: 解析 conf")
    void detailParsesConf() throws Exception {
        ClusterInstance detail = client.detail("sales");

        assertEquals("sales", detail.alias());
        assertEquals("salesdb", detail.name());
        assertEquals("READ_WRITE", detail.conf().get("access_mode"));
        assertNotNull(detail.dir());
    }

    @Test
    @DisplayName("create: 发送 alias/name/start，解析 201 响应")
    void createSendsBody() throws Exception {
        ClusterInstance created = client.create("newdb", "newdb", true);

        assertEquals("newdb", created.alias());
        assertNotNull(LAST_CREATE_BODY.get());
        assertTrue(LAST_CREATE_BODY.get().contains("\"alias\":\"newdb\""));
        assertTrue(LAST_CREATE_BODY.get().contains("\"name\":\"newdb\""));
        assertTrue(LAST_CREATE_BODY.get().contains("\"start\":true"));
    }

    @Test
    @DisplayName("start/shutdown/destroy: 调用对应端点")
    void lifecycleCallsEndpoints() throws Exception {
        assertEquals("RUNNING", client.start("sales").stateText());
        assertEquals("STOPPED", client.shutdown("sales").stateText());
        client.destroy("sales");
    }

    @Test
    @DisplayName("error: 409 的 retCode/retMsg 原样带出")
    void errorEnvelope() {
        ManagementException error = assertThrows(ManagementException.class,
                () -> client.detail("bad"));

        assertEquals(409, error.httpStatus());
        assertEquals(409, error.retCode());
        assertEquals("Alias exists", error.retMsg());
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("GET".equals(method) && "/admin/databases".equals(path)) {
            respond(exchange, 200, """
                    {"instanceManagementEnabled":true,"storage":{"dataDir":"/tmp/instances"},
                     "databases":[
                       {"alias":"zeta","source":"external","manageable":false,"name":"z",
                        "state":"RUNNING","pid":null,"pgPort":5500,"mgmtPort":-1,
                        "health":"UNKNOWN","uptimeSeconds":null,"error":null,
                        "dir":null,"target":"host:5500"},
                       {"alias":"sales","source":"management","manageable":true,"name":"sales",
                        "state":"STOPPED","pid":123,"pgPort":4409,"mgmtPort":8409,
                        "health":"DOWN","uptimeSeconds":42,"error":null,
                        "dir":"/tmp/instances/sales","target":null},
                       {"alias":"alpha","source":"management","manageable":true,"name":"alpha",
                        "state":"RUNNING","pid":124,"pgPort":4410,"mgmtPort":8410,
                        "health":"UP","uptimeSeconds":100,"error":null,
                        "dir":"/tmp/instances/alpha","target":null}
                     ]}
                    """);
            return;
        }
        if ("GET".equals(method) && "/admin/databases/sales".equals(path)) {
            respond(exchange, 200, """
                    {"alias":"sales","source":"management","manageable":true,"name":"salesdb",
                     "state":"STOPPED","pid":123,"pgPort":4409,"mgmtPort":8409,
                     "health":"DOWN","uptimeSeconds":42,"error":null,
                     "dir":"/tmp/instances/sales","target":null,
                     "conf":{"access_mode":"READ_WRITE","port":"4409"}}
                    """);
            return;
        }
        if ("GET".equals(method) && "/admin/databases/bad".equals(path)) {
            respond(exchange, 409, """
                    {"retCode":409,"retMsg":"Alias exists"}
                    """);
            return;
        }
        if ("POST".equals(method) && "/admin/databases".equals(path)) {
            LAST_CREATE_BODY.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, 201, instanceJson("newdb", "RUNNING"));
            return;
        }
        if ("POST".equals(method) && path.endsWith("/start")) {
            respond(exchange, 200, instanceJson("sales", "RUNNING"));
            return;
        }
        if ("POST".equals(method) && path.endsWith("/shutdown")) {
            respond(exchange, 200, instanceJson("sales", "STOPPED"));
            return;
        }
        if ("DELETE".equals(method) && "/admin/databases/sales".equals(path)) {
            respond(exchange, 200, "{\"alias\":\"sales\",\"destroyed\":true}");
            return;
        }
        respond(exchange, 404, "{\"retCode\":404,\"retMsg\":\"not found\"}");
    }

    private static String instanceJson(String alias, String state) {
        return """
                {"alias":"%s","source":"management","manageable":true,"name":"%s",
                 "state":"%s","pid":125,"pgPort":4409,"mgmtPort":8409,
                 "health":"UP","uptimeSeconds":1,"error":null,
                 "dir":"/tmp/instances/%s","target":null}
                """.formatted(alias, alias, state, alias);
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
