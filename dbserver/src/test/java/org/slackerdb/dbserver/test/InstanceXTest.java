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
        serverConfiguration.setDataServiceHistory("ON");
        dbPort = serverConfiguration.getPort();
        dbPortX  = serverConfiguration.getPortX();

        // 初始化数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

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

        public WebSocketTestClient(String url) throws Exception {
            HttpClient client = HttpClient.newHttpClient();
            CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            responseQueue.offer(data.toString());
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
}
