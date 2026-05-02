package org.slackerdb.dbproxy.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.dbserver.server.DBInstance;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.TimeZone;

/**
 * SpringBootTest for DBProxy.
 *
 * Uses webEnvironment = NONE to avoid starting Spring Boot's embedded web server,
 * which would conflict with Javalin (used by ProxyInstance/DBInstance for HTTP serving).
 * The ProxyInstance and DBInstance are started manually in @BeforeAll, same as the
 * existing ProxyApiTest.
 *
 * Auto-configuration exclusions:
 * - DataSourceAutoConfiguration: we manage our own DuckDB connection via DBInstance
 * - SqlInitializationAutoConfiguration: we handle schema creation manually
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(classes = SpringBootProxyTest.class)
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        SqlInitializationAutoConfiguration.class
})
@Configuration
public class SpringBootProxyTest {

    static int proxyPort = 4322;   // 使用固定端口，避免与 ProxyApiTest 冲突
    static int proxyPortX = 4323;  // 管理端口
    static int dbPort;
    static int dbPortX;
    static ProxyInstance proxyInstance;
    static DBInstance dbInstance;

    @BeforeAll
    static void initAll() {
        try {
            // 强制使用UTC时区，以避免时区问题在PG和后端数据库中不一致的行为
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            // 启动代理服务，使用固定端口
            ServerConfiguration proxyConfiguration = new ServerConfiguration();
            proxyConfiguration.setPort(proxyPort);
            proxyConfiguration.setPortX(proxyPortX);
            proxyInstance = new ProxyInstance(proxyConfiguration);
            proxyInstance.start();

            // 等待proxy启动完成
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            System.err.println("ERROR:: Failed to start proxy: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        // 启动数据库服务，并注册到proxy
        org.slackerdb.dbserver.configuration.ServerConfiguration serverConfiguration =
                new org.slackerdb.dbserver.configuration.ServerConfiguration();
        serverConfiguration.setPort(0);  // 随机端口
        serverConfiguration.setPortX(0); // 随机管理端口
        serverConfiguration.setRemoteListener("127.0.0.1:" + proxyPort); // 注册到proxy
        serverConfiguration.setData("mem");
        serverConfiguration.setLog_level("INFO");
        serverConfiguration.setSqlHistory("OFF");
        // 使用测试资源目录中的配置文件
        serverConfiguration.setDataServiceSchema("src/test/resources/conf/data_service_example.service");
        serverConfiguration.setDataServiceHistory("ON");

        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        dbPort = serverConfiguration.getPort();
        dbPortX = serverConfiguration.getPortX();

        // 等待远程注册成功
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 等待服务启动完成
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 配置proxy转发规则：将/mem路径转发到数据库的/api路径
        String dbApiUrl = "http://127.0.0.1:" + dbPortX + "/api";
        proxyInstance.proxyInstanceX.forwarderPathMappings.put("/mem", dbApiUrl);

        System.out.println("TEST:: Proxy started on port " + proxyPort + ", management on " + proxyPortX);
        System.out.println("TEST:: DB started on port " + dbPort + ", management on " + dbPortX);
        System.out.println("TEST:: Forwarding configured: /mem -> " + dbApiUrl);
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("TEST:: Will shutdown proxy and db ...");
        proxyInstance.stop();
        dbInstance.stop();
        System.out.println("TEST:: Proxy and DB stopped successful.");
    }

    @Test
    void testSimpleApiQueryThroughProxy() throws Exception {
        // 首先在数据库上注册一个服务
        JSONObject registerTestObj = new JSONObject();
        registerTestObj.put("serviceName", "springBootProxyQuery");
        registerTestObj.put("serviceVersion", "1.0");
        registerTestObj.put("serviceType", "GET");
        registerTestObj.put("sql", "SELECT 100 as result");
        registerTestObj.put("category", "test");

        HttpClient client = HttpClient.newHttpClient();

        // 直接向数据库注册服务（不通过proxy）
        HttpRequest registerRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/registerService"))
                .POST(HttpRequest.BodyPublishers.ofString(registerTestObj.toString()))
                .header("Content-Type", "application/json")
                .build();
        var ignored = client.send(registerRequest, HttpResponse.BodyHandlers.ofString());

        // 通过proxy调用API，使用/mem前缀
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + proxyPortX + "/mem/1.0/springBootProxyQuery/"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JSONObject responseObj = JSONObject.parseObject(response.body()).getJSONObject("data");
        responseObj.remove("timestamp");
        assert JSON.toJSONString(responseObj, JSONWriter.Feature.MapSortField).equals("""
                {"affectedRows":1,"columnNames":["result"],"columnTypes":["INTEGER"],"dataset":[[100]]}
                """.trim());

        System.out.println("TEST:: Simple API query through proxy successful");
    }

    @Test
    void testDataServiceExampleThroughProxy() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // 通过proxy调用预定义的数据服务
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + proxyPortX + "/mem/v1/getUsers/"))
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
            throw new AssertionError("Service not found or error: " + fullResponse);
        }

        System.out.println("TEST:: Data service example through proxy successful");
    }
}
