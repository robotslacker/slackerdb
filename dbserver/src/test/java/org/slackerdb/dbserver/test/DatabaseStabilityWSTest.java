package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.TimeZone;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 使用 WebSocket SQL REPL 进行数据库稳定性测试。
 * 仿效 DatabaseStabilityTest，但通过 WebSocket 执行 SQL 操作。
 */
public class DatabaseStabilityWSTest {
    private int dbPortX;

    // 从 InstanceXTest 复制的 WebSocket 客户端辅助类
    static class WebSocketTestClient {
        private final WebSocket ws;
        private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
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
            String response = responseQueue.poll(5, TimeUnit.SECONDS);
            if (response == null) {
                throw new RuntimeException("Timeout waiting for response");
            }
            return response;
        }

        public void close() throws Exception {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    /**
     * 通过 WebSocket 执行一条 SQL 并获取结果（适用于查询）。
     * 返回结果行数（如果成功），否则返回 -1 并增加错误计数。
     */
    private int executeSqlViaWebSocket(WebSocketTestClient client, String sessionId, String sql,
                                       AtomicInteger errorCount, String threadName) {
        try {
            // 1. 发送 exec 消息
            JSONObject execData = new JSONObject();
            execData.put("sessionId", sessionId);
            execData.put("sql", sql);
            execData.put("fetchSize", 100);
            JSONObject execMsg = new JSONObject();
            execMsg.put("id", "exec-" + System.currentTimeMillis());
            execMsg.put("type", "exec");
            execMsg.put("data", execData);
            String execResp = client.sendAndReceive(execMsg.toString());
//            System.err.println(execResp);
            JSONObject execJson = JSONObject.parseObject(execResp);
            // 检查错误响应
            if (execJson.containsKey("error")) {
                System.err.println(threadName + ": Exec error: " + execJson.getString("error"));
                errorCount.incrementAndGet();
                return -1;
            }
            if (!"exec".equals(execJson.getString("type"))) {
                System.err.println(threadName + ": Unexpected response type for exec: " + execJson);
                errorCount.incrementAndGet();
                return -1;
            }
            String taskId = execJson.getJSONObject("data").getString("taskId");
            if (taskId == null || taskId.isEmpty()) {
                System.err.println(threadName + ": No taskId in exec response");
                errorCount.incrementAndGet();
                return -1;
            }

            // 2. 轮询 fetch 直到完成
            JSONObject fetchData = new JSONObject();
            fetchData.put("sessionId", sessionId);
            fetchData.put("taskId", taskId);
            fetchData.put("maxRows", 100);
            JSONObject fetchMsg = new JSONObject();
            fetchMsg.put("id", "fetch-" + System.currentTimeMillis());
            fetchMsg.put("type", "fetch");
            fetchMsg.put("data", fetchData);

            JSONObject fetchJson = null;
            for (int i = 0; i < 30; i++) { // 最多尝试 30 次，每次等待 100ms
                String fetchResp = client.sendAndReceive(fetchMsg.toString());
                fetchJson = JSONObject.parseObject(fetchResp);
                // 检查错误响应
                if (fetchJson.containsKey("error")) {
                    System.err.println(threadName + ": Fetch error: " + fetchJson.getString("error"));
                    errorCount.incrementAndGet();
                    return -1;
                }
                if (!"running".equals(fetchJson.getJSONObject("data").getString("status"))) {
                    break;
                }
                Thread.sleep(100);
            }
            String status = fetchJson.getJSONObject("data").getString("status");
            if ("error".equals(status)) {
                System.err.println(threadName + ": SQL error: " + fetchJson.getJSONObject("data").getString("error"));
                errorCount.incrementAndGet();
                return -1;
            }
            if (!"completed".equals(status)) {
                System.err.println(threadName + ": Unexpected fetch status: " + status);
                errorCount.incrementAndGet();
                return -1;
            }

            // 3. 解析结果
            if (fetchJson.getJSONObject("data").containsKey("rows")) {
                return fetchJson.getJSONObject("data").getJSONArray("rows").size();
            } else if (fetchJson.getJSONObject("data").containsKey("updateCount")) {
                return fetchJson.getJSONObject("data").getIntValue("updateCount");
            } else {
                // 没有结果集，可能是 DDL
                return 0;
            }
        } catch (Exception e) {
            e.printStackTrace();
            errorCount.incrementAndGet();
            return -1;
        }
    }

    /**
     * 创建一个 WebSocket 会话。
     */
    private String createSession(WebSocketTestClient client) throws Exception {
        JSONObject startMsg = new JSONObject();
        startMsg.put("id", "start-1");
        startMsg.put("type", "start");
        startMsg.put("data", new JSONObject());
        String startResp = client.sendAndReceive(startMsg.toString());
        JSONObject startJson = JSONObject.parseObject(startResp);
        if (!"start".equals(startJson.getString("type"))) {
            throw new RuntimeException("Failed to start session: " + startResp);
        }
        return startJson.getJSONObject("data").getString("sessionId");
    }

    /**
     * 关闭一个 WebSocket 会话。
     */
    private void closeSession(WebSocketTestClient client, String sessionId) throws Exception {
        JSONObject closeData = new JSONObject();
        closeData.put("sessionId", sessionId);
        JSONObject closeMsg = new JSONObject();
        closeMsg.put("id", "close-1");
        closeMsg.put("type", "close");
        closeMsg.put("data", closeData);
        client.sendAndReceive(closeMsg.toString());
    }

    @Test
    public void testDatabaseStabilityWithWebSocket() {
        int THREAD_COUNT = 5;
        long taskCount = 100; // 每个线程执行的任务数（为了快速测试）
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setPortX(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        dbPortX = serverConfiguration.getPortX();

        DBInstance dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        printSystemStatus();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicLong totalFinishedTest = new AtomicLong(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            int threadId = i;
            executor.submit(() -> {
                String threadName = "Thread-" + threadId;
                WebSocketTestClient client = null;
                String sessionId = null;
                try {
                    client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");
                    sessionId = createSession(client);
                    String tableName = "ws_table_" + threadId;
                    boolean tableCreated = false;

                    while (totalFinishedTest.get() < taskCount) {
                        totalFinishedTest.incrementAndGet();

                        // 创建表（如果尚未创建）
                        if (!tableCreated) {
                            String createSQL = String.format(
                                    "CREATE TABLE IF NOT EXISTS %s (name VARCHAR(255), value INT)",
                                    tableName);
                            int result = executeSqlViaWebSocket(client, sessionId, createSQL, errorCount, threadName);
                            if (result >= 0) {
                                tableCreated = true;
                            }
                        }

                        // 插入
                        String insertSQL = String.format(
                                "INSERT INTO %s (name, value) VALUES ('TestName', %d)",
                                tableName, (int) (Math.random() * 1000));
                        int inserted = executeSqlViaWebSocket(client, sessionId, insertSQL, errorCount, threadName);
                        if (inserted != 1) {
                            System.err.println(threadName + ": Insert failed, inserted " + inserted);
                        }

                        // 更新
                        String updateSQL = String.format(
                                "UPDATE %s SET value = %d WHERE name = 'TestName'",
                                tableName, (int) (Math.random() * 1000));
                        int updated = executeSqlViaWebSocket(client, sessionId, updateSQL, errorCount, threadName);
                        if (updated < 0) {
                            System.err.println(threadName + ": Update failed");
                        }

                        // 查询
                        String selectSQL = String.format(
                                "SELECT * FROM %s WHERE name = 'TestName'",
                                tableName);
                        int rows = executeSqlViaWebSocket(client, sessionId, selectSQL, errorCount, threadName);
                        if (rows <= 0) {
                            System.err.println(threadName + ": Select returned no rows");
                        }

                        // 删除
                        String deleteSQL = String.format(
                                "DELETE FROM %s WHERE name = 'TestName'",
                                tableName);
                        int deleted = executeSqlViaWebSocket(client, sessionId, deleteSQL, errorCount, threadName);
                        if (deleted < 0) {
                            System.err.println(threadName + ": Delete failed");
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    errorCount.incrementAndGet();
                } finally {
                    if (client != null && sessionId != null) {
                        try {
                            closeSession(client, sessionId);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    if (client != null) {
                        try {
                            client.close();
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
            });
        }

        executor.shutdown();
        while (!executor.isTerminated()) {
            try {
                Sleeper.sleep(3 * 1000);
            } catch (InterruptedException ignored) {}
        }

        printSystemStatus();
        System.out.println("Database will shutting down ...");
        dbInstance.stop();
        printSystemStatus();

        if (errorCount.get() > 0) {
            System.err.println("WebSocket stability test completed with " + errorCount.get() + " errors.");
        } else {
            System.out.println("WebSocket stability test completed successfully with no errors.");
        }
    }

    private static void printSystemStatus() {
        Runtime runtime = Runtime.getRuntime();
        long memoryUsed = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        int threadCount = Thread.activeCount();
        System.out.printf("Memory used: %d bytes, Max memory: %d bytes. Active threads: %d%n",
                memoryUsed, maxMemory, threadCount);
    }
}