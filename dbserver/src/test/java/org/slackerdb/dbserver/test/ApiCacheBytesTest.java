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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache-hit path writes pre-serialized data bytes instead of re-serializing the
 * result map. These tests pin the JSON contract that path must keep: same fields,
 * nulls preserved, description escaped, cached flag toggling.
 */
class ApiCacheBytesTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String SQL =
            "SELECT * FROM (VALUES (1, 'a', CAST(NULL AS INTEGER)), (2, 'b', 3)) AS t(id, name, val)";

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

    @Test
    void cacheHitKeepsJsonContractAndNulls() throws Exception {
        String description = "say \"hi\"\nthen\\leave";
        registerService("cacheNulls", SQL, description, "10 seconds");

        HttpResponse<String> firstResponse = get("/api/1.0/cacheNulls");
        assertTrue(firstResponse.headers().firstValue("content-type").orElse("").contains("application/json"));
        JSONObject first = JSONObject.parseObject(firstResponse.body());
        assertEquals(0, first.getIntValue("retCode"), first.toString());
        assertFalse(first.getBooleanValue("cached"), first.toString());
        assertEquals(description, first.getString("description"));
        assertNotNull(first.getString("timestamp"));

        JSONArray firstRows = first.getJSONObject("data").getJSONArray("dataset");
        assertEquals(2, firstRows.size());
        assertNull(firstRows.getJSONArray(0).get(2), "NULL must survive the byte path");
        assertEquals(3, firstRows.getJSONArray(1).getIntValue(2));

        HttpResponse<String> secondResponse = get("/api/1.0/cacheNulls");
        JSONObject second = JSONObject.parseObject(secondResponse.body());
        assertEquals(0, second.getIntValue("retCode"), second.toString());
        assertTrue(second.getBooleanValue("cached"), "second call must be served from cache: " + second);
        assertEquals(description, second.getString("description"));
        assertEquals(first.getString("timestamp"), second.getString("timestamp"));
        assertEquals(first.getJSONObject("data").toJSONString(),
                second.getJSONObject("data").toJSONString(),
                "cached data bytes must be identical to the refresh result");
    }

    @Test
    void uncachedServiceStillUsesTheSameEnvelope() throws Exception {
        registerService("noCacheRows", SQL, "plain", "0");

        JSONObject response = JSONObject.parseObject(get("/api/1.0/noCacheRows").body());
        assertEquals(0, response.getIntValue("retCode"), response.toString());
        assertFalse(response.getBooleanValue("cached"));
        assertEquals("plain", response.getString("description"));
        assertEquals(2, response.getJSONObject("data").getJSONArray("dataset").size());
    }

    // ---------------------------------------------------------------- helpers

    private static void registerService(String name, String sql, String description, String snapshotLimit)
            throws Exception {
        JSONObject service = new JSONObject();
        service.put("serviceName", name);
        service.put("serviceVersion", "1.0");
        service.put("serviceType", "GET");
        service.put("sql", sql);
        service.put("description", description);
        service.put("snapshotLimit", snapshotLimit);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + apiPort + "/api/registerService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(service.toJSONString()))
                .build();
        JSONObject response = JSONObject.parseObject(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(0, response.getIntValue("retCode"), "register failed: " + response);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + apiPort + path))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
