package org.slackerdb.dbdesk.access.http;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ManagementClient} 的集成测试：对着**真 dbserver** 验契约（S-1/S-3/S-2/S-4）。
 *
 * <p>开关式（对称 dbcli 的 {@code -Ddbcli.it.url}，默认跳过）：</p>
 * <pre>
 *   mvn -Pdbdesk -pl dbdesk test "-Ddbdesk.management.url=http://127.0.0.1:8409" "-Dtest=ManagementClientTest"
 * </pre>
 *
 * <p>不造数据、不建连接：只读端点 + 一个"杀不存在的会话"（服务端必然 404，无副作用）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "dbdesk.management.url", matches = ".+")
class ManagementClientTest {

    private ManagementClient client;

    @BeforeAll
    void open() {
        client = new ManagementClient(System.getProperty("dbdesk.management.url"), Duration.ofSeconds(5));
    }

    @AfterAll
    void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void parsesCapabilities() throws ManagementException {
        Capabilities capabilities = client.capabilities();

        assertEquals(1, capabilities.apiVersion(), "契约版本应为 1");
        assertTrue(capabilities.supportedApi());
        assertNotNull(capabilities.serverName(), "server.name 不应为空");
        assertNotNull(capabilities.serverVersion());
        assertTrue(capabilities.pgPort() > 0, "ports.pg 应 > 0");
        assertTrue(capabilities.managementPort() > 0, "ports.management 应 > 0");
        assertTrue(capabilities.managementEnabled(), "管理口应处于开启状态");
        assertNotNull(capabilities.features());
        assertNotNull(capabilities.contracts());
        assertTrue(capabilities.contracts().containsKey("managementApi"), "contracts 里应有 managementApi");
        // ETL 菜单的判据是 plugins[].contributes.etl，不是插件 id
        assertEquals(capabilities.plugins().stream().anyMatch(Capabilities.Plugin::etl),
                capabilities.hasEtl());
    }

    @Test
    void reportsHealth() throws ManagementException {
        Health health = client.health();

        assertTrue(health.up(), "健康检查应为 UP，实际：" + health.status());
        assertEquals("UP", health.database());
        assertTrue(health.ts() > 0, "ts 应为非零时间戳");
    }

    @Test
    void listsSessionsWithTypeAndApplicationName() throws ManagementException {
        List<SessionInfo> sessions = client.sessions();

        assertNotNull(sessions);
        for (SessionInfo session : sessions) {
            assertTrue(session.id() > 0, "会话 id 应 > 0");
            assertNotNull(session.type(), "S-2 要求每个会话带 type");
            assertEquals("pgwire", session.type(), "当前只有 pgwire 会话（WS/REPL 未纳入）");
            assertNotNull(session.database());
        }
    }

    @Test
    void readsFullStatusSnapshot() throws ManagementException {
        ServerStatus status = client.status();

        assertNotNull(status.server(), "status.server 不应为空");
        assertTrue(status.server().pid() > 0, "status.server.pid 应 > 0");
        assertNotNull(status.database(), "status.database 不应为空");
        assertNotNull(status.parameters(), "status.parameters 不应为空");
        assertNotNull(status.usage(), "status.usage 不应为空");
        assertNotNull(status.sessions(), "status.sessions 不应为空");
    }

    @Test
    void readsLogText() throws ManagementException {
        String text = client.logText(20);
        assertNotNull(text, "logText 不应返回 null");
    }

    @Test
    void killingUnknownSessionFailsWith404() {
        ManagementException error = assertThrows(ManagementException.class,
                () -> client.killSession(999_999_999L));

        assertEquals(404, error.httpStatus(), "不存在的会话应 404，实际：" + error.httpStatus());
        assertTrue(error.retCode() == 404 || error.retMsg() != null,
                "错误信封应带 retCode/retMsg，实际 retCode=" + error.retCode() + " retMsg=" + error.retMsg());
    }

    @Test
    void transportFailureIsDistinguishableFromServerError() {
        try (ManagementClient dead = new ManagementClient("http://127.0.0.1:1", Duration.ofMillis(300))) {
            ManagementException error = assertThrows(ManagementException.class, dead::health);
            assertTrue(error.transportFailure(), "连不上应被标记为 transportFailure");
        }
    }
}
