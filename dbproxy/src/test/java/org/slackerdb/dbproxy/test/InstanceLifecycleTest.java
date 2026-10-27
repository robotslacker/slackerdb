package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.instance.InstanceController;
import org.slackerdb.dbproxy.instance.InstanceException;
import org.slackerdb.dbproxy.instance.InstanceSpec;
import org.slackerdb.dbproxy.instance.InstanceState;
import org.slackerdb.dbproxy.instance.ObservedInstance;
import org.slackerdb.dbproxy.server.ProxyInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例生命周期端到端测试：真实启动一个 dbserver 子进程。
 *
 * <p>验证的是 process 模式下最容易出错的一条链：目录/conf/bin 落盘 → 子进程起来 →
 * 通过代理的 PG 口选路 → shutdown 后明确拒绝 → resume → destroy 清目录。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(240)
class InstanceLifecycleTest {

    @TempDir
    static Path root;

    private static ProxyInstance proxy;
    private static InstanceController controller;
    private static int proxyPort;

    @BeforeAll
    static void init() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData_Dir(root.toString());

        proxy = new ProxyInstance(configuration);
        proxy.start();
        proxyPort = configuration.getPort();
        controller = proxy.getInstanceController();
        assertNotNull(controller, "instance management must be enabled when data_dir is set");
    }

    @AfterAll
    static void tearDown() {
        try {
            if (controller != null) {
                try {
                    controller.destroy("itdb");
                } catch (RuntimeException ignored) {
                    // 用例可能已经销毁或从未创建成功
                }
            }
        } finally {
            if (proxy != null) {
                proxy.stop();
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("create: writes the full instance layout and starts the child process")
    void createStartsTheInstance() {
        ObservedInstance created = controller.create(new InstanceSpec("itdb", null, true, Map.of()));

        assertEquals(InstanceState.RUNNING, created.state());
        assertNotNull(created.pid(), "a running instance must have a verified pid");
        assertEquals("itdb", created.dataName());

        Path dir = root.resolve("itdb");
        assertTrue(Files.isRegularFile(dir.resolve("instance.conf")));
        assertTrue(Files.isRegularFile(dir.resolve("bin").resolve("start.sh")));
        assertTrue(Files.isRegularFile(dir.resolve("bin").resolve("stop.bat")));
        assertTrue(Files.isDirectory(dir.resolve("data")));
        assertTrue(Files.isDirectory(dir.resolve("logs")));
    }

    @Test
    @Order(2)
    @DisplayName("route: the proxy forwards a JDBC connection to the managed instance")
    void connectsThroughProxy() throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select 1")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    @Test
    @Order(3)
    @DisplayName("shutdown: stops the process, keeps data, and new connections are refused")
    void shutdownKeepsData() throws Exception {
        ObservedInstance stopped = controller.shutdown("itdb");

        assertEquals(InstanceState.STOPPED, stopped.state());
        Path dir = root.resolve("itdb");
        assertTrue(Files.isDirectory(dir), "shutdown must not delete the instance directory");
        try (var files = Files.list(dir.resolve("data"))) {
            assertTrue(files.findAny().isPresent(), "shutdown must keep the database files");
        }
        assertThrows(SQLException.class, InstanceLifecycleTest::connect,
                "a stopped instance must be refused with an error, not hang");
    }

    @Test
    @Order(4)
    @DisplayName("resume: starts the same directory again with the same ports")
    void resumeStartsAgain() throws Exception {
        ObservedInstance resumed = controller.start("itdb");

        assertEquals(InstanceState.RUNNING, resumed.state());
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select 2")) {
            assertTrue(resultSet.next());
            assertEquals(2, resultSet.getInt(1));
        }
    }

    @Test
    @Order(5)
    @DisplayName("create: a duplicate alias is rejected without touching the running instance")
    void duplicateAliasIsRejected() {
        InstanceException failure = assertThrows(InstanceException.class,
                () -> controller.create(InstanceSpec.of("itdb")));
        assertEquals(409, failure.status());
        assertEquals(InstanceState.RUNNING, controller.status("itdb").state());
    }

    @Test
    @Order(6)
    @DisplayName("create: alias may differ from the database name; start=false only writes files")
    void aliasMayDifferFromName() {
        ObservedInstance created = controller.create(new InstanceSpec("itdb2", "otherdb", false, Map.of()));

        assertEquals(InstanceState.STOPPED, created.state());
        assertEquals("otherdb", created.dataName());
        assertTrue(Files.isRegularFile(root.resolve("itdb2").resolve("instance.conf")));

        controller.destroy("itdb2");
        assertFalse(Files.exists(root.resolve("itdb2")));
    }

    @Test
    @Order(7)
    @DisplayName("destroy: stops and removes the whole directory")
    void destroyRemovesDirectory() {
        controller.destroy("itdb");

        assertFalse(Files.exists(root.resolve("itdb")), "destroy must remove the instance directory");
        assertThrows(InstanceException.class, () -> controller.status("itdb"));
    }

    @Test
    @Order(8)
    @DisplayName("destroy: a broken instance (invalid conf, no process) can still be removed")
    void destroyBrokenInstance() throws Exception {
        controller.create(new InstanceSpec("broken", null, false, Map.of()));
        // 把配置写坏：端口非法 → 观察态 UNKNOWN，且没有存活进程
        Files.writeString(root.resolve("broken").resolve("instance.conf"),
                "data=broken\nport=0\nport_x=0\n");

        controller.destroy("broken");

        assertFalse(Files.exists(root.resolve("broken")),
                "an instance that cannot be routed and has no process must still be deletable");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + proxyPort + "/itdb", "", "");
    }
}
