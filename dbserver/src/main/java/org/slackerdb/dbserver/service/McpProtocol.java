package org.slackerdb.dbserver.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slackerdb.dbserver.BuildInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 2026-07-28 规范支撑：协议版本协商、能力与身份发现、结果信封、缓存契约。
 *
 * <p><b>为什么单独一个类</b>：{@code McpServer} 负责"有哪些工具/资源"，
 * 本类负责"这些内容怎么按规范落到线上"。两者分开后，协议层的每条规则
 * （哪些字段必填、错误码是多少、双代怎么判定）都能在一处读完。</p>
 *
 * <h2>2026-07-28 相对旧版的关键差异（本类按此实现）</h2>
 * <ul>
 *   <li><b>无状态</b>：{@code initialize} 握手取消。每个请求在
 *       {@code params._meta} 里自带 {@code io.modelcontextprotocol/protocolVersion}
 *       与 {@code .../clientCapabilities}。版本不匹配返回
 *       {@code UnsupportedProtocolVersionError}。</li>
 *   <li><b>server/discover 强制实现</b>：服务端必须公布自己支持的协议版本、能力与身份。</li>
 *   <li><b>结果必带 resultType</b>：{@code "complete"} 或 {@code "input_required"}。
 *       旧版服务端的结果缺该字段时，客户端必须当作 {@code "complete"}——
 *       所以本类在<b>我们发出</b>的结果里始终带上它。</li>
 *   <li><b>GET 端点移除</b>：只保留 POST；{@code ping}/{@code logging/setLevel} 移除。</li>
 *   <li><b>缓存契约</b>：{@code tools/list}、{@code prompts/list}、{@code resources/list}、
 *       {@code resources/read}、{@code resources/templates/list} 的结果<b>必须</b>
 *       带 {@code ttlMs} 与 {@code cacheScope}。</li>
 *   <li><b>确定性 tools/list</b>：按名字排序，便于客户端缓存与 LLM 提示词缓存命中。</li>
 *   <li><b>错误码重划</b>：{@code -32020}~{@code -32099} 保留给 MCP；
 *       资源不存在由 {@code -32002} 改为 {@code -32602}。</li>
 *   <li><b>跨调用状态</b>：不再有协议级会话；需要跨调用状态的服务器必须使用
 *       <b>服务端铸造的显式 handle</b>，作为普通工具参数传递
 *       （规范的非规范性指导原文举的例子就是"一个数据库事务"）。
 *       本项目由 {@link McpSessionStore} 提供该 handle。</li>
 * </ul>
 *
 * <p><b>双代兼容</b>：已安装的客户端普遍滞后。本类同时支持
 * (a) 无状态代：请求带 {@code _meta.protocolVersion}；
 * (b) 旧代：带 {@code initialize}/{@code notifications/initialized} 握手。
 * 两条路径共用同一份能力与身份描述，避免"两份真相"。</p>
 */
final class McpProtocol {

    private McpProtocol() {
    }

    // ---------------------------------------------------------------- 常量

    /** 本服务端首要支持的规范版本。 */
    static final String PROTOCOL_VERSION = "2026-07-28";

    /**
     * 本服务端还能接受的历史版本。
     *
     * <p>保留 {@code 2024-11-05} 是因为当前发布的实现与已安装客户端都在用它；
     * 客户端拿这个版本号请求时，我们按旧代语义应答，但仍然发出
     * {@code resultType}（新客户端会忽略多余字段，旧客户端不受影响）。</p>
     */
    static final List<String> SUPPORTED_VERSIONS = List.of(
            "2026-07-28",
            "2025-11-25",
            "2025-06-18",
            "2025-03-26",
            "2024-11-05"
    );

    static final String SERVER_NAME = "SlackerDB-MCP-Server";
    static final String SERVER_TITLE = "SlackerDB";

    /** {@code _meta} 中的保留键（规范定义，以斜杠分隔的命名空间）。 */
    static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    static final String META_LOG_LEVEL = "io.modelcontextprotocol/logLevel";

    // MCP 保留区间 -32020 ~ -32099
    static final int ERR_UNSUPPORTED_PROTOCOL_VERSION = -32020;
    static final int ERR_MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;

    // JSON-RPC 标准码
    static final int ERR_PARSE = -32700;
    static final int ERR_INVALID_REQUEST = -32600;
    static final int ERR_METHOD_NOT_FOUND = -32601;
    static final int ERR_INVALID_PARAMS = -32602;
    static final int ERR_INTERNAL = -32603;

    static final String RESULT_COMPLETE = "complete";
    static final String RESULT_INPUT_REQUIRED = "input_required";

    static final String SCOPE_PUBLIC = "public";
    static final String SCOPE_PRIVATE = "private";

    // ---------------------------------------------------------------- 版本与身份

    /** 服务端身份。{@code server/discover} 与旧代 {@code initialize} 共用同一份。 */
    static Map<String, Object> serverInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", SERVER_NAME);
        info.put("title", SERVER_TITLE);
        info.put("version", serverVersion());
        return info;
    }

    /** 服务端版本号：构建期生成（读不到时是 {@code unknown}），不再在这里维护一份硬编码的版本。 */
    private static String serverVersion() {
        return BuildInfo.version();
    }

    /**
     * 服务端能力。
     *
     * <p>只声明<b>确实实现</b>的能力：旧实现声明了 {@code resources.write} 与
     * 三个 {@code listChanged}，但没有任何对应方法或通知，属于虚假声明，
     * 客户端会据此走错分支。这里保持不变更通知（本服务端确实不发）。</p>
     */
    static Map<String, Object> capabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("tools", Map.of("listChanged", false));
        caps.put("resources", Map.of("subscribe", false, "listChanged", false));
        caps.put("prompts", Map.of("listChanged", false));
        return caps;
    }

    /** 服务端已实现的 RPC 方法名（供 {@code server/discover} 与自检使用）。 */
    static List<String> implementedMethods() {
        return List.of(
                "server/discover",
                "tools/list",
                "tools/call",
                "resources/list",
                "resources/read",
                "resources/templates/list",
                "prompts/list",
                "prompts/get"
        );
    }

    // ---------------------------------------------------------------- 请求元信息

    /** 从请求里取出的元信息。 */
    record RequestMeta(String protocolVersion, boolean present, JSONObject clientCapabilities) {

        /** 是否属于"无状态代"：请求自带 protocolVersion。 */
        boolean modernEra() {
            return present;
        }
    }

    /**
     * 从 {@code params._meta} 解析元信息。
     *
     * <p>没有 {@code _meta.protocolVersion} 就是旧代请求（或手写客户端），
     * 兼容版本处理，而不是直接拒绝——拒绝会让现存的所有客户端一夜之间连不上。</p>
     */
    static RequestMeta readMeta(JSONObject params) {
        JSONObject meta = params == null ? null : params.getJSONObject("_meta");
        if (meta == null) {
            return new RequestMeta(SUPPORTED_VERSIONS.get(SUPPORTED_VERSIONS.size() - 1), false, null);
        }
        String version = meta.getString(META_PROTOCOL_VERSION);
        JSONObject clientCaps = meta.getJSONObject(META_CLIENT_CAPABILITIES);
        if (version == null || version.isBlank()) {
            return new RequestMeta(SUPPORTED_VERSIONS.get(SUPPORTED_VERSIONS.size() - 1), false, clientCaps);
        }
        return new RequestMeta(version.trim(), true, clientCaps);
    }

    /** 版本是否在支持列表内。 */
    static boolean supportsVersion(String version) {
        return version != null && SUPPORTED_VERSIONS.contains(version);
    }

    // ---------------------------------------------------------------- 结果信封

    /**
     * 包一个成功结果。
     *
     * <p>{@code resultType} 在所有结果上必填；列表类结果的 {@code ttlMs}/{@code cacheScope}
     * 由调用方通过 {@code extra} 传入（规范要求它们出现在列表与 read 的结果里）。</p>
     */
    static Map<String, Object> complete(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", RESULT_COMPLETE);
        if (payload != null) {
            result.putAll(payload);
        }
        return result;
    }

    /** 包一个需要客户端补充输入的结果（MRTR）。 */
    static Map<String, Object> inputRequired(List<Map<String, Object>> inputRequests, Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", RESULT_INPUT_REQUIRED);
        result.put("inputRequests", inputRequests == null ? List.of() : inputRequests);
        if (payload != null) {
            result.putAll(payload);
        }
        return result;
    }

    /**
     * 给列表类结果补上缓存契约。
     *
     * @param ttlMs       建议客户端缓存时长（毫秒）；0 表示不要缓存
     * @param cacheScope  {@link #SCOPE_PUBLIC} 或 {@link #SCOPE_PRIVATE}
     */
    static Map<String, Object> withCache(Map<String, Object> payload, long ttlMs, String cacheScope) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        result.put("ttlMs", ttlMs);
        result.put("cacheScope", cacheScope == null ? SCOPE_PRIVATE : cacheScope);
        return result;
    }

    /** 资源不存在：规范把资源类错误归到 {@code -32602}（旧实现用的 {@code -32002} 已不再用于此）。 */
    static Map<String, Object> resourceNotFound(String uri) {
        return error(ERR_INVALID_PARAMS, "Resource not found: " + uri);
    }

    /** JSON-RPC 错误对象。 */
    static Map<String, Object> error(int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    static Map<String, Object> unsupportedVersion(String requested) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", ERR_UNSUPPORTED_PROTOCOL_VERSION);
        error.put("message", "Unsupported protocol version: " + requested);
        error.put("data", Map.of(
                "requested", requested == null ? "" : requested,
                "supported", new ArrayList<>(SUPPORTED_VERSIONS)
        ));
        return error;
    }

    // ---------------------------------------------------------------- 内容块

    /**
     * 工具结果内容块。
     *
     * <p>规范已放宽 {@code structuredContent} 可以是任意 JSON 值，但绝大多数
     * 已安装客户端仍只渲染 {@code content} 文本。所以两者都给：
     * {@code content} 放人类可读（表格）文本，{@code structuredContent} 放机器可用的结构化值。</p>
     */
    static List<Map<String, Object>> textContent(String text) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", text == null ? "" : text));
        return content;
    }

    /** 结构化内容块，mimeType 用 JSON。 */
    static List<Map<String, Object>> jsonContent(JSONObject value) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", value == null ? "{}" : value.toJSONString()));
        return content;
    }

    /** 从一行行数据渲染成 Markdown 表格，作为给 LLM 看的文本形态。 */
    static String renderTable(List<String> columns, List<List<Object>> rows) {
        if (columns == null || columns.isEmpty()) {
            return "(no columns)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append('|');
        for (int i = 0; i < columns.size(); i++) {
            sb.append("---|");
        }
        sb.append('\n');
        if (rows == null || rows.isEmpty()) {
            sb.append("_(0 rows)_");
            return sb.toString();
        }
        for (List<Object> row : rows) {
            sb.append("| ");
            for (int i = 0; i < columns.size(); i++) {
                Object value = i < row.size() ? row.get(i) : null;
                String cell = value == null ? "NULL" : String.valueOf(value);
                cell = cell.replace("|", "\\|").replace("\n", " ");
                if (cell.length() > 200) {
                    cell = cell.substring(0, 200) + "…";
                }
                sb.append(cell).append(" | ");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 入参校验

    /** 取一个必填字符串参数。 */
    static String requireString(JSONObject params, String key) {
        String value = params == null ? null : params.getString(key);
        if (value == null || value.isBlank()) {
            throw new McpParamException("Field '" + key + "' must be a non-empty string");
        }
        return value;
    }

    /** 取一个可选字符串参数。 */
    static String optionalString(JSONObject params, String key) {
        String value = params == null ? null : params.getString(key);
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * 取一个可选整数参数并夹到区间内。
     *
     * <p>夹取而不是报错：越界时按上限处理并在结果里如实回报，比直接失败对 LLM 更友好。</p>
     */
    static int optionalInt(JSONObject params, String key, int defaultValue, int min, int max) {
        Integer value = params == null ? null : params.getInteger(key);
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** 入参不合法：由调用方翻译成 {@code -32602}。 */
    static final class McpParamException extends RuntimeException {
        McpParamException(String message) {
            super(message);
        }
    }

    /** 把 {@link #jsonContent} 用的 JSONArray 转成 list，便于构造 {@code contents}。 */
    static List<Object> toList(JSONArray array) {
        return array == null ? List.of() : new ArrayList<>(array);
    }
}
