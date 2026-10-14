package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONArray;
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
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class InstanceXSqlReplTest {
    static int dbPort;
    static int dbPortX;
    static DBInstance dbInstance;

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

    // ------------------------------------------------------------------
    // 以下为 SQL REPL 审计问题的回归用例
    // ------------------------------------------------------------------

    private static String startSession(WebSocketTestClient client) throws Exception {
        JSONObject startMsg = new JSONObject();
        startMsg.put("id", "start");
        startMsg.put("type", "start");
        startMsg.put("data", new JSONObject());
        JSONObject startJson = JSONObject.parseObject(client.sendAndReceive(startMsg.toString()));
        String sessionId = startJson.getJSONObject("data").getString("sessionId");
        assert sessionId != null && !sessionId.isEmpty();
        return sessionId;
    }

    /** 提交一条 SQL 并轮询 fetch 直到任务结束，返回最后一次 fetch 的响应。 */
    private static JSONObject execAndCollect(WebSocketTestClient client, String sessionId, String sql,
                                             int maxRows) throws Exception {
        JSONObject execData = new JSONObject();
        execData.put("sessionId", sessionId);
        execData.put("sql", sql);
        JSONObject execMsg = new JSONObject();
        execMsg.put("id", "exec");
        execMsg.put("type", "exec");
        execMsg.put("data", execData);
        JSONObject execJson = JSONObject.parseObject(client.sendAndReceive(execMsg.toString()));
        String taskId = execJson.getJSONObject("data").getString("taskId");
        assert taskId != null && !taskId.isEmpty() : "exec 未返回 taskId: " + execJson;

        JSONObject fetchMsg = new JSONObject();
        fetchMsg.put("id", "fetch");
        fetchMsg.put("type", "fetch");
        for (int i = 0; i < 60; i++) {
            JSONObject fetchData = new JSONObject();
            fetchData.put("sessionId", sessionId);
            fetchData.put("taskId", taskId);
            fetchData.put("maxRows", maxRows);
            fetchMsg.put("data", fetchData);
            JSONObject fetchJson = JSONObject.parseObject(client.sendAndReceive(fetchMsg.toString()));
            String status = fetchJson.getJSONObject("data").getString("status");
            if (!"running".equals(status)) {
                return fetchJson;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("fetch 超时");
    }

    /**
     * 分页必须不丢行：历史实现里 {@code while (rs.next() && fetched < maxRows)} 的短路求值
     * 会在每次页边界多读并丢弃一行，7 行/maxRows=2 只能拿到 5 行。
     */
    @Test
    void testSqlReplPagingKeepsEveryRow() throws Exception {
        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");
        String sessionId = startSession(client);

        int[][] cases = {{7, 2}, {10, 3}, {10, 4}, {1, 1}, {10, 1}};
        for (int[] testCase : cases) {
            int rowCount = testCase[0];
            int pageSize = testCase[1];

            JSONObject execData = new JSONObject();
            execData.put("sessionId", sessionId);
            execData.put("sql", "SELECT i FROM range(0," + rowCount + ") t(i)");
            JSONObject execMsg = new JSONObject();
            execMsg.put("id", "exec");
            execMsg.put("type", "exec");
            execMsg.put("data", execData);
            JSONObject execJson = JSONObject.parseObject(client.sendAndReceive(execMsg.toString()));
            String taskId = execJson.getJSONObject("data").getString("taskId");

            java.util.List<Integer> seen = new java.util.ArrayList<>();
            for (int page = 0; page < rowCount + 5; page++) {
                JSONObject fetchData = new JSONObject();
                fetchData.put("sessionId", sessionId);
                fetchData.put("taskId", taskId);
                fetchData.put("maxRows", pageSize);
                JSONObject fetchMsg = new JSONObject();
                fetchMsg.put("id", "fetch");
                fetchMsg.put("type", "fetch");
                fetchMsg.put("data", fetchData);
                JSONObject fetchJson = JSONObject.parseObject(client.sendAndReceive(fetchMsg.toString()));
                JSONObject data = fetchJson.getJSONObject("data");
                if ("running".equals(data.getString("status"))) {
                    page--;
                    Thread.sleep(50);
                    continue;
                }
                JSONArray rows = data.getJSONArray("rows");
                for (int i = 0; rows != null && i < rows.size(); i++) {
                    seen.add(rows.getJSONObject(i).getInteger("i"));
                }
                if (!data.getBooleanValue("hasMore")) {
                    break;
                }
            }
            java.util.List<Integer> expected = new java.util.ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                expected.add(i);
            }
            assert seen.equals(expected)
                    : "分页丢行：rows=" + rowCount + " maxRows=" + pageSize + " got=" + seen + " expect=" + expected;
        }
        client.close();
    }

    /** maxRows <= 0 必须明确报错（历史实现会让客户端一直拿到 hasMore=true）。 */
    @Test
    void testSqlReplRejectsNonPositiveMaxRows() throws Exception {
        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");
        String sessionId = startSession(client);

        JSONObject execData = new JSONObject();
        execData.put("sessionId", sessionId);
        execData.put("sql", "SELECT i FROM range(0,3) t(i)");
        JSONObject execMsg = new JSONObject();
        execMsg.put("id", "exec");
        execMsg.put("type", "exec");
        execMsg.put("data", execData);
        String taskId = JSONObject.parseObject(client.sendAndReceive(execMsg.toString()))
                .getJSONObject("data").getString("taskId");

        JSONObject fetchData = new JSONObject();
        fetchData.put("sessionId", sessionId);
        fetchData.put("taskId", taskId);
        fetchData.put("maxRows", 0);
        JSONObject fetchMsg = new JSONObject();
        fetchMsg.put("id", "fetch");
        fetchMsg.put("type", "fetch");
        fetchMsg.put("data", fetchData);
        JSONObject response = JSONObject.parseObject(client.sendAndReceive(fetchMsg.toString()));
        assert response.containsKey("error") : "maxRows=0 应返回错误对象：" + response;
        assert response.getString("error").contains("positive") : response.toJSONString();
        client.close();
    }

    /**
     * DATE/TIME/TIMESTAMPTZ/BLOB/LIST 等列必须能回包。
     *
     * <p>历史实现里这些值让 ObjectMapper 序列化失败，而异常被 catch 吞掉，
     * 客户端永远收不到任何响应（不是报错，是彻底静默）。</p>
     */
    @Test
    void testSqlReplRepliesForNonScalarTypes() throws Exception {
        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");
        String sessionId = startSession(client);

        String[][] types = {
                {"DATE", "DATE '2026-10-09'"},
                {"TIME", "TIME '12:34:56'"},
                {"TIMESTAMPTZ", "TIMESTAMPTZ '2026-10-09 12:34:56+00'"},
                {"BLOB", "'\\xDE\\xAD'::BLOB"},
                {"LIST", "[1,2,3]"},
                {"JSON", "'{\"a\":1}'::JSON"}
        };
        for (String[] type : types) {
            JSONObject response = execAndCollect(client, sessionId, "SELECT " + type[1] + " AS v", 10);
            JSONObject data = response.getJSONObject("data");
            assert data != null : type[0] + " 未回包（静默丢弃）";
            assert "completed".equals(data.getString("status")) : type[0] + " 状态异常：" + response;
            assert data.getJSONArray("rows").getJSONObject(0).get("v") != null : type[0] + " 取值为空：" + response;
        }
        client.close();
    }

    /** REPL 必须支持 PL/SQL 匿名块（与 PG 协议路径同一个引擎）。 */
    @Test
    void testSqlReplSupportsPlSqlBlock() throws Exception {
        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");
        String sessionId = startSession(client);

        String block = """
                DO $$
                DECLARE
                    i     INTEGER := 1;
                    total INTEGER := 0;
                BEGIN
                    CREATE OR REPLACE TABLE repl_plsql_t(n INTEGER);
                    WHILE i <= 6 LOOP
                        INSERT INTO repl_plsql_t VALUES (:i);
                        total := total + i;
                        i := i + 1;
                    END LOOP;
                    INSERT INTO repl_plsql_t VALUES (:total);
                END;
                $$;""";
        JSONObject blockResult = execAndCollect(client, sessionId, block, 100);
        assert "completed".equals(blockResult.getJSONObject("data").getString("status"))
                : "PL/SQL 块执行失败：" + blockResult;

        JSONObject count = execAndCollect(client, sessionId, "SELECT count(*) AS c FROM repl_plsql_t", 10);
        assert count.getJSONObject("data").getJSONArray("rows").getJSONObject(0).getInteger("c") == 7
                : "PL/SQL 效果不正确：" + count;
        client.close();
    }

    /** 二次 cancel 必须返回 no-running-statement（文档契约），而不是裸 error。 */
    @Test
    void testSqlReplSecondCancelContract() throws Exception {
        WebSocketTestClient client = new WebSocketTestClient("ws://127.0.0.1:" + dbPortX + "/sql/ws");
        String sessionId = startSession(client);

        JSONObject cancelData = new JSONObject();
        cancelData.put("sessionId", sessionId);
        JSONObject cancelMsg = new JSONObject();
        cancelMsg.put("id", "cancel");
        cancelMsg.put("type", "cancel");
        cancelMsg.put("data", cancelData);
        JSONObject first = JSONObject.parseObject(client.sendAndReceive(cancelMsg.toString()));
        assert first.getJSONObject("data") != null : "首次 cancel 不应返回裸 error：" + first;

        JSONObject second = JSONObject.parseObject(client.sendAndReceive(cancelMsg.toString()));
        assert second.getJSONObject("data") != null : "二次 cancel 不应返回裸 error：" + second;
        assert "no-running-statement".equals(second.getJSONObject("data").getString("status"))
                : "二次 cancel 应为 no-running-statement：" + second;
        client.close();
    }
}