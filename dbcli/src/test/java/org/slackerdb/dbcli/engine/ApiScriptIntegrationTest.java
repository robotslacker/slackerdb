package org.slackerdb.dbcli.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiScriptIntegrationTest {

    private HttpServer server;
    private String base;
    private String lastBody;
    private String lastAuthorization;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/discard", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, "ok");
        });
        server.createContext("/echo", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, lastBody);
        });
        server.createContext("/login", exchange -> respond(exchange, 200,
                "{\"access_token\":\"abc\",\"user\":{\"id\":7,\"name\":\"demo\"},\"items\":[{\"id\":1}]}"));
        server.createContext("/me", exchange -> {
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, 200, "{\"user\":{\"name\":\"demo\"}}");
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/login");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/missing", exchange -> respond(exchange, 404,
                "{\"error\":\"not_found\"}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("_ECHO @name 捕获多行 JSON，bodyvar 原样发送且捕获内容不上屏")
    void echoCaptureFeedsApiBody() {
        Harness harness = new Harness();
        try {
            int code = harness.run("""
                    _ECHO @payload
                    {"message":"contains ; and -- safely","n":1}
                    echo off
                    _api POST BASE_URL/discard header='Content-Type: application/json' bodyvar=payload
                    _assert {% API.STATUS = 200 %}
                    _assert {% API.BODY = ok %}
                    """.replace("BASE_URL", base));

            assertEquals(0, code);
            assertEquals("{\"message\":\"contains ; and -- safely\",\"n\":1}", lastBody);
            assertTrue(harness.stdout().contains("HTTP 200"), harness.stdout());
            assertTrue(harness.stdout().contains("Assert successful."), harness.stdout());
            assertFalse(harness.stdout().contains("contains ; and -- safely"),
                    "捕获的请求体不应被 _ECHO 打印: " + harness.stdout());
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("API.JSON 取 token，带入下一次请求头")
    void jsonTokenFeedsNextRequest() {
        Harness harness = new Harness();
        try {
            int code = harness.run("""
                    _api GET BASE_URL/login
                    _assert {% API.STATUS = 200 %}
                    _assert {% API.JSON.access_token = abc %}
                    _assert {% API.JSON.items[0].id = 1 %}
                    _assert {% API.JSON.items.0.id = 1 %}
                    _set @token ${API.JSON.access_token}
                    _api GET BASE_URL/me header='Authorization: Bearer ${token}'
                    _assert {% API.JSON.user.name = demo %}
                    """.replace("BASE_URL", base));

            assertEquals(0, code);
            assertEquals("Bearer abc", lastAuthorization);
            assertTrue(harness.stdout().contains("Assert successful."), harness.stdout());
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("默认跟随 3xx；最终响应状态参与断言")
    void followsRedirectByDefaultInScript() {
        Harness harness = new Harness();
        try {
            int code = harness.run("""
                    _api GET BASE_URL/redirect
                    _assert {% API.STATUS = 200 %}
                    _assert {% API.JSON.access_token = abc %}
                    """.replace("BASE_URL", base));

            assertEquals(0, code);
            assertTrue(harness.stdout().contains("HTTP 200"), harness.stdout());
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("空的 _ECHO @name 捕获成空串，bodyvar 发送空请求体")
    void emptyEchoCaptureSendsEmptyBody() {
        Harness harness = new Harness();
        try {
            int code = harness.run("""
                    _ECHO @empty
                    echo off
                    _api POST BASE_URL/discard bodyvar=empty
                    _assert {% API.STATUS = 200 %}
                    """.replace("BASE_URL", base));

            assertEquals(0, code);
            assertEquals("", lastBody);
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("_ECHO 捕获名非法是可读错误，不影响后续脚本")
    void invalidEchoCaptureNameIsReadable() {
        Harness harness = new Harness();
        try {
            int code = harness.run("""
                    _ECHO @bad name
                    body
                    echo off
                    _echo t
                    after
                    echo off
                    """.replace("BASE_URL", base));

            assertEquals(0, code);
            assertTrue(harness.stderr().contains("Invalid echo capture name"), harness.stderr());
            assertTrue(harness.stdout().contains("after"), harness.stdout());
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("allow=404 接受错误状态；未允许的 404 使 API.OK=false")
    void allowControlsStatusFailure() {
        Harness harness = new Harness();
        try {
            int code = harness.run("""
                    _api GET BASE_URL/missing allow=404
                    _assert {% API.STATUS = 404 %}
                    _assert {% API.OK = true %}
                    _api GET BASE_URL/missing
                    _assert {% API.OK = false %}
                    _assert {% API.JSON.error = not_found %}
                    """.replace("BASE_URL", base));

            assertEquals(0, code);
            assertTrue(harness.stdout().contains("HTTP 404 (unexpected status)."), harness.stdout());
            assertEquals(4, harness.stdout().split("Assert successful\\.", -1).length - 1,
                    harness.stdout());
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("follow=false 保留 302 与 Location")
    void followFalseKeepsRedirect() {
        Harness harness = new Harness();
        try {
            int code = harness.run("""
                    _api GET BASE_URL/redirect follow=false allow=302
                    _assert {% API.STATUS = 302 %}
                    _assert {% API.HEADER.Location = /login %}
                    _set @loc ${API.HEADER.LOCATION}
                    _assert {% VAR.loc = /login %}
                    """.replace("BASE_URL", base));

            assertEquals(0, code);
            assertTrue(harness.stdout().contains("HTTP 302"), harness.stdout());
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("传输失败设置 API.STATUS=0 / OK=false / ERROR，并计一次错误")
    void transportFailureSetsApiVariables() throws IOException {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }

        Harness harness = new Harness();
        try {
            int code = harness.run("""
                    _api GET http://127.0.0.1:PORT/nope
                    _assert {% API.STATUS = 0 %}
                    _assert {% API.OK = false %}
                    _assert {% API.ERROR EXISTS %}
                    """.replace("PORT", String.valueOf(port)));

            assertEquals(0, code);
            assertTrue(harness.stderr().contains("API request failed"), harness.stderr());
            assertTrue(harness.stdout().contains("Assert successful."), harness.stdout());
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("API.* 可用于 _IF 条件")
    void apiValuesDriveControlFlow() {
        Harness harness = new Harness();
        try {
            int code = harness.run("""
                    _api GET BASE_URL/login
                    _if {% API.OK = true %}
                      _echo t
                      in-branch
                      echo off
                    _else
                      _echo t
                      else-branch
                      echo off
                    _endif
                    """.replace("BASE_URL", base));

            assertEquals(0, code);
            assertTrue(harness.stdout().contains("in-branch"), harness.stdout());
            assertTrue(!harness.stdout().contains("else-branch"), harness.stdout());
        } finally {
            harness.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 与 ScriptRunnerTest 同一种最小夹具，但只关心输出/退出码。 */
    private static final class Harness implements AutoCloseable {
        private final FakeSqlExecutor sql = new FakeSqlExecutor();
        private final StringWriter out = new StringWriter();
        private final StringWriter err = new StringWriter();
        private final ScriptContext context;
        private final ScriptRunner runner;

        Harness() {
            this.context = new ScriptContext(sql, new ScriptOutput(new PrintWriter(out, true),
                    new PrintWriter(err, true)), new VariableResolver(Map.of()), Path.of("."));
            this.runner = new ScriptRunner(context);
        }

        int run(String script) {
            return runner.runText(script, "<api-test>", List.of());
        }

        String stdout() {
            return out.toString();
        }

        String stderr() {
            return err.toString();
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
