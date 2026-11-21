package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for "connection has an open result".
 *
 * <p>Two ways a REPL session can be left with a pending result: the client abandons a
 * paginated result, or the statement is interrupted by maxExecutionTime. Reusing the
 * session afterwards must not fail with DuckDB's open-result error.</p>
 */
class SqlReplOpenResultTest {
    static DBInstance dbInstance;
    static int wsPort;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setSqlHistory("OFF");
        configuration.setDataServiceHistory("OFF");
        configuration.setDataServiceSchema("");
        configuration.setMax_repl_concurrent_queries(2);

        dbInstance = new DBInstance(configuration);
        dbInstance.start();
        wsPort = configuration.getPortX();
    }

    @AfterAll
    static void tearDownAll() {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    @Test
    void abandonedPagedResultDoesNotPoisonTheSession() throws Exception {
        try (WsClient client = new WsClient(wsPort)) {
            String sid = client.start();

            String taskId = client.exec(sid, "SELECT * FROM range(100000)", 60000);
            JSONObject firstPage = client.fetchFirstPage(sid, taskId, 10);
            assertEquals("completed", firstPage.getJSONObject("data").getString("status"));
            assertTrue(firstPage.getJSONObject("data").getBooleanValue("hasMore"),
                    "first page must leave the result open on purpose");

            // New statement while the previous paginated result is still open.
            String nextTask = client.exec(sid, "SELECT 42 AS answer", 60000);
            JSONObject next = client.fetchFirstPage(sid, nextTask, 10);
            assertEquals("completed", next.getJSONObject("data").getString("status"), next.toString());
            assertEquals(42, next.getJSONObject("data").getJSONArray("rows").getJSONObject(0).getIntValue("answer"));
        }
    }

    @Test
    void interruptedStatementCanBeFollowedByANewQuery() throws Exception {
        try (WsClient client = new WsClient(wsPort)) {
            String sid = client.start();

            String taskId = client.exec(sid, "SELECT sum(range) FROM range(3000000000)", 100);
            JSONObject failed = client.fetchUntilSettled(sid, taskId);
            assertEquals("error", failed.getJSONObject("data").getString("status"), failed.toString());
            assertTrue(String.valueOf(failed.getJSONObject("data").getString("error")).toLowerCase()
                            .contains("interrupt"),
                    "expected the statement to be interrupted: " + failed);

            String nextTask = client.exec(sid, "SELECT 7 AS answer", 60000);
            JSONObject next = client.fetchUntilSettled(sid, nextTask);
            assertEquals("completed", next.getJSONObject("data").getString("status"), next.toString());
            assertEquals(7, next.getJSONObject("data").getJSONArray("rows").getJSONObject(0).getIntValue("answer"));
        }
    }

    // ---------------------------------------------------------------- ws client

    static final class WsClient implements WebSocket.Listener, AutoCloseable {
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();
        private final WebSocket ws;

        WsClient(int port) throws Exception {
            ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/sql/ws"), this)
                    .get(15, TimeUnit.SECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                queue.offer(buffer.toString());
                buffer.setLength(0);
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        String start() throws Exception {
            JSONObject message = new JSONObject();
            message.put("id", "s-" + System.nanoTime());
            message.put("type", "start");
            message.put("data", new JSONObject());
            JSONObject response = call(message);
            String sid = response.getJSONObject("data").getString("sessionId");
            assertNotNull(sid);
            return sid;
        }

        String exec(String sid, String sql, long maxExecutionTimeMs) throws Exception {
            JSONObject data = new JSONObject();
            data.put("sessionId", sid);
            data.put("sql", sql);
            data.put("fetchSize", 1000);
            data.put("maxExecutionTime", maxExecutionTimeMs);
            JSONObject message = new JSONObject();
            message.put("id", "e-" + System.nanoTime());
            message.put("type", "exec");
            message.put("data", data);
            JSONObject response = call(message);
            return response.getJSONObject("data").getString("taskId");
        }

        JSONObject fetch(String sid, String taskId, int maxRows) throws Exception {
            JSONObject data = new JSONObject();
            data.put("sessionId", sid);
            data.put("taskId", taskId);
            data.put("maxRows", maxRows);
            JSONObject message = new JSONObject();
            message.put("id", "f-" + System.nanoTime());
            message.put("type", "fetch");
            message.put("data", data);
            return call(message);
        }

        JSONObject fetchUntilSettled(String sid, String taskId) throws Exception {
            for (int i = 0; i < 600; i++) {
                JSONObject response = fetch(sid, taskId, 1000);
                String status = response.getJSONObject("data").getString("status");
                if (!"running".equals(status)) {
                    return response;
                }
                Thread.sleep(50);
            }
            throw new IllegalStateException("task did not settle");
        }

        /** Wait until the task finishes, then return the first page of its result. */
        JSONObject fetchFirstPage(String sid, String taskId, int maxRows) throws Exception {
            for (int i = 0; i < 600; i++) {
                JSONObject response = fetch(sid, taskId, maxRows);
                String status = response.getJSONObject("data").getString("status");
                if (!"running".equals(status)) {
                    return response;
                }
                Thread.sleep(50);
            }
            throw new IllegalStateException("task did not settle");
        }

        private JSONObject call(JSONObject message) throws Exception {
            ws.sendText(message.toJSONString(), true).get(10, TimeUnit.SECONDS);
            String response = queue.poll(60, TimeUnit.SECONDS);
            if (response == null) {
                throw new IllegalStateException("ws response timeout");
            }
            JSONObject parsed = JSONObject.parseObject(response);
            if (parsed.containsKey("error")) {
                throw new IllegalStateException(parsed.getString("error"));
            }
            return parsed;
        }

        @Override
        public void close() {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (Exception ignored) {
            }
        }
    }
}
