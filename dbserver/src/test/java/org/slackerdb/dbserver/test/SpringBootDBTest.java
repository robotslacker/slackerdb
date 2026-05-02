package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.TimeZone;

/**
 * SpringBootTest for DBServer.
 *
 * Uses webEnvironment = NONE to avoid starting Spring Boot's embedded web server,
 * which would conflict with Javalin (used by DBInstance for HTTP serving).
 * The DBInstance is started manually in @BeforeAll, same as the existing tests.
 *
 * Auto-configuration exclusions:
 * - DataSourceAutoConfiguration: we manage our own DuckDB connection via DBInstance
 * - SqlInitializationAutoConfiguration: we handle schema creation manually
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(classes = SpringBootDBTest.class)
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        SqlInitializationAutoConfiguration.class
})
@Configuration
public class SpringBootDBTest {

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
        dbPortX = serverConfiguration.getPortX();

        // 初始化数据库
        dbInstance = new DBInstance(serverConfiguration);
        dbInstance.start();

        // 创建public模式（如果不存在），因为data_service_example.service中使用了searchPath "public"
        try {
            Statement stmt = dbInstance.backendSysConnection.createStatement();
            stmt.execute("CREATE SCHEMA IF NOT EXISTS public");
            // 验证模式是否存在
            ResultSet rs = stmt.executeQuery(
                    "SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'public'");
            if (rs.next()) {
                System.out.println("TEST:: Public schema exists.");
            } else {
                System.out.println("TEST:: Public schema not found after creation.");
            }
            rs.close();
            stmt.close();
        } catch (Exception e) {
            throw new ServerException("Failed to create public schema", e);
        }

        assert dbInstance.instanceState.equalsIgnoreCase("RUNNING");
        System.out.println("TEST:: Server started successful ...");
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("TEST:: Will shutdown server ...");
        System.out.println("TEST:: Active sessions : " + dbInstance.activeSessions);
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
    void testSimpleApiQuery() throws Exception {
        JSONObject registerTestObj = new JSONObject();
        registerTestObj.put("serviceName", "springBootQueryTest");
        registerTestObj.put("serviceVersion", "1.0");
        registerTestObj.put("serviceType", "GET");
        registerTestObj.put("sql", "SELECT 42 as answer");
        registerTestObj.put("category", "test");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/registerService"))
                .POST(HttpRequest.BodyPublishers.ofString(registerTestObj.toString()))
                .header("Content-Type", "application/json")
                .build();
        var ignored = client.send(request, HttpResponse.BodyHandlers.ofString());

        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + dbPortX + "/api/1.0/springBootQueryTest/"))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());

        JSONObject responseObj = JSONObject.parseObject(response2.body()).getJSONObject("data");
        responseObj.remove("timestamp");
        assert JSON.toJSONString(responseObj, JSONWriter.Feature.MapSortField).equals("""
                {"affectedRows":1,"columnNames":["answer"],"columnTypes":["INTEGER"],"dataset":[[42]]}
                """.trim());
    }

    @Test
    void testBasicSQLExecution() throws Exception {
        // 测试基本的SQL执行
        String url = "jdbc:postgresql://127.0.0.1:" + dbPort + "/mem";
        try (Connection conn = DriverManager.getConnection(url, "", "")) {
            try (Statement stmt = conn.createStatement()) {
                // 创建测试表
                stmt.execute("CREATE TABLE IF NOT EXISTS springboot_test (id INT, name VARCHAR(50))");

                // 插入数据
                int rows = stmt.executeUpdate("INSERT INTO springboot_test VALUES (1, 'spring'), (2, 'boot')");
                assert rows == 2;

                // 查询数据
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM springboot_test");
                assert rs.next();
                assert rs.getInt(1) == 2;
                rs.close();

                // 清理
                stmt.execute("DROP TABLE IF EXISTS springboot_test");
            }
        }
    }
}
