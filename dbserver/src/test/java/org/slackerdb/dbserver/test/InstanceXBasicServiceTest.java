package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONArray;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.TimeZone;

public class InstanceXBasicServiceTest {
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
    void testDatabaseConnection() throws Exception {
        // 测试通过JDBC连接到数据库
        String url = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        try (Connection conn = DriverManager.getConnection(url, "", "")) {
            assert conn != null;
            assert !conn.isClosed();
            
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT 1 as test_value");
                assert rs.next();
                assert rs.getInt(1) == 1;
                rs.close();
            }
        }
    }

    @Test
    void testInstanceState() {
        // 测试实例状态
        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
        assert dbInstance.activeSessions.get() >= 0;
    }

    @Test
    void testBasicSQLExecution() throws Exception {
        // 测试基本的SQL执行
        String url = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        try (Connection conn = DriverManager.getConnection(url, "", "")) {
            try (Statement stmt = conn.createStatement()) {
                // 创建测试表
                stmt.execute("CREATE TABLE IF NOT EXISTS test_basic (id INT, name VARCHAR(50))");
                
                // 插入数据
                int rows = stmt.executeUpdate("INSERT INTO test_basic VALUES (1, 'test1'), (2, 'test2')");
                assert rows == 2;
                
                // 查询数据
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_basic");
                assert rs.next();
                assert rs.getInt(1) == 2;
                rs.close();
                
                // 清理
                stmt.execute("DROP TABLE IF EXISTS test_basic");
            }
        }
    }


    @Test
    void testServiceLoadRegisterUnregisterDump() throws Exception
    {
        HttpClient client = HttpClient.newHttpClient();

        // 1. 创建临时文件，包含两个服务定义
        Path tempInputFile = Files.createTempFile("services", ".json");
        JSONArray initialServices = new JSONArray();
        // 服务A
        JSONObject serviceA = new JSONObject();
        serviceA.put("serviceName", "serviceA");
        serviceA.put("category", "test");
        serviceA.put("serviceVersion", "v1");
        serviceA.put("serviceType", "GET");
        serviceA.put("sql", "SELECT 1 as col");
        serviceA.put("description", "Service A");
        serviceA.put("searchPath", "public");
        serviceA.put("snapshotLimit", "300 seconds");
        serviceA.put("parameters", new JSONArray());
        // 服务B
        JSONObject serviceB = new JSONObject();
        serviceB.put("serviceName", "serviceB");
        serviceB.put("category", "test");
        serviceB.put("serviceVersion", "v1");
        serviceB.put("serviceType", "POST");
        serviceB.put("sql", "SELECT 2 as col");
        serviceB.put("description", "Service B");
        serviceB.put("searchPath", "public");
        serviceB.put("snapshotLimit", "600 seconds");
        JSONArray paramsB = new JSONArray();
        JSONObject param1 = new JSONObject();
        param1.put("name", "limit");
        param1.put("defaultValue", "10");
        paramsB.add(param1);
        serviceB.put("parameters", paramsB);

        initialServices.add(serviceA);
        initialServices.add(serviceB);
        Files.writeString(tempInputFile, initialServices.toJSONString());

        // 2. 通过 /api/loadRegisterService 加载文件内容
        HttpRequest loadRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/loadRegisterService"))
                .POST(HttpRequest.BodyPublishers.ofString(Files.readString(tempInputFile)))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> loadResponse = client.send(loadRequest, HttpResponse.BodyHandlers.ofString());
        assert loadResponse.statusCode() == 200;
        JSONObject loadResult = JSONObject.parseObject(loadResponse.body());
        assert loadResult.getInteger("retCode") == 0;

        // 3. 验证两个服务已注册（通过 /api/listRegisteredService）
        HttpRequest listRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/listRegisteredService"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString());
        JSONArray listArray = JSONArray.parseArray(listResponse.body());
        // 应该至少包含两个服务（可能还有之前测试注册的服务，比如 data_service_example 中的 getUsers）
        // 我们只检查 serviceA 和 serviceB 是否存在
        boolean foundA = false, foundB = false;
        for (int i = 0; i < listArray.size(); i++) {
            JSONObject svc = listArray.getJSONObject(i);
            if ("serviceA".equals(svc.getString("serviceName")) && "v1".equals(svc.getString("serviceVersion"))) {
                foundA = true;
            }
            if ("serviceB".equals(svc.getString("serviceName")) && "v1".equals(svc.getString("serviceVersion"))) {
                foundB = true;
            }
        }
        assert foundA && foundB : "Loaded services not found in list";

        // 4. 注册两个新服务（serviceC 和 serviceD）
        JSONObject registerC = new JSONObject();
        registerC.put("serviceName", "serviceC");
        registerC.put("category", "test");
        registerC.put("serviceVersion", "v1");
        registerC.put("serviceType", "GET");
        registerC.put("sql", "SELECT 3 as col");
        registerC.put("description", "Service C");
        HttpRequest regRequestC = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/registerService"))
                .POST(HttpRequest.BodyPublishers.ofString(registerC.toString()))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> regResponseC = client.send(regRequestC, HttpResponse.BodyHandlers.ofString());
        assert regResponseC.statusCode() == 200;
        assert JSONObject.parseObject(regResponseC.body()).getInteger("retCode") == 0;

        JSONObject registerD = new JSONObject();
        registerD.put("serviceName", "serviceD");
        registerD.put("category", "test");
        registerD.put("serviceVersion", "v2");
        registerD.put("serviceType", "POST");
        registerD.put("sql", "SELECT 4 as col");
        registerD.put("description", "Service D");
        HttpRequest regRequestD = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/registerService"))
                .POST(HttpRequest.BodyPublishers.ofString(registerD.toString()))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> regResponseD = client.send(regRequestD, HttpResponse.BodyHandlers.ofString());
        assert regResponseD.statusCode() == 200;
        assert JSONObject.parseObject(regResponseD.body()).getInteger("retCode") == 0;

        // 5. 取消注册 serviceA
        JSONObject unregisterA = new JSONObject();
        unregisterA.put("serviceName", "serviceA");
        unregisterA.put("serviceVersion", "v1");
        unregisterA.put("serviceType", "GET");
        HttpRequest unregRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/unRegisterService"))
                .POST(HttpRequest.BodyPublishers.ofString(unregisterA.toString()))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> unregResponse = client.send(unregRequest, HttpResponse.BodyHandlers.ofString());
        assert unregResponse.statusCode() == 200;
        assert JSONObject.parseObject(unregResponse.body()).getInteger("retCode") == 0;

        // 6. 导出所有注册服务（dump）
        HttpRequest dumpRequest = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/dumpRegisteredService"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> dumpResponse = client.send(dumpRequest, HttpResponse.BodyHandlers.ofString());
        assert dumpResponse.statusCode() == 200;
        // 响应是一个 JSON 文件（附件），但端点返回的是 JSON 数组？实际上 /api/dumpRegisteredService 返回的是 JSON 文件内容，不是包装对象。
        // 根据 APIService 代码，它直接返回 JSON 数组字符串。
        String dumpJson = dumpResponse.body();
        JSONArray dumpedArray = JSONArray.parseArray(dumpJson);

        // 7. 验证导出的 JSON 符合预期：应包含 serviceB, serviceC, serviceD，不包含 serviceA
        boolean hasA = false, hasB = false, hasC = false, hasD = false;
        for (int i = 0; i < dumpedArray.size(); i++) {
            JSONObject svc = dumpedArray.getJSONObject(i);
            String name = svc.getString("serviceName");
            String version = svc.getString("serviceVersion");
            if ("serviceA".equals(name) && "v1".equals(version)) hasA = true;
            if ("serviceB".equals(name) && "v1".equals(version)) hasB = true;
            if ("serviceC".equals(name) && "v1".equals(version)) hasC = true;
            if ("serviceD".equals(name) && "v2".equals(version)) hasD = true;
        }
        assert !hasA : "serviceA should have been unregistered";
        assert hasB : "serviceB should be present";
        assert hasC : "serviceC should be present";
        assert hasD : "serviceD should be present";

        // 可选：验证字段是否正确（例如 snapshotLimit 格式）
        for (int i = 0; i < dumpedArray.size(); i++) {
            JSONObject svc = dumpedArray.getJSONObject(i);
            if ("serviceB".equals(svc.getString("serviceName"))) {
                assert "POST".equals(svc.getString("serviceType"));
                assert svc.containsKey("parameters");
                JSONArray params = svc.getJSONArray("parameters");
                assert params.size() == 1;
                assert "limit".equals(params.getJSONObject(0).getString("name"));
                assert "10".equals(params.getJSONObject(0).getString("defaultValue"));
            }
        }

        // 清理临时文件
        Files.deleteIfExists(tempInputFile);
    }
}