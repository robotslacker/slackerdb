package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * PluginManagerTest 测试插件管理功能
 * 测试步骤：
 * 1. 启动数据库，指定Port和portx
 * 2. 调用load函数来加载插件jar
 */
public class PluginManagerTest {
    static int dbPort;
    static int dbPortX;
    static DBInstance dbInstance;
    static Path pluginExampleJarPath;

    @BeforeAll
    static void initAll() throws Exception {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 检查插件示例jar文件是否存在
        pluginExampleJarPath = Paths.get("C:/temp/slackerdb-plugin-example-0.1.9.jar");

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);  // 使用随机端口
        serverConfiguration.setPortX(0); // 使用随机端口
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");

        dbPort = serverConfiguration.getPort();
        dbPortX = serverConfiguration.getPortX();

        // 初始化数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
        System.out.println("TEST:: Server started successful on port " + dbPort + ", portX " + dbPortX);
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("TEST:: Will shutdown server ...");
        System.out.println("TEST:: Active sessions : " + dbInstance.activeSessions);
        dbInstance.stop();
        System.out.println("TEST:: Server stopped successful.");
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }
    
    @BeforeEach
    void cleanupPlugins() {
        // 在每个测试开始前，清理所有已加载的插件
        // 确保测试之间的隔离性
        PluginManager pluginManager = dbInstance.getPluginManager();
        if (pluginManager != null) {
            // 获取所有插件并卸载它们
            List<PluginWrapper> plugins = new ArrayList<>(pluginManager.getPlugins());
            for (PluginWrapper plugin : plugins) {
                try {
                    // 如果插件正在运行，先停止
                    if (plugin.getPluginState() == PluginState.STARTED) {
                        pluginManager.stopPlugin(plugin.getPluginId());
                    }
                    // 卸载插件
                    pluginManager.unloadPlugin(plugin.getPluginId());
                    System.out.println("TEST:: Cleanup - Unloaded plugin: " + plugin.getPluginId());
                } catch (Exception e) {
                    System.out.println("TEST:: Cleanup - Failed to unload plugin " + plugin.getPluginId() + ": " + e.getMessage());
                }
            }
        }
    }

    @Test
    void testPluginLoadAndStartApi() throws Exception {
        // 首先检查插件文件是否存在
        if (!Files.exists(pluginExampleJarPath)) {
            System.out.println("SKIP: Plugin example jar not found, skipping test");
            return;
        }

        // 使用全路径加载插件
        System.out.println("TEST:: Using plugin from: " + pluginExampleJarPath);

        // 调用/plugin/load API，传入完整路径
        HttpClient client = HttpClient.newHttpClient();
        
        JSONObject requestBody = new JSONObject();
        // 传入完整路径
        String fullPath = pluginExampleJarPath.toAbsolutePath().toString();
        requestBody.put("filename", fullPath);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/load"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("TEST:: Load plugin response: " + response.body());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        int retCode = responseObj.getIntValue("retCode");
        String retMsg = responseObj.getString("retMsg");
        
        // 验证响应
        assert retCode == 0 : "Expected retCode 0 but got " + retCode + ", message: " + retMsg;
        assert retMsg.contains("successfully") || retMsg.contains("成功") : "Unexpected message: " + retMsg;
        
        // 验证插件ID是否返回
        String pluginId = responseObj.getString("pluginId");
        assert pluginId != null && !pluginId.isEmpty() : "Plugin ID should be returned";
        
        System.out.println("TEST:: Plugin loaded successfully with ID: " + pluginId);
        
        // 验证插件列表包含新加载的插件
        testPluginListApi(pluginId);
        
        // 测试启动插件
        testStartPluginApi(pluginId);
        
        // 测试停止插件
        testStopPluginApi(pluginId);
    }
    
    @Test
    void testPluginListApi() throws Exception {
        testPluginListApi(null);
    }
    
    private void testPluginListApi(String expectedPluginId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/list"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("TEST:: List plugins response: " + response.body());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        int retCode = responseObj.getIntValue("retCode");
        String retMsg = responseObj.getString("retMsg");
        
        assert retCode == 0 : "Expected retCode 0 but got " + retCode + ", message: " + retMsg;
        
        // 如果有预期的插件ID，检查是否在列表中
        if (expectedPluginId != null) {
            var plugins = responseObj.getJSONArray("plugins");
            boolean found = false;
            for (int i = 0; i < plugins.size(); i++) {
                JSONObject plugin = plugins.getJSONObject(i);
                if (expectedPluginId.equals(plugin.getString("pluginId"))) {
                    found = true;
                    System.out.println("TEST:: Found plugin in list: " + plugin);
                    break;
                }
            }
            assert found : "Plugin " + expectedPluginId + " should be in the plugin list";
        }
    }
    
    @Test
    void testPluginLoadWithInvalidFile() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("filename", "non-existent-plugin.jar");
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/load"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("TEST:: Load invalid plugin response: " + response.body());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        int retCode = responseObj.getIntValue("retCode");
        
        // 应该返回错误代码
        assert retCode == -1 : "Expected retCode -1 for invalid file but got " + retCode;
    }
    
    private void testStartPluginApi(String pluginId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("plugid", pluginId);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/start"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("TEST:: Start plugin response: " + response.body());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        int retCode = responseObj.getIntValue("retCode");
        String retMsg = responseObj.getString("retMsg");
        
        // 验证响应
        assert retCode == 0 : "Expected retCode 0 but got " + retCode + ", message: " + retMsg;
        assert retMsg.contains("successfully") || retMsg.contains("成功") : "Unexpected message: " + retMsg;
        
        System.out.println("TEST:: Plugin started successfully: " + pluginId);
        
        // 验证插件状态变为STARTED
        verifyPluginState(pluginId, "STARTED");
    }
    
    private void testStopPluginApi(String pluginId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("plugid", pluginId);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/stop"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("TEST:: Stop plugin response: " + response.body());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        int retCode = responseObj.getIntValue("retCode");
        String retMsg = responseObj.getString("retMsg");
        
        // 验证响应
        assert retCode == 0 : "Expected retCode 0 but got " + retCode + ", message: " + retMsg;
        assert retMsg.contains("successfully") || retMsg.contains("成功") : "Unexpected message: " + retMsg;
        
        System.out.println("TEST:: Plugin stopped successfully: " + pluginId);
        
        // 验证插件状态变为STOPPED
        verifyPluginState(pluginId, "STOPPED");
    }
    
    private void verifyPluginState(String pluginId, String expectedState) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/list"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        var plugins = responseObj.getJSONArray("plugins");
        
        boolean found = false;
        for (int i = 0; i < plugins.size(); i++) {
            JSONObject plugin = plugins.getJSONObject(i);
            if (pluginId.equals(plugin.getString("pluginId"))) {
                found = true;
                String actualState = plugin.getString("state");
                assert expectedState.equals(actualState) :
                    "Plugin " + pluginId + " should be in state " + expectedState + " but is " + actualState;
                System.out.println("TEST:: Plugin state verified: " + pluginId + " = " + actualState);
                break;
            }
        }
        assert found : "Plugin " + pluginId + " should be in the plugin list";
    }
    
    @Test
    void testPluginStartWithoutLoad() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("plugid", "non-existent-plugin");
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/start"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("TEST:: Start non-existent plugin response: " + response.body());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        int retCode = responseObj.getIntValue("retCode");
        
        // 应该返回错误代码
        assert retCode == -1 : "Expected retCode -1 for non-existent plugin but got " + retCode;
    }
    
    @Test
    void testPluginUnloadApi() throws Exception {
        // 首先检查插件文件是否存在
        if (!Files.exists(pluginExampleJarPath)) {
            System.out.println("SKIP: Plugin example jar not found, skipping test");
            return;
        }

        System.out.println("TEST:: Using plugin from: " + pluginExampleJarPath);

        // 调用/plugin/load API，传入完整路径
        HttpClient client = HttpClient.newHttpClient();
        
        JSONObject requestBody = new JSONObject();
        // 传入完整路径
        String fullPath = pluginExampleJarPath.toAbsolutePath().toString();
        requestBody.put("filename", fullPath);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/load"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        String pluginId = responseObj.getString("pluginId");
        
        System.out.println("TEST:: Plugin loaded with ID: " + pluginId);
        
        // 测试卸载插件
        testUnloadPluginApi(pluginId);
    }
    
    private void testUnloadPluginApi(String pluginId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("plugid", pluginId);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/unload"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("TEST:: Unload plugin response: " + response.body());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        int retCode = responseObj.getIntValue("retCode");
        String retMsg = responseObj.getString("retMsg");
        
        // 验证响应
        assert retCode == 0 : "Expected retCode 0 but got " + retCode + ", message: " + retMsg;
        assert retMsg.contains("successfully") || retMsg.contains("成功") : "Unexpected message: " + retMsg;
        
        System.out.println("TEST:: Plugin unloaded successfully: " + pluginId);
        
        // 验证插件列表中不再包含该插件
        verifyPluginNotInList(pluginId);
    }
    
    private void verifyPluginNotInList(String pluginId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/list"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        var plugins = responseObj.getJSONArray("plugins");
        
        boolean found = false;
        for (int i = 0; i < plugins.size(); i++) {
            JSONObject plugin = plugins.getJSONObject(i);
            if (pluginId.equals(plugin.getString("pluginId"))) {
                found = true;
                break;
            }
        }
        assert !found : "Plugin " + pluginId + " should not be in the plugin list after unload";
    }
    
    @Test
    void testPluginStopWithoutStart() throws Exception {
        // 首先加载插件但不启动
        if (!Files.exists(pluginExampleJarPath)) {
            System.out.println("SKIP: Plugin example jar not found, skipping test");
            return;
        }

        HttpClient client = HttpClient.newHttpClient();
        
        JSONObject requestBody = new JSONObject();
        // 传入完整路径
        String fullPath = pluginExampleJarPath.toAbsolutePath().toString();
        requestBody.put("filename", fullPath);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/load"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        String pluginId = responseObj.getString("pluginId");
        
        // 尝试停止未启动的插件
        JSONObject stopRequestBody = new JSONObject();
        stopRequestBody.put("plugid", pluginId);
        
        HttpRequest stopRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/stop"))
                .POST(HttpRequest.BodyPublishers.ofString(stopRequestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> stopResponse = client.send(stopRequest, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("TEST:: Stop non-started plugin response: " + stopResponse.body());
        
        JSONObject stopResponseObj = JSONObject.parseObject(stopResponse.body());
        int retCode = stopResponseObj.getIntValue("retCode");
        
        // 应该返回错误代码（插件未运行）
        assert retCode == -1 : "Expected retCode -1 for non-started plugin but got " + retCode;
    }
    
    @Test
    void testPluginUnloadWithoutLoad() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("plugid", "non-existent-plugin");
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/unload"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("TEST:: Unload non-existent plugin response: " + response.body());
        
        JSONObject responseObj = JSONObject.parseObject(response.body());
        int retCode = responseObj.getIntValue("retCode");
        
        // 应该返回错误代码
        assert retCode == -1 : "Expected retCode -1 for non-existent plugin but got " + retCode;
    }
    
    @Test
    void testPluginFullLifecycle() throws Exception {
        // 完整生命周期测试：load -> start -> stop -> unload
        if (!Files.exists(pluginExampleJarPath)) {
            System.out.println("SKIP: Plugin example jar not found, skipping test");
            return;
        }

        HttpClient client = HttpClient.newHttpClient();
        
        // 1. Load plugin
        JSONObject loadRequestBody = new JSONObject();
        // 传入完整路径
        String fullPath = pluginExampleJarPath.toAbsolutePath().toString();
        loadRequestBody.put("filename", fullPath);
        
        HttpRequest loadRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/load"))
                .POST(HttpRequest.BodyPublishers.ofString(loadRequestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> loadResponse = client.send(loadRequest, HttpResponse.BodyHandlers.ofString());
        
        JSONObject loadResponseObj = JSONObject.parseObject(loadResponse.body());
        String pluginId = loadResponseObj.getString("pluginId");
        assert loadResponseObj.getIntValue("retCode") == 0 : "Load should succeed";
        
        System.out.println("TEST:: Full lifecycle - Plugin loaded: " + pluginId);
        
        // 2. Start plugin
        JSONObject startRequestBody = new JSONObject();
        startRequestBody.put("plugid", pluginId);
        
        HttpRequest startRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/start"))
                .POST(HttpRequest.BodyPublishers.ofString(startRequestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> startResponse = client.send(startRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject startResponseObj = JSONObject.parseObject(startResponse.body());
        assert startResponseObj.getIntValue("retCode") == 0 : "Start should succeed";
        
        System.out.println("TEST:: Full lifecycle - Plugin started");
        
        // 3. Stop plugin
        JSONObject stopRequestBody = new JSONObject();
        stopRequestBody.put("plugid", pluginId);
        
        HttpRequest stopRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/stop"))
                .POST(HttpRequest.BodyPublishers.ofString(stopRequestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> stopResponse = client.send(stopRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject stopResponseObj = JSONObject.parseObject(stopResponse.body());
        assert stopResponseObj.getIntValue("retCode") == 0 : "Stop should succeed";
        
        System.out.println("TEST:: Full lifecycle - Plugin stopped");
        
        // 4. Unload plugin
        JSONObject unloadRequestBody = new JSONObject();
        unloadRequestBody.put("plugid", pluginId);
        
        HttpRequest unloadRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/unload"))
                .POST(HttpRequest.BodyPublishers.ofString(unloadRequestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> unloadResponse = client.send(unloadRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject unloadResponseObj = JSONObject.parseObject(unloadResponse.body());
        assert unloadResponseObj.getIntValue("retCode") == 0 : "Unload should succeed";
        
        System.out.println("TEST:: Full lifecycle - Plugin unloaded");
        
        // 5. Verify plugin is not in list
        verifyPluginNotInList(pluginId);
    }
    
    @Test
    void testPluginDoubleCycle() throws Exception {
        // 两次循环测试：load -> start -> stop -> unload -> load -> start -> stop -> unload
        if (!Files.exists(pluginExampleJarPath)) {
            System.out.println("SKIP: Plugin example jar not found, skipping test");
            return;
        }

        HttpClient client = HttpClient.newHttpClient();
        
        // 第一次循环
        System.out.println("TEST:: Double cycle - First cycle begin");
        
        // 1. Load plugin
        JSONObject loadRequestBody = new JSONObject();
        // 传入完整路径
        String fullPath = pluginExampleJarPath.toAbsolutePath().toString();
        loadRequestBody.put("filename", fullPath);
        
        HttpRequest loadRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/load"))
                .POST(HttpRequest.BodyPublishers.ofString(loadRequestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> loadResponse = client.send(loadRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject loadResponseObj = JSONObject.parseObject(loadResponse.body());
        String pluginId = loadResponseObj.getString("pluginId");
        assert loadResponseObj.getIntValue("retCode") == 0 : "First load should succeed";
        
        System.out.println("TEST:: Double cycle - First load: " + pluginId);
        
        // 2. Start plugin
        JSONObject startRequestBody = new JSONObject();
        startRequestBody.put("plugid", pluginId);
        
        HttpRequest startRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/start"))
                .POST(HttpRequest.BodyPublishers.ofString(startRequestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> startResponse = client.send(startRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject startResponseObj = JSONObject.parseObject(startResponse.body());
        assert startResponseObj.getIntValue("retCode") == 0 : "First start should succeed";
        
        System.out.println("TEST:: Double cycle - First start");
        
        // 3. Stop plugin
        JSONObject stopRequestBody = new JSONObject();
        stopRequestBody.put("plugid", pluginId);
        
        HttpRequest stopRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/stop"))
                .POST(HttpRequest.BodyPublishers.ofString(stopRequestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> stopResponse = client.send(stopRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject stopResponseObj = JSONObject.parseObject(stopResponse.body());
        assert stopResponseObj.getIntValue("retCode") == 0 : "First stop should succeed";
        
        System.out.println("TEST:: Double cycle - First stop");
        
        // 4. Unload plugin
        JSONObject unloadRequestBody = new JSONObject();
        unloadRequestBody.put("plugid", pluginId);
        
        HttpRequest unloadRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/unload"))
                .POST(HttpRequest.BodyPublishers.ofString(unloadRequestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> unloadResponse = client.send(unloadRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject unloadResponseObj = JSONObject.parseObject(unloadResponse.body());
        assert unloadResponseObj.getIntValue("retCode") == 0 : "First unload should succeed";
        
        System.out.println("TEST:: Double cycle - First unload");
        
        // 验证插件不在列表中
        verifyPluginNotInList(pluginId);
        
        // 第二次循环
        System.out.println("TEST:: Double cycle - Second cycle begin");
        
        // 1. Load plugin again
        HttpRequest loadRequest2 = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/load"))
                .POST(HttpRequest.BodyPublishers.ofString(loadRequestBody.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> loadResponse2 = client.send(loadRequest2, HttpResponse.BodyHandlers.ofString());
        JSONObject loadResponseObj2 = JSONObject.parseObject(loadResponse2.body());
        String pluginId2 = loadResponseObj2.getString("pluginId");
        assert loadResponseObj2.getIntValue("retCode") == 0 : "Second load should succeed";
        
        System.out.println("TEST:: Double cycle - Second load: " + pluginId2);
        
        // 插件ID应该相同
        assert pluginId.equals(pluginId2) : "Plugin ID should be the same in both cycles";
        
        // 2. Start plugin again
        JSONObject startRequestBody2 = new JSONObject();
        startRequestBody2.put("plugid", pluginId2);
        
        HttpRequest startRequest2 = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/start"))
                .POST(HttpRequest.BodyPublishers.ofString(startRequestBody2.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> startResponse2 = client.send(startRequest2, HttpResponse.BodyHandlers.ofString());
        JSONObject startResponseObj2 = JSONObject.parseObject(startResponse2.body());
        assert startResponseObj2.getIntValue("retCode") == 0 : "Second start should succeed";
        
        System.out.println("TEST:: Double cycle - Second start");
        
        // 3. Stop plugin again
        JSONObject stopRequestBody2 = new JSONObject();
        stopRequestBody2.put("plugid", pluginId2);
        
        HttpRequest stopRequest2 = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/stop"))
                .POST(HttpRequest.BodyPublishers.ofString(stopRequestBody2.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> stopResponse2 = client.send(stopRequest2, HttpResponse.BodyHandlers.ofString());
        JSONObject stopResponseObj2 = JSONObject.parseObject(stopResponse2.body());
        assert stopResponseObj2.getIntValue("retCode") == 0 : "Second stop should succeed";
        
        System.out.println("TEST:: Double cycle - Second stop");
        
        // 4. Unload plugin again
        JSONObject unloadRequestBody2 = new JSONObject();
        unloadRequestBody2.put("plugid", pluginId2);
        
        HttpRequest unloadRequest2 = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/plugin/unload"))
                .POST(HttpRequest.BodyPublishers.ofString(unloadRequestBody2.toString()))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> unloadResponse2 = client.send(unloadRequest2, HttpResponse.BodyHandlers.ofString());
        JSONObject unloadResponseObj2 = JSONObject.parseObject(unloadResponse2.body());
        assert unloadResponseObj2.getIntValue("retCode") == 0 : "Second unload should succeed";
        
        System.out.println("TEST:: Double cycle - Second unload");
        
        // 验证插件不在列表中
        verifyPluginNotInList(pluginId2);
        
        System.out.println("TEST:: Double cycle - Completed successfully");
    }
}