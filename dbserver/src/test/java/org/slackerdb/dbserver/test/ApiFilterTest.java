package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 服务级 filter 的端到端测试。
 *
 * <p>filter 在 /api/registerService（以及 .service 文件）里配置，调用方不需要、
 * 也不能在 /api/... 请求里再传 filter。缓存保存的是过滤前的原始结果，
 * 因此只有 filter 不同的多个服务共享同一份缓存快照。</p>
 */
public class ApiFilterTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final String ROWS_SQL =
            "SELECT * FROM (VALUES (1, 'Alice', 100), (2, 'bob', 200), (3, 'Carol', 300)) "
                    + "AS t(id, name, amount)";

    /** 共享缓存用例专用 SQL：避免与其它用例的缓存条目互相影响。 */
    private static final String SHARE_SQL =
            "SELECT * FROM (VALUES (11, 'A1', 110), (12, 'A2', 120)) AS t(id, name, amount)";

    /** 改 filter 复用缓存用例专用 SQL。 */
    private static final String CHANGE_SQL =
            "SELECT * FROM (VALUES (21, 'C1', 210), (22, 'C2', 220)) AS t(id, name, amount)";

    static DBInstance dbInstance;
    static int apiPort;

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

        dbInstance = new DBInstance(configuration);
        dbInstance.start();
        apiPort = configuration.getPortX();
    }

    @AfterAll
    static void tearDownAll() {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    /** 注册时配置 filter，之后调用 /api/... 不用带任何参数，自动过滤。 */
    @Test
    void registeredFilterAppliesAutomatically() throws Exception {
        registerService("filterRows", "GET", ROWS_SQL,
                "id >= 2 ORDER BY amount DESC LIMIT 1", null);

        JSONObject response = parse(get("/api/1.0/filterRows"));
        assertEquals(0, response.getIntValue("retCode"), response.toString());
        JSONObject data = response.getJSONObject("data");
        assertEquals(1, data.getIntValue("affectedRows"));
        assertFalse(data.containsKey("totalRows"), "服务级 filter 不改变响应字段");
        JSONArray dataset = data.getJSONArray("dataset");
        assertEquals(1, dataset.size());
        assertEquals(3, dataset.getJSONArray(0).getIntValue(0));

        // 客户端额外带的 query 参数与 filter 无关，不改变服务端过滤行为
        JSONObject withExtraParam = parse(getRaw("/api/1.0/filterRows?foo=bar"));
        assertEquals(3, withExtraParam.getJSONObject("data").getJSONArray("dataset")
                .getJSONArray(0).getIntValue(0));
    }

    /** 没有配置 filter 的服务行为完全不变。 */
    @Test
    void serviceWithoutFilterIsUnchanged() throws Exception {
        registerService("noFilterRows", "GET", ROWS_SQL, null, null);

        JSONObject response = parse(get("/api/1.0/noFilterRows"));
        JSONObject data = response.getJSONObject("data");
        assertEquals(3, data.getJSONArray("dataset").size());
        assertFalse(data.containsKey("totalRows"));
    }

    /** POST 服务同样在注册时配置 filter；请求 body 里的 filter 只是普通 SQL 参数，不参与过滤。 */
    @Test
    void postServiceFilterAppliesAutomatically() throws Exception {
        registerService("filterPost", "POST", ROWS_SQL,
                "lower(name) = 'alice' OR length(name) = 3 ORDER BY id", null);

        JSONObject response = parse(postJson("/api/1.0/filterPost", "{}"));
        assertEquals(0, response.getIntValue("retCode"), response.toString());
        JSONArray dataset = response.getJSONObject("data").getJSONArray("dataset");
        assertEquals(2, dataset.size());
        assertEquals(1, dataset.getJSONArray(0).getIntValue(0));
        assertEquals(2, dataset.getJSONArray(1).getIntValue(0));

        // 请求里的 "filter" 不再具有特殊含义：SQL 没用到它，过滤结果不变
        JSONObject body = new JSONObject();
        body.put("filter", "id = 3");
        JSONObject withBodyFilter = parse(postJson("/api/1.0/filterPost", body.toJSONString()));
        JSONArray stillFiltered = withBodyFilter.getJSONObject("data").getJSONArray("dataset");
        assertEquals(2, stillFiltered.size());
        assertEquals(1, stillFiltered.getJSONArray(0).getIntValue(0));
    }

    /** 注册时校验 filter 语法：非法表达式直接拒绝注册。 */
    @Test
    void registrationRejectsInvalidFilter() throws Exception {
        JSONObject service = serviceJson("filterInvalid", "GET", ROWS_SQL, "id =", null);
        JSONObject response = parse(postJson("/api/registerService", service.toJSONString()));
        assertEquals(-1, response.getIntValue("retCode"), response.toString());
        assertTrue(response.getString("retMsg").contains("Invalid filter"),
                response.getString("retMsg"));

        HttpResponse<String> query = get("/api/1.0/filterInvalid");
        assertEquals(404, query.statusCode(), "非法 filter 的服务不应被注册");
    }

    /** 从 .service 文件内容加载（/api/loadRegisterService）同样支持 filter。 */
    @Test
    void serviceFileLoadSupportsFilter() throws Exception {
        String definition = """
                [{
                  "serviceName": "filterLoaded",
                  "serviceVersion": "1.0",
                  "serviceType": "GET",
                  "sql": "SELECT * FROM (VALUES (1, 'x'), (2, 'y')) AS t(id, name)",
                  "filter": "id = 2"
                }]
                """;
        JSONObject load = parse(postJson("/api/loadRegisterService", definition));
        assertEquals(0, load.getIntValue("retCode"), load.toString());

        JSONObject response = parse(get("/api/1.0/filterLoaded"));
        assertEquals(0, response.getIntValue("retCode"), response.toString());
        JSONArray dataset = response.getJSONObject("data").getJSONArray("dataset");
        assertEquals(1, dataset.size());
        assertEquals(2, dataset.getJSONArray(0).getIntValue(0));
    }

    /** listRegisteredService / downloadRegisteredService 必须带出 filter，保证定义可回读。 */
    @Test
    void listAndDumpExposeFilter() throws Exception {
        registerService("filterListed", "GET", ROWS_SQL, "id = 1", null);

        JSONArray listed = JSONArray.parseArray(getRaw("/api/listRegisteredService").body());
        JSONObject listedService = findService(listed, "filterListed");
        assertEquals("id = 1", listedService.getString("filter"));

        JSONArray dumped = JSONArray.parseArray(
                postJson("/api/downloadRegisteredService", "").body());
        JSONObject dumpedService = findService(dumped, "filterListed");
        assertEquals("id = 1", dumpedService.getString("filter"));
    }

    /**
     * 核心场景：SQL 与 searchPath 相同、只有 filter 不同的两个服务，
     * 共享同一份"过滤前"的缓存快照；第二个服务直接命中缓存。
     */
    @Test
    void filtersDifferOnlyShareOneRawSnapshot() throws Exception {
        registerService("filterShareA", "GET", SHARE_SQL, "id = 11", "300 seconds");
        registerService("filterShareB", "GET", SHARE_SQL, "id = 12", "300 seconds");

        JSONObject first = parse(get("/api/1.0/filterShareA"));
        assertEquals(0, first.getIntValue("retCode"), first.toString());
        assertFalse(first.getBooleanValue("cached"), first.toString());
        assertEquals(11, first.getJSONObject("data").getJSONArray("dataset")
                .getJSONArray(0).getIntValue(0));
        long firstTimestamp = first.getJSONObject("data").getLongValue("timestamp");

        JSONObject second = parse(get("/api/1.0/filterShareB"));
        assertEquals(0, second.getIntValue("retCode"), second.toString());
        assertTrue(second.getBooleanValue("cached"),
                "相同 SQL/searchPath 的不同服务应共享原始缓存：" + second);
        assertEquals(12, second.getJSONObject("data").getJSONArray("dataset")
                .getJSONArray(0).getIntValue(0));
        assertEquals(firstTimestamp,
                second.getJSONObject("data").getLongValue("timestamp"),
                "两个服务应命中同一份原始快照");
    }

    /** 只改 filter 重新注册时，原始缓存仍然有效（不需要重新查库，也不会返回旧过滤结果）。 */
    @Test
    void filterChangeReusesRawCache() throws Exception {
        registerService("filterChange", "GET", CHANGE_SQL, "id = 21", "300 seconds");
        JSONObject before = parse(get("/api/1.0/filterChange"));
        assertFalse(before.getBooleanValue("cached"), before.toString());
        assertEquals(21, before.getJSONObject("data").getJSONArray("dataset")
                .getJSONArray(0).getIntValue(0));
        long timestamp = before.getJSONObject("data").getLongValue("timestamp");

        unregisterService("filterChange");
        registerService("filterChange", "GET", CHANGE_SQL, "id = 22", "300 seconds");

        JSONObject after = parse(get("/api/1.0/filterChange"));
        assertEquals(0, after.getIntValue("retCode"), after.toString());
        assertTrue(after.getBooleanValue("cached"),
                "filter 不参与缓存键，改 filter 后原始缓存应继续可用：" + after);
        assertEquals(22, after.getJSONObject("data").getJSONArray("dataset")
                .getJSONArray(0).getIntValue(0));
        assertEquals(timestamp, after.getJSONObject("data").getLongValue("timestamp"));
    }

    /** 非结果集语句没有结果集可过滤：服务级 filter 被忽略，不影响执行结果。 */
    @Test
    void nonResultSetServiceIgnoresFilter() throws Exception {
        registerService("filterDdl", "GET",
                "CREATE OR REPLACE TEMPORARY TABLE filter_ignore_tmp(i INTEGER)",
                "id = 1", null);

        JSONObject response = parse(get("/api/1.0/filterDdl"));
        assertEquals(0, response.getIntValue("retCode"), response.toString());
        assertFalse(response.containsKey("data"));
        assertFalse(response.toString().contains("Invalid filter"));
    }

    /** ORDER BY / NULLS LAST 在服务级 filter 里同样生效。 */
    @Test
    void orderByPlacesNullsLastByDefault() throws Exception {
        registerService("filterNulls", "GET",
                "SELECT * FROM (VALUES (1, 10), (2, NULL), (3, 30)) AS t(id, amount)",
                "ORDER BY amount ASC", null);

        JSONObject response = parse(get("/api/1.0/filterNulls"));
        assertEquals(0, response.getIntValue("retCode"), response.toString());
        JSONArray dataset = response.getJSONObject("data").getJSONArray("dataset");
        assertEquals(3, dataset.size());
        assertEquals(1, dataset.getJSONArray(0).getIntValue(0));
        assertEquals(3, dataset.getJSONArray(1).getIntValue(0));
        assertEquals(2, dataset.getJSONArray(2).getIntValue(0));
    }

    // ---------------------------------------------------------------- 工具

    private static void registerService(String name, String type, String sql,
                                        String filter, String snapshotLimit) throws Exception {
        JSONObject response = parse(postJson("/api/registerService",
                serviceJson(name, type, sql, filter, snapshotLimit).toJSONString()));
        assertEquals(0, response.getIntValue("retCode"), "register failed: " + response);
    }

    private static JSONObject serviceJson(String name, String type, String sql,
                                          String filter, String snapshotLimit) {
        JSONObject service = new JSONObject();
        service.put("serviceName", name);
        service.put("serviceVersion", "1.0");
        service.put("serviceType", type);
        service.put("sql", sql);
        if (filter != null) {
            service.put("filter", filter);
        }
        if (snapshotLimit != null) {
            service.put("snapshotLimit", snapshotLimit);
        }
        return service;
    }

    private static void unregisterService(String name) throws Exception {
        JSONObject body = new JSONObject();
        body.put("serviceName", name);
        body.put("serviceVersion", "1.0");
        body.put("serviceType", "GET");
        JSONObject response = parse(postJson("/api/unRegisterService", body.toJSONString()));
        assertEquals(0, response.getIntValue("retCode"), "unregister failed: " + response);
    }

    private static JSONObject findService(JSONArray services, String name) {
        for (int index = 0; index < services.size(); index++) {
            JSONObject service = services.getJSONObject(index);
            if (name.equals(service.getString("serviceName"))) {
                return service;
            }
        }
        fail("service not found: " + name);
        return null;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return getRaw(path);
    }

    private static HttpResponse<String> getRaw(String pathOrUrl) throws Exception {
        String url = pathOrUrl.startsWith("http")
                ? pathOrUrl
                : "http://127.0.0.1:" + apiPort + pathOrUrl;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + apiPort + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JSONObject parse(HttpResponse<String> response) {
        assertNotNull(response.body());
        return JSONObject.parseObject(response.body());
    }
}
