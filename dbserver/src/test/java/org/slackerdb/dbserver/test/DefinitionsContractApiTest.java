package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 契约：{@code /definitions/{kind}} 的 CRUD、校验与乐观并发。
 *
 * <p>约定的重点不是"能存能取"，而是：未知 kind 400、重名 409、{@code If-Match} 过期 409、
 * 超大 payload 413、非法 id 400 —— 这些是客户端要据此做提示/重试的分支。</p>
 */
class DefinitionsContractApiTest {

    private static DBInstance dbInstance;
    private static int managementPort;
    private static HttpClient http;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setPortX(0);   // 随机端口：start() 会把实际端口写回配置
        cfg.setData("definitions");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("WARN");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        managementPort = cfg.getPortX();
        assertTrue(managementPort > 0, "管理口应已绑定端口，实际：" + managementPort);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void tearDownAll() {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    private static String base() {
        return "http://127.0.0.1:" + managementPort;
    }

    private static HttpResponse<String> send(String method, String path, String body, String ifMatch)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("能力矩阵声明 definitions=true")
    void capabilitiesAdvertiseDefinitions() throws Exception {
        HttpResponse<String> response = send("GET", "/capabilities", null, null);
        assertEquals(200, response.statusCode());
        JSONObject features = JSON.parseObject(response.body()).getJSONObject("features");
        assertTrue(features.getBooleanValue("definitions"), "features.definitions 应为 true");
    }

    @Test
    @DisplayName("CRUD 往返：POST 201 → GET → PUT(If-Match) → DELETE 204")
    void crudRoundTrip() throws Exception {
        String name = "契约数据集 " + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = send("POST", "/definitions/datasets",
                "{\"name\":\"" + name + "\",\"sql\":\"select 1\"}", null);
        assertEquals(201, created.statusCode(), "创建应返回 201：" + created.body());
        JSONObject definition = JSON.parseObject(created.body());
        String id = definition.getString("id");
        assertNotNull(id, "服务端应生成 id");
        String updatedAt = definition.getString("updatedAt");
        assertNotNull(updatedAt, "应带 updatedAt 做乐观并发");

        HttpResponse<String> list = send("GET", "/definitions/datasets", null, null);
        assertEquals(200, list.statusCode());
        JSONArray items = JSON.parseObject(list.body()).getJSONArray("items");
        assertTrue(items.stream().anyMatch(item -> id.equals(((JSONObject) item).getString("id"))),
                "列表里应包含刚创建的定义");

        HttpResponse<String> one = send("GET", "/definitions/datasets/" + id, null, null);
        assertEquals(200, one.statusCode());
        assertEquals(name, JSON.parseObject(one.body()).getString("name"));

        HttpResponse<String> updated = send("PUT", "/definitions/datasets/" + id,
                "{\"name\":\"" + name + "-改\",\"sql\":\"select 2\"}", updatedAt);
        assertEquals(200, updated.statusCode(), "带正确 If-Match 的更新应成功：" + updated.body());
        assertEquals(name + "-改", JSON.parseObject(updated.body()).getString("name"));

        HttpResponse<String> deleted = send("DELETE", "/definitions/datasets/" + id, null, null);
        assertEquals(204, deleted.statusCode());
        assertEquals(404, send("GET", "/definitions/datasets/" + id, null, null).statusCode());
    }

    @Test
    @DisplayName("校验分支：未知 kind / 非法 id / 空名 / 重名 / 过期 If-Match / 超大 payload")
    void rejectsInvalidRequests() throws Exception {
        assertEquals(400, send("GET", "/definitions/unknown-kind", null, null).statusCode(),
                "未知 kind 应 400");
        assertEquals(400, send("GET", "/definitions/datasets/bad%20id", null, null).statusCode(),
                "非法 id 应 400");
        assertEquals(400, send("POST", "/definitions/datasets", "{\"sql\":\"select 1\"}", null).statusCode(),
                "缺 name 应 400");
        assertTrue(send("POST", "/definitions/datasets", "not-json", null).statusCode() >= 400,
                "非 JSON 应被拒绝");

        String name = "同名 " + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(201, send("POST", "/definitions/datasets",
                "{\"name\":\"" + name + "\",\"sql\":\"select 1\"}", null).statusCode());
        assertEquals(409, send("POST", "/definitions/datasets",
                "{\"name\":\"" + name + "\",\"sql\":\"select 2\"}", null).statusCode(),
                "同 kind 内重名应 409");

        HttpResponse<String> created = send("POST", "/definitions/datasets",
                "{\"name\":\"" + name + "-2\",\"sql\":\"select 1\"}", null);
        String id = JSON.parseObject(created.body()).getString("id");
        assertEquals(409, send("PUT", "/definitions/datasets/" + id,
                "{\"name\":\"" + name + "-2\",\"sql\":\"select 3\"}", "1999-01-01T00:00:00Z").statusCode(),
                "过期 If-Match 应 409");

        String huge = "x".repeat(1024 * 1024 + 10);
        assertEquals(413, send("POST", "/definitions/charts",
                "{\"name\":\"huge\",\"data\":\"" + huge + "\"}", null).statusCode(),
                "超过 1 MB 应 413");
    }
}
