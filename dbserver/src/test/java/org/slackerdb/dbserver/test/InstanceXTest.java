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
import java.util.TimeZone;

public class InstanceXTest {
    static int dbPort;
    static int dbPortX;
    static DBInstance dbInstance ;
    static String     protocol = "postgresql";

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
        dbPort = serverConfiguration.getPort();
        dbPortX  = serverConfiguration.getPortX();

        // 初始化数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

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

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/registerService"))
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
                {"columnNames":["1"],"columnTypes":["INTEGER"],"dataset":[[1]]}
                """.trim());
    }

    @Test
    void testApiQueryWithContext() throws Exception
    {
        JSONObject registerTestObj = new JSONObject();
        registerTestObj.put("serviceName", "testApiQueryWithContext");
        registerTestObj.put("serviceVersion", "1.0");
        registerTestObj.put("serviceType", "GET");
        registerTestObj.put("sql", "SELECT '${context1}' as col1, 2 as col2");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest requestLogin = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/login"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> responseLogin = client.send(requestLogin, HttpResponse.BodyHandlers.ofString());
        String userToken = JSONObject.parseObject(responseLogin.body()).getString("token");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/registerService"))
                .POST(HttpRequest.BodyPublishers.ofString(registerTestObj.toString()))
                .header("Content-Type", "application/json")
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());

        JSONObject contextObj = new JSONObject();
        contextObj.put("context1", "abc");
        contextObj.put("context2", "def");
        HttpRequest requestContext = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/setContext"))
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
                {"columnNames":["col1","col2"],"columnTypes":["VARCHAR","INTEGER"],"dataset":[["abc",2]]}
                """.trim());
    }

    @Test
    void testApiQueryWithSnapshot() throws Exception
    {
        JSONObject registerTestObj = new JSONObject();
        registerTestObj.put("serviceName", "testApiQueryWithSnapshot");
        registerTestObj.put("serviceVersion", "1.0");
        registerTestObj.put("serviceType", "GET");
        registerTestObj.put("sql", "SELECT 1 as col1, 2 as col2, '中国' as col3");

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/registerService"))
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
        {"columnNames":["col1","col2","col3"],"columnTypes":["INTEGER","INTEGER","VARCHAR"],"dataset":[[1,2,"中国"]]}
        """.trim());

        // 第二次查询应该看到被缓存的结果
        HttpResponse<String> response2_2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
        assert JSONObject.parseObject(response2_2.body()).getBoolean("cached");
        JSONObject responseObj2 = JSONObject.parseObject(response2_2.body()).getJSONObject("data");
        responseObj2.remove("timestamp");
        assert JSON.toJSONString(responseObj2, JSONWriter.Feature.MapSortField).equals("""
        {"columnNames":["col1","col2","col3"],"columnTypes":["INTEGER","INTEGER","VARCHAR"],"dataset":[[1,2,"中国"]]}
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
        {"columnNames":["col1","col2","col3"],"columnTypes":["INTEGER","INTEGER","VARCHAR"],"dataset":[[1,2,"中国"]]}
        """.trim());
    }
}
