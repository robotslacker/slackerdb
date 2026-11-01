package org.slackerdb.dbcli.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 {@code _API} 的结果投影：是否成功，以及可供 {@code _ASSERT} / {@code _IF} / {@code ${...}}
 * 使用的 {@code API.*} 变量。
 */
public record ApiResult(boolean ok, Map<String, String> variables) {

    public ApiResult {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    /**
     * 把响应投影成变量。
     *
     * @param response 响应（失败时 error 非 null）
     * @param request  原请求（用于 allow/follow 对应的判定）
     */
    public static ApiResult from(ApiResponse response, ApiRequest request) {
        int status = response.status();
        boolean ok = response.error() == null
                && (request.allowAll()
                    || status / 100 == 2
                    || request.allowedStatuses().contains(status));

        Map<String, String> values = new LinkedHashMap<>();
        values.put("API.STATUS", String.valueOf(status));
        values.put("API.OK", String.valueOf(ok));
        values.put("API.BODY", response.body());
        if (response.error() != null) {
            values.put("API.ERROR", response.error());
        }

        for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
            String headerName = entry.getKey();
            String joined = String.join(", ", entry.getValue());
            if (!headerName.isBlank()) {
                values.put("API.HEADER." + headerName, joined);
                String canonical = ApiResponse.canonicalHeaderName(headerName);
                if (!canonical.isEmpty()) {
                    values.put("API.HEADER." + canonical, joined);
                }
            }
        }

        if (response.error() == null) {
            for (Map.Entry<String, String> entry : JsonPaths.flatten(response.body()).entrySet()) {
                values.put("API.JSON." + entry.getKey(), entry.getValue());
            }
        }
        return new ApiResult(ok, values);
    }
}
