package org.slackerdb.dbcli.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRequestParserTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("默认 GET、30 秒超时、跟随重定向")
    void parsesDefaults() throws Exception {
        ApiRequest request = ApiRequestParser.parse("GET http://example.com/api", tempDir, name -> null);

        assertEquals("GET", request.method());
        assertEquals("http://example.com/api", request.uri().toString());
        assertEquals(Duration.ofSeconds(30), request.timeout());
        assertTrue(request.follow());
        assertFalse(request.allowAll());
        assertEquals(Set.of(), request.allowedStatuses());
        assertArrayEquals(new byte[0], request.body());
    }

    @Test
    @DisplayName("header 可重复；body/timeout/allow/follow 解析")
    void parsesOptions() throws Exception {
        ApiRequest request = ApiRequestParser.parse(
                "POST http://example.com/api "
                        + "header='Content-Type: application/json' "
                        + "header='X-Trace: a:b' "
                        + "body='{\"a\":1}' timeout=7 allow=404,409 follow=false",
                tempDir, name -> null);

        assertEquals("POST", request.method());
        assertEquals(2, request.headers().size());
        assertEquals("Content-Type", request.headers().get(0).name());
        assertEquals("application/json", request.headers().get(0).value());
        assertEquals("X-Trace", request.headers().get(1).name());
        assertEquals("a:b", request.headers().get(1).value());
        assertEquals("{\"a\":1}", new String(request.body(), StandardCharsets.UTF_8));
        assertEquals(Duration.ofSeconds(7), request.timeout());
        assertEquals(Set.of(404, 409), request.allowedStatuses());
        assertFalse(request.follow());
    }

    @Test
    @DisplayName("bodyvar 读取捕获变量；bodyfile 读取 UTF-8 文件")
    void parsesCapturedBodyAndFile() throws Exception {
        Map<String, String> captured = Map.of("payload", "{\n  \"a\": 1\n}");
        ApiRequest fromVar = ApiRequestParser.parse(
                "POST http://example.com/api bodyvar=payload", tempDir, captured::get);
        assertEquals("{\n  \"a\": 1\n}", new String(fromVar.body(), StandardCharsets.UTF_8));

        Path file = tempDir.resolve("payload.json");
        Files.writeString(file, "{\"b\":2}", StandardCharsets.UTF_8);
        ApiRequest fromFile = ApiRequestParser.parse(
                "POST http://example.com/api bodyfile=payload.json", tempDir, name -> null);
        assertEquals("{\"b\":2}", new String(fromFile.body(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("行尾分号被忽略；引号里的分号保留")
    void stripsOnlyTrailingSemicolon() throws Exception {
        ApiRequest request = ApiRequestParser.parse(
                "POST http://example.com/api body='a;b';", tempDir, name -> null);

        assertEquals("a;b", new String(request.body(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("allow=all")
    void parsesAllowAll() throws Exception {
        ApiRequest request = ApiRequestParser.parse(
                "GET http://example.com/api allow=all", tempDir, name -> null);

        assertTrue(request.allowAll());
        assertEquals(Set.of(), request.allowedStatuses());
    }

    @Test
    @DisplayName("参数错误是可读消息")
    void reportsReadableErrors() {
        assertMessage("GET", "_API requires a method and a URL.");
        assertMessage("TRACE http://example.com", "Unsupported _API method [TRACE].");
        assertMessage("GET ftp://example.com/a", "_API URL must be an absolute http:// or https:// URL: [ftp://example.com/a].");
        assertMessage("GET http://user:pass@example.com/a",
                "_API URL must not contain user info; use an Authorization header.");
        assertMessage("GET http://example.com nope=1", "Unknown _API option [nope].");
        assertMessage("GET http://example.com body='x'",
                "_API option [body] cannot be used with GET or HEAD.");
        assertMessage("POST http://example.com body='x' body='y'",
                "_API option [body] was given twice.");
        assertMessage("POST http://example.com bodyvar=missing",
                "Unknown _API body variable [missing].");
        assertMessage("GET http://example.com allow=99",
                "_API option [allow] requires HTTP status codes or 'all', got [99].");
        assertMessage("GET http://example.com follow=maybe",
                "_API option [follow] requires true or false, got [maybe].");
    }

    private void assertMessage(String arguments, String expected) {
        ApiParseException error = assertThrows(ApiParseException.class,
                () -> ApiRequestParser.parse(arguments, tempDir, name -> null));
        assertEquals(expected, error.getMessage());
    }
}
