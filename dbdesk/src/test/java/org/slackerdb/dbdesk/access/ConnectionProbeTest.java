package org.slackerdb.dbdesk.access;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbdesk.access.profile.DbDeskProfile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接判定：管理口身份、端口配对、代理路径推导与降级。
 */
class ConnectionProbeTest {

    private static final String PROXY_CAPS = """
            {"apiVersion":1,"role":"proxy",
             "ports":{"pg":4409,"management":8409,"managementEnabled":true},
             "provisioning":{"enabled":true,"mode":"process","adminBase":"/admin",
               "features":["list","create"],"backendSources":["management","external"]},
             "managementForwarding":{"enabled":true,"prefixMode":"alias"}}
            """;
    private static final String PROXY_CAPS_WITHOUT_ROLE = """
            {"apiVersion":1,
             "ports":{"pg":4409,"management":8409,"managementEnabled":true},
             "provisioning":{"enabled":true,"mode":"process"}}
            """;
    private static final String INSTANCE_CAPS = """
            {"apiVersion":1,"server":{"name":"local","version":"test"},
             "ports":{"pg":4409,"management":8500,"managementEnabled":true},
             "features":{},"plugins":[]}
            """;
    private static final String SALES_INSTANCE_CAPS = """
            {"apiVersion":1,"server":{"name":"sales","version":"test"},
             "ports":{"pg":5500,"management":8500,"managementEnabled":true},
             "features":{},"plugins":[]}
            """;
    private static final String HEALTH_UP = """
            {"status":"UP","database":"UP","management":"UP","ts":1}
            """;
    private static final String HEALTH_DOWN = """
            {"status":"DOWN","database":"DOWN","management":"UP","ts":1}
            """;

    private static HttpServer proxy;
    private static HttpServer proxyWithoutInstanceManagement;
    private static HttpServer proxyWithoutRole;
    private static HttpServer plain;
    private static HttpServer unhealthy;
    private static int proxyPort;
    private static int proxyWithoutInstanceManagementPort;
    private static int proxyWithoutRolePort;
    private static int plainPort;
    private static int unhealthyPort;

    @BeforeAll
    static void startServers() throws IOException {
        proxy = start(Map.of(
                "/capabilities", PROXY_CAPS,
                "/sales/capabilities", SALES_INSTANCE_CAPS,
                "/sales/health", HEALTH_UP));
        proxyPort = proxy.getAddress().getPort();

        proxyWithoutInstanceManagement = start(Map.of("/capabilities", PROXY_CAPS));
        proxyWithoutInstanceManagementPort = proxyWithoutInstanceManagement.getAddress().getPort();

        proxyWithoutRole = start(Map.of("/capabilities", PROXY_CAPS_WITHOUT_ROLE));
        proxyWithoutRolePort = proxyWithoutRole.getAddress().getPort();

        plain = start(Map.of(
                "/capabilities", INSTANCE_CAPS,
                "/health", HEALTH_UP));
        plainPort = plain.getAddress().getPort();

        unhealthy = start(Map.of(
                "/capabilities", INSTANCE_CAPS,
                "/health", HEALTH_DOWN));
        unhealthyPort = unhealthy.getAddress().getPort();
    }

    @AfterAll
    static void stopServers() {
        for (HttpServer server : new HttpServer[]{proxy, proxyWithoutInstanceManagement,
                proxyWithoutRole, plain, unhealthy}) {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    @Test
    @DisplayName("只有代理管理口: 进入 CLUSTER，不要求数据库端口")
    void clusterOnlyWhenProxyManagementAndNoDatabase() throws Exception {
        DbDeskProfile profile = new DbDeskProfile("proxy", "127.0.0.1", null, proxyPort, null);

        ConnectionProbe.Result result = ConnectionProbe.probe(profile, mustNotRun());

        assertEquals(ManagementScope.CLUSTER, result.scope());
        assertTrue(result.hasClusterManagement());
        assertFalse(result.hasInstanceManagement());
        assertNull(result.jdbcUrl());
        assertEquals("http://127.0.0.1:" + proxyPort, result.clusterUrl());
        assertNotNull(result.proxyCapabilities());
    }

    @Test
    @DisplayName("老代理没有 role，但声明 provisioning: 仍然识别为代理")
    void proxyDetectedWithoutRoleByProvisioning() throws Exception {
        DbDeskProfile profile = new DbDeskProfile("proxy", "127.0.0.1", null, proxyWithoutRolePort, null);

        ConnectionProbe.Result result = ConnectionProbe.probe(profile, mustNotRun());

        assertEquals(ManagementScope.CLUSTER, result.scope());
        assertTrue(result.hasClusterManagement());
    }

    @Test
    @DisplayName("只有实例管理口: 拒绝")
    void instanceManagementOnlyRejected() {
        DbDeskProfile profile = new DbDeskProfile("local", "127.0.0.1", null, plainPort, null);

        ConnectionProbe.ProbeException error = assertThrows(ConnectionProbe.ProbeException.class,
                () -> ConnectionProbe.probe(profile, mustNotRun()));

        assertTrue(error.getMessage().contains("dbproxy"), error.getMessage());
    }

    @Test
    @DisplayName("实例数据口 + 代理管理口: 拒绝")
    void instanceDatabasePortWithProxyManagementRejected() {
        DbDeskProfile profile = new DbDeskProfile("bad", "127.0.0.1", 5500, proxyPort, "sales");

        ConnectionProbe.ProbeException error = assertThrows(ConnectionProbe.ProbeException.class,
                () -> ConnectionProbe.probe(profile, mustNotRun()));

        assertTrue(error.getMessage().contains("不匹配"), error.getMessage());
        assertTrue(error.getMessage().contains("实例数据端口"), error.getMessage());
    }

    @Test
    @DisplayName("代理数据口 + 实例管理口: 拒绝")
    void proxyDatabasePortWithInstanceManagementRejected() {
        DbDeskProfile profile = new DbDeskProfile("bad", "127.0.0.1", 5500, plainPort, "sales");

        ConnectionProbe.ProbeException error = assertThrows(ConnectionProbe.ProbeException.class,
                () -> ConnectionProbe.probe(profile, mustNotRun()));

        assertTrue(error.getMessage().contains("不匹配"), error.getMessage());
        assertTrue(error.getMessage().contains("代理数据端口"), error.getMessage());
    }

    @Test
    @DisplayName("代理数据口 + 代理管理口: 自动推导 /<database>，实例管理可用")
    void proxyDataPortUsesForwardedInstanceManagement() throws Exception {
        DbDeskProfile profile = new DbDeskProfile("proxy", "127.0.0.1", 4409, proxyPort, "sales");

        ConnectionProbe.Result result = ConnectionProbe.probe(profile, jdbcOk());

        assertEquals(ManagementScope.INSTANCE, result.scope());
        assertTrue(result.hasInstanceManagement());
        assertFalse(result.hasClusterManagement());
        assertEquals("http://127.0.0.1:" + proxyPort + "/sales", result.managementUrl());
        assertEquals("sales", result.capabilities().serverName());
        assertNull(result.warning());
    }

    @Test
    @DisplayName("代理管理口开着、实例管理口不可达: 降级 SQL only")
    void proxyDataPortDegradesWhenForwardedInstanceManagementMissing() throws Exception {
        DbDeskProfile profile = new DbDeskProfile("proxy", "127.0.0.1", 4409,
                proxyWithoutInstanceManagementPort, "sales");

        ConnectionProbe.Result result = ConnectionProbe.probe(profile, jdbcOk());

        assertEquals(ManagementScope.NONE, result.scope());
        assertFalse(result.hasInstanceManagement());
        assertFalse(result.hasClusterManagement());
        assertNotNull(result.jdbcUrl());
        assertNotNull(result.warning());
        assertTrue(result.warning().contains("SQL only"), result.warning());
    }

    @Test
    @DisplayName("直连实例: 数据口匹配、健康 UP 才是 INSTANCE")
    void directInstanceMatchesPortAndHealth() throws Exception {
        DbDeskProfile profile = new DbDeskProfile("local", "127.0.0.1", 4409, plainPort, "m0");

        ConnectionProbe.Result result = ConnectionProbe.probe(profile, jdbcOk());

        assertEquals(ManagementScope.INSTANCE, result.scope());
        assertTrue(result.hasInstanceManagement());
        assertEquals("http://127.0.0.1:" + plainPort, result.managementUrl());
        assertNull(result.proxyCapabilities());
    }

    @Test
    @DisplayName("直连实例健康 DOWN: 保持原有严格语义，连接失败")
    void directInstanceUnhealthyFails() {
        DbDeskProfile profile = new DbDeskProfile("local", "127.0.0.1", 4409, unhealthyPort, "m0");

        ConnectionProbe.ProbeException error = assertThrows(ConnectionProbe.ProbeException.class,
                () -> ConnectionProbe.probe(profile, mustNotRun()));

        assertTrue(error.getMessage().contains("健康"), error.getMessage());
    }

    @Test
    @DisplayName("只有数据库端口: 不访问管理口")
    void databaseOnly() throws Exception {
        DbDeskProfile profile = new DbDeskProfile("local", "127.0.0.1", 4409, null, "m0");

        ConnectionProbe.Result result = ConnectionProbe.probe(profile, jdbcOk());

        assertEquals(ManagementScope.NONE, result.scope());
        assertTrue(result.hasDatabase());
        assertFalse(result.hasInstanceManagement());
        assertFalse(result.hasClusterManagement());
        assertNull(result.warning());
    }

    private static ConnectionProbe.DatabaseChecker jdbcOk() {
        return profile -> "jdbc:postgresql://127.0.0.1:"
                + profile.databasePort() + "/" + profile.database();
    }

    private static ConnectionProbe.DatabaseChecker mustNotRun() {
        return profile -> {
            throw new AssertionError("不应进行 JDBC 探测");
        };
    }

    private static HttpServer start(Map<String, String> responses) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        responses.forEach((path, body) -> server.createContext(path, exchange -> respond(exchange, 200, body)));
        server.start();
        return server;
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
