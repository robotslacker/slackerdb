package org.slackerdb.dbserver.service;

import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 工具面的<b>实际执行体</b>。
 *
 * <p>MCP 作为内部工具，不再施加只读、行数上限或语句超时；{@code run_sql}
 * 可以执行读写语句，返回行数由调用方的 {@code max_rows} 控制（默认不限）。</p>
 *
 * <h2>工具清单</h2>
 * <ul>
 *   <li>{@code open_session} —— 铸造一个会话 handle（跨调用状态的载体，见 {@link McpSessionStore}）</li>
 *   <li>{@code close_session} —— 释放 handle</li>
 *   <li>{@code run_sql} —— 执行 SQL（默认返回全部行）</li>
 *   <li>{@code list_tables} —— 列出表/视图</li>
 *   <li>{@code describe_table} —— 列出列定义</li>
 *   <li>{@code sample_rows} —— 取样若干行</li>
 * </ul>
 *
 * <h2>与其它入口同一条执行路径</h2>
 * <p>所有语句都经由 dbserver 的连接池取得连接后执行，<b>不新建旁路</b>：
 * 连接池上限、会话状态重置等既有约束同样作用于 MCP 工具调用。
 * 这正是"给 agent 开一扇门，不新增第二条执行路径"的落地方式。</p>
 */
final class McpToolkit {

    /** 工具执行所需的运行时依赖。 */
    record Config(
            int maxRowsCeiling,
            String defaultSchema
    ) {
    }

    private final McpSessionStore sessions;
    private final Config config;
    private final Logger logger;

    McpToolkit(McpSessionStore sessions, Config config, Logger logger) {
        this.sessions = sessions;
        this.config = config;
        this.logger = logger;
    }

    void shutdown() {
        sessions.closeAll();
    }

    // ------------------------------------------------------------ 工具定义

    /** 工具定义（name/description/inputSchema/outputSchema）。 */
    record ToolSpec(String name, String description, JSONObject inputSchema, JSONObject outputSchema) {
    }

    /**
     * 全部内置工具，<b>按名字排序</b>。
     *
     * <p>规范 SHOULD 要求确定性顺序，用于客户端缓存与 LLM 提示词缓存命中；
     * 同时单用户工具定义一旦被批准就不该静默漂移（tool poisoning 的教训之一）。</p>
     */
    List<ToolSpec> toolSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        specs.add(openSessionSpec());
        specs.add(closeSessionSpec());
        specs.add(runSqlSpec());
        specs.add(listTablesSpec());
        specs.add(describeTableSpec());
        specs.add(sampleRowsSpec());
        specs.add(listServicesSpec());
        specs.sort((a, b) -> a.name().compareTo(b.name()));
        return specs;
    }

    private ToolSpec openSessionSpec() {
        JSONObject input = schema(Map.of(
                "read_only", prop("boolean", "Deprecated and ignored. MCP runs with the server's own "
                        + "permissions; this flag no longer restricts writes.")
        ), List.of());
        JSONObject output = schema(Map.of(
                "handle", prop("string", "Opaque server-minted session handle."),
                "read_only", prop("boolean", "Always false; MCP does not enforce read-only."),
                "idle_expiry_ms", prop("integer", "Idle time after which the session is reclaimed.")
        ), List.of("handle", "read_only", "idle_expiry_ms"));
        return new ToolSpec("open_session",
                "Open a stateful SQL session and return a handle. Pass that handle to run_sql to keep "
                        + "temporary tables, transactions and session settings across calls. "
                        + "Sessions expire when idle and are closed by close_session.",
                input, output);
    }

    private ToolSpec closeSessionSpec() {
        JSONObject input = schema(Map.of(
                "handle", prop("string", "Session handle returned by open_session.")
        ), List.of("handle"));
        JSONObject output = schema(Map.of(
                "closed", prop("boolean", "True if the handle existed and was closed.")
        ), List.of("closed"));
        return new ToolSpec("close_session",
                "Close a session opened by open_session and release its connection immediately.",
                input, output);
    }

    private ToolSpec runSqlSpec() {
        JSONObject input = schema(Map.of(
                "sql", prop("string", "A single SQL statement, or several separated by ';'. "
                        + "Read and write statements are both allowed."),
                "handle", prop("string", "Optional session handle from open_session. "
                        + "Without it the statement runs on a short-lived pooled connection."),
                "max_rows", prop("integer", "Maximum rows to return. Default -1 = unlimited; "
                        + "0 or any negative value also means unlimited.")
        ), List.of("sql"));
        // 声明的字段必须与 execute() 实际返回的完全一致：客户端会在 tools/list 之后
        // 用 outputSchema 校验 structuredContent，多一个未声明字段就会被判为
        // InvalidParams（additionalProperties=false），调用直接失败。
        JSONObject output = schema(Map.of(
                "columns", arrayOf(prop("string", "Column name")),
                "rows", Map.of("type", "array", "description", "Result rows, each an array of column values."),
                "row_count", prop("integer", "Rows returned."),
                "truncated", prop("boolean", "True if more rows existed than max_rows allowed."),
                "read_only", prop("boolean", "Always false; MCP does not enforce read-only."),
                "statement_type", enumProp(List.of("query", "update"),
                        "'query' for a statement that produced a result set, 'update' otherwise."),
                "elapsed_ms", prop("integer", "Server-side execution time in milliseconds.")
        ), List.of("columns", "rows", "row_count", "truncated", "read_only", "statement_type", "elapsed_ms"));
        return new ToolSpec("run_sql",
                "Run SQL against this DuckDB instance and return the result. By default all rows are "
                        + "returned; pass max_rows to cap the result. Read and write statements are both "
                        + "allowed. Prefer the registered data services (see the slackerdb://services "
                        + "resource) over ad-hoc SQL when one matches.",
                input, output);
    }

    private ToolSpec listTablesSpec() {
        JSONObject input = schema(Map.of(
                "schema", prop("string", "Optional schema name filter."),
                "max_rows", prop("integer", "Maximum rows to return.")
        ), List.of());
        JSONObject output = schema(Map.of(
                "tables", arrayOf(Map.of(
                        "type", "object",
                        "description", "One table or view.",
                        "properties", tableProperties(),
                        "required", List.of("schema", "name", "type"),
                        "additionalProperties", false)),
                "row_count", prop("integer", "Number of tables returned.")
        ), List.of("tables", "row_count"));
        return new ToolSpec("list_tables",
                "List tables and views available in this database, optionally filtered by schema.",
                input, output);
    }

    private ToolSpec describeTableSpec() {
        JSONObject input = schema(Map.of(
                "table", prop("string", "Table or view name. May be schema-qualified."),
                "schema", prop("string", "Optional schema name when the table is not qualified.")
        ), List.of("table"));
        JSONObject output = schema(Map.of(
                "schema", prop("string", "Resolved schema name."),
                "table", prop("string", "Resolved table name."),
                "columns", Map.of("type", "array", "description", "Objects with name, type, nullable, default."),
                "row_count", prop("integer", "Number of columns.")
        ), List.of("columns", "row_count"));
        return new ToolSpec("describe_table",
                "Describe the columns of a table or view: name, data type, nullability and default.",
                input, output);
    }

    private ToolSpec sampleRowsSpec() {
        JSONObject input = schema(Map.of(
                "table", prop("string", "Table or view name. May be schema-qualified."),
                "limit", prop("integer", "Rows to sample. Default 5, ceiling " + config.maxRowsCeiling() + ".")
        ), List.of("table"));
        JSONObject output = schema(Map.of(
                "columns", arrayOf(prop("string", "Column name")),
                "rows", Map.of("type", "array", "description", "Sampled rows."),
                "row_count", prop("integer", "Rows returned."),
                "truncated", prop("boolean", "True if more rows existed than the limit allowed.")
        ), List.of("columns", "rows", "row_count", "truncated"));
        return new ToolSpec("sample_rows",
                "Return a small sample of rows from a table so you can see real values before writing a query.",
                input, output);
    }

    /**
     * {@code list_services} 的定义。
     *
     * <p><b>为什么清单既是资源又是工具</b>：规范把"可读上下文"放在 resources，
     * 但并非所有客户端都会消费 resources —— 例如 DSH 的 MCP 桥接只桥接 tools
     * （其文档明确写 Resources 与 Prompts 暂缓实现）。只提供资源会让这类客户端
     * 完全看不到受治理的数据服务，从而退化成到处写 ad-hoc SQL。
     * 所以同一份注册表同时以资源与工具暴露，两个入口读的是同一份真相。</p>
     */
    private ToolSpec listServicesSpec() {
        JSONObject input = schema(Map.of(
                "category", prop("string", "Optional category filter.")
        ), List.of());
        JSONObject output = schema(Map.of(
                "services", Map.of("type", "array", "description",
                        "Registered services with name, version, method, description and parameters."),
                "row_count", prop("integer", "Number of services returned."),
                "hint", prop("string", "How to call a matching service (advisory, optional).")
        ), List.of("services", "row_count"));
        return new ToolSpec("list_services",
                "List the registered data services: parameterized SQL exposed as named, cached, versioned "
                        + "HTTP endpoints. Prefer a matching service over writing ad-hoc SQL — these are the "
                        + "curated, governed queries for this database. Each entry carries the endpoint to call "
                        + "and the parameters it accepts.",
                input, output);
    }

    // ------------------------------------------------------------ 工具执行

    /**
     * 执行一个工具。
     *
     * @return 结构化结果；由协议层同时渲染成文本与 structuredContent
     */
    JSONObject call(String name, JSONObject arguments) throws Exception {
        JSONObject args = arguments == null ? new JSONObject() : arguments;
        return switch (name) {
            case "open_session" -> openSession(args);
            case "close_session" -> closeSession(args);
            case "run_sql" -> runSql(args);
            case "list_tables" -> listTables(args);
            case "describe_table" -> describeTable(args);
            case "sample_rows" -> sampleRows(args);
            case "list_services" -> listServices(args);
            default -> throw new McpProtocol.McpParamException("Unknown tool: " + name);
        };
    }

    private JSONObject openSession(JSONObject args) throws SQLException {
        // read_only 是历史参数，保留以兼容旧客户端，但不再生效：MCP 是内部工具，
        // 权限完全由服务器本身（access_mode）决定。
        McpSessionStore.Session session = sessions.create();
        JSONObject result = new JSONObject();
        result.put("handle", session.handle);
        result.put("read_only", false);
        result.put("idle_expiry_ms", sessions.idleTimeoutMillis());
        return result;
    }

    private JSONObject closeSession(JSONObject args) {
        String handle = McpProtocol.requireString(args, "handle");
        JSONObject result = new JSONObject();
        result.put("closed", sessions.close(handle));
        return result;
    }

    private JSONObject runSql(JSONObject args) throws Exception {
        String sql = McpProtocol.requireString(args, "sql");
        // max_rows <= 0 表示不限制；默认 -1 即返回全部行。
        int maxRows = McpProtocol.optionalInt(args, "max_rows", -1,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        String handle = McpProtocol.optionalString(args, "handle");

        // 有 handle 就一次取出会话（require 会刷新空闲计时），避免重复查表
        McpSessionStore.Session session = handle == null ? null : sessions.require(handle);
        boolean readOnly = false; // MCP 不再做只读判定，写语句按服务器权限执行

        if (session != null) {
            return execute(session.connection, sql, maxRows, readOnly, session);
        }
        try (Connection connection = sessions.borrow()) {
            return execute(connection, sql, maxRows, readOnly, null);
        }
    }

    /**
     * 执行并物化结果。
     *
     * <p>MCP 是内部工具，不施加语句超时；由服务器自身的 DuckDB 执行。</p>
     */
    private JSONObject execute(Connection connection,
                               String sql,
                               int maxRows,
                               boolean readOnly,
                               McpSessionStore.Session session) throws Exception {
        long started = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            boolean hasResultSet = statement.execute();
            JSONObject result = new JSONObject();
            result.put("read_only", readOnly);
            if (!hasResultSet) {
                int affected = statement.getUpdateCount();
                result.put("columns", new JSONArray());
                result.put("rows", new JSONArray());
                result.put("row_count", affected < 0 ? 0 : affected);
                result.put("truncated", false);
                result.put("statement_type", "update");
                result.put("elapsed_ms", System.currentTimeMillis() - started);
                return result;
            }
            try (ResultSet rs = statement.getResultSet()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnName(i));
                }
                JSONArray rows = new JSONArray();
                boolean truncated = false;
                int count = 0;
                while (rs.next()) {
                    if (maxRows > 0 && count >= maxRows) {
                        truncated = true;
                        break;
                    }
                    JSONArray row = new JSONArray();
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(normalize(rs.getObject(i)));
                    }
                    rows.add(row);
                    count++;
                }
                result.put("columns", columns);
                result.put("rows", rows);
                result.put("row_count", count);
                result.put("truncated", truncated);
                result.put("statement_type", "query");
                result.put("elapsed_ms", System.currentTimeMillis() - started);
                if (session != null) {
                    session.statements.incrementAndGet();
                }
                return result;
            }
        }
    }

    private JSONObject listTables(JSONObject args) throws Exception {
        String schemaFilter = McpProtocol.optionalString(args, "schema");
        int maxRows = McpProtocol.optionalInt(args, "max_rows", config.maxRowsCeiling(), 1, config.maxRowsCeiling());
        StringBuilder sql = new StringBuilder(
                "select table_schema as schema, table_name as name, table_type as type "
                        + "from information_schema.tables ");
        if (schemaFilter != null) {
            sql.append("where table_schema = ? ");
        }
        sql.append("order by table_schema, table_name");
        JSONObject executed;
        try (Connection connection = sessions.borrow()) {
            // 从连接池借一条连接执行，确保与其它工具同一条执行路径
            executed = (JSONObject) runWithParams(connection, sql.toString(),
                    schemaFilter == null ? List.of() : List.of(schemaFilter), maxRows);
        }
        // runWithParams 回的是通用结果网格，而本工具的 outputSchema 声明的是
        // tables 数组（含 schema/name/type）。这里显式投影成声明的形状，
        // 否则模型按 schema 读 structuredContent.tables 只会拿到 undefined。
        JSONArray grid = executed.getJSONArray("rows");
        JSONArray tables = new JSONArray();
        if (grid != null) {
            for (int i = 0; i < grid.size(); i++) {
                JSONArray row = grid.getJSONArray(i);
                JSONObject table = new JSONObject();
                table.put("schema", row.getString(0));
                table.put("name", row.getString(1));
                table.put("type", row.getString(2));
                tables.add(table);
            }
        }
        JSONObject result = new JSONObject();
        result.put("tables", tables);
        result.put("row_count", tables.size());
        return result;
    }

    private JSONObject describeTable(JSONObject args) throws Exception {
        String table = McpProtocol.requireString(args, "table");
        String schema = McpProtocol.optionalString(args, "schema");
        if (table.contains(".")) {
            String[] parts = table.split("\\.", 2);
            schema = parts[0].replace("\"", "");
            table = parts[1].replace("\"", "");
        }
        if (schema == null) {
            schema = config.defaultSchema();
        }
        String sql = "select column_name as name, data_type as type, is_nullable as nullable, "
                + "column_default as \"default\" from information_schema.columns "
                + "where table_schema = ? and table_name = ? order by ordinal_position";
        JSONObject executed;
        try (Connection connection = sessions.borrow()) {
            executed = (JSONObject) runWithParams(connection, sql, List.of(schema, table), config.maxRowsCeiling());
        }
        JSONObject result = new JSONObject();
        result.put("schema", schema);
        result.put("table", table);
        result.put("columns", executed.get("rows"));
        result.put("row_count", executed.get("row_count"));
        return result;
    }

    private JSONObject sampleRows(JSONObject args) throws Exception {
        String table = McpProtocol.requireString(args, "table");
        int limit = McpProtocol.optionalInt(args, "limit", 5, 1, config.maxRowsCeiling());
        // 表名不能作为绑定参数，这里按 DuckDB 的标识符规则重新引用，拒绝含引号的名字
        if (table.contains("\"") || table.contains(";") || table.contains("'")) {
            throw new McpProtocol.McpParamException("Invalid table name: " + table);
        }
        String sql = "select * from " + quoteIdentifier(table) + " limit " + limit;
        try (Connection connection = sessions.borrow()) {
            return (JSONObject) runWithParams(connection, sql, List.of(), limit);
        }
    }

    /** 带绑定参数执行并物化（供内省工具使用；调用方只传本类内部构造的查询）。 */
    private Object runWithParams(Connection connection, String sql, List<Object> params, int maxRows) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnName(i));
                }
                JSONArray rows = new JSONArray();
                boolean truncated = false;
                int count = 0;
                while (rs.next()) {
                    if (count >= maxRows) {
                        truncated = true;
                        break;
                    }
                    JSONArray row = new JSONArray();
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(normalize(rs.getObject(i)));
                    }
                    rows.add(row);
                    count++;
                }
                JSONObject result = new JSONObject();
                result.put("columns", columns);
                result.put("rows", rows);
                result.put("row_count", count);
                result.put("truncated", truncated);
                return result;
            }
        }
    }

    /**
     * 把 DuckDB 的复杂类型压成可 JSON 序列化的值。
     *
     * <p>pgwire 侧对 LIST/STRUCT/MAP 是明确拒绝的，但在 MCP 工具面里拒绝会让 LLM
     * 完全看不到这类列。这里退化成字符串，并在结果里保留列类型信息供模型判断。</p>
     */
    private static Object normalize(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        return String.valueOf(value);
    }

    /** 按 DuckDB 规则引用标识符：只允许字母数字下划线，其余加双引号并转义。 */
    private static String quoteIdentifier(String identifier) {
        if (identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return identifier;
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    // ------------------------------------------------------------ JSON Schema 小工具

    private static JSONObject schema(Map<String, Object> properties, List<String> required) {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject props = new JSONObject();
        props.putAll(properties);
        schema.put("properties", props);
        schema.put("required", new JSONArray(new ArrayList<Object>(required)));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    /** 取值受限于枚举的属性（例如 statement_type）。 */
    private static Map<String, Object> enumProp(List<String> values, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        p.put("enum", new ArrayList<>(values));
        return p;
    }

    /** {@code list_tables} 中单个表/视图对象的属性。 */
    private static Map<String, Object> tableProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("schema", prop("string", "Schema the table or view lives in."));
        properties.put("name", prop("string", "Table or view name."));
        properties.put("type", prop("string", "'BASE TABLE' or 'VIEW'."));
        return properties;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("items", items);
        return p;
    }

    // ------------------------------------------------------------ 资源

    /**
     * 资源清单：schema 自省与已注册数据服务都以资源形式暴露。
     *
     * <p>把 schema 做成资源而不是工具，是规范的建议方向：资源是"可读上下文"，
     * 客户端可以在对话开始前就把它塞进上下文，而不必让模型先调用一次工具。</p>
     */
    record ResourceSpec(String uri, String name, String description, String mimeType, String kind) {
    }

    List<ResourceSpec> resourceSpecs() {
        List<ResourceSpec> specs = new ArrayList<>();
        specs.add(new ResourceSpec("slackerdb://schema/tables", "Database tables",
                "Every table and view in this database, with schema and type.", "application/json", "tables"));
        specs.add(new ResourceSpec("slackerdb://services", "Registered data services",
                "SQL registered as named, cacheable HTTP endpoints. Prefer these over ad-hoc SQL.",
                "application/json", "services"));
        return specs;
    }

    /** 资源模板：表的列定义。 */
    List<Map<String, Object>> resourceTemplates() {
        List<Map<String, Object>> templates = new ArrayList<>();
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("uriTemplate", "slackerdb://schema/table/{table}");
        template.put("name", "Table schema");
        template.put("description", "Column definitions for one table.");
        template.put("mimeType", "application/json");
        templates.add(template);
        return templates;
    }

    /** 读一个资源。 */
    JSONObject readResource(String uri) throws Exception {
        if ("slackerdb://schema/tables".equals(uri)) {
            JSONObject tables = listTables(new JSONObject());
            JSONObject payload = new JSONObject();
            payload.put("tables", tables.get("rows"));
            return payload;
        }
        if ("slackerdb://services".equals(uri)) {
            return servicesAsJson();
        }
        if (uri != null && uri.startsWith("slackerdb://schema/table/")) {
            String table = uri.substring("slackerdb://schema/table/".length());
            JSONObject args = new JSONObject();
            args.put("table", table);
            return describeTable(args);
        }
        throw new McpProtocol.McpParamException("Unknown resource URI: " + uri);
    }

    /** 由 {@link McpServer} 注入已注册服务列表（来自 APIService），避免两条注册表。 */
    private java.util.function.Supplier<List<Map<String, Object>>> servicesSupplier = List::of;

    void setServicesSupplier(java.util.function.Supplier<List<Map<String, Object>>> supplier) {
        this.servicesSupplier = supplier;
    }

    /**
     * {@code list_services}：把语义层的服务注册表以工具形式暴露。
     *
     * <p>与资源 {@code slackerdb://services} 读同一份数据，只是投影成工具结果，
     * 好让只桥接 tools 的客户端（如 DSH）也能看到。</p>
     */
    private JSONObject listServices(JSONObject args) throws Exception {
        String category = McpProtocol.optionalString(args, "category");
        JSONArray services = new JSONArray();
        for (Map<String, Object> service : servicesSupplier.get()) {
            if (category != null) {
                Object serviceCategory = service.get("category");
                if (serviceCategory == null || !category.equalsIgnoreCase(String.valueOf(serviceCategory))) {
                    continue;
                }
            }
            services.add(service);
        }
        JSONObject result = new JSONObject();
        result.put("services", services);
        result.put("row_count", services.size());
        result.put("hint", "Call a matching service through the Data Service HTTP API "
                + "(GET/POST <endpoint> with the listed parameters) instead of writing ad-hoc SQL.");
        return result;
    }

    JSONObject servicesAsJson() {
        JSONArray services = new JSONArray();
        for (Map<String, Object> service : servicesSupplier.get()) {
            services.add(service);
        }
        JSONObject result = new JSONObject();
        result.put("services", services);
        result.put("hint", "Call these through the Data Service HTTP API; they are cached and versioned.");
        return result;
    }

    /** 提示词：把内部常用问法固化下来，而不是让每个客户端各自发挥。 */
    List<Map<String, Object>> prompts() {
        List<Map<String, Object>> prompts = new ArrayList<>();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("name", "topic");
        arguments.put("description", "What the user wants to know.");
        arguments.put("required", true);

        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("name", "answer_with_services");
        prompt.put("description", "Answer a data question by preferring registered data services over ad-hoc SQL.");
        prompt.put("arguments", List.of(arguments));
        prompts.add(prompt);
        return prompts;
    }

    /** 取一条提示词。 */
    Map<String, Object> prompt(String name, String topic) {
        if (!"answer_with_services".equals(name)) {
            throw new McpProtocol.McpParamException("Unknown prompt: " + name);
        }
        String subject = topic == null ? "the user's question" : topic;
        String text = """
                Answer %s using this database.

                Rules:
                1. Read the resource slackerdb://services first. If a registered data service matches the
                   question, use it — those are curated, cached and versioned. Say which service you used.
                2. Only if no service matches, inspect slackerdb://schema/tables and use describe_table or
                   sample_rows before writing SQL.
                3. Use run_sql for read-only queries. Never assume a column exists — check it first.
                4. Report NULLs and empty results explicitly instead of guessing.
                5. If the data cannot answer the question, say so rather than inventing numbers.
                """.formatted(subject);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", Map.of("type", "text", "text", text));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", "Service-first answering policy");
        result.put("messages", List.of(message));
        return result;
    }

    Config config() {
        return config;
    }

    static String upper(String sql) {
        return sql == null ? "" : sql.toUpperCase(Locale.ROOT);
    }
}
