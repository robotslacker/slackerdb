package org.slackerdb.dbdesk.access.mcp;

/**
 * MCP 工具定义（{@code tools/list} 的展示字段）。
 *
 * @param inputSchema JSON Schema，保留为通用对象，避免把 MCP 的 schema 形状写死
 */
public record McpTool(String name, String description, Object inputSchema, String category) {
}
