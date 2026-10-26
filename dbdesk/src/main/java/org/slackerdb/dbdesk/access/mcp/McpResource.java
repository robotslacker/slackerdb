package org.slackerdb.dbdesk.access.mcp;

/**
 * MCP 资源定义（{@code resources/list} 的展示字段）。
 */
public record McpResource(
        String uri,
        String name,
        String description,
        String mimeType,
        Object contents,
        String category) {
}
