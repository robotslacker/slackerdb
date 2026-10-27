package org.slackerdb.dbdesk.access.http;

/**
 * 插件管理操作的结果。
 *
 * <p>dbserver 的插件端点即使业务失败也返回 HTTP 200，因此不能只看 HTTP 状态；
 * {@code success=false} 时 {@link #message()} 是服务端 {@code retMsg}。</p>
 */
public record PluginActionResult(boolean success, String message, String pluginId) {
}
