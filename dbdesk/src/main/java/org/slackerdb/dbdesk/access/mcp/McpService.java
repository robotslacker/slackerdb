package org.slackerdb.dbdesk.access.mcp;

import java.util.List;

/**
 * MCP 服务定义（{@code resources/read slackerdb://services} 的展示字段）。
 */
public record McpService(
        String name,
        String apiName,
        String version,
        String method,
        String description,
        String category,
        List<String> capabilities,
        List<String> useCases,
        Object parameters,
        Object examples) {

    public McpService {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        useCases = useCases == null ? List.of() : List.copyOf(useCases);
    }
}
