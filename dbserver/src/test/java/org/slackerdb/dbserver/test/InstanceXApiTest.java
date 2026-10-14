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
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.ResultSet;
import com.alibaba.fastjson2.JSONArray;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InstanceXApiTest {
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
    void testStatusApiWithPluginInfo() throws Exception
    {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/status"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JSONObject statusResponse = JSONObject.parseObject(response.body());
        
        // 检查status响应包含必要的字段
        assert statusResponse.containsKey("parameters") : "Status response should contain 'parameters' field";
        assert statusResponse.containsKey("plugins") : "Status response should contain 'plugins' field";
        
        JSONObject parameters = statusResponse.getJSONObject("parameters");
        assert parameters.containsKey("mcpLlmKey") : "Parameters should contain 'mcpLlmKey' field";
        
        JSONArray plugins = statusResponse.getJSONArray("plugins");
        // 插件列表应该是一个数组
        assert plugins != null : "Plugins should not be null";
        
        System.out.println("TEST:: Status API test passed. Plugins count: " + plugins.size());
        System.out.println("TEST:: mcpLlmKey value: " + parameters.getString("mcpLlmKey"));
        
        // 验证mcpLlmKey的值（应该是默认值）
        String mcpLlmKey = parameters.getString("mcpLlmKey");
        assert mcpLlmKey != null : "mcpLlmKey should not be null";
        assert mcpLlmKey.isEmpty() : "mcpLlmKey should be empty string by default";
    }

    // ------------------------------------------------------------------
    // 以下为 API 审计问题的回归用例（每一项都对应一个实测过的缺陷）
    // ------------------------------------------------------------------

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(new URI("http://127.0.0.1:" + dbPortX + path))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + path))
                .GET()
                .header("Content-Type", "application/json");
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String serviceJson(String name, String type, String sql, String category, String searchPath) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"serviceName\":\"").append(name).append("\",");
        sb.append("\"serviceVersion\":\"1.0\",");
        sb.append("\"serviceType\":\"").append(type).append("\",");
        if (category != null) {
            sb.append("\"category\":\"").append(category).append("\",");
        }
        if (searchPath != null) {
            sb.append("\"searchPath\":\"").append(searchPath).append("\",");
        }
        sb.append("\"sql\":\"").append(sql.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"}");
        return sb.toString();
    }

    /** 重复注册必须被拒绝，且不能静默覆盖已有定义。 */
    @Test
    void testDuplicateRegistrationRejected() throws Exception {
        assertEquals(0, post("/api/registerService",
                serviceJson("regDupService", "GET", "SELECT 1 AS v", "regression", "main"))
                .body().contains("\"retCode\":0") ? 0 : -1, "首次注册应成功");
        String before = get("/api/1.0/regDupService", "snapshotLimit", "0").body();
        assertTrue(before.contains("[[1]]"), "首次查询结果应为 1，实际：" + before);

        HttpResponse<String> dup = post("/api/registerService",
                serviceJson("regDupService", "GET", "SELECT 2 AS v", "regression", "main"));
        JSONObject dupJson = JSONObject.parseObject(dup.body());
        assertEquals(-1, dupJson.getIntValue("retCode"), "重复注册必须被拒绝：" + dup.body());
        assertTrue(dupJson.getString("retMsg").contains("already registered"), dup.body());

        String after = get("/api/1.0/regDupService", "snapshotLimit", "0").body();
        assertTrue(after.contains("[[1]]"), "重复注册不得覆盖已有定义，实际：" + after);
    }

    /** 服务未声明 parameters 时，${var} 缺参同样必须报错（历史实现会返回占位符文本）。 */
    @Test
    void testMissingPlaceholderParameterRejected() throws Exception {
        post("/api/registerService",
                serviceJson("regPlaceholder", "GET", "SELECT '${must}' AS m", "regression", "main"));
        HttpResponse<String> missing = get("/api/1.0/regPlaceholder", "snapshotLimit", "0");
        assertTrue(missing.body().contains("Missing required parameters"),
                "缺参必须报错而不是返回占位符文本：" + missing.body());
        assertFalse(missing.body().contains("${must}"), "不能把 ${must} 原样返回：" + missing.body());

        HttpResponse<String> supplied = get("/api/1.0/regPlaceholder?must=ok", "snapshotLimit", "0");
        assertTrue(supplied.body().contains("\"ok\""), "提供参数后应正常替换：" + supplied.body());
    }

    /** 缓存键必须包含服务身份：SQL 文本相同的不同服务不能共用缓存。 */
    @Test
    void testQueryCacheIsolatedBetweenServices() throws Exception {
        post("/api/registerService",
                serviceJson("regCacheGood", "GET", "SELECT 1 AS v", "regression", "main"));
        HttpResponse<String> good = get("/api/1.0/regCacheGood");
        assertTrue(good.body().contains("\"retCode\":0"), good.body());

        // 同一个 SQL 文本 + 不存在的 searchPath：必须自己失败，而不是命中共用缓存
        post("/api/registerService",
                serviceJson("regCacheBad", "GET", "SELECT 1 AS v", "regression", "no_such_schema_xyz"));
        HttpResponse<String> bad = get("/api/1.0/regCacheBad");
        JSONObject badJson = JSONObject.parseObject(bad.body());
        assertEquals(-1, badJson.getIntValue("retCode"), "不同服务不得共用缓存：" + bad.body());
        assertFalse(bad.body().contains("\"cached\":true"), bad.body());
    }

    /** 未注册服务：HTTP 404 + retMsg（历史实现是 HTTP 200 + regMsg 笔误）。 */
    @Test
    void testUnknownServiceReturnsJson404() throws Exception {
        HttpResponse<String> response = get("/api/1.0/regNotRegistered");
        assertEquals(404, response.statusCode(), "HTTP 状态码应为 404");
        JSONObject json = JSONObject.parseObject(response.body());
        assertEquals(404, json.getIntValue("retCode"));
        assertNotNull(json.getString("retMsg"), "响应体应使用 retMsg：" + response.body());
    }

    /** category 是可选字段：API 文档里的示例（没有 category）必须可注册。 */
    @Test
    void testCategoryIsOptional() throws Exception {
        HttpResponse<String> response = post("/api/registerService",
                "{\"serviceName\":\"regNoCategory\",\"serviceVersion\":\"1.0\",\"serviceType\":\"GET\",\"sql\":\"SELECT 7 AS v\"}");
        assertEquals(0, JSONObject.parseObject(response.body()).getIntValue("retCode"), response.body());
        HttpResponse<String> query = get("/api/1.0/regNoCategory", "snapshotLimit", "0");
        assertTrue(query.body().contains("[[7]]"), query.body());
    }

    /** 下载接口必须真的返回内容（历史实现固定 HTTP 500）。 */
    @Test
    void testDownloadRegisteredServiceReturnsBody() throws Exception {
        HttpResponse<String> response = post("/api/downloadRegisteredService", "");
        assertEquals(200, response.statusCode(), "下载接口不应返回 500");
        assertTrue(response.body().trim().startsWith("["), "响应体应是服务定义 JSON 数组");
        assertTrue(response.body().contains("getUsers"), "应包含已加载的服务定义");
    }

    /** 类型矩阵：DATE/LIST/BLOB/JSON 等在 HTTP 面必须能正常返回。 */
    @Test
    void testValueTypesAreJsonSafe() throws Exception {
        post("/api/registerService", serviceJson("regTypes",
                "GET",
                "SELECT DATE '2026-10-09' AS d, [1,2,3] AS l, '\\xDE\\xAD'::BLOB AS b, "
                        + "'{\"a\":1}'::JSON AS j, TIME '12:34:56' AS t",
                "regression", "main"));
        String body = get("/api/1.0/regTypes", "snapshotLimit", "0").body();
        assertTrue(body.contains("\"retCode\":0"), body);
        assertTrue(body.contains("\"2026-10-09\""), "DATE 应为 ISO 字符串：" + body);
        assertTrue(body.contains("\"12:34:56\""), "TIME 应为 ISO 字符串：" + body);
        assertTrue(body.contains("[1,2,3]"), "LIST 应为 JSON 数组：" + body);
        assertTrue(body.contains("\"3q0=\""), "BLOB 应为 Base64：" + body);
        assertTrue(body.contains("{\"a\":1}"), "JSON 列应为真实 JSON 值：" + body);
        assertFalse(body.contains("\"array\":false"), "不得泄漏 JsonNode 内部字段：" + body);
    }

}