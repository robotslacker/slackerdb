package org.slackerdb.dbcli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _API} 的命令行级端到端测试：脚本文件 → Cli → ScriptRunner → HTTP → stdout。
 */
class CliApiTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200,
                "{\"status\":\"ok\",\"token\":\"abc\"}"));
        server.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("--execute 跑 _API：状态/JSON/echo 捕获请求体都进入 stdout")
    void executeScriptWithApi() throws Exception {
        Path script = tempDir.resolve("api.sql");
        Files.writeString(script, """
                _ECHO @payload
                {"message":"from echo; and -- safe"}
                echo off

                _api GET BASE_URL/ok
                _assert {% API.STATUS = 200 %}
                _assert {% API.JSON.token = abc %}

                _api POST BASE_URL/echo header='Content-Type: application/json' bodyvar=payload
                _assert {% API.STATUS = 200 %}
                _assert {% API.BODY CONTAINS "from echo" %}
                """.replace("BASE_URL", base));

        StringWriter out = new StringWriter();
        Cli cli = new Cli();
        cli.setStdin(new ByteArrayInputStream(new byte[0]));
        CommandLine commandLine = new CommandLine(cli);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(out, true));

        int exitCode = commandLine.execute("--execute", script.toString());

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("HTTP 200"), out.toString());
        assertTrue(out.toString().contains("{\"message\":\"from echo; and -- safe\"}"),
                out.toString());
        assertTrue(out.toString().contains("Assert successful."), out.toString());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
