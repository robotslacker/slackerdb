package org.slackerdb.dbdesk.access.http;

/**
 * {@code GET /health} 的映射（服务端契约 S-3）。
 *
 * <p>状态栏每 2 秒轮询它；服务端保证这个端点"便宜"（不查库、不扫会话）。
 * 判红点看 {@link #up()}，不要自己拼字段。</p>
 */
public record Health(String status, String database, String management, String instanceState, long ts) {

    public boolean up() {
        return "UP".equalsIgnoreCase(status);
    }
}
