package org.slackerdb.common.api;

/**
 * 管理 API 的契约版本。
 *
 * <p>为什么要有一个独立常量：管理面（{@code port_x} 上的 HTTP/WS 接口）现在有多个消费方
 * （控制台、{@code dbcli}、第三方客户端）。客户端不能靠"探测一堆端点然后猜"来判断服务端能力，
 * 只能对着一份声明过的版本号协商：{@code GET /capabilities} 返回 {@link #MANAGEMENT}，
 * 客户端声明自己支持的最低版本，不匹配时隐藏对应功能并提示，而不是崩。</p>
 *
 * <p>版本只在**破坏性变更**时递增（字段删除、语义改变）；新增字段不算。</p>
 */
public final class ApiVersion {

    /** 管理 API 契约版本（{@code GET /capabilities} 的 {@code apiVersion} 字段）。 */
    public static final int MANAGEMENT = 1;

    /** MCP 协议版本（服务端声明在 {@code contracts.mcp} 里）。 */
    public static final String MCP = "2026-07-28";

    private ApiVersion() {
    }
}
