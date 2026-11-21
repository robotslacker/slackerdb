package org.slackerdb.dbcli.common;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SESSION 变量在“父进程 → 进程作业子进程”之间的编码。
 *
 * <p>用 URL 编码逐项转义，再整体 Base64 URL 编码，避免名字/值里的换行、{@code =}、空白破坏
 * 环境变量格式。编码结果放在 {@link #ENV_SESSION} 环境变量里。</p>
 */
public final class VariableScopeCodec {

    /** 进程作业子进程继承的 SESSION 快照。 */
    public static final String ENV_SESSION = "_DBCLI_SESSION_VARS";

    /** 进程作业子进程的 LOCAL 初始变量（作业参数；优先级高于继承的 SESSION）。 */
    public static final String ENV_LOCAL = "_DBCLI_LOCAL_VARS";

    private VariableScopeCodec() {
    }

    public static String encode(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Map.of();
        }
        String text = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        Map<String, String> variables = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Invalid session variable payload.");
            }
            String name = URLDecoder.decode(line.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(line.substring(separator + 1), StandardCharsets.UTF_8);
            variables.put(name, value);
        }
        return variables;
    }
}
