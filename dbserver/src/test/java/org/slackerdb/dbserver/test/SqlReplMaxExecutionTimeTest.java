package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL REPL（WebSocket {@code /sql/ws}）与服务器 {@code max_execution_time} 的关系。
 *
 * <p>验证：</p>
 * <ol>
 *   <li>REPL 连接跟随服务器 GLOBAL（backend 连接是内部豁免的 0，REPL 必须 RESET 回 global）；</li>
 *   <li>客户端可以按会话执行 {@code SET max_execution_time=0} 覆盖；</li>
 *   <li>{@code exec.data.maxExecutionTime} 按单次执行覆盖，响应回显本次生效值，执行后恢复会话原值；</li>
 *   <li>覆盖只作用于该会话/该次执行，新会话仍然回到 GLOBAL。</li>
 * </ol>
 */
public class SqlReplMaxExecutionTimeTest {

    static final String SLOW_SQL = "SELECT count(*) AS v FROM range(5000000000)";

    static int dbPortX;
    static DBInstance dbInstance;

    @BeforeAll
    static void initAll() throws ServerException {
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        serverConfiguration.setPort(0);
        serverConfiguration.setPortX(0);
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        serverConfiguration.setDataServiceHistory("OFF");
        serverConfiguration.setMax_execution_time(100);
        dbPortX = serverConfiguration.getPortX();

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();
        assertEquals("RUNNING", dbInstance.instanceState);
    }

    @AfterAll
    static void tearDownAll() {
        dbInstance.stop();
        assertEquals("IDLE", dbInstance.instanceState);
    }

    @Test
    void replFollowsGlobalAndAllowsPerSessionOverride() throws Exception {
        // backend 连接永远 0（内部豁免）；池连接看到的是 GLOBAL
        assertEquals("0", setting(dbInstance.backendSysConnection), "backend 连接必须始终豁免");
        assertEquals("100", poolSetting(), "连接池连接应看到服务器 GLOBAL 设置");

        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");
        try {
            // 1. 默认跟随 GLOBAL：慢查询应超时
            String session1 = startSession(client);
            JSONObject timeout = execAndWait(client, session1, SLOW_SQL, null, 10_000);
            assertEquals("error", timeout.getString("status"), "REPL 慢查询应被 GLOBAL 超时中断：" + timeout);
            assertTrue(timeout.getString("error").contains("maximum execution time"),
                    "超时错误信息应来自 DuckDB：" + timeout);

            // 2. 同一会话内 SET 覆盖为 0：慢查询应正常完成
            assertEquals("completed",
                    execAndWait(client, session1, "SET SESSION max_execution_time=0", null, 10_000)
                            .getString("status"));
            JSONObject completed = execAndWait(client, session1, SLOW_SQL, null, 30_000);
            assertEquals("completed", completed.getString("status"), "会话内 SET 0 后应不再超时：" + completed);
            assertEquals(5_000_000_000L, completed.getJSONArray("rows").getJSONObject(0).getLongValue("v"));

            // 3. 新会话不继承上一个会话的覆盖，仍然跟随 GLOBAL
            String session2 = startSession(client);
            JSONObject timeoutAgain = execAndWait(client, session2, SLOW_SQL, null, 10_000);
            assertEquals("error", timeoutAgain.getString("status"), "新 REPL 会话必须回到 GLOBAL：" + timeoutAgain);
        } finally {
            client.close();
        }
    }

    @Test
    void perExecOverrideIsAppliedEchoedAndRestored() throws Exception {
        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");
        try {
            String sessionId = startSession(client);

            // 1. 显式 20ms：exec 响应回显 20，慢查询超时
            JSONObject exec20 = exec(client, sessionId, SLOW_SQL, 20L);
            assertEquals(20L, exec20.getLongValue("maxExecutionTime"),
                    "exec 响应应回显本次生效值：" + exec20);
            JSONObject timedOut = fetchUntilDone(client, sessionId, exec20.getString("taskId"), 10_000);
            assertEquals("error", timedOut.getString("status"), timedOut.toJSONString());

            // 2. 缺省：回显会话真实值（GLOBAL=100），且实际执行也用 100
            JSONObject probe = exec(client, sessionId, "SELECT current_setting('max_execution_time') AS v", null);
            assertEquals(100L, probe.getLongValue("maxExecutionTime"),
                    "缺省时应回显会话真实值：" + probe);
            JSONObject probeRows = fetchUntilDone(client, sessionId, probe.getString("taskId"), 10_000);
            assertEquals(100L, probeRows.getJSONArray("rows").getJSONObject(0).getLongValue("v"),
                    "上次 20ms 覆盖必须已恢复：" + probeRows);

            // 3. 客户端 SET 会话值 5000；单次 20ms 失败后仍应回到 5000
            assertEquals("completed",
                    execAndWait(client, sessionId, "SET SESSION max_execution_time=5000", null, 10_000)
                            .getString("status"));
            JSONObject exec20Again = exec(client, sessionId, SLOW_SQL, 20L);
            assertEquals("error", fetchUntilDone(client, sessionId, exec20Again.getString("taskId"), 10_000)
                    .getString("status"));

            JSONObject after = exec(client, sessionId, "SELECT current_setting('max_execution_time') AS v", null);
            assertEquals(5000L, after.getLongValue("maxExecutionTime"),
                    "应回显客户端会话值：" + after);
            JSONObject afterRows = fetchUntilDone(client, sessionId, after.getString("taskId"), 10_000);
            assertEquals(5000L, afterRows.getJSONArray("rows").getJSONObject(0).getLongValue("v"),
                    "单次覆盖不能污染客户端会话值：" + afterRows);

            // 4. 0 = 本次不限制（即使会话是 5000，这里用 0 应跑完 5e9 行）
            JSONObject unlimited = exec(client, sessionId, SLOW_SQL, 0L);
            assertEquals(0L, unlimited.getLongValue("maxExecutionTime"));
            JSONObject unlimitedRows = fetchUntilDone(client, sessionId, unlimited.getString("taskId"), 30_000);
            assertEquals("completed", unlimitedRows.getString("status"), unlimitedRows.toJSONString());
            assertEquals(5_000_000_000L,
                    unlimitedRows.getJSONArray("rows").getJSONObject(0).getLongValue("v"));
        } finally {
            client.close();
        }
    }

    /** 超时发生在显式事务里：事务被置为 aborted，恢复要等 ROLLBACK 之后的 exec 补做。 */
    @Test
    void overrideIsRestoredAfterAnAbortedTransaction() throws Exception {
        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");
        try {
            String sessionId = startSession(client);

            assertEquals("completed",
                    execAndWait(client, sessionId, "SET SESSION max_execution_time=5000", null, 10_000)
                            .getString("status"));
            assertEquals("completed",
                    execAndWait(client, sessionId, "BEGIN", null, 10_000).getString("status"));

            JSONObject timedOut = execAndWait(client, sessionId, SLOW_SQL, 20L, 10_000);
            assertEquals("error", timedOut.getString("status"), "事务内超时应失败：" + timedOut);

            // 事务处于 aborted 时 ROLLBACK 本身能执行，但恢复仍会失败；下一次 exec 提交时补做
            assertEquals("completed",
                    execAndWait(client, sessionId, "ROLLBACK", null, 10_000).getString("status"));

            JSONObject probe = exec(client, sessionId, "SELECT current_setting('max_execution_time') AS v", null);
            JSONObject rows = fetchUntilDone(client, sessionId, probe.getString("taskId"), 10_000);
            assertEquals(5000L, rows.getJSONArray("rows").getJSONObject(0).getLongValue("v"),
                    "事务中止后，单次覆盖仍必须恢复会话原值：" + rows);
        } finally {
            client.close();
        }
    }

    @Test
    void invalidPerExecValueIsRejected() throws Exception {        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");
        try {
            String sessionId = startSession(client);
            for (Object invalid : new Object[]{-1, "abc", 1.5}) {
                JSONObject response = sendExec(client, sessionId, SLOW_SQL, invalid);
                assertTrue(response.containsKey("error"),
                        "非法 maxExecutionTime=" + invalid + " 必须被拒绝：" + response);
                assertTrue(response.getString("error").contains("maxExecutionTime"),
                        response.toJSONString());
            }
            // 拒绝后会话仍然可用
            assertEquals("completed",
                    execAndWait(client, sessionId, "SELECT 1 AS v", null, 10_000).getString("status"));
        } finally {
            client.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    static String setting(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT current_setting('max_execution_time')")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    static String poolSetting() throws SQLException {
        Connection connection = dbInstance.dbDataSourcePool.getConnection();
        try {
            return setting(connection);
        } finally {
            dbInstance.dbDataSourcePool.releaseConnection(connection);
        }
    }

    static String startSession(WebSocketTestClient client) throws Exception {
        JSONObject message = new JSONObject();
        message.put("id", "start");
        message.put("type", "start");
        message.put("data", new JSONObject());
        JSONObject response = JSONObject.parseObject(client.sendAndReceive(message.toString()));
        String sessionId = response.getJSONObject("data").getString("sessionId");
        assertNotNull(sessionId);
        return sessionId;
    }

    /** 发送 exec（可选 maxExecutionTime），返回 exec 响应的 data。 */
    static JSONObject exec(WebSocketTestClient client, String sessionId, String sql, Long maxExecutionTime)
            throws Exception {
        JSONObject execData = new JSONObject();
        execData.put("sessionId", sessionId);
        execData.put("sql", sql);
        execData.put("fetchSize", 100);
        if (maxExecutionTime != null) {
            execData.put("maxExecutionTime", maxExecutionTime);
        }
        JSONObject response = sendExecRaw(client, execData);
        assertTrue(response.getJSONObject("data") != null, "exec 失败：" + response);
        assertNotNull(response.getJSONObject("data").getString("taskId"));
        return response.getJSONObject("data");
    }

    /** 发送带任意 maxExecutionTime 值的 exec，返回原始响应（用于非法值断言）。 */
    static JSONObject sendExec(WebSocketTestClient client, String sessionId, String sql, Object maxExecutionTime)
            throws Exception {
        JSONObject execData = new JSONObject();
        execData.put("sessionId", sessionId);
        execData.put("sql", sql);
        execData.put("fetchSize", 100);
        execData.put("maxExecutionTime", maxExecutionTime);
        return sendExecRaw(client, execData);
    }

    private static JSONObject sendExecRaw(WebSocketTestClient client, JSONObject execData) throws Exception {
        JSONObject execMessage = new JSONObject();
        execMessage.put("id", "exec");
        execMessage.put("type", "exec");
        execMessage.put("data", execData);
        return JSONObject.parseObject(client.sendAndReceive(execMessage.toString()));
    }

    /** 轮询 fetch 直到任务不再 running。 */
    static JSONObject fetchUntilDone(WebSocketTestClient client, String sessionId, String taskId, long deadlineMs)
            throws Exception {
        JSONObject fetchData = new JSONObject();
        fetchData.put("sessionId", sessionId);
        fetchData.put("taskId", taskId);
        fetchData.put("maxRows", 100);
        JSONObject fetchMessage = new JSONObject();
        fetchMessage.put("id", "fetch");
        fetchMessage.put("type", "fetch");
        fetchMessage.put("data", fetchData);

        long deadline = System.currentTimeMillis() + deadlineMs;
        while (System.currentTimeMillis() < deadline) {
            JSONObject response = JSONObject.parseObject(client.sendAndReceive(fetchMessage.toString()));
            JSONObject data = response.getJSONObject("data");
            if (!"running".equals(data.getString("status"))) {
                return data;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("SQL 未在 " + deadlineMs + "ms 内结束");
    }

    /** exec + fetch 直到结束。 */
    static JSONObject execAndWait(WebSocketTestClient client, String sessionId, String sql,
                                  Long maxExecutionTime, long deadlineMs) throws Exception {
        JSONObject execData = exec(client, sessionId, sql, maxExecutionTime);
        return fetchUntilDone(client, sessionId, execData.getString("taskId"), deadlineMs);
    }

    /** 极简 WebSocket 客户端（与 {@link InstanceXSqlReplTest} 中的实现同构）。 */
    static class WebSocketTestClient {
        private final WebSocket ws;
        private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
        private StringBuilder currentMessage = new StringBuilder();

        WebSocketTestClient(String url) throws Exception {
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
                    });
            ws = wsFuture.join();
            Thread.sleep(100);
        }

        String sendAndReceive(String message) throws Exception {
            responseQueue.clear();
            currentMessage = new StringBuilder();
            ws.sendText(message, true);
            String response = responseQueue.poll(5, TimeUnit.SECONDS);
            if (response == null) {
                throw new AssertionError("等待 REPL 响应超时");
            }
            return response;
        }

        void close() throws Exception {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }
}
