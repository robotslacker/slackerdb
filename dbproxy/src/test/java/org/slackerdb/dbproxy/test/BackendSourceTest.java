package org.slackerdb.dbproxy.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.common.discovery.Announcement;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.instance.BackendSource;
import org.slackerdb.dbproxy.instance.BackendView;
import org.slackerdb.dbproxy.instance.InstanceSpec;
import org.slackerdb.dbproxy.instance.InstanceText;
import org.slackerdb.dbproxy.server.ProxyInstance;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两类后端的区分：management（目录）与 external（组播注册）。
 *
 * <p>锁住四条规则：状态里能区分来源、外部只转发、别名冲突内部优先、冲突的外部条目会被摘掉。</p>
 */
@Timeout(120)
class BackendSourceTest {

    @TempDir
    static Path root;

    private static ProxyInstance proxy;
    private static int proxyPortX;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeAll
    static void init() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData_Dir(root.toString());

        proxy = new ProxyInstance(configuration);
        proxy.start();
        proxyPortX = configuration.getPortX();

        writeInstance("managed");
        writeInstance("late");
        // 让扫描快照包含刚写的目录
        proxy.getInstanceRegistry().refresh();

        InetAddress source = InetAddress.getByName("127.0.0.1");
        proxy.applyAnnouncement(Announcement.hello("ext", "ext", "127.0.0.1", 5432, -1, "", 1), source);
        // 与内部实例同名：必须被丢弃
        proxy.applyAnnouncement(Announcement.hello("managed", "managed", "127.0.0.1", 5432, -1, "", 2), source);
        // 外部先到、内部后出现：列表里只保留内部，且外部条目被摘掉
        proxy.applyAnnouncement(Announcement.hello("late", "late", "127.0.0.1", 5433, -1, "", 3), source);
    }

    @AfterAll
    static void tearDown() {
        if (proxy != null) {
            proxy.stop();
        }
    }

    @Test
    @DisplayName("list: 状态里同时出现 management 与 external，并且外部不可治理")
    void listDistinguishesSources() {
        List<BackendView> backends = proxy.listBackends(true);

        BackendView managed = backends.stream().filter(b -> b.alias().equals("managed")).findFirst().orElse(null);
        assertNotNull(managed);
        assertEquals(BackendSource.MANAGEMENT, managed.source());
        assertTrue(managed.manageable());
        assertNotNull(managed.dir());
        assertNull(managed.target());

        BackendView external = backends.stream().filter(b -> b.alias().equals("ext")).findFirst().orElse(null);
        assertNotNull(external);
        assertEquals(BackendSource.EXTERNAL, external.source());
        assertFalse(external.manageable());
        assertEquals(5432, external.pgPort());
        assertEquals("127.0.0.1:5432", external.target());

        String table = InstanceText.table(backends);
        assertTrue(table.contains("management"), table);
        assertTrue(table.contains("external"), table);
    }

    @Test
    @DisplayName("conflict: 同名外部通告不会进入路由表，也不会重复出现在状态里")
    void managedAliasWins() {
        assertFalse(proxy.proxyTarget.containsKey("managed"),
                "an external announcement must be discarded when the alias is managed locally");
        long count = proxy.listBackends(false).stream()
                .filter(b -> b.alias().equals("managed"))
                .count();
        assertEquals(1, count);

        // 外部先注册、内部后出现的场景：冲突条目必须被摘掉
        assertFalse(proxy.proxyTarget.containsKey("late"));
        assertTrue(proxy.listBackends(false).stream()
                .anyMatch(b -> b.alias().equals("late") && b.source() == BackendSource.MANAGEMENT));
    }

    @Test
    @DisplayName("conflict: 已存在的外部条目在内部实例出现后被摘掉")
    void managedInstancePrunesPreexistingExternal() throws Exception {
        InetAddress source = InetAddress.getByName("127.0.0.1");
        proxy.applyAnnouncement(Announcement.hello("moved", "moved", "127.0.0.1", 5434, -1, "", 4), source);
        assertTrue(proxy.proxyTarget.containsKey("moved"),
                "the external entry must exist before the internal one is created");

        proxy.getInstanceController().create(new InstanceSpec("moved", null, false, Map.of()));

        List<BackendView> backends = proxy.listBackends(true);
        assertFalse(proxy.proxyTarget.containsKey("moved"),
                "the shadowed external entry must be pruned once the instance directory exists");
        assertTrue(backends.stream().anyMatch(b -> b.alias().equals("moved")
                && b.source() == BackendSource.MANAGEMENT));
    }

    @Test
    @DisplayName("api: /admin/databases 暴露 source/manageable，外部详情可读")
    void adminListExposesSource() throws Exception {
        HttpResponse<String> list = get("/admin/databases");
        assertEquals(200, list.statusCode());
        JSONArray databases = JSON.parseObject(list.body()).getJSONArray("databases");

        JSONObject external = findByAlias(databases, "ext");
        assertEquals("external", external.getString("source"));
        assertFalse(external.getBooleanValue("manageable"));
        assertEquals("127.0.0.1:5432", external.getString("target"));

        JSONObject managed = findByAlias(databases, "managed");
        assertEquals("management", managed.getString("source"));
        assertTrue(managed.getBooleanValue("manageable"));

        HttpResponse<String> detail = get("/admin/databases/ext");
        assertEquals(200, detail.statusCode());
        assertEquals("external", JSON.parseObject(detail.body()).getString("source"));
    }

    @Test
    @DisplayName("api: 外部后端拒绝 start/shutdown/restart/destroy/logs")
    void externalIsReadOnlyForLifecycle() throws Exception {
        for (String action : List.of("start", "shutdown", "restart")) {
            HttpResponse<String> response = post("/admin/databases/ext/" + action);
            assertEquals(409, response.statusCode(), action + " must be rejected for external backends");
            assertTrue(response.body().contains("external"), response.body());
        }

        HttpResponse<String> destroy = delete("/admin/databases/ext");
        assertEquals(409, destroy.statusCode());
        assertTrue(destroy.body().contains("external"), destroy.body());

        HttpResponse<String> logs = get("/admin/databases/ext/logs");
        assertEquals(409, logs.statusCode());
    }

    @Test
    @DisplayName("api: 内部实例的生命周期操作仍然可用")
    void managedStillSupportsLifecycle() throws Exception {
        HttpResponse<String> shutdown = post("/admin/databases/managed/shutdown");
        assertEquals(200, shutdown.statusCode(), shutdown.body());
        assertEquals("STOPPED", JSON.parseObject(shutdown.body()).getString("state"));
    }

    // ------------------------------------------------------------------ helpers

    private static JSONObject findByAlias(JSONArray array, String alias) {
        for (Object item : array) {
            JSONObject json = (JSONObject) item;
            if (alias.equals(json.getString("alias"))) {
                return json;
            }
        }
        throw new AssertionError("alias not found in " + array + ": " + alias);
    }

    private static String base() {
        return "http://127.0.0.1:" + proxyPortX;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(10)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void writeInstance(String alias) throws Exception {
        Path dir = root.resolve(alias);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("instance.conf"), """
                data=%s
                data_dir=data
                port=1
                port_x=-1
                pid=instance.pid
                log=logs/server.log
                bind=127.0.0.1
                """.formatted(alias), StandardCharsets.UTF_8);
    }
}
