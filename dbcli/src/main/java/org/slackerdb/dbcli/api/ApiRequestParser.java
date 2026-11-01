package org.slackerdb.dbcli.api;

import org.slackerdb.dbcli.common.PathArguments;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code _API} 的文本参数解析器。
 *
 * <p>语法层只把整行原文交进来；本类负责：方法/URL、可重复的 {@code header=}、
 * 三种请求体来源（{@code body=} / {@code bodyfile=} / {@code bodyvar=}）、
 * {@code timeout=}、{@code allow=}、{@code follow=}。所有错误都是可直接打印的一行消息。</p>
 */
public final class ApiRequestParser {

    /** 请求体（内联或文件）上限。 */
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    /** 支持的方法。 */
    private static final Set<String> METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private ApiRequestParser() {
    }

    /** 变量查找（{@code bodyvar=} 用）；返回 null 表示未定义。 */
    @FunctionalInterface
    public interface VariableLookup {
        String lookup(String name);
    }

    /**
     * 解析 {@code _API} 之后的整行原文。
     *
     * @param arguments        IR 里的 {@code arguments}
     * @param workingDirectory 脚本工作目录（{@code bodyfile=} 相对路径的基准）
     * @param variables        变量查找
     */
    public static ApiRequest parse(String arguments, Path workingDirectory, VariableLookup variables)
            throws ApiParseException {
        String text = stripTrailingSemicolon(arguments == null ? "" : arguments.strip());
        List<String> tokens = tokenize(text);
        if (tokens.size() < 2) {
            throw new ApiParseException("_API requires a method and a URL.");
        }

        String method = tokens.get(0).toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new ApiParseException("Unsupported _API method [" + tokens.get(0) + "].");
        }

        URI uri = parseUri(unquote(tokens.get(1)));

        List<ApiRequest.Header> headers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        byte[] body = new byte[0];
        boolean hasBody = false;
        int timeoutSeconds = (int) DEFAULT_TIMEOUT.toSeconds();
        boolean follow = true;
        boolean allowAll = false;
        Set<Integer> allowedStatuses = new LinkedHashSet<>();

        for (int index = 2; index < tokens.size(); index++) {
            String token = tokens.get(index);
            int equals = token.indexOf('=');
            if (equals <= 0) {
                throw new ApiParseException("_API options must be name=value, got ["
                        + token + "].");
            }
            String name = token.substring(0, equals).toLowerCase(Locale.ROOT);
            String rawValue = token.substring(equals + 1);
            String value = unquote(rawValue);

            if (!"header".equals(name) && !seen.add(name)) {
                throw new ApiParseException("_API option [" + name + "] was given twice.");
            }
            switch (name) {
                case "header" -> headers.add(parseHeader(value));
                case "body" -> {
                    ensureNoBody(hasBody, name);
                    body = utf8Bytes(value, "request body");
                    hasBody = true;
                }
                case "bodyfile" -> {
                    ensureNoBody(hasBody, name);
                    body = readBodyFile(value, workingDirectory);
                    hasBody = true;
                }
                case "bodyvar" -> {
                    ensureNoBody(hasBody, name);
                    String variable = value == null ? "" : value.strip();
                    if (variable.isEmpty()) {
                        throw new ApiParseException("_API option [bodyvar] requires a variable name.");
                    }
                    String captured = variables == null ? null : variables.lookup(variable);
                    if (captured == null) {
                        throw new ApiParseException("Unknown _API body variable ["
                                + variable + "].");
                    }
                    body = utf8Bytes(captured, "request body");
                    hasBody = true;
                }
                case "timeout" -> timeoutSeconds = parseTimeout(value);
                case "allow" -> {
                    Allow allow = parseAllow(value);
                    allowAll = allow.all();
                    allowedStatuses = allow.codes();
                }
                case "follow" -> follow = parseBoolean("follow", value);
                default -> throw new ApiParseException("Unknown _API option [" + name + "].");
            }
        }

        if ("GET".equals(method) || "HEAD".equals(method)) {
            if (hasBody) {
                throw new ApiParseException("_API option [body] cannot be used with GET or HEAD.");
            }
        }

        Duration timeout = timeoutSeconds == 0 ? Duration.ZERO : Duration.ofSeconds(timeoutSeconds);
        return new ApiRequest(method, uri, headers, body, timeout, follow, allowAll, allowedStatuses);
    }

    private static void ensureNoBody(boolean hasBody, String option) throws ApiParseException {
        if (hasBody) {
            throw new ApiParseException("_API option [" + option + "] was given twice.");
        }
    }

    private static ApiRequest.Header parseHeader(String value) throws ApiParseException {
        int colon = value.indexOf(':');
        if (colon < 0) {
            throw new ApiParseException("_API option [header] requires '<Name>: <Value>'.");
        }
        String name = value.substring(0, colon).strip();
        String headerValue = value.substring(colon + 1).strip();
        if (name.isEmpty() || name.chars().anyMatch(Character::isWhitespace)) {
            throw new ApiParseException("_API option [header] has an invalid name: ["
                    + name + "].");
        }
        return new ApiRequest.Header(name, headerValue);
    }

    private static int parseTimeout(String value) throws ApiParseException {
        try {
            int seconds = Integer.parseInt(value);
            if (seconds < 0) {
                throw new NumberFormatException("negative");
            }
            return seconds;
        } catch (NumberFormatException invalid) {
            throw new ApiParseException("_API option [timeout] requires a non-negative integer, got ["
                    + value + "].");
        }
    }

    private static boolean parseBoolean(String option, String value) throws ApiParseException {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new ApiParseException("_API option [" + option + "] requires true or false, got ["
                + value + "].");
    }

    private static Allow parseAllow(String value) throws ApiParseException {
        String text = value == null ? "" : value.strip();
        if ("all".equalsIgnoreCase(text)) {
            return new Allow(true, Set.of());
        }
        if (text.isEmpty()) {
            throw new ApiParseException("_API option [allow] requires HTTP status codes or 'all', got [].");
        }
        Set<Integer> codes = new LinkedHashSet<>();
        for (String part : text.split(",", -1)) {
            String codeText = part.strip();
            try {
                int code = Integer.parseInt(codeText);
                if (code < 100 || code > 599) {
                    throw new NumberFormatException("out of range");
                }
                codes.add(code);
            } catch (NumberFormatException invalid) {
                throw new ApiParseException("_API option [allow] requires HTTP status codes or 'all', got ["
                        + value + "].");
            }
        }
        return new Allow(false, codes);
    }

    private static URI parseUri(String value) throws ApiParseException {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException invalid) {
            throw new ApiParseException("_API URL must be an absolute http:// or https:// URL: ["
                    + value + "].");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ApiParseException("_API URL must be an absolute http:// or https:// URL: ["
                    + value + "].");
        }
        if (uri.getUserInfo() != null) {
            throw new ApiParseException("_API URL must not contain user info; use an Authorization header.");
        }
        return uri;
    }

    private static byte[] readBodyFile(String value, Path workingDirectory) throws ApiParseException {
        Path path;
        try {
            path = PathArguments.resolve(workingDirectory, value);
        } catch (PathArguments.InvalidPathException invalidPath) {
            throw new ApiParseException(invalidPath.getMessage());
        }
        byte[] raw;
        try {
            // 先看文件大小：大文件不应先被整个读进内存再报“超限”
            if (Files.size(path) > MAX_BODY_BYTES + 3L) {
                throw new ApiParseException("_API request body exceeds " + MAX_BODY_BYTES + " bytes.");
            }
            raw = Files.readAllBytes(path);
        } catch (IOException readFailed) {
            throw new ApiParseException("Cannot read _API body file [" + path + "]: "
                    + messageOf(readFailed), readFailed);
        }
        int offset = hasUtf8Bom(raw) ? 3 : 0;
        if (raw.length - offset > MAX_BODY_BYTES) {
            throw new ApiParseException("_API request body exceeds " + MAX_BODY_BYTES + " bytes.");
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String text = decoder.decode(ByteBuffer.wrap(raw, offset, raw.length - offset)).toString();
            return text.getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException notUtf8) {
            throw new ApiParseException("Cannot read _API body file [" + path
                    + "] as UTF-8: " + messageOf(notUtf8), notUtf8);
        }
    }

    private static byte[] utf8Bytes(String text, String label) throws ApiParseException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiParseException("_API " + label + " exceeds " + MAX_BODY_BYTES + " bytes.");
        }
        return bytes;
    }

    private static String messageOf(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank()
                ? (failure == null ? "unknown failure" : failure.getClass().getSimpleName())
                : message;
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
    }

    /**
     * 去掉行尾一个可选 {@code ;}；只去掉**引号外**的最后一个分号。
     */
    private static String stripTrailingSemicolon(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end == 0 || text.charAt(end - 1) != ';') {
            return text;
        }
        boolean single = false;
        boolean doubleQuote = false;
        for (int index = 0; index < end - 1; index++) {
            char character = text.charAt(index);
            if (single) {
                if (character == '\'') {
                    single = false;
                }
            } else if (doubleQuote) {
                if (character == '"') {
                    doubleQuote = false;
                }
            } else if (character == '\'') {
                single = true;
            } else if (character == '"') {
                doubleQuote = true;
            }
        }
        if (single || doubleQuote) {
            return text;
        }
        return text.substring(0, end - 1).stripTrailing();
    }

    /**
     * 按引号外空白切分 token；引号保留在 token 里，值解析时再剥。
     */
    private static List<String> tokenize(String text) throws ApiParseException {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean doubleQuote = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (single) {
                current.append(character);
                if (character == '\'') {
                    single = false;
                }
                continue;
            }
            if (doubleQuote) {
                current.append(character);
                if (character == '"') {
                    doubleQuote = false;
                }
                continue;
            }
            if (character == '\'') {
                single = true;
                current.append(character);
            } else if (character == '"') {
                doubleQuote = true;
                current.append(character);
            } else if (Character.isWhitespace(character)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (single || doubleQuote) {
            throw new ApiParseException("Unclosed quote in _API arguments.");
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** 去掉成对的最外层引号；不做反斜杠转义。 */
    private static String unquote(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private record Allow(boolean all, Set<Integer> codes) {
    }
}
