package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.instance.InstanceConf;
import org.slackerdb.dbproxy.instance.InstanceController;
import org.slackerdb.dbproxy.instance.InstanceException;
import org.slackerdb.dbproxy.instance.InstanceSpec;
import org.slackerdb.dbproxy.instance.InstanceState;
import org.slackerdb.dbproxy.instance.ObservedInstance;
import org.slackerdb.dbproxy.instance.PortAllocator;
import org.slackerdb.dbproxy.server.ProxyInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code create} 的 key/value 配置项：端口、日志、数据路径、pid、bind、插件参数等都由用户指定。
 *
 * <p>代理只给"用户没提到"的项填默认值（端口才会自动分配）；值是否合理不做判断——
 * 端口冲突、路径不可写之类的问题会在启动时由 dbserver 报出来。</p>
 */
@Timeout(240)
class InstanceOverridesTest {

    @TempDir
    static Path root;

    /** 实例目录之外的数据目录（模拟 data_dir 指向代理数据目录之外）。 */
    @TempDir
    static Path externalRoot;

    private static ProxyInstance proxy;
    private static InstanceController controller;

    @BeforeAll
    static void init() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData_Dir(root.toString());
        configuration.setDbserver_Jar("slackerdb-dbserver-*-standalone.jar");

        proxy = new ProxyInstance(configuration);
        proxy.start();
        controller = proxy.getInstanceController();
        assertNotNull(controller, "instance management must be enabled when data_dir is set");
    }

    @AfterAll
    static void tearDown() {
        if (controller != null) {
            try {
                for (ObservedInstance instance : controller.list()) {
                    try {
                        controller.destroy(instance.alias());
                    } catch (RuntimeException ignored) {
                        // 已销毁或无法销毁：交给进程退出
                    }
                }
            } catch (RuntimeException ignored) {
                // 扫描失败不影响关闭代理
            }
        }
        if (proxy != null) {
            proxy.stop();
        }
    }

    @Test
    @DisplayName("overrides: user values win for every key, only the missing mgmt port is allocated")
    void overridesWinOverDefaults() throws Exception {
        int[] ports = PortAllocator.allocate(root, 1, Set.of());
        int pgPort = ports[0];

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("port", String.valueOf(pgPort));
        overrides.put("data", "overdb");
        overrides.put("data_dir", "custom-data");
        overrides.put("log", "mylogs/custom.log");
        overrides.put("pid", "custom.pid");
        overrides.put("bind", "0.0.0.0");
        overrides.put("access_mode", "READ_ONLY");
        overrides.put("max_connections", "7");
        overrides.put("scheduler.home", "someDir");

        ObservedInstance created = controller.create(new InstanceSpec("overrides", null, false, overrides));

        assertEquals(InstanceState.STOPPED, created.state());
        assertEquals("overdb", created.dataName());
        assertEquals(pgPort, created.pgPort(), "the user's port= must be used as-is");
        assertTrue(created.mgmtPort() > 0, "the missing port_x must be allocated");
        assertFalse(created.mgmtPort() == pgPort);

        Path confFile = root.resolve("overrides").resolve("instance.conf");
        InstanceConf conf = InstanceConf.load(confFile);
        assertEquals("overdb", conf.get("data"));
        assertEquals("custom-data", conf.get("data_dir"));
        assertEquals("mylogs/custom.log", conf.get("log"));
        assertEquals("custom.pid", conf.get("pid"));
        assertEquals("0.0.0.0", conf.get("bind"));
        assertEquals("READ_ONLY", conf.get("access_mode"));
        assertEquals("7", conf.get("max_connections"));
        // 插件参数的大小写有意义，不能被转成小写
        assertEquals("someDir", conf.get("scheduler.home"));
        assertTrue(Files.readString(confFile, StandardCharsets.UTF_8).contains("scheduler.home=someDir"));
        assertEquals(String.valueOf(pgPort), conf.get("port"));
        assertEquals(String.valueOf(created.mgmtPort()), conf.get("port_x"));

        controller.destroy("overrides");
    }

    @Test
    @DisplayName("create: 'web' is a normal alias now that the proxy is API-only")
    void webAliasIsAvailable() {
        ObservedInstance created = controller.create(new InstanceSpec("web", null, false, Map.of()));

        assertEquals("web", created.alias());
        assertEquals(InstanceState.STOPPED, created.state());
        controller.destroy("web");
    }

    @Test
    @DisplayName("overrides: logfile= is normalized to log= so dbserver and the proxy agree")
    void logfileAliasIsNormalized() throws Exception {
        controller.create(new InstanceSpec("logalias", null, false,
                Map.of("logfile", "mylogs/alias.log")));
        InstanceConf aliasConf = InstanceConf.load(root.resolve("logalias").resolve("instance.conf"));
        assertEquals("mylogs/alias.log", aliasConf.get("log"));
        assertNull(aliasConf.get("logfile"), "only the canonical log key may be written");
        controller.destroy("logalias");

        // 同时给出两个键时，规范键 log 胜出，与 portX/port_x 的规则一致（与传入顺序无关）
        for (String alias : new String[]{"logboth", "logreversed"}) {
            Map<String, String> both = new LinkedHashMap<>();
            if ("logboth".equals(alias)) {
                both.put("logfile", "mylogs/alias.log");
                both.put("log", "mylogs/explicit.log");
            } else {
                both.put("log", "mylogs/explicit.log");
                both.put("logfile", "mylogs/alias.log");
            }
            controller.create(new InstanceSpec(alias, null, false, both));
            InstanceConf conf = InstanceConf.load(root.resolve(alias).resolve("instance.conf"));
            assertEquals("mylogs/explicit.log", conf.get("log"), alias);
            assertNull(conf.get("logfile"), alias);
            controller.destroy(alias);
        }
    }

    @Test
    @DisplayName("overrides: both ports given means nothing is allocated")
    void bothPortsAreKept() throws Exception {
        int[] ports = PortAllocator.allocate(root, 2, Set.of());

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("port", String.valueOf(ports[0]));
        overrides.put("port_x", String.valueOf(ports[1]));

        ObservedInstance created = controller.create(new InstanceSpec("bothports", null, false, overrides));

        assertEquals(ports[0], created.pgPort());
        assertEquals(ports[1], created.mgmtPort());
        controller.destroy("bothports");
    }

    @Test
    @DisplayName("overrides: structurally broken key/value is rejected before creating anything")
    void invalidOverrideIsRejected() {
        InstanceException badKey = assertThrows(InstanceException.class,
                () -> controller.create(new InstanceSpec("badkey", null, false,
                        Map.of("bad key", "x"))));
        assertEquals(400, badKey.status());

        InstanceException multiline = assertThrows(InstanceException.class,
                () -> controller.create(new InstanceSpec("multiline", null, false,
                        Map.of("log", "a\nb"))));
        assertEquals(400, multiline.status());

        assertFalse(Files.exists(root.resolve("badkey")));
        assertFalse(Files.exists(root.resolve("multiline")));
    }

    @Test
    @DisplayName("ports: passing 0 lets the proxy allocate, and the real ports are visible afterwards")
    void zeroPortsAreAllocatedAtCreate() throws Exception {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("port", "0");
        overrides.put("port_x", "0");

        ObservedInstance created = controller.create(new InstanceSpec("zeroports", null, true, overrides));

        int pgPort = created.pgPort();
        int mgmtPort = created.mgmtPort();
        assertTrue(pgPort > 0 && mgmtPort > 0,
                "0 must be replaced by real ports: pg=" + pgPort + ", mgmt=" + mgmtPort);
        assertTrue(pgPort != mgmtPort);
        assertEquals(InstanceState.RUNNING, created.state());

        // 后续通过状态/管理 API 查到的就是真实端口
        ObservedInstance status = controller.status("zeroports");
        assertEquals(pgPort, status.pgPort());
        assertEquals(mgmtPort, status.mgmtPort());
        InstanceConf conf = InstanceConf.load(root.resolve("zeroports").resolve("instance.conf"));
        assertEquals(pgPort, conf.port());
        assertEquals(mgmtPort, conf.portX());
        assertFalse(Files.readString(root.resolve("zeroports").resolve("instance.conf"),
                StandardCharsets.UTF_8).contains("port=0"));

        controller.destroy("zeroports");
    }

    @Test
    @DisplayName("start: a port=0 already present in the config file is allocated and persisted too")
    void startAllocatesZeroPorts() throws Exception {
        controller.create(new InstanceSpec("legacyzero", null, false, Map.of()));
        Path confFile = root.resolve("legacyzero").resolve("instance.conf");
        String text = Files.readString(confFile, StandardCharsets.UTF_8)
                .replaceAll("(?m)^port=\\d+$", "port=0")
                .replaceAll("(?m)^port_x=\\d+$", "port_x=0");
        Files.writeString(confFile, text, StandardCharsets.UTF_8);

        ObservedInstance started = controller.start("legacyzero");

        assertEquals(InstanceState.RUNNING, started.state());
        assertTrue(started.pgPort() > 0 && started.mgmtPort() > 0);
        InstanceConf conf = InstanceConf.load(confFile);
        assertEquals(started.pgPort(), conf.port());
        assertEquals(started.mgmtPort(), conf.portX());

        controller.destroy("legacyzero");
    }

    @Test
    @DisplayName("destroy: a data_dir outside the proxy data_dir is ignored (data is kept)")
    void destroyKeepsExternalDataDir() throws Exception {
        Path externalData = Files.createDirectories(externalRoot.resolve("sales-data"));
        Path marker = Files.writeString(externalData.resolve("keep.txt"), "keep me",
                StandardCharsets.UTF_8);

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("data_dir", externalData.toString());
        overrides.put("port", "0");
        overrides.put("port_x", "0");

        ObservedInstance created = controller.create(new InstanceSpec("extdata", null, true, overrides));
        assertEquals(InstanceState.RUNNING, created.state());

        controller.destroy("extdata");

        assertFalse(Files.exists(root.resolve("extdata")),
                "the instance directory itself must still be removed");
        assertTrue(Files.isDirectory(externalData),
                "a data_dir outside the proxy data_dir must not be deleted");
        assertTrue(Files.isRegularFile(marker));
        assertEquals("keep me", Files.readString(marker, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("destroy: a data_dir inside the instance directory is removed with it")
    void destroyRemovesInternalDataDir() throws Exception {
        controller.create(new InstanceSpec("intdata", null, false,
                Map.of("data_dir", "inside-data")));
        Path innerData = Files.createDirectories(root.resolve("intdata").resolve("inside-data"));
        Files.writeString(innerData.resolve("x.txt"), "x", StandardCharsets.UTF_8);

        controller.destroy("intdata");

        assertFalse(Files.exists(root.resolve("intdata")),
                "everything under the instance directory, including an internal data_dir, must be removed");
    }

    @Test
    @DisplayName("overrides: a custom pid file and log file are followed by status, logs and shutdown")
    void customPidAndLogAreFollowed() throws Exception {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("pid", "run/custom.pid");
        overrides.put("log", "mylogs/custom.log");

        ObservedInstance created = controller.create(new InstanceSpec("customfiles", null, true, overrides));

        assertEquals(InstanceState.RUNNING, created.state());
        Path dir = root.resolve("customfiles");
        Path customPid = dir.resolve("run").resolve("custom.pid");
        assertTrue(Files.isRegularFile(customPid),
                "dbserver must write the pid file named by pid=, got: " + dir);
        assertFalse(Files.exists(dir.resolve("instance.pid")),
                "the proxy must not assume the default instance.pid");
        assertEquals(String.valueOf(created.pid()),
                Files.readString(customPid, StandardCharsets.UTF_8).trim(),
                "the pid reported by the API must come from the configured file");
        assertEquals(created.pid(), controller.status("customfiles").pid());

        assertTrue(Files.isRegularFile(dir.resolve("mylogs").resolve("custom.log")),
                "dbserver must write the log file named by log=");
        assertFalse(Files.exists(dir.resolve("logs").resolve("server.log")),
                "the proxy must not assume the default logs/server.log");
        String logs = controller.logs("customfiles", 200);
        assertTrue(logs.contains("custom.log"), "the configured log file must be read: " + logs);
        assertFalse(logs.startsWith("(no log file"), logs);

        controller.shutdown("customfiles");
        assertEquals(InstanceState.STOPPED, controller.status("customfiles").state());
        assertFalse(Files.exists(customPid), "the configured pid file is removed on graceful shutdown");

        controller.destroy("customfiles");
    }

    @Test
    @DisplayName("overrides: user ports/data/log actually drive the launched dbserver")
    void overridesDriveTheLaunchedProcess() throws Exception {
        int[] ports = PortAllocator.allocate(root, 2, Set.of());

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("data", "overdb");
        overrides.put("data_dir", "data");
        overrides.put("port", String.valueOf(ports[0]));
        overrides.put("port_x", String.valueOf(ports[1]));
        overrides.put("pid", "instance.pid");
        overrides.put("log", "logs/server.log");
        overrides.put("bind", "127.0.0.1");
        // 照抄来的 daemon=true 不能让代理误判启动失败：托管启动必须前台
        overrides.put("daemon", "true");
        overrides.put("advertise_address", "");
        overrides.put("access_mode", "READ_WRITE");

        ObservedInstance created = controller.create(new InstanceSpec("launched", null, true, overrides));

        assertEquals(InstanceState.RUNNING, created.state());
        assertEquals("overdb", created.dataName());
        assertEquals(ports[0], created.pgPort());
        assertEquals(ports[1], created.mgmtPort());

        controller.destroy("launched");
        assertTrue(controller.listFresh().stream()
                        .noneMatch(instance -> instance.alias().equals("launched")),
                "destroy must remove the instance from the fresh listing");
    }
}
