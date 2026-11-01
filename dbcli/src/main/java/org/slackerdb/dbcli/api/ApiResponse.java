package org.slackerdb.dbcli.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 HTTP 请求的结果。
 *
 * <p>{@code error != null} 表示请求级失败（连接、超时、重定向循环、编码/读取/大小错误）；
 * 此时 {@code status = 0}、{@code body = ""}。HTTP 层面的非预期状态码**不是** {@code error}，
 * 它由 {@code ApiCommand} 按 {@code allow=} 判定并计一次失败。</p>
 */
public record ApiResponse(
        int status,
        Map<String, List<String>> headers,
        String body,
        String error) {

    public ApiResponse {
        headers = copyHeaders(headers);
        body = body == null ? "" : body;
    }

    /** 请求级失败：没有可用的 HTTP 响应。 */
    public static ApiResponse failure(String message) {
        return new ApiResponse(0, Map.of(), "", message == null ? "request failed" : message);
    }

    /** 某个响应头的全部值（不存在返回空表）；头名不区分大小写。 */
    public List<String> headerValues(String name) {
        if (name == null) {
            return List.of();
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    /** 某个响应头的值（多值用 {@code ", "} 连接）；不存在返回空串。 */
    public String header(String name) {
        return String.join(", ", headerValues(name));
    }

    /** 是否存在某个响应头（头名不区分大小写）。 */
    public boolean hasHeader(String name) {
        return !headerValues(name).isEmpty();
    }

    /** 规范头名：大写，非字母数字换成 {@code _}。 */
    public static String canonicalHeaderName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        StringBuilder canonical = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if ((character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')) {
                canonical.append(Character.toUpperCase(character));
            } else {
                canonical.append('_');
            }
        }
        return canonical.toString();
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                copy.put(entry.getKey(), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
            }
        }
        return Map.copyOf(copy);
    }
}
