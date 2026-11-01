package org.slackerdb.dbcli.api;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 一条已经解析完成的 HTTP 请求。
 *
 * <p>不可变值对象：请求体是克隆后的字节数组；超时为 {@link Duration#ZERO} 表示不设总超时。</p>
 *
 * @param method          大写方法名
 * @param uri             绝对 http(s) URI
 * @param headers         请求头（可重复）
 * @param body            请求体字节（没有请求体时为空数组）
 * @param timeout         单次请求总超时；{@link Duration#ZERO} 表示不限
 * @param follow          是否跟随 3xx
 * @param allowAll        任意 HTTP 状态都算成功
 * @param allowedStatuses 额外接受的状态码（{@code allow=404,409}）
 */
public record ApiRequest(
        String method,
        URI uri,
        List<Header> headers,
        byte[] body,
        Duration timeout,
        boolean follow,
        boolean allowAll,
        Set<Integer> allowedStatuses) {

    public ApiRequest {
        body = body == null ? new byte[0] : body.clone();
        headers = headers == null ? List.of() : List.copyOf(headers);
        timeout = timeout == null ? Duration.ZERO : timeout;
        allowedStatuses = allowedStatuses == null ? Set.of() : Set.copyOf(allowedStatuses);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** 一条请求头；同名头允许重复，按列表顺序发送。 */
    public record Header(String name, String value) {
        public Header {
            name = name == null ? "" : name;
            value = value == null ? "" : value;
        }
    }
}
