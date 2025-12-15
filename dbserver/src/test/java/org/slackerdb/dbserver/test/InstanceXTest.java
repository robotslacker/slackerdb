package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.ResultSet;
import com.alibaba.fastjson2.JSONArray;
import java.nio.file.Files;
import java.nio.file.Path;

public class InstanceXTest {
    static int dbPort;
    static int dbPortX;
    static DBInstance dbInstance ;

    @BeforeAll
    static void initAll() throws ServerException {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setPortX(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        serverConfiguration.setDataServiceSchema("conf/data_service_example.service");
        serverConfiguration.setDataServiceHistory("ON");
        dbPort = serverConfiguration.getPort();
        dbPortX  = serverConfiguration.getPortX();

        // 初始化数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        // 创建public模式（如果不存在），因为data_service_example.service中使用了searchPath "public"
        try {
            Statement stmt = dbInstance.backendSysConnection.createStatement();
            stmt.execute("CREATE SCHEMA IF NOT EXISTS public");
            // 验证模式是否存在
            ResultSet rs = stmt.executeQuery("SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'public'");
            if (rs.next()) {
                System.out.println("TEST:: Public schema exists.");
            } else {
                System.out.println("TEST:: Public schema not found after creation.");
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            throw new ServerException("Failed to create public schema", e);
        }

        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
        System.out.println("TEST:: Server started successful ...");
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("TEST:: Will shutdown server ...");
        System.out.println("TEST:: Active sessions : " +  dbInstance.activeSessions);
        dbInstance.stop();
        System.out.println("TEST:: Server stopped successful.");
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    @Test
    void testSimpleApiQuery() throws Exception
    {
        JSONObject registerTestObj = new JSONObject();
        registerTestObj.put("serviceName", "queryTest1");
        registerTestObj.put("serviceVersion", "1.0");
        registerTestObj.put("serviceType", "GET");
        registerTestObj.put("sql", "SELECT 1");
        registerTestObj.put("category", "test");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/registerService"))
                .POST(HttpRequest.BodyPublishers.ofString(registerTestObj.toString()))
                .header("Content-Type", "application/json")
                .build();
        var ignored = client.send(request, HttpResponse.BodyHandlers.ofString());

        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/1.0/queryTest1/"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());

        JSONObject responseObj = JSONObject.parseObject(response2.body()).getJSONObject("data");
        responseObj.remove("timestamp");
        assert JSON.toJSONString(responseObj, JSONWriter.Feature.MapSortField).equals("""
                {"affectedRows":1,"columnNames":["1"],"columnTypes":["INTEGER"],"dataset":[[1]]}
                """.trim());
    }

    @Test
    void testApiQueryWithContext() throws Exception
    {
        JSONObject registerTestObj = new JSONObject();
        registerTestObj.put("serviceName", "testApiQueryWithContext");
        registerTestObj.put("serviceVersion", "1.0");
        registerTestObj.put("serviceType", "GET");
        registerTestObj.put("category", "test");
        registerTestObj.put("sql", "SELECT '${context1}' as col1, 2 as col2");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest requestLogin = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/login"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> responseLogin = client.send(requestLogin, HttpResponse.BodyHandlers.ofString());
        String userToken = JSONObject.parseObject(responseLogin.body()).getString("token");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/registerService"))
                .POST(HttpRequest.BodyPublishers.ofString(registerTestObj.toString()))
                .header("Content-Type", "application/json")
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());

        JSONObject contextObj = new JSONObject();
        contextObj.put("context1", "abc");
        contextObj.put("context2", "def");
        HttpRequest requestContext = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/setContext"))
                .header("Content-Type", "application/json")
                .header("Authorization", userToken)
                .POST(HttpRequest.BodyPublishers.ofString(contextObj.toString()))
                .build();
        client.send(requestContext, HttpResponse.BodyHandlers.ofString());

        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/1.0/testApiQueryWithContext/"))
                .GET()
                .header("Content-Type", "application/json")
                .header("Authorization", userToken)
                .build();
        HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
        JSONObject responseObj = JSONObject.parseObject(response2.body()).getJSONObject("data");
        responseObj.remove("timestamp");
        assert JSON.toJSONString(responseObj, JSONWriter.Feature.MapSortField).equals("""
                {"affectedRows":1,"columnNames":["col1","col2"],"columnTypes":["VARCHAR","INTEGER"],"dataset":[["abc",2]]}
                """.trim());
    }

    @Test
    void testApiQueryWithSnapshot() throws Exception
    {
        JSONObject registerTestObj = new JSONObject();
        registerTestObj.put("serviceName", "testApiQueryWithSnapshot");
        registerTestObj.put("serviceVersion", "1.0");
        registerTestObj.put("serviceType", "GET");
        registerTestObj.put("category", "test");
        registerTestObj.put("sql", "SELECT 1 as col1, 2 as col2, '中国' as col3");

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/registerService"))
                .POST(HttpRequest.BodyPublishers.ofString(registerTestObj.toString()))
                .header("Content-Type", "application/json")
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());

        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/1.0/testApiQueryWithSnapshot/"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response2_1 = client.send(request2, HttpResponse.BodyHandlers.ofString());
        JSONObject responseObj = JSONObject.parseObject(response2_1.body()).getJSONObject("data");
        responseObj.remove("timestamp");
        assert !JSONObject.parseObject(response2_1.body()).getBoolean("cached");
        assert JSON.toJSONString(responseObj, JSONWriter.Feature.MapSortField).equals("""
        {"affectedRows":1,"columnNames":["col1","col2","col3"],"columnTypes":["INTEGER","INTEGER","VARCHAR"],"dataset":[[1,2,"中国"]]}
        """.trim());

        // 第二次查询应该看到被缓存的结果
        HttpResponse<String> response2_2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
        assert JSONObject.parseObject(response2_2.body()).getBoolean("cached");
        JSONObject responseObj2 = JSONObject.parseObject(response2_2.body()).getJSONObject("data");
        responseObj2.remove("timestamp");
        assert JSON.toJSONString(responseObj2, JSONWriter.Feature.MapSortField).equals("""
        {"affectedRows":1,"columnNames":["col1","col2","col3"],"columnTypes":["INTEGER","INTEGER","VARCHAR"],"dataset":[[1,2,"中国"]]}
        """.trim());

        // 第三次查询指定缓存的时间
        HttpRequest request3 = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/1.0/testApiQueryWithSnapshot/"))
                .GET()
                .header("Content-Type", "application/json")
                .header("snapshotLimit", "5")
                .build();
        HttpResponse<String> response3_1 = client.send(request3, HttpResponse.BodyHandlers.ofString());
        JSONObject responseObj3 = JSONObject.parseObject(response3_1.body()).getJSONObject("data");
        responseObj3.remove("timestamp");
        assert !JSONObject.parseObject(response2_1.body()).getBoolean("cached");
        assert JSON.toJSONString(responseObj, JSONWriter.Feature.MapSortField).equals("""
        {"affectedRows":1,"columnNames":["col1","col2","col3"],"columnTypes":["INTEGER","INTEGER","VARCHAR"],"dataset":[[1,2,"中国"]]}
        """.trim());
    }

    // WebSocket 测试辅助类
    static class WebSocketTestClient {
        private final WebSocket ws;
        private final java.util.concurrent.LinkedBlockingQueue<String> responseQueue = new java.util.concurrent.LinkedBlockingQueue<>();
        private StringBuilder currentMessage = new StringBuilder();

        public WebSocketTestClient(String url) throws Exception {
            HttpClient client = HttpClient.newHttpClient();
            CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            currentMessage.append(data);
                            if (last) {
                                responseQueue.offer(currentMessage.toString());
                                currentMessage = new StringBuilder();
                            }
                            return WebSocket.Listener.super.onText(webSocket, data, last);
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            error.printStackTrace();
                        }
                    });
            ws = wsFuture.join();
            // 等待连接建立
            Thread.sleep(100);
        }

        public String sendAndReceive(String message) throws Exception {
            responseQueue.clear(); // 清除之前的响应
            currentMessage = new StringBuilder(); // 重置累积器，以防残留片段
            ws.sendText(message, true);
            // 等待响应，超时时间为5秒
            String response = responseQueue.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            if (response == null) {
                throw new RuntimeException("Timeout waiting for response");
            }
            return response;
        }

        public void close() throws Exception {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @Test
    void testSqlReplExec() throws Exception {
        // 连接到 WebSocket
        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");

        // 1. 创建会话
        JSONObject startMsg = new JSONObject();
        startMsg.put("id", "1");
        startMsg.put("type", "start");
        startMsg.put("data", new JSONObject());
        String startResp = client.sendAndReceive(startMsg.toString());
        JSONObject startJson = JSONObject.parseObject(startResp);
        assert startJson.getString("type").equals("start");
        String sessionId = startJson.getJSONObject("data").getString("sessionId");
        assert sessionId != null && !sessionId.isEmpty();

        // 2. 执行SQL（异步）
        JSONObject execData = new JSONObject();
        execData.put("sessionId", sessionId);
        execData.put("sql", "SELECT 1 as col");
        execData.put("fetchSize", 100);
        JSONObject execMsg = new JSONObject();
        execMsg.put("id", "2");
        execMsg.put("type", "exec");
        execMsg.put("data", execData);
        String execResp = client.sendAndReceive(execMsg.toString());
        JSONObject execJson = JSONObject.parseObject(execResp);
        assert execJson.getString("type").equals("exec");
        String taskId = execJson.getJSONObject("data").getString("taskId");
        assert taskId != null && !taskId.isEmpty();
        String status = execJson.getJSONObject("data").getString("status");
        assert "running".equals(status) || "completed".equals(status);

        // 3. 获取结果（轮询直到完成）
        JSONObject fetchData = new JSONObject();
        fetchData.put("sessionId", sessionId);
        fetchData.put("taskId", taskId);
        fetchData.put("maxRows", 100);
        JSONObject fetchMsg = new JSONObject();
        fetchMsg.put("id", "3");
        fetchMsg.put("type", "fetch");
        fetchMsg.put("data", fetchData);

        JSONObject fetchJson = null;
        for (int i = 0; i < 10; i++) {
            String fetchResp = client.sendAndReceive(fetchMsg.toString());
            fetchJson = JSONObject.parseObject(fetchResp);
            if (!"running".equals(fetchJson.getJSONObject("data").getString("status"))) {
                break;
            }
            Thread.sleep(50);
        }
        assert "completed".equals(fetchJson.getJSONObject("data").getString("status"));
        assert fetchJson.getJSONObject("data").containsKey("columns");
        assert fetchJson.getJSONObject("data").containsKey("rows");
        assert fetchJson.getJSONObject("data").getJSONArray("columns").contains("col");
        assert fetchJson.getJSONObject("data").getJSONArray("rows").size() == 1;
        assert fetchJson.getJSONObject("data").getJSONArray("rows").getJSONObject(0).getInteger("col") == 1;

        // 4. 关闭会话
        JSONObject closeData = new JSONObject();
        closeData.put("sessionId", sessionId);
        JSONObject closeMsg = new JSONObject();
        closeMsg.put("id", "4");
        closeMsg.put("type", "close");
        closeMsg.put("data", closeData);
        String closeResp = client.sendAndReceive(closeMsg.toString());
        JSONObject closeJson = JSONObject.parseObject(closeResp);
        assert closeJson.getString("type").equals("close");
        assert "closed".equals(closeJson.getJSONObject("data").getString("status"));

        client.close();
    }

    @Test
    void testSqlReplCancel() throws Exception {
        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");

        // 1. 创建会话
        JSONObject startMsg = new JSONObject();
        startMsg.put("id", "1");
        startMsg.put("type", "start");
        startMsg.put("data", new JSONObject());
        String startResp = client.sendAndReceive(startMsg.toString());
        JSONObject startJson = JSONObject.parseObject(startResp);
        String sessionId = startJson.getJSONObject("data").getString("sessionId");
        assert sessionId != null && !sessionId.isEmpty();

        // 立即发送取消请求
        JSONObject cancelData = new JSONObject();
        cancelData.put("sessionId", sessionId);
        JSONObject cancelMsg = new JSONObject();
        cancelMsg.put("id", "2");
        cancelMsg.put("type", "cancel");
        cancelMsg.put("data", cancelData);
        String cancelResp = client.sendAndReceive(cancelMsg.toString());
        JSONObject cancelJson = JSONObject.parseObject(cancelResp);
        // 取消可能成功或没有运行语句，两者都可接受
        String status = cancelJson.getJSONObject("data").getString("status");
        assert "canceled".equals(status) || "no-running-statement".equals(status);

        // 3. 关闭会话
        JSONObject closeData = new JSONObject();
        closeData.put("sessionId", sessionId);
        JSONObject closeMsg = new JSONObject();
        closeMsg.put("id", "3");
        closeMsg.put("type", "close");
        closeMsg.put("data", closeData);
        String closeResp = client.sendAndReceive(closeMsg.toString());
        JSONObject closeJson = JSONObject.parseObject(closeResp);
        assert "closed".equals(closeJson.getJSONObject("data").getString("status"));

        client.close();
    }

    @Test
    void testDataServiceExample() throws Exception
    {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/v1/getUsers/"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JSONObject fullResponse = JSONObject.parseObject(response.body());
        if (fullResponse.containsKey("data")) {
            JSONObject responseObj = fullResponse.getJSONObject("data");
            responseObj.remove("timestamp");
            assert JSON.toJSONString(responseObj, JSONWriter.Feature.MapSortField).equals("""
                    {"affectedRows":1,"columnNames":["42"],"columnTypes":["INTEGER"],"dataset":[[42]]}
                    """.trim());
        } else {
            // Service not found, maybe wrong path or service not loaded
            throw new AssertionError("Service not found or error: " + fullResponse);
        }
    }

    @Test
    void testServiceLoadRegisterUnregisterDump() throws Exception
    {
        HttpClient client = HttpClient.newHttpClient();

        // 1. 创建临时文件，包含两个服务定义
        Path tempInputFile = Files.createTempFile("services", ".json");
        JSONArray initialServices = new JSONArray();
        // 服务A
        JSONObject serviceA = new JSONObject();
        serviceA.put("serviceName", "serviceA");
        serviceA.put("category", "test");
        serviceA.put("serviceVersion", "v1");
        serviceA.put("serviceType", "GET");
        serviceA.put("sql", "SELECT 1 as col");
        serviceA.put("description", "Service A");
        serviceA.put("searchPath", "public");
        serviceA.put("snapshotLimit", "300 seconds");
        serviceA.put("parameters", new JSONArray());
        // 服务B
        JSONObject serviceB = new JSONObject();
        serviceB.put("serviceName", "serviceB");
        serviceB.put("category", "test");
        serviceB.put("serviceVersion", "v1");
        serviceB.put("serviceType", "POST");
        serviceB.put("sql", "SELECT 2 as col");
        serviceB.put("description", "Service B");
        serviceB.put("searchPath", "public");
        serviceB.put("snapshotLimit", "600 seconds");
        JSONArray paramsB = new JSONArray();
        JSONObject param1 = new JSONObject();
        param1.put("name", "limit");
        param1.put("defaultValue", "10");
        paramsB.add(param1);
        serviceB.put("parameters", paramsB);

        initialServices.add(serviceA);
        initialServices.add(serviceB);
        Files.writeString(tempInputFile, initialServices.toJSONString());

        // 2. 通过 /api/loadRegisterService 加载文件内容
        HttpRequest loadRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/loadRegisterService"))
                .POST(HttpRequest.BodyPublishers.ofString(Files.readString(tempInputFile)))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> loadResponse = client.send(loadRequest, HttpResponse.BodyHandlers.ofString());
        assert loadResponse.statusCode() == 200;
        JSONObject loadResult = JSONObject.parseObject(loadResponse.body());
        assert loadResult.getInteger("retCode") == 0;

        // 3. 验证两个服务已注册（通过 /api/listRegisteredService）
        HttpRequest listRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/listRegisteredService"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString());
        JSONArray listArray = JSONArray.parseArray(listResponse.body());
        // 应该至少包含两个服务（可能还有之前测试注册的服务，比如 data_service_example 中的 getUsers）
        // 我们只检查 serviceA 和 serviceB 是否存在
        boolean foundA = false, foundB = false;
        for (int i = 0; i < listArray.size(); i++) {
            JSONObject svc = listArray.getJSONObject(i);
            if ("serviceA".equals(svc.getString("serviceName")) && "v1".equals(svc.getString("serviceVersion"))) {
                foundA = true;
            }
            if ("serviceB".equals(svc.getString("serviceName")) && "v1".equals(svc.getString("serviceVersion"))) {
                foundB = true;
            }
        }
        assert foundA && foundB : "Loaded services not found in list";

        // 4. 注册两个新服务（serviceC 和 serviceD）
        JSONObject registerC = new JSONObject();
        registerC.put("serviceName", "serviceC");
        registerC.put("category", "test");
        registerC.put("serviceVersion", "v1");
        registerC.put("serviceType", "GET");
        registerC.put("sql", "SELECT 3 as col");
        registerC.put("description", "Service C");
        HttpRequest regRequestC = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/registerService"))
                .POST(HttpRequest.BodyPublishers.ofString(registerC.toString()))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> regResponseC = client.send(regRequestC, HttpResponse.BodyHandlers.ofString());
        assert regResponseC.statusCode() == 200;
        assert JSONObject.parseObject(regResponseC.body()).getInteger("retCode") == 0;

        JSONObject registerD = new JSONObject();
        registerD.put("serviceName", "serviceD");
        registerD.put("category", "test");
        registerD.put("serviceVersion", "v2");
        registerD.put("serviceType", "POST");
        registerD.put("sql", "SELECT 4 as col");
        registerD.put("description", "Service D");
        HttpRequest regRequestD = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/registerService"))
                .POST(HttpRequest.BodyPublishers.ofString(registerD.toString()))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> regResponseD = client.send(regRequestD, HttpResponse.BodyHandlers.ofString());
        assert regResponseD.statusCode() == 200;
        assert JSONObject.parseObject(regResponseD.body()).getInteger("retCode") == 0;

        // 5. 取消注册 serviceA
        JSONObject unregisterA = new JSONObject();
        unregisterA.put("serviceName", "serviceA");
        unregisterA.put("serviceVersion", "v1");
        unregisterA.put("serviceType", "GET");
        HttpRequest unregRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/unRegisterService"))
                .POST(HttpRequest.BodyPublishers.ofString(unregisterA.toString()))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> unregResponse = client.send(unregRequest, HttpResponse.BodyHandlers.ofString());
        assert unregResponse.statusCode() == 200;
        assert JSONObject.parseObject(unregResponse.body()).getInteger("retCode") == 0;

        // 6. 导出所有注册服务（dump）
        HttpRequest dumpRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/dumpRegisteredService"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> dumpResponse = client.send(dumpRequest, HttpResponse.BodyHandlers.ofString());
        assert dumpResponse.statusCode() == 200;
        // 响应是一个 JSON 文件（附件），但端点返回的是 JSON 数组？实际上 /api/dumpRegisteredService 返回的是 JSON 文件内容，不是包装对象。
        // 根据 APIService 代码，它直接返回 JSON 数组字符串。
        String dumpJson = dumpResponse.body();
        JSONArray dumpedArray = JSONArray.parseArray(dumpJson);

        // 7. 验证导出的 JSON 符合预期：应包含 serviceB, serviceC, serviceD，不包含 serviceA
        boolean hasA = false, hasB = false, hasC = false, hasD = false;
        for (int i = 0; i < dumpedArray.size(); i++) {
            JSONObject svc = dumpedArray.getJSONObject(i);
            String name = svc.getString("serviceName");
            String version = svc.getString("serviceVersion");
            if ("serviceA".equals(name) && "v1".equals(version)) hasA = true;
            if ("serviceB".equals(name) && "v1".equals(version)) hasB = true;
            if ("serviceC".equals(name) && "v1".equals(version)) hasC = true;
            if ("serviceD".equals(name) && "v2".equals(version)) hasD = true;
        }
        assert !hasA : "serviceA should have been unregistered";
        assert hasB : "serviceB should be present";
        assert hasC : "serviceC should be present";
        assert hasD : "serviceD should be present";

        // 可选：验证字段是否正确（例如 snapshotLimit 格式）
        for (int i = 0; i < dumpedArray.size(); i++) {
            JSONObject svc = dumpedArray.getJSONObject(i);
            if ("serviceB".equals(svc.getString("serviceName"))) {
                assert "POST".equals(svc.getString("serviceType"));
                assert svc.containsKey("parameters");
                JSONArray params = svc.getJSONArray("parameters");
                assert params.size() == 1;
                assert "limit".equals(params.getJSONObject(0).getString("name"));
                assert "10".equals(params.getJSONObject(0).getString("defaultValue"));
            }
        }

        // 清理临时文件
        Files.deleteIfExists(tempInputFile);
    }
}
