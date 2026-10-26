package org.slackerdb.dbserver.test;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步备份任务契约：创建任务 -> 轮询 -> 下载。
 */
public class BackupApiTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    static DBInstance dbInstance;
    static String baseUrl;
    static int dbPort;

    @BeforeAll
    static void initAll() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData("backupmem");
        configuration.setLog_level("INFO");
        configuration.setSqlHistory("OFF");

        dbPort = configuration.getPort();
        dbInstance = new DBInstance(configuration);
        dbInstance.start();
        baseUrl = "http://127.0.0.1:" + configuration.getPortX();

        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + dbPort + "/backupmem", "", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS backup_probe (id INTEGER, note VARCHAR)");
            statement.execute("INSERT INTO backup_probe VALUES (1, 'hello')");
        }
    }

    @AfterAll
    static void shutdown() {
        if (dbInstance != null) {
            dbInstance.stop();
        }
    }

    @Test
    @DisplayName("POST /backups returns a task that can be polled and downloaded")
    void backupTaskRoundTrip() throws Exception {
        String tag = "auto_test_" + System.nanoTime();
        Path backupFile = Path.of("backup", tag + ".db").toAbsolutePath().normalize();
        try {
            HttpResponse<String> started = CLIENT.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/backups"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"backupTag\":\"" + tag + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, started.statusCode(), "backup start should return 202: " + started.body());
            JSONObject startBody = JSONObject.parseObject(started.body());
            assertEquals(0, startBody.getIntValue("retCode"));
            String id = startBody.getString("id");
            assertNotNull(id);

            JSONObject task = null;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                HttpResponse<String> status = CLIENT.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/backups/" + id)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, status.statusCode(), "backup status should return 200");
                task = JSONObject.parseObject(status.body());
                String state = task.getString("status");
                if ("COMPLETED".equals(state) || "FAILED".equals(state)) {
                    break;
                }
                Thread.sleep(100);
            }
            assertNotNull(task);
            assertEquals("COMPLETED", task.getString("status"), "backup failed: " + task);

            HttpResponse<byte[]> download = CLIENT.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/backups/" + id + "/download")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode(), "backup download should return 200");
            assertTrue(download.body().length > 0, "downloaded backup must not be empty");
        } finally {
            Files.deleteIfExists(backupFile);
        }
    }

    @Test
    @DisplayName("invalid backup tags are rejected before a task is created")
    void invalidTagIsRejected() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/backups"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"backupTag\":\"../escape\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertEquals(400, JSONObject.parseObject(response.body()).getIntValue("retCode"));
    }
}
