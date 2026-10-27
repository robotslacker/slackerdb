package org.slackerdb.pluginExample;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对 plugin-example 构建出的真实 JAR 做完整插件管理验收。
 *
 * <p>由 Failsafe 在 package 之后执行，{@code plugin.jar} 由 pom.xml 注入：</p>
 * <pre>
 *   mvn -pl plugin-example -am verify
 * </pre>
 *
 * <p>测试覆盖启动扫描、运行期 load/unload/start/stop、简单文件名/完整路径和错误路径。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PluginManagerIT {

    private static final String PLUGIN_ID = "plugin-example";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private DBInstance server;
    private int managementPort;
    private Path pluginJar;

    @BeforeAll
    void startServer() throws Exception {
        String configuredJar = System.getProperty("plugin.jar");
        assertNotNull(configuredJar, "plugin.jar system property is required (Failsafe passes it)");
        pluginJar = Path.of(configuredJar);
        assertTrue(Files.isRegularFile(pluginJar), "Plugin JAR does not exist: " + pluginJar);

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        // 不配置 plugins_dir：主实例专门验证运行期完整路径加载。
        server = new DBInstance(configuration);
        server.start();
        managementPort = configuration.getPortX();
        assertEquals("RUNNING", server.instanceState);
    }

    @AfterAll
    void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void unloadAllPlugins() throws Exception {
        for (JSONObject plugin : listPlugins(managementPort)) {
            String id = plugin.getString("pluginId");
            JSONObject response = post(managementPort, "/plugin/unload", Map.of("plugid", id));
            assertEquals(0, response.getIntValue("retCode"),
                    "cleanup unload failed for " + id + ": " + response);
        }
    }

    @Test
    void startupScansPluginsDirAndRuntimeLoadsBySimpleFilename() throws Exception {
        Path pluginsDir = Files.createTempDirectory("plugin-startup-it");
        Files.copy(pluginJar, pluginsDir.resolve(pluginJar.getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING);

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setPlugins_dir(pluginsDir.toString());

        DBInstance startupServer = new DBInstance(configuration);
        try {
            startupServer.start();
            int portX = configuration.getPortX();
            assertEquals("RUNNING", startupServer.instanceState);

            JSONObject plugin = findPlugin(listPlugins(portX), PLUGIN_ID);
            assertNotNull(plugin, "startup scan should load " + PLUGIN_ID);
            assertEquals("STARTED", plugin.getString("state"),
                    "startup should load and start the plugin");

            JSONObject unload = post(portX, "/plugin/unload", Map.of("plugid", PLUGIN_ID));
            assertEquals(0, unload.getIntValue("retCode"), unload.toString());
            assertNull(findPlugin(listPlugins(portX), PLUGIN_ID));

            JSONObject load = post(portX, "/plugin/load",
                    Map.of("filename", pluginJar.getFileName().toString()));
            assertEquals(0, load.getIntValue("retCode"), load.toString());
            assertEquals(PLUGIN_ID, load.getString("pluginId"));
            assertEquals("RESOLVED", stateOf(portX, PLUGIN_ID),
                    "load must not auto-start the plugin");

            JSONObject start = post(portX, "/plugin/start", Map.of("plugid", PLUGIN_ID));
            assertEquals(0, start.getIntValue("retCode"), start.toString());
            assertEquals("STARTED", stateOf(portX, PLUGIN_ID));

            JSONObject stop = post(portX, "/plugin/stop", Map.of("plugid", PLUGIN_ID));
            assertEquals(0, stop.getIntValue("retCode"), stop.toString());
            assertEquals("STOPPED", stateOf(portX, PLUGIN_ID));

            JSONObject unloadAgain = post(portX, "/plugin/unload", Map.of("plugid", PLUGIN_ID));
            assertEquals(0, unloadAgain.getIntValue("retCode"), unloadAgain.toString());
            assertNull(findPlugin(listPlugins(portX), PLUGIN_ID));
        } finally {
            startupServer.stop();
            deleteRecursively(pluginsDir);
        }
    }

    @Test
    void serverShutdownStopsLoadedPlugins() throws Exception {
        Path pluginsDir = Files.createTempDirectory("plugin-shutdown-it");
        Files.copy(pluginJar, pluginsDir.resolve(pluginJar.getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING);

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setPlugins_dir(pluginsDir.toString());

        DBInstance startupServer = new DBInstance(configuration);
        try {
            startupServer.start();
            assertEquals("STARTED", stateOf(configuration.getPortX(), PLUGIN_ID),
                    "plugin should be started before shutdown");

            startupServer.stop();
            assertNull(startupServer.getPluginManager(),
                    "DBInstance.stop() should stop and unload plugins");
        } finally {
            if ("RUNNING".equalsIgnoreCase(startupServer.instanceState)) {
                startupServer.stop();
            }
            deleteRecursively(pluginsDir);
        }
    }

    @Test
    void fullLifecycleByAbsolutePathAndDoubleCycle() throws Exception {
        assertNull(findPlugin(listPlugins(managementPort), PLUGIN_ID));

        for (int cycle = 1; cycle <= 2; cycle++) {
            JSONObject load = post(managementPort, "/plugin/load",
                    Map.of("filename", pluginJar.toAbsolutePath().toString()));
            assertEquals(0, load.getIntValue("retCode"), "cycle " + cycle + " load: " + load);
            assertEquals(PLUGIN_ID, load.getString("pluginId"));
            assertEquals("RESOLVED", stateOf(managementPort, PLUGIN_ID));

            JSONObject start = post(managementPort, "/plugin/start", Map.of("plugid", PLUGIN_ID));
            assertEquals(0, start.getIntValue("retCode"), "cycle " + cycle + " start: " + start);
            assertEquals("STARTED", stateOf(managementPort, PLUGIN_ID));

            JSONObject stop = post(managementPort, "/plugin/stop", Map.of("plugid", PLUGIN_ID));
            assertEquals(0, stop.getIntValue("retCode"), "cycle " + cycle + " stop: " + stop);
            assertEquals("STOPPED", stateOf(managementPort, PLUGIN_ID));

            JSONObject unload = post(managementPort, "/plugin/unload", Map.of("plugid", PLUGIN_ID));
            assertEquals(0, unload.getIntValue("retCode"), "cycle " + cycle + " unload: " + unload);
            assertNull(findPlugin(listPlugins(managementPort), PLUGIN_ID));
        }
    }

    @Test
    void simpleFilenameWithoutPluginsDirIsRejected() throws Exception {
        JSONObject response = post(managementPort, "/plugin/load",
                Map.of("filename", pluginJar.getFileName().toString()));

        assertEquals(-1, response.getIntValue("retCode"), response.toString());
        assertTrue(response.getString("retMsg").contains("Plugin directory is not configured"),
                response.getString("retMsg"));
    }

    @Test
    void errorPathsAreReportedByRetCode() throws Exception {
        JSONObject missingFile = post(managementPort, "/plugin/load",
                Map.of("filename", pluginJar.resolveSibling("does-not-exist.jar").toString()));
        assertEquals(-1, missingFile.getIntValue("retCode"), missingFile.toString());

        JSONObject unloadMissing = post(managementPort, "/plugin/unload",
                Map.of("plugid", "does-not-exist"));
        assertEquals(-1, unloadMissing.getIntValue("retCode"), unloadMissing.toString());

        JSONObject startMissing = post(managementPort, "/plugin/start",
                Map.of("plugid", "does-not-exist"));
        assertEquals(-1, startMissing.getIntValue("retCode"), startMissing.toString());

        JSONObject stopNotStarted = post(managementPort, "/plugin/stop",
                Map.of("plugid", "does-not-exist"));
        assertEquals(-1, stopNotStarted.getIntValue("retCode"), stopNotStarted.toString());

        JSONObject load = post(managementPort, "/plugin/load",
                Map.of("filename", pluginJar.toAbsolutePath().toString()));
        assertEquals(0, load.getIntValue("retCode"), load.toString());

        JSONObject stopLoadedButNotStarted = post(managementPort, "/plugin/stop",
                Map.of("plugid", PLUGIN_ID));
        assertEquals(-1, stopLoadedButNotStarted.getIntValue("retCode"),
                stopLoadedButNotStarted.toString());

        JSONObject duplicate = post(managementPort, "/plugin/load",
                Map.of("filename", pluginJar.toAbsolutePath().toString()));
        assertEquals(-1, duplicate.getIntValue("retCode"), duplicate.toString());
    }

    private String stateOf(int portX, String pluginId) throws Exception {
        JSONObject plugin = findPlugin(listPlugins(portX), pluginId);
        assertNotNull(plugin, "plugin " + pluginId + " should be loaded");
        return plugin.getString("state");
    }

    private static JSONObject findPlugin(List<JSONObject> plugins, String pluginId) {
        return plugins.stream()
                .filter(plugin -> pluginId.equals(plugin.getString("pluginId")))
                .findFirst()
                .orElse(null);
    }

    private static List<JSONObject> listPlugins(int portX) throws Exception {
        JSONObject response = get(portX, "/plugin/list");
        assertEquals(0, response.getIntValue("retCode"), response.toString());
        JSONArray plugins = response.getJSONArray("plugins");
        assertNotNull(plugins, response.toString());
        return plugins.toList(JSONObject.class);
    }

    private static JSONObject get(int portX, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(portX, path)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSONObject.parseObject(response.body());
    }

    private static JSONObject post(int portX, String path, Map<String, ?> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(portX, path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSONObject.toJSONString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSONObject.parseObject(response.body());
    }

    private static URI uri(int portX, String path) {
        return URI.create("http://127.0.0.1:" + portX + path);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 测试清理失败不掩盖真实断言
                }
            });
        }
    }
}
