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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M6 契约：门户访问日志的写入与查询。 */
class PortalAccessApiTest {

    private static DBInstance dbInstance;
    private static int managementPort;
    private static HttpClient http;

    @BeforeAll
    static void initAll() throws Exception {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setPortX(0);
        cfg.setData("portalaccess");
        cfg.setSqlHistory("OFF");
        cfg.setLog_level("WARN");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        managementPort = cfg.getPortX();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void tearDownAll() {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    private static HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("写入 204 → 查询能按 portalId 过滤；缺 portalId → 400")
    void recordsAndQueries() throws Exception {
        String portalId = "portal-" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(204, send("POST", "/portal-access",
                "{\"portalId\":\"" + portalId + "\",\"token\":\"t1\",\"path\":\"/portal/t1\"}").statusCode());
        assertEquals(204, send("POST", "/portal-access",
                "{\"portalId\":\"" + portalId + "\",\"token\":\"t1\",\"path\":\"/portal/t1\"}").statusCode());
        send("POST", "/portal-access", "{\"portalId\":\"other\",\"token\":\"t2\",\"path\":\"/portal/t2\"}");

        HttpResponse<String> response = send("GET", "/portal-access?portalId=" + portalId, null);
        assertEquals(200, response.statusCode());
        JSONArray items = JSON.parseObject(response.body()).getJSONArray("items");
        assertEquals(2, items.size(), "应有两条记录：" + response.body());
        JSONObject first = items.getJSONObject(0);
        assertEquals("/portal/t1", first.getString("path"));
        assertTrue(first.getString("at").length() > 10, "应带时间戳");

        long ownCount = items.stream().filter(item -> portalId.equals(((JSONObject) item).getString("portalId")))
                .count();
        assertEquals(2, ownCount, "过滤应按 portalId 生效");

        assertEquals(400, send("POST", "/portal-access", "{\"token\":\"x\"}").statusCode(),
                "缺 portalId 应 400");
    }

    @Test
    @DisplayName("limit 被钳位，非法值退回默认（不报错）")
    void clampsLimit() throws Exception {
        assertEquals(200, send("GET", "/portal-access?limit=0", null).statusCode());
        assertEquals(200, send("GET", "/portal-access?limit=abc", null).statusCode());
        assertEquals(200, send("GET", "/portal-access?limit=99999", null).statusCode());
    }
}
