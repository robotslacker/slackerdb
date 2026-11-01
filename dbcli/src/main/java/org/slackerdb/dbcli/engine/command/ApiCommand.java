package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.api.ApiCancelledException;
import org.slackerdb.dbcli.api.ApiException;
import org.slackerdb.dbcli.api.ApiParseException;
import org.slackerdb.dbcli.api.ApiRequest;
import org.slackerdb.dbcli.api.ApiRequestParser;
import org.slackerdb.dbcli.api.ApiResponse;
import org.slackerdb.dbcli.api.ApiResult;

import java.util.Map;
import java.util.Set;

/**
 * {@code _API}：发一次 HTTP 请求，打印状态/响应体，并刷新 {@code API.*} 运行时变量。
 *
 * <p>命令实现只负责编排：文本解析在 {@link ApiRequestParser}，网络在 {@link ApiClient}，
 * 结果投影在 {@link ApiResult}。错误一律走 {@link CommandCall#reportError} / {@link CommandCall#countFailure}。</p>
 */
public final class ApiCommand implements ScriptCommand {

    @Override
    public Set<String> names() {
        return Set.of("API");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        String arguments = ir.get("arguments") == null ? "" : String.valueOf(ir.get("arguments"));
        ApiRequest request;
        try {
            request = ApiRequestParser.parse(arguments, call.context().workingDirectory(),
                    call.context().variables()::lookup);
        } catch (ApiParseException invalid) {
            call.reportError(invalid.getMessage());
            return;
        }

        ApiResponse response;
        try {
            response = call.context().apiClient().send(request, call.host()::stopRequested);
        } catch (ApiCancelledException ignored) {
            // 停止流程负责输出/退出；这里只把控制权交回去。
            call.host().checkStopRequested();
            call.reportError("API request cancelled.");
            return;
        } catch (ApiException failed) {
            response = ApiResponse.failure(failed.getMessage());
            apply(call, request, response);
            call.reportError("API request failed [" + request.method() + " " + request.uri()
                    + "]: " + failed.getMessage());
            return;
        } catch (RuntimeException unexpected) {
            String message = messageOf(unexpected);
            response = ApiResponse.failure(message);
            apply(call, request, response);
            call.reportError("API request failed [" + request.method() + " " + request.uri()
                    + "]: " + message);
            return;
        }

        ApiResult result = apply(call, request, response);
        if (result.ok()) {
            call.context().output().printStatus("HTTP " + response.status());
        } else {
            call.countFailure("HTTP " + response.status() + " (unexpected status).");
        }
        printBody(call, response.body());
    }

    private static ApiResult apply(CommandCall call, ApiRequest request, ApiResponse response) {
        ApiResult result = ApiResult.from(response, request);
        call.context().variables().setRuntimeVariables(result.variables());
        return result;
    }

    private static void printBody(CommandCall call, String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        String[] lines = body.split("\\R", -1);
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) {
            end--;
        }
        for (int index = 0; index < end; index++) {
            call.context().output().printData(lines[index]);
        }
    }

    private static String messageOf(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank()
                ? (failure == null ? "request failed" : failure.getClass().getSimpleName())
                : message;
    }
}
