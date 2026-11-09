package org.slackerdb.dbserver.server;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置调度能力（ETL）的在场契约：配置决定"有没有这个能力"，能力决定路由与声明。
 *
 * <ul>
 *   <li>没配 {@code plugin-scheduler.home/projectHome}：{@code /scheduler/*} 不存在，
 *       {@code /capabilities.features.etl=false}，{@code /status.scheduler.state=DISABLED}（带原因）；</li>
 *   <li>配置齐备：路由存在、{@code etl=true}、状态 ACTIVE；</li>
 *   <li>配置错（目录不存在）：**拒绝启动**，并且启动失败后实例回到 IDLE（资源已回滚）。</li>
 * </ul>
 */
class SchedulerCapabilityTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("未配置 scheduler：没有这个能力（路由 404、etl=false、状态给出原因）")
    void schedulerAbsentWithoutConfiguration() throws Exception {
        DBInstance instance = startInstance(baseConfiguration());
        try {
            String baseUrl = "http://127.0.0.1:" + instance.serverConfiguration.getPortX();

            HttpResponse<String> scheduler = send(baseUrl + "/scheduler/capabilities");
            assertEquals(404, scheduler.statusCode(),
                    "without configuration the scheduler routes must not exist, body=" + scheduler.body());

            JSONObject capabilities = JSONObject.parseObject(send(baseUrl + "/capabilities").body());
            assertFalse(capabilities.getJSONObject("features").getBooleanValue("etl"),
                    "features.etl must be false when the capability is absent");

            JSONObject status = JSONObject.parseObject(send(baseUrl + "/status").body());
            JSONObject schedulerStatus = status.getJSONObject("scheduler");
            assertNotNull(schedulerStatus, "status must explain why the scheduler is absent");
            assertEquals("DISABLED", schedulerStatus.getString("state"));
            assertNotNull(schedulerStatus.getString("reason"), "a disabled capability must carry its reason");
        } finally {
            instance.stop();
        }
    }

    @Test
    @DisplayName("配置齐备：能力在场（路由可用、etl=true、状态 ACTIVE）")
    void schedulerPresentWhenConfigured() throws Exception {
        ServerConfiguration configuration = baseConfiguration();
        configuration.setPluginProperty("plugin-scheduler.home", tempDir.toString());
        configuration.setPluginProperty("plugin-scheduler.projectHome", tempDir.toString());

        DBInstance instance = startInstance(configuration);
        try {
            String baseUrl = "http://127.0.0.1:" + instance.serverConfiguration.getPortX();

            HttpResponse<String> scheduler = send(baseUrl + "/scheduler/capabilities");
            assertEquals(200, scheduler.statusCode(), "body=" + scheduler.body());
            JSONObject schedulerCapabilities = JSONObject.parseObject(scheduler.body());
            assertTrue(schedulerCapabilities.containsKey("quartz"), scheduler.body());

            JSONObject capabilities = JSONObject.parseObject(send(baseUrl + "/capabilities").body());
            assertTrue(capabilities.getJSONObject("features").getBooleanValue("etl"),
                    "features.etl must be true when the capability is present");

            JSONObject status = JSONObject.parseObject(send(baseUrl + "/status").body());
            assertEquals("ACTIVE", status.getJSONObject("scheduler").getString("state"));
        } finally {
            instance.stop();
        }
    }

    @Test
    @DisplayName("配置错（home 目录不存在）：拒绝启动，且实例回滚到 IDLE")
    void invalidConfigurationRefusesToStart() {
        ServerConfiguration configuration = baseConfiguration();
        configuration.setPluginProperty("plugin-scheduler.home", tempDir.resolve("missing").toString());
        configuration.setPluginProperty("plugin-scheduler.projectHome", tempDir.toString());

        DBInstance instance = new DBInstance(configuration);
        ServerException failure = assertThrows(ServerException.class, instance::start);
        assertTrue(failure.getMessage().contains("plugin-scheduler.home"), failure.getMessage());
        assertEquals("IDLE", instance.instanceState,
                "a refused startup must roll the instance back instead of leaking listeners/threads");
    }

    private ServerConfiguration baseConfiguration() {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(-1);
        configuration.setPortX(0);
        configuration.setData("mem");
        configuration.setLog_level("INFO");
        configuration.setSqlHistory("OFF");
        configuration.setDataServiceHistory("OFF");
        return configuration;
    }

    private static DBInstance startInstance(ServerConfiguration configuration) throws Exception {
        DBInstance instance = new DBInstance(configuration);
        instance.start();
        assertEquals("RUNNING", instance.instanceState.toUpperCase());
        return instance;
    }

    private static HttpResponse<String> send(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
