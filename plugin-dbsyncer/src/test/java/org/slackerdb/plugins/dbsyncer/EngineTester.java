package org.slackerdb.plugins.dbsyncer;

import com.alibaba.fastjson2.JSONWriter;
import io.javalin.Javalin;
import org.slackerdb.plugin.DBPluginContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import com.alibaba.fastjson2.JSON;

/**
 * DBSyncer 插件测试工具 - 改进版
 * 演示如何使用 DBSyncer 插件的 HTTP API
 * 插件提供以下端点：
 * 1. GET  /plugin/syncer/status - 查看所有同步配置状态
 * 2. GET  /plugin/syncer/{syncer_name}/status - 查看指定规则状态
 * 3. POST /plugin/syncer/create - 创建新的同步器配置
 * 4. POST /plugin/syncer/{syncer_name}/delete - 删除指定同步器配置
 * 5. POST /plugin/syncer/{syncer_name}/set - 更新同步器配置
 * 6. POST /plugin/syncer/start - 启动所有 auto=true 的配置
 * 7. POST /plugin/syncer/stop - 停止所有运行中的配置
 * 8. POST /plugin/syncer/list - 查看所有同步器列表
 * 9. POST /plugin/syncer/{syncer_name}/list - 查看指定同步器的规则列表
 */

public class EngineTester {
    private static final String BASE_URL = "http://localhost:8080";
    private static final HttpClient client = HttpClient.newHttpClient();
    private static Connection conn;
    private static Javalin javalin;
    private static DBSyncer plugin;
    
    // 测试配置 - 可配置的测试参数
    private static final String TEST_SYNCER_NAME = "test_mysql_sync";
    private static final String TEST_MYSQL_HOST = "192.168.183.134";
    private static final String TEST_MYSQL_PORT = "3406";
    private static final String TEST_MYSQL_USER = "root";
    private static final String TEST_MYSQL_PASSWORD = "123456";
    private static final String TEST_MYSQL_DATABASE = "cheetah_kjzhxy";
    
    // 测试状态跟踪
    private static boolean configCreated = false;

    // 测试结果统计
    private static int testsPassed = 0;
    private static int testsFailed = 0;
    private static int testsSkipped = 0;

    public static void main(String[] args) {
        try {
            System.out.println("=== DBSyncer 插件集成测试开始 ===\n");
            
            setup();
            
            // 启动插件
            plugin.onStart();
            
            // 执行测试套件
            runTestSuite();
            
            // 打印测试结果统计
            printTestSummary();
            
        } catch (Exception e) {
            System.err.println("测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
            testsFailed++;
        } finally {
            try {
                teardown();
            } catch (Exception ex) {
                System.err.println("清理过程中发生错误: " + ex.getMessage());
                ex.printStackTrace();
            }
        }
    }
    
    /**
     * 运行完整的测试套件
     */
    private static void runTestSuite() throws Exception {
        System.out.println("创建MySQL同步配置...");
        if (!testCreateConfig()) {
            System.out.println("配置创建失败，跳过后续相关测试");
            markTestSkipped("创建同步规则", "配置创建失败");
            markTestSkipped("启动同步器", "配置创建失败");
            markTestSkipped("停止同步器", "配置创建失败");
            markTestSkipped("更新同步配置", "配置创建失败");
            markTestSkipped("删除同步配置", "配置创建失败");
        } else {
            // 注意：根据新的设计，Role设置已经融合到同步器创建中
            // 不再需要单独的创建规则步骤
            
            System.out.println("\n4. 查看所有同步配置状态（创建后）");
            testGetAllStatus();
            
            System.out.println("\n5. 查看指定配置状态");
            testGetSpecificStatus(TEST_SYNCER_NAME);
            
            System.out.println("\n6. 查看同步器列表");
            testGetSyncerList();
            
            System.out.println("\n7. 查看同步器规则列表");
            testGetSyncerRuleList(TEST_SYNCER_NAME);
            
            System.out.println("\n=== 阶段3: 启动/停止测试 ===");
            System.out.println("8. 启动同步器");
            testStartSpecificConfig(TEST_SYNCER_NAME);
            
            System.out.println("\n9. 查看同步器状态（启动后）");
            testGetSpecificStatus(TEST_SYNCER_NAME);
            
            System.out.println("\n10. 等待5秒，查看同步消息...");
            waitForSync(1000000);
            
        }
    }
    
    /**
     * 测试：查看所有同步配置状态
     * 端点：GET /plugin/syncer/status
     */
    private static void testGetAllStatus() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/plugin/syncer/status"))
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        printRequestInfo(request, response);
        
        if (response.statusCode() == 200) {
            Map<String, Object> result = JSON.parseObject(response.body());
            Integer retCode = (Integer) result.get("retCode");
            if (retCode != null && retCode == 0) {
                System.out.println("   结果: 成功获取配置列表");
                // 打印配置数量
                Object data = result.get("data");
                if (data instanceof java.util.List) {
                    System.out.println("   配置数量: " + ((java.util.List<?>) data).size());
                }
            } else {
                System.out.println("   结果: 获取配置列表失败 - " + result.get("retMsg"));
            }
        } else {
            System.out.println("   结果: HTTP状态码异常: " + response.statusCode());
        }
    }
    
    /**
     * 测试：查看指定配置状态
     * 端点：GET /plugin/syncer/{syncer_name}/status
     */
    private static void testGetSpecificStatus(String syncerName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/plugin/syncer/" + syncerName + "/status"))
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        printRequestInfo(request, response);
        
        if (response.statusCode() == 200) {
            Map<String, Object> result = JSON.parseObject(response.body());
            Integer retCode = (Integer) result.get("retCode");
            if (retCode != null && retCode == 0) {
                System.out.println("   结果: 成功获取配置 '" + syncerName + "' 的状态");
            } else {
                System.out.println("   结果: 获取配置状态失败 - " + result.get("retMsg"));
            }
        } else if (response.statusCode() == 404) {
            System.out.println("   结果: 配置 '" + syncerName + "' 不存在");
        } else {
            System.out.println("   结果: HTTP状态码异常: " + response.statusCode());
        }
    }
    
    /**
     * 测试：查看同步器列表
     * 端点：POST /plugin/syncer/list
     */
    private static void testGetSyncerList() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/plugin/syncer/list"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        printRequestInfo(request, response);
        
        if (response.statusCode() == 200) {
            Map<String, Object> result = JSON.parseObject(response.body());
            Integer retCode = (Integer) result.get("retCode");
            if (retCode != null && retCode == 0) {
                System.out.println("   结果: 成功获取同步器列表");
            } else {
                System.out.println("   结果: 获取同步器列表失败 - " + result.get("retMsg"));
            }
        }
    }
    
    /**
     * 测试：查看同步器规则列表
     * 端点：POST /plugin/syncer/{syncer_name}/list
     */
    private static void testGetSyncerRuleList(String syncerName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/plugin/syncer/" + syncerName + "/list"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        printRequestInfo(request, response);
        
        if (response.statusCode() == 200) {
            Map<String, Object> result = JSON.parseObject(response.body());
            Integer retCode = (Integer) result.get("retCode");
            if (retCode != null && retCode == 0) {
                System.out.println("   结果: 成功获取同步器 '" + syncerName + "' 的规则列表");
            } else {
                System.out.println("   结果: 获取规则列表失败 - " + result.get("retMsg"));
            }
        }
    }
    
    /**
     * 测试：启动指定配置
     * 端点：POST /plugin/syncer/{rule_name}/start
     */
    private static void testStartSpecificConfig(String ruleName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/plugin/syncer/" + ruleName + "/start"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        printRequestInfo(request, response);
        
        if (response.statusCode() == 200) {
            Map<String, Object> result = JSON.parseObject(response.body());
            Integer retCode = (Integer) result.get("retCode");
            if (retCode != null && retCode == 0) {
                System.out.println("   结果: 成功启动配置 '" + ruleName + "'");
            } else {
                System.out.println("   结果: 启动失败 - " + result.get("retMsg"));
            }
        }
    }
    
    /**
     * 测试：停止指定配置
     * 端点：POST /plugin/syncer/{rule_name}/stop
     */
    private static void testStopSpecificConfig(String ruleName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/plugin/syncer/" + ruleName + "/stop"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        printRequestInfo(request, response);
        
        if (response.statusCode() == 200) {
            String body = response.body();
            if (body.contains("message")) {
                System.out.println("   结果: " + JSON.parseObject(body).get("message"));
            } else {
                System.out.println("   结果: 停止请求已发送");
            }
        } else if (response.statusCode() == 500) {
            Map<String, Object> result = JSON.parseObject(response.body());
            System.out.println("   结果: 停止失败 - " + result.get("error"));
        }
    }
    
    /**
     * 测试：创建新的同步配置
     * 端点：POST /plugin/syncer/create
     * @return 是否创建成功
     */
    private static boolean testCreateConfig() throws Exception {
        // 准备配置数据 - 使用可配置的测试连接信息
        Map<String, Object> config = new HashMap<>();
        config.put("syncer_type", "MYSQL");
        config.put("syncer_name", TEST_SYNCER_NAME);
        config.put("syncer_source", "{\"hostname\":\"" + TEST_MYSQL_HOST + "\",\"port\":\"" + TEST_MYSQL_PORT + "\",\"user\":\"" + TEST_MYSQL_USER + "\",\"password\":\"" + TEST_MYSQL_PASSWORD + "\",\"serverId\":\"1001\",\"database\":\"" + TEST_MYSQL_DATABASE + "\",\"topic.prefix\":\"" + TEST_SYNCER_NAME + "\"}");
        config.put("auto", true);
        config.put("enabled", true);
        config.put("status", "CREATED");
        
        // 根据新的设计，Role字段现在直接包含在同步器配置中
        config.put("source_database", TEST_MYSQL_DATABASE);
        config.put("target_database", TEST_MYSQL_DATABASE);
        config.put("source_table", "tb_data_set_column");
        config.put("target_table", "tb_data_set_column");

        String jsonBody = JSON.toJSONString(config);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/plugin/syncer/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        printRequestInfo(request, response);
        
        if (response.statusCode() == 200) {
            Map<String, Object> result = JSON.parseObject(response.body());
            Integer retCode = (Integer) result.get("retCode");
            if (retCode != null && retCode == 0) {
                System.out.println("   结果: 成功创建MySQL同步配置");
                configCreated = true;
                markTestPassed("创建MySQL同步配置");
                return true;
            } else {
                System.out.println("   结果: 创建失败 - " + result.get("retMsg"));
                markTestFailed("创建MySQL同步配置", result.get("retMsg").toString());
                return false;
            }
        } else {
            markTestFailed("创建MySQL同步配置", "HTTP状态码异常: " + response.statusCode());
            return false;
        }
    }
    
    /**
     * 测试：删除同步配置
     * 端点：POST /plugin/syncer/{syncer_name}/delete
     */
    private static void testDeleteConfig(String syncerName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/plugin/syncer/" + syncerName + "/delete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        printRequestInfo(request, response);
        
        if (response.statusCode() == 200) {
            Map<String, Object> result = JSON.parseObject(response.body());
            Integer retCode = (Integer) result.get("retCode");
            if (retCode != null && retCode == 0) {
                System.out.println("   结果: 成功删除配置 '" + syncerName + "'");
                configCreated = false;
            } else {
                System.out.println("   结果: 删除失败 - " + result.get("retMsg"));
            }
        }
    }

    /**
     * 等待同步消息
     */
    private static void waitForSync(int seconds) throws InterruptedException {
        System.out.print("   等待");
        for (int i = 1; i <= seconds; i++) {
            Thread.sleep(1000);
            System.out.print(".");
            if (i % 5 == 0 && i < seconds) {
                System.out.print(" " + i + "s");
            }
        }
        System.out.println(" " + seconds + "秒完成");
    }
    
    /**
     * 打印请求信息
     */
    private static void printRequestInfo(HttpRequest request, HttpResponse<String> response) {
        System.out.println("   URL: " + request.uri());
        System.out.println("   状态码: " + response.statusCode());
        try {
            // 尝试解析为JSON对象并美化输出
            System.out.println("   响应: " + JSON.toJSONString(JSON.parse(response.body()), JSONWriter.Feature.PrettyFormat));
        } catch (Exception e) {
            // 如果不是JSON，直接输出
            System.out.println("   响应: " + response.body());
        }
    }
    
    /**
     * 测试环境设置
     */
    private static void setup() throws Exception {
        System.out.println("正在设置DBSyncer集成测试环境...");

        // 1. 创建数据库连接（使用DuckDB内存数据库）
        conn = createDuckDBConnection();
        System.out.println("数据库连接创建成功");

        // 2. 创建Javalin应用实例
        javalin = Javalin.create().start(8080);
        System.out.println("Javalin应用实例创建成功");

        // 3. 创建日志记录器
        org.slf4j.Logger pluginLogger = org.slf4j.LoggerFactory.getLogger(DBSyncer.class);

        // 4. 创建DBPluginContext并注入资源
        DBPluginContext ctx = new DBPluginContext();
        ctx.setDbBackendConn(conn);
        ctx.setLogger(pluginLogger);
        ctx.setJavalin(javalin);
        System.out.println("DBPluginContext创建并配置完成");

        // 5. 创建插件实例（使用独立支持）
        plugin = DBSyncer.standAloneInstance();

        // 6. 注入上下文
        plugin.setDBPluginContext(ctx);
        
        System.out.println("测试环境设置完成\n");
    }
    
    /**
     * 测试环境清理
     * 注意：清理过程中的线程中断是预期的，直接忽略即可
     */
    private static void teardown() {
        System.out.println("\n正在清理DBSyncer集成测试环境...");

        try {
            // 清理测试创建的配置
            // 注意：根据新的设计，Role已经融合到同步器配置中，不再需要单独的规则清理
            
            if (configCreated) {
                try {
                    System.out.println("清理测试配置...");
                    testDeleteConfig(TEST_SYNCER_NAME);
                } catch (Exception e) {
                    System.err.println("清理配置时出错: " + e.getMessage());
                }
            }

            if (plugin != null) {
                System.out.println("调用插件stop()方法...");
                plugin.stop();
                System.out.println("插件stop()方法执行完成");
            }

            if (conn != null) {
                conn.close();
                System.out.println("数据库连接已关闭");
            }

            if (javalin != null) {
                javalin.stop();
                System.out.println("Javalin已停止");
            }

            System.out.println("DBSyncer集成测试环境清理完成");
        } catch (Exception e) {
            // 捕获所有异常，包括可能的运行时异常
            System.err.println("清理过程中发生异常: " + e.getMessage());
            // 如果是中断异常，恢复中断状态
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                System.out.println("清理过程被中断，这是预期的行为");
            }
        }
    }
    
    /**
     * 创建DuckDB连接
     */
    private static Connection createDuckDBConnection() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:duckdb:");
        // 设置非自动提交模式，以便插件可以管理事务
        conn.setAutoCommit(false);
        // 简单初始化
        conn.createStatement().execute("CREATE TABLE IF NOT EXISTS demo (id INT, name VARCHAR)");
        conn.createStatement().execute("INSERT INTO demo VALUES (1, 'test data')");
        conn.commit(); // 提交初始化事务
        return conn;
    }

    /**
     * 打印测试结果统计
     */
    private static void printTestSummary() {
        System.out.println("\n=== 测试结果统计 ===");
        System.out.println("测试通过: " + testsPassed);
        System.out.println("测试失败: " + testsFailed);
        System.out.println("测试跳过: " + testsSkipped);
        System.out.println("测试总数: " + (testsPassed + testsFailed + testsSkipped));
        
        if (testsFailed == 0) {
            System.out.println("\n✓ 所有测试通过！");
        } else {
            System.out.println("\n✗ 有 " + testsFailed + " 个测试失败，请检查上述输出。");
        }
    }
    
    /**
     * 标记测试通过
     */
    private static void markTestPassed(String testName) {
        System.out.println("   ✓ " + testName + ": 通过");
        testsPassed++;
    }
    
    /**
     * 标记测试失败
     */
    private static void markTestFailed(String testName, String reason) {
        System.out.println("   ✗ " + testName + ": 失败 - " + reason);
        testsFailed++;
    }
    
    /**
     * 标记测试跳过
     */
    private static void markTestSkipped(String testName, String reason) {
        System.out.println("   - " + testName + ": 跳过 - " + reason);
        testsSkipped++;
    }
}
