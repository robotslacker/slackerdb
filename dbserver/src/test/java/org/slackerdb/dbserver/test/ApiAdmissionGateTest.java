package org.slackerdb.dbserver.test;

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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With the admission gate full, a request must fail fast with 503 instead of piling up
 * inside DuckDB. The gate is acquired directly by the test so no timing races are needed.
 */
class ApiAdmissionGateTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

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
        configuration.setMax_api_concurrent_queries(1);

        dbInstance = new DBInstance(configuration);
        dbInstance.start();
        apiPort = configuration.getPortX();

        JSONObject service = new JSONObject();
        service.put("serviceName", "gateRows");
        service.put("serviceVersion", "1.0");
        service.put("serviceType", "GET");
        service.put("sql", "SELECT * FROM (VALUES (1), (2)) AS t(id)");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + apiPort + "/api/registerService"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(service.toJSONString()))
                .build();
        JSONObject registered = JSONObject.parseObject(
                CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(0, registered.getIntValue("retCode"), registered.toString());
    }

    @AfterAll
    static void tearDownAll() {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    @Test
    void apiAndReplGatesAreIndependent() {
        assertNotSame(dbInstance.getApiQueryGate(), dbInstance.getReplQueryGate());
        assertTrue(dbInstance.getApiQueryGate().isEnabled());
        assertFalse(dbInstance.getReplQueryGate().isEnabled(),
                "repl limit defaults to 0 (unlimited) and is configured separately");
    }

    @Test
    void fullGateReturns503AndRecoversAfterRelease() throws Exception {
        assertTrue(dbInstance.getApiQueryGate().tryAcquire(0), "test should hold the only slot");
        try {
            HttpResponse<String> rejected = get("/api/1.0/gateRows", "500");
            assertEquals(503, rejected.statusCode(), rejected.body());
            JSONObject body = JSONObject.parseObject(rejected.body());
            assertEquals(-1, body.getIntValue("retCode"), body.toString());
            assertTrue(body.getString("retMsg").contains("Server busy"), body.toString());
        } finally {
            dbInstance.getApiQueryGate().release();
        }

        HttpResponse<String> accepted = get("/api/1.0/gateRows", null);
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertEquals(0, JSONObject.parseObject(accepted.body()).getIntValue("retCode"),
                "the same request must succeed once the slot is free");
    }

    @Test
    void zeroBudgetHeaderMeansUnlimited() throws Exception {
        HttpResponse<String> response = get("/api/1.0/gateRows", "0");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(0, JSONObject.parseObject(response.body()).getIntValue("retCode"),
                "maxExecutionTime=0 is the documented 'no limit' value: " + response.body());
    }

    private static HttpResponse<String> get(String path, String maxExecutionTime) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + apiPort + path))
                .GET();
        if (maxExecutionTime != null) {
            builder.header("maxExecutionTime", maxExecutionTime);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
