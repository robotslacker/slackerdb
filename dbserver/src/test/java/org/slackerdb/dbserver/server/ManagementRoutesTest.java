package org.slackerdb.dbserver.server;

import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.configuration.ServerConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理口路由注册顺序的契约测试。
 *
 * <p><b>为什么需要它</b>：Javalin 7 的路由匹配是"按注册顺序取第一个匹配"
 * （{@code PathMatcher.findFirstEntry}），而 {@link DBInstanceX} 是在插件/模块挂载之前
 * 构造的。因此宿主要是注册了 {@code get("/{...}*")} 一类的通配兜底路由，之后注册的
 * <b>所有 GET 路由</b>都会被它遮蔽，表现为"插件已 STARTED，但它的 GET 端点全部 404"。
 * 这不是假设：{@code b90f7a9}（"remove console from server."）引入兜底之后，
 * plugin-scheduler 的 GET 端点就是这样集体失效的。</p>
 *
 * <p>本用例用"服务启动之后再注册一个 GET 路由"来复现插件/模块的挂载方式（它们同样是
 * 在宿主把路由注册完之后才注册），从而把这条顺序约束钉死。</p>
 */
class ManagementRoutesTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static DBInstance dbInstance;
    private static Javalin managementApp;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        // 只验证管理口：关掉 pgwire 监听（port=-1），省掉一次无关的端口占用
        configuration.setPort(-1);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setSqlHistory("OFF");
        configuration.setDataServiceHistory("OFF");

        dbInstance = new DBInstance(configuration);
        dbInstance.start();
        assertEquals("RUNNING", dbInstance.instanceState.toUpperCase());

        managementApp = dbInstance.dbInstanceX.getManagementApp();
        assertNotNull(managementApp, "management app must be available when portX is enabled");
        baseUrl = "http://127.0.0.1:" + configuration.getPortX();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    @Test
    @DisplayName("服务启动之后注册的 GET 路由必须可达（插件/模块就是这样注册的）")
    void getRouteRegisteredAfterStartIsReachable() throws Exception {
        managementApp.unsafe.routes.get("/probe/late-get", ctx -> ctx.json(Map.of("ok", true)));

        HttpResponse<String> response = send("GET", "/probe/late-get");

        assertEquals(200, response.statusCode(),
                "a GET route registered after the host finished wiring must not be shadowed, body=" + response.body());
        JSONObject body = JSONObject.parseObject(response.body());
        assertTrue(body.getBooleanValue("ok"), "handler must be the one registered late, body=" + response.body());
    }

    @Test
    @DisplayName("未知路径仍然返回统一的 JSON 404 信封（GET/HEAD）")
    void unknownPathStillReturnsJsonEnvelope() throws Exception {
        HttpResponse<String> get = send("GET", "/no/such/path");

        assertEquals(404, get.statusCode());
        JSONObject body = JSONObject.parseObject(get.body());
        assertNotNull(body, "404 body must be JSON, got=" + get.body());
        assertEquals(404, body.getIntValue("retCode"), "body=" + get.body());
        assertEquals("/no/such/path is not found.", body.getString("retMsg"), "body=" + get.body());

        // HEAD 只校验状态码：命中不存在的路径必须是 404，而不是"有 GET 处理器就 200"
        assertEquals(404, send("HEAD", "/no/such/path").statusCode());
    }

    @Test
    @DisplayName("路由自己写的 404 响应体不会被兜底信封覆盖")
    void routeWrittenNotFoundBodyIsPreserved() throws Exception {
        managementApp.unsafe.routes.get("/probe/own-404", ctx -> ctx.status(404).json(Map.of(
                "retCode", 404,
                "retMsg", "own body")));

        HttpResponse<String> response = send("GET", "/probe/own-404");

        assertEquals(404, response.statusCode());
        JSONObject body = JSONObject.parseObject(response.body());
        assertEquals("own body", body.getString("retMsg"),
                "the 404 error handler must not overwrite a body the route already wrote, body=" + response.body());
    }

    private static HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
