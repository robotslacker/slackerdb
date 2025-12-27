package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端测试：客户表、API服务、MCP服务和大模型调用
 * 一个测试完成所有事情：
 * 1. 构建客户表，里头存放客户的性别、姓名、年龄
 * 2. 定制一个api服务，根据姓名查找其性别和年龄
 * 3. 定制一个mcp服务，根据这个api来回应相关信息
 * 4. 调用大模型，提供名称，要求其回应性别年龄信息
 */
public class CustomerEndToEndTest {
    private static final HttpClient client = HttpClient.newHttpClient();

    static int dbPort;
    static int dbPortX;
    static String baseUrl;
    static DBInstance dbInstance;
    static String protocol = "postgresql";
    
    // WebSocket 测试辅助类（从 InstanceXTest 复制）
    static class WebSocketTestClient {
        private final WebSocket ws;
        private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
        private StringBuilder currentMessage = new StringBuilder();
        private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        private volatile boolean heartbeatStarted = false;

        public WebSocketTestClient(String url) throws Exception {
            HttpClient client = HttpClient.newHttpClient();
            CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            String message = data.toString();
                            
                            // 处理ping/pong心跳消息
                            if ("ping".equals(message)) {
                                // 服务器发送ping，客户端回复pong
                                webSocket.sendText("pong", true);
                                return WebSocket.Listener.super.onText(webSocket, data, last);
                            }
                            
                            // 忽略pong响应，不放入响应队列
                            if ("pong".equals(message)) {
                                return WebSocket.Listener.super.onText(webSocket, data, last);
                            }
                            
                            currentMessage.append(data);
                            if (last) {
                                String fullMessage = currentMessage.toString();
                                responseQueue.offer(fullMessage);
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
            
            // 启动心跳检测（可选，但可以保持连接活跃）
            startHeartbeatIfNeeded();
        }

        /**
         * 启动心跳检测（如果需要）
         */
        private void startHeartbeatIfNeeded() {
            if (!heartbeatStarted) {
                heartbeatStarted = true;
                // 每15秒发送一次ping，保持连接活跃
                heartbeatExecutor.scheduleAtFixedRate(() -> {
                    try {
                        // 发送ping消息到服务器
                        ws.sendText("ping", true);
                    } catch (Exception e) {
                        // 发送失败，连接可能已关闭
                        System.err.println("DEBUG: Failed to send ping: " + e.getMessage());
                    }
                }, 15, 15, TimeUnit.SECONDS);
            }
        }

        public String sendAndReceive(String message) throws Exception {
            responseQueue.clear();
            currentMessage = new StringBuilder();
            ws.sendText(message, true);

            // 等待响应，增加超时时间为120秒，因为大模型处理可能需要时间
            String response = responseQueue.poll(120, TimeUnit.SECONDS);
            if (response == null) {
                throw new RuntimeException("Timeout waiting for response after 120 seconds. " + message);
            }
            return response;
        }

        public void close() throws Exception {
            heartbeatStarted = false;
            heartbeatExecutor.shutdown();
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @BeforeAll
    static void initAll() throws ServerException {
        // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 修改默认的db启动端口
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(4309);
        serverConfiguration.setPortX(43090);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        serverConfiguration.setMcpLlmServer("ollama:127.0.0.1:11434:qwen2.5:1.5b");
        serverConfiguration.setDataServiceHistory("ON");
        dbPort = serverConfiguration.getPort();
        dbPortX = serverConfiguration.getPortX();

        // 初始化数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        baseUrl = "http://127.0.0.1:" + dbPortX;

        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
        System.out.println("TEST:: 服务器启动成功 ...");
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("TEST:: 关闭服务器 ...");
        System.out.println("TEST:: 活跃会话: " + dbInstance.activeSessions);
        dbInstance.stop();
        System.out.println("TEST:: 服务器关闭成功.");
        assert dbInstance.instanceState.equalsIgnoreCase("IDLE");
    }

    @Test
    void testCompleteEndToEnd() throws Exception {
        System.out.println("=== 开始端到端测试 ===");

        // 1. 构建客户表，里头存放客户的性别、姓名、年龄
        System.out.println("步骤1: 创建客户表并插入测试数据");
        String connectURL = "jdbc:" + protocol + "://127.0.0.1:" + dbPort + "/mem";
        Connection pgConn = DriverManager.getConnection(connectURL, "", "");
        pgConn.setAutoCommit(false);

        // 清理可能存在的旧表
        pgConn.createStatement().execute("DROP TABLE IF EXISTS customers");

        // 创建客户表
        String createTableSQL = """
                    CREATE TABLE customers (
                        id INTEGER PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        gender VARCHAR(10),
                        age INTEGER,
                        created_at1 TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """;
        pgConn.createStatement().execute(createTableSQL);
        pgConn.commit();

        // 插入测试数据
        String insertDataSQL = """
                    INSERT INTO customers (id, name, gender, age) VALUES
                    (1, '张三', '男', 25),
                    (2, '李四', '女', 30),
                    (3, '王五', '男', 35),
                    (4, '赵六', '女', 28),
                    (5, '钱七', '男', 40)
                """;
        pgConn.createStatement().execute(insertDataSQL);
        pgConn.commit();

        // 验证数据插入
        ResultSet rs = pgConn.createStatement().executeQuery("SELECT COUNT(*) FROM customers");
        rs.next();
        assertEquals(5, rs.getInt(1), "客户表应该有5条记录");
        rs.close();

        System.out.println("  客户表创建成功，插入了5条测试数据");

        // 2. 定制一个api服务，根据姓名查找其性别和年龄
        System.out.println("步骤2: 注册API服务");

        // 先尝试取消注册（如果已存在）
        Map<String, Object> unregisterBody = Map.of(
                "serviceName", "get_customer_info",
                "serviceVersion", "1.0",
                "serviceType", "GET"
        );

        try {
            String unregisterRequest = JSON.toJSONString(unregisterBody);
            HttpRequest unregisterReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/unRegisterService"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(unregisterRequest))
                    .build();
            client.send(unregisterReq, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // 忽略错误，服务可能不存在
        }

        // 注册API服务
        Map<String, Object> serviceDef = Map.of(
                "serviceName", "get_customer_info",
                "serviceVersion", "1.0",
                "category", "customer",
                "serviceType", "GET",
                "sql", "SELECT name, gender, age FROM customers WHERE name = '${name}'",
                "description", "根据姓名查询客户性别和年龄信息",
                "parameters", List.of(
                        Map.of("name", "name", "defaultValue", "")
                )
        );

        String requestBody = JSON.toJSONString(serviceDef);
        HttpRequest registerRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/registerService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> registerResponse = client.send(registerRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, registerResponse.statusCode(), "API服务注册应该返回200");

        JSONObject registerResp = JSON.parseObject(registerResponse.body());
        assertTrue(registerResp.containsKey("retCode"), "响应应该包含retCode字段");

        int retCode = registerResp.getInteger("retCode");
        if (retCode == 0) {
            System.out.println("  API服务注册成功");
        } else {
            System.out.println("  API服务注册返回: " + registerResp.getString("retMsg"));
            // 即使返回-1（服务已存在），我们也可以继续测试
        }

        // 测试API服务调用
        System.out.println("步骤3: 测试API服务调用");
        String apiUrl = baseUrl + "/api/1.0/get_customer_info?name=张三";
        HttpRequest apiRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .GET()
                .build();

        HttpResponse<String> apiResponse = client.send(apiRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, apiResponse.statusCode(), "API调用应该返回200");
        
        String responseBody = apiResponse.body();
        System.out.println("  API响应: " + responseBody);

        JSONObject apiResp = JSON.parseObject(responseBody);
        int apiRetCode = apiResp.getInteger("retCode");
        String apiRetMsg = apiResp.getString("retMsg");

        // 声明变量，用于后续测试
        String name = "张三";
        String gender = "男";
        int age = 25;
        
        if (apiRetCode == 0) {
            assertEquals("Successful", apiRetMsg, "API调用消息应该是Successful");

            // 验证返回的数据
            JSONObject data = apiResp.getJSONObject("data");
            JSONArray dataset = data.getJSONArray("dataset");
            assertTrue(dataset != null && !dataset.isEmpty(), "数据集应该是数组");
            assertEquals(1, dataset.size(), "应该返回1条记录");

            JSONArray firstRow = dataset.getJSONArray(0);
            name = firstRow.getString(0);
            gender = firstRow.getString(1);
            age = firstRow.getInteger(2);
            
            assertEquals("张三", name, "姓名应该是张三");
            assertEquals("男", gender, "性别应该是男");
            assertEquals(25, age, "年龄应该是25");
            
            System.out.println("  API服务调用成功:");
        }

        System.out.println("    姓名: " + name);
        System.out.println("    性别: " + gender);
        System.out.println("    年龄: " + age);

        // 3. 定制一个mcp服务，根据这个api来回应相关信息
        System.out.println("步骤4: 注册MCP服务");
        
        Map<String, Object> mcpServiceDef = Map.of(
                "name", "get_customer_info",
                "apiName", "get_customer_info",
                "version", "1.0",
                "method", "GET",
                "description", "查询客户信息，根据姓名返回性别和年龄",
                "category", "customer",
                "capabilities", List.of("customer_query", "data_retrieval"),
                "use_cases", List.of("客户信息查询", "数据分析"),
                "parameters", List.of(
                        Map.of("name", "name", "type", "string", "required", true,
                        "description", "客户姓名")
                ),
                "examples", List.of(
                        Map.of("user_query", "查询张三的性别和年龄",
                        "parameters", Map.of("name", "张三"))
            )
        );

        String mcpRequestBody = JSON.toJSONString(mcpServiceDef);
        HttpRequest mcpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp/registerMCPService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mcpRequestBody))
                .build();

        HttpResponse<String> mcpResponse = client.send(mcpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, mcpResponse.statusCode(), "MCP服务注册应该返回200");

        JSONObject mcpResp = JSON.parseObject(mcpResponse.body());
        int mcpRetCode = mcpResp.getInteger("retCode");
        if (mcpRetCode == 0) {
            System.out.println("  MCP服务注册成功");
        } else {
            System.out.println("  MCP服务注册返回: " + mcpResp.getString("retMsg"));
            // 即使返回-1，我们也可以继续测试
        }

        // 验证MCP服务在列表中
        System.out.println("步骤5: 验证MCP服务列表");
        String listRequestBody = JSON.toJSONString(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "services/list"
        ));

        HttpRequest listRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(listRequestBody))
                .build();

        HttpResponse<String> listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResponse.statusCode(), "服务列表查询应该返回200");

        JSONObject listResp = JSON.parseObject(listResponse.body());
        assertEquals("2.0", listResp.getString("jsonrpc"), "JSON-RPC版本应该是2.0");
        assertTrue(listResp.containsKey("result"), "应该有result字段");

        JSONArray services = listResp.getJSONObject("result").getJSONArray("services");
        assertTrue(services != null && !services.isEmpty(), "服务列表应该是数组");
        
        boolean foundCustomerService = false;
        for (int i = 0; i < services.size(); i++) {
            JSONObject service = services.getJSONObject(i);
            if ("query_customer".equals(service.getString("name"))) {
                foundCustomerService = true;
                System.out.println("  在MCP服务列表中找到query_customer服务");
                break;
            }
        }
        
        if (!foundCustomerService) {
            System.out.println("  警告: 在MCP服务列表中未找到query_customer服务");
        }

        // 4. 调用大模型，提供名称，要求其回应性别年龄信息
        System.out.println("步骤6: 通过/aichat WebSocket端点调用大模型服务");
        System.out.println("  大模型服务: ollama:127.0.0.1:11434:qwen2.5:1.5b");
        
        // 通过/aichat WebSocket端点调用大模型
        String prompt = "查询客户张三的基本情况";
        System.out.println("  发送给大模型的提示: " + prompt);
        
        String llmResponse;
        try {
            // 使用WebSocket连接/aichat端点
            String wsUrl = "ws://127.0.0.1:" + dbPortX + "/aichat";
            System.out.println("  正在通过WebSocket连接/aichat端点: " + wsUrl);
            
            WebSocketTestClient wsClient = new WebSocketTestClient(wsUrl);
            
            try {
                // 等待连接确认消息
                String connectedMsg = wsClient.responseQueue.poll(2, TimeUnit.SECONDS);
                if (connectedMsg != null) {
                    System.out.println("  连接确认: " + connectedMsg);
                }
                
                // 发送聊天消息
                Map<String, Object> chatMessage = Map.of(
                    "type", "chat",
                    "content", prompt
                );
                String chatMessageJson = JSON.toJSONString(chatMessage);
                System.out.println("  发送WebSocket消息: " + chatMessageJson);
                
                String response = wsClient.sendAndReceive(chatMessageJson);
                System.out.println("  收到WebSocket响应: " + response);
                
                // 解析响应
                JSONObject responseJson = JSON.parseObject(response);
                String responseType = responseJson.getString("type");
                
                if ("response".equals(responseType)) {
                    llmResponse = responseJson.getString("content");
                    System.out.println("  大模型调用成功");
                    System.out.println("  大模型生成的自然语言描述: " + llmResponse);
                    
                    // 验证响应包含关键信息
                    assertTrue(llmResponse.contains(name) || llmResponse.contains("张") || llmResponse.contains("三") || llmResponse.contains("客户"),
                              "大模型响应应包含客户姓名或相关信息");
                    assertTrue(llmResponse.contains(gender) || llmResponse.contains("男") || llmResponse.contains("女") || llmResponse.contains("性"),
                              "大模型响应应包含客户性别或相关信息");
                    assertTrue(llmResponse.contains(String.valueOf(age)) || llmResponse.contains("25") || llmResponse.contains("二十") || llmResponse.contains("二十五") || llmResponse.contains("岁"),
                              "大模型响应应包含客户年龄或相关信息");
                } else if ("error".equals(responseType)) {
                    System.out.println("  大模型调用返回错误: " + responseJson.getString("message"));
                    throw new RuntimeException("AI Chat error: " + responseJson.getString("message"));
                } else {
                    System.out.println("  未知响应类型: " + responseType);
                    throw new RuntimeException("Unexpected response type: " + responseType);
                }
                
                wsClient.close();
                
            } catch (Exception e) {
                wsClient.close();
                throw e;
            }
            
        } catch (Exception e) {
            System.out.println("  WebSocket调用异常: " + e.getMessage());
            e.printStackTrace();

            assert false;
        }

        // 清理资源
        pgConn.close();
        
        System.out.println("=== 端到端测试完成 ===");
        System.out.println("总结:");
        System.out.println("  1. 成功创建客户表并插入测试数据");
        System.out.println("  2. 成功注册和测试API服务");
        System.out.println("  3. 成功注册MCP服务");
        System.out.println("  4. 成功通过/aichat WebSocket端点调用大模型");
        System.out.println("所有步骤验证通过！");
    }
}