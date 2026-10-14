package org.slackerdb.dbserver.service;

import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.javalin.Javalin;
import io.javalin.http.Context;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.server.DBDataSourcePool;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.sql.JsonValues;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class APIService {
    static class DBServiceDefinition {
        public String serviceName;
        public String serviceVersion;
        public String category;
        public String serviceType;
        public String searchPath;
        public String sql;
        public String description;
        public Long snapshotLimit = 300*1000L;
        public Map<String, String> parameter = new ConcurrentHashMap<>();
    }
    private final Logger logger;
    private final DBInstance dbInstance;

    private final ConcurrentHashMap<String, DBServiceDefinition> registeredDBService = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** SQL 模板中的参数占位符：{@code ${name}}。 */
    private static final Pattern SQL_PARAMETER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /** category 会作为文件名保存，这些字符不允许出现（允许为空）。 */
    private static final Pattern ILLEGAL_CATEGORY_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    /** 无 category 的服务在按 category 落盘时使用的文件名。 */
    private static final String UNCATEGORIZED_CATEGORY = "uncategorized";

    private static void handleSqlQuery(
            Context ctx,
            DBServiceDefinition dbServiceDefinition,
            DBDataSourcePool dbDataSourcePool,
            String dbInstanceName,
            Cache<String, Map<String, Object>> caffeineQueryCache,
            ExpiringMap<String, HashMap<String, String>> expiringMap,
            Logger logger
    ) {
        String sql = dbServiceDefinition.sql;

        // 处理SQL参数，首先复制服务里头配置的默认参数，随后用查询请求的参数来覆盖
        Map<String, String> sqlParameters;
        if (dbServiceDefinition.parameter != null && !dbServiceDefinition.parameter.isEmpty())
        {
            sqlParameters = new HashMap<>(dbServiceDefinition.parameter);
        }
        else
        {
            sqlParameters = new HashMap<>();
        }
        if (dbServiceDefinition.serviceType.equalsIgnoreCase("GET"))
        {
            for (String key : ctx.queryParamMap().keySet())
            {
                sqlParameters.put(key, ctx.queryParam(key));
            }
        }
        if (dbServiceDefinition.serviceType.equalsIgnoreCase("POST"))
        {
            JSONObject rawPostParameters = JSONObject.parseObject(ctx.body());
            for (String key : rawPostParameters.keySet())
            {
                sqlParameters.put(key, rawPostParameters.getString(key));
            }
        }
        long snapshotLimit;

        String snapshotLimitStr = ctx.header("snapshotLimit");
        if (snapshotLimitStr != null) {
            snapshotLimit =  Utils.convertDurationStrToSeconds(snapshotLimitStr) * 1000;
        }
        else
        {
            snapshotLimit = Long.MAX_VALUE;
        }

        // 把Token中的信息也绑定到变量中, 作为可能的查询变量
        String token = ctx.header("Authorization");
        if ((token != null) && expiringMap.containsKey(token))
        {
            sqlParameters.putAll(expiringMap.get(token));
        }

        // 数据结果缓存
        String cacheKey = null;
        if (snapshotLimit > dbServiceDefinition.snapshotLimit)
        {
            snapshotLimit = dbServiceDefinition.snapshotLimit;
        }
        if (caffeineQueryCache != null && snapshotLimit > 0) {
            cacheKey = generateCacheKey(dbServiceDefinition, sql, sqlParameters);

            Map<String, Object> cacheResult = caffeineQueryCache.getIfPresent(cacheKey);
            if (cacheResult != null) {
                Long lastQueriedTimeStamp = (Long) cacheResult.get("timestamp");
                if (System.currentTimeMillis() - lastQueriedTimeStamp <= snapshotLimit) {
                    ctx.attribute("cached", true);
                    ctx.attribute("affectedRows", cacheResult.get("affectedRows"));
                    // 数据还没有过时，从缓存中获得
                    ctx.json(
                            Map.of(
                                    "retCode", 0,
                                    "retMsg", "Successful",
                                    "description", dbServiceDefinition.description,
                                    "timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(lastQueriedTimeStamp)),
                                    "cached", true,
                                    "data", cacheResult
                            )
                    );
                    return;
                }
            }
        }

        // 处理传递的SQL语句。
        // 注意：扫描/替换必须**无条件执行**，不能只在"已经有参数"时才做：
        // 否则服务未声明 parameters、请求又没带参数时，SQL 会带着 ${var} 原样执行，
        // DuckDB 把 '${var}' 当作字符串字面量，于是"缺参数"被静默变成"返回占位符文本"。
        {
            Matcher matcher = SQL_PARAMETER_PATTERN.matcher(sql);
            // 首先检查所有变量是否都有值
            Set<String> missingKeys = new LinkedHashSet<>();
            while (matcher.find()) {
                String key = matcher.group(1);
                if (!sqlParameters.containsKey(key) || sqlParameters.get(key) == null) {
                    missingKeys.add(key);
                }
            }
            if (!missingKeys.isEmpty()) {
                ctx.attribute("cached", false);
                ctx.attribute("affectedRows", -1);
                ctx.json(
                        Map.of(
                                "retCode", -1,
                                "retMsg", "Missing required parameters: " + String.join(", ", missingKeys),
                                "description", dbServiceDefinition.description,
                                "cached", false
                        )
                );
                return;
            }
            // 重新匹配进行替换
            matcher.reset();
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String key = matcher.group(1);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(sqlParameters.get(key)));
            }
            matcher.appendTail(sb);
            sql = sb.toString();
        }

        // 执行数据库查询
        Connection conn = null;
        Statement stmt = null;
        try {
            String userSearchPath;
            if (dbServiceDefinition.searchPath != null && !dbServiceDefinition.searchPath.isEmpty()) {
                userSearchPath = dbServiceDefinition.searchPath;
            }
            else
            {
                userSearchPath = dbInstanceName;
            }

            // 把查询路径指向默认数据库
            conn = dbDataSourcePool.getConnection();
            stmt = conn.createStatement();
            stmt.execute("set search_path = 'memory.duck_catalog," + userSearchPath + "'");
            stmt.close();
            stmt = null;

            try (PreparedStatement preparedStatement = conn.prepareStatement(sql)) {
                boolean hasResultSet = preparedStatement.execute();
                if (hasResultSet) {
                    try (ResultSet rs = preparedStatement.getResultSet()) {
                        ResultSetMetaData resultSetMetaData = rs.getMetaData();
                        List<String> columnNames = new ArrayList<>();
                        List<String> columnTypes = new ArrayList<>();
                        List<List<Object>> dataset = new ArrayList<>();
                        for (int nPos = 0; nPos < resultSetMetaData.getColumnCount(); nPos++) {
                            columnNames.add(resultSetMetaData.getColumnName(nPos + 1));
                            columnTypes.add(resultSetMetaData.getColumnTypeName(nPos + 1));
                        }
                        while (rs.next()) {
                            List<Object> row = new ArrayList<>();
                            for (int nPos = 0; nPos < resultSetMetaData.getColumnCount(); nPos++) {
                                // 统一转成 JSON 安全类型：DATE/TIME/TIMESTAMPTZ/BLOB/LIST/JSON
                                // 等类型直接交给 Jackson 会失败（内部错误 500 / 类名泄漏），
                                // 转换后 HTTP 与 SQL REPL 两个端点的取值形态保持一致。
                                row.add(JsonValues.toJsonValue(rs.getObject(nPos + 1)));
                            }
                            dataset.add(row);
                        }
                        Map<String, Object> result = new HashMap<>();
                        result.put("columnTypes", columnTypes);
                        result.put("columnNames", columnNames);
                        result.put("timestamp", System.currentTimeMillis());
                        result.put("dataset", dataset);
                        result.put("affectedRows", dataset.size());
                        if (cacheKey != null) {
                            caffeineQueryCache.put(cacheKey, result);
                        }
                        ctx.attribute("cached", false);
                        ctx.attribute("affectedRows", dataset.size());
                        ctx.json(
                                Map.of(
                                        "retCode", 0,
                                        "retMsg", "Successful",
                                        "description", dbServiceDefinition.description,
                                        "timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(System.currentTimeMillis())),
                                        "cached", false, "data", result
                                )
                        );
                    }
                } else {
                    // 非SQL查询语句
                    ctx.attribute("cached", false);
                    ctx.attribute("affectedRows", -1);
                    ctx.json(
                            Map.of(
                                    "retCode", 0,
                                    "retMsg", "Successful",
                                    "description", dbServiceDefinition.description,
                                    "affectedRows", preparedStatement.getUpdateCount(),
                                    "cached", false
                            )
                    );
                }
            }
            dbDataSourcePool.releaseConnection(conn);
        } catch (Exception e) {
            logger.trace("[SERVER-API] Query failed: ", e);
            ctx.attribute("cached", false);
            ctx.attribute("affectedRows", -1);
            if (conn != null) {
                dbDataSourcePool.releaseConnection(conn);
            }
            ctx.json(
                    Map.of(
                            "retCode", -1,
                            "retMsg", e.getMessage(),
                            "description", dbServiceDefinition.description,
                            "cached", false
                    )
            );
        } finally {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException ignored) {}
            }
        }
    }

    /**
     * 服务注册表的统一键：{@code 类型大写#名称#版本}（名称与版本保持原样、大小写敏感）。
     *
     * <p>注册、注销、查表、文件加载必须全部走这一个函数。历史实现里查重用的是
     * 整个键 {@code toUpperCase()}，而写入用的是原样键，两者永不相等，
     * 于是"重复注册"检测失效、同名服务被静默覆盖。</p>
     */
    private static String serviceKey(String serviceType, String serviceName, String serviceVersion) {
        return (serviceType == null ? "" : serviceType.toUpperCase()) + "#" + serviceName + "#" + serviceVersion;
    }

    /**
     * 生成稳定的缓存键。
     *
     * <p>键里必须包含服务身份与 searchPath：只按 SQL 文本+参数做键时，
     * 两个 SQL 文本相同但 searchPath / 定义不同的服务会共用同一个缓存条目，
     * 结果就是"一个服务返回另一个服务的数据"（实测可复现）。</p>
     */
    private static String generateCacheKey(DBServiceDefinition service, String sql, Map<String, String> parameters) {
        StringBuilder sb = new StringBuilder();
        sb.append(service.serviceType == null ? "" : service.serviceType.toUpperCase())
                .append('#').append(service.serviceName)
                .append('#').append(service.serviceVersion)
                .append('|').append(service.searchPath == null ? "" : service.searchPath)
                .append('|').append(sql);
        if (parameters != null && !parameters.isEmpty()) {
            // 按键排序以保证稳定
            List<String> keys = new ArrayList<>(parameters.keySet());
            Collections.sort(keys);
            sb.append('|');
            for (String key : keys) {
                sb.append(key).append('=').append(parameters.get(key)).append('&');
            }
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    // 估计对象的大小
    private static int estimateSize(Map<String, Object> result) {
        try {
            return MAPPER.writeValueAsBytes(result).length;
        } catch (JsonProcessingException e) {
            return 1024;
        }
    }

    /**
     * category 是否含有文件系统非法字符（{@code \ / : * ? " < > |}）。
     *
     * <p>允许为空：API 文档中 category 是可选字段，历史实现把"空"也当成非法，
     * 导致文档里的注册示例直接被拒绝。</p>
     */
    private static boolean hasIllegalCategoryChars(String category) {
        if (category == null || category.trim().isEmpty()) {
            return false;
        }
        return ILLEGAL_CATEGORY_CHARS.matcher(category).find();
    }

    // 实现会话信息的自动过期处理,
    private final ExpiringMap<String, HashMap<String,String>> sessionContextMap = ExpiringMap.builder()
            .maxSize(1000)
            .expiration(30, TimeUnit.MINUTES)
            .expirationPolicy(ExpirationPolicy.ACCESSED)
            .build();

    // 数据结果缓存，在过期时间内的数据不会重复查询
    private Cache<String, Map<String, Object>> queryResultCache =  null;

    // 加载服务定义文件
    private void registerServiceDefinitionFromFile(String serviceDefinitionFile) throws IOException, JSONException
    {
        // 从文件中读取所有内容到字符串
        String serviceContents = new String(Files.readAllBytes(Path.of(serviceDefinitionFile)));
        registerServiceDefinitionFromJsonString(serviceContents, "file:" + serviceDefinitionFile);
    }

    // 从JSON字符串加载服务定义
    private void registerServiceDefinitionFromJsonString(String serviceContents, String source) throws JSONException
    {
        JSONArray serviceDefinitionList = new JSONArray();
        try {
            JSONObject serviceDefinitionObj = JSONObject.parseObject(serviceContents);
            serviceDefinitionList.add(serviceDefinitionObj);
        }
        catch (JSONException ignored)
        {
            JSONArray serviceDefinitionSubList = JSONArray.parseArray(serviceContents);
            serviceDefinitionList.addAll(serviceDefinitionSubList);
        }
        for (Object serviceDefinition : serviceDefinitionList) {
            JSONObject serviceDefinitionJsonObj = (JSONObject)serviceDefinition;
            DBServiceDefinition dbService = new DBServiceDefinition();
            dbService.serviceName = serviceDefinitionJsonObj.getString("serviceName");
            dbService.serviceVersion = serviceDefinitionJsonObj.getString("serviceVersion");
            dbService.category = serviceDefinitionJsonObj.getString("category");
            dbService.serviceType = serviceDefinitionJsonObj.getString("serviceType");
            dbService.sql = serviceDefinitionJsonObj.getString("sql");
            dbService.description = serviceDefinitionJsonObj.getString("description");
            if (dbService.description == null) {
                dbService.description = "NO DESCRIPTION.";
            }
            dbService.searchPath = serviceDefinitionJsonObj.getString("searchPath");
            if (serviceDefinitionJsonObj.containsKey("snapshotLimit")) {
                dbService.snapshotLimit =
                        Utils.convertDurationStrToSeconds(serviceDefinitionJsonObj.getString("snapshotLimit")) * 1000;
            }
            String serviceParametersStr = serviceDefinitionJsonObj.getString("parameters");
            JSONArray serviceParameters;
            if (serviceParametersStr != null && !serviceParametersStr.trim().isEmpty()) {
                serviceParameters = JSONArray.parseArray(serviceParametersStr);
                for (Object obj : serviceParameters) {
                    JSONObject serviceParameter = (JSONObject) obj;
                    if (serviceParameter.containsKey("name")) {
                        dbService.parameter.put(
                                serviceParameter.getString("name"),
                                serviceParameter.getString("defaultValue"));
                    }
                }
            }
            // 验证category
            if (hasIllegalCategoryChars(dbService.category)) {
                logger.warn("[SERVER][LOAD       ] Invalid category ignored: {} (serviceName={}, serviceVersion={}, category={}) from {}",
                        dbService.serviceType.toUpperCase() + "#" + dbService.serviceName + "#" + dbService.serviceVersion,
                        dbService.serviceName, dbService.serviceVersion, dbService.category, source);
                // 忽略，不注册
                continue;
            }
            String key = serviceKey(dbService.serviceType, dbService.serviceName, dbService.serviceVersion);
            if (registeredDBService.containsKey(key)) {
                logger.warn("[SERVER][LOAD       ] Duplicate service definition ignored: {} (serviceName={}, serviceVersion={}, serviceType={}) from {}",
                        key, dbService.serviceName, dbService.serviceVersion, dbService.serviceType, source);
                // 忽略，不注册
                continue;
            }
            registeredDBService.put(key, dbService);
        }
    }

    public APIService(
            DBInstance dbInstance,
            Javalin managementApp,
            Logger logger
    )
    {
        this.logger = logger;
        this.dbInstance = dbInstance;

        // 初始化查询结果缓存
        if (this.dbInstance.serverConfiguration.getQuery_result_cache_size() > 0) {
            queryResultCache = Caffeine.newBuilder()
                    .maximumWeight(this.dbInstance.serverConfiguration.getQuery_result_cache_size())
                    .weigher((String key, Map<String, Object> value) -> estimateSize(value))
                    .recordStats()
                    .build();
        }

        // 加载注册服务的预定义文件
        List<String> serviceDefinitionFiles = new ArrayList<>();
        if (!this.dbInstance.serverConfiguration.getData_service_schema().trim().isEmpty()) {
            File schemaFile = new File(this.dbInstance.serverConfiguration.getData_service_schema());
            if (schemaFile.isFile()) {
                serviceDefinitionFiles.add(schemaFile.getAbsolutePath());
            } else if (schemaFile.isDirectory()) {
                File[] files = schemaFile.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile() && file.getName().endsWith(".service")) {
                            serviceDefinitionFiles.add(file.getAbsolutePath());
                        }
                    }
                }
            } else {
                logger.warn("[SERVER][STARTUP    ] API services definition file(s) [{}] does not exist!",
                        this.dbInstance.serverConfiguration.getData_service_schema());
            }
        }
        // 脚本按照名称来排序
        Collections.sort(serviceDefinitionFiles);
        for (String serviceDefinitionFile : serviceDefinitionFiles) {
            try {
                registerServiceDefinitionFromFile(serviceDefinitionFile);
                logger.info("[SERVER][STARTUP    ] API services definition file [{}] load successful.",
                        serviceDefinitionFile);
            } catch (Exception exception)
            {
                logger.error("[SERVER][STARTUP    ] API services definition file {} load failed.",
                        serviceDefinitionFile, exception);
            }
        }

        // 用户登录
        managementApp.unsafe.routes.post("/api/login", ctx -> {
            // 保存用户的Token
            String userToken = UUID.randomUUID().toString();
            userToken = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(userToken.getBytes());
            sessionContextMap.put(userToken, new HashMap<>());
            ctx.json(Map.of("retCode", 0, "token", userToken, "retMsg", "Login successful."));
        });

        // 用户登出
        managementApp.unsafe.routes.post("/api/logout", ctx -> {
            String token = ctx.header("Authorization");
            if ((token == null) || !sessionContextMap.containsKey(token))
            {
                ctx.json(Map.of("retCode", -1, "retMsg", "User not login or expired."));
                return;
            }
            sessionContextMap.remove(token);
            ctx.json(Map.of("retCode", 0, "retMsg", "Successful"));
        });

        // 设置用户的会话信息
        managementApp.unsafe.routes.post("/api/setContext", ctx -> {
            String token = ctx.header("Authorization");
            if ((token == null) || !sessionContextMap.containsKey(token))
            {
                ctx.json(Map.of("retCode", -1, "retMsg", "User not login or expired."));
                return;
            }
            JSONObject contextObj = JSONObject.parseObject(ctx.body());
            if (!contextObj.isEmpty()) {
                HashMap<String, String> sessionContextVariables = sessionContextMap.get(token);
                for (String key : contextObj.keySet()) {
                    sessionContextVariables.put(key, contextObj.getString(key));
                }
                sessionContextMap.put(token, sessionContextVariables);
            }
            ctx.json(Map.of("retCode", 0, "retMsg", "Successful"));
        });

        // 设置用户的会话信息
        managementApp.unsafe.routes.post("/api/removeContext", ctx -> {
            String token = ctx.header("Authorization");
            if ((token == null) || !sessionContextMap.containsKey(token))
            {
                ctx.json(Map.of("retCode", -1, "retMsg", "User not login or expired."));
                return;
            }
            JSONObject contextObj = JSONObject.parseObject(ctx.body());
            if (contextObj.containsKey("removedKeyList"))
            {
                JSONArray contextRemoveList = contextObj.getJSONArray("removedKeyList");
                if (!contextRemoveList.isEmpty()) {
                    HashMap<String, String> sessionContextVariables = sessionContextMap.get(token);
                    for (Object key : contextRemoveList.toArray()) {
                        sessionContextVariables.remove(key.toString());
                    }
                    sessionContextMap.put(token, sessionContextVariables);
                }
            }
            ctx.json(Map.of("retCode", 0, "retMsg", "Successful"));
        });

        // 注册服务
        managementApp.unsafe.routes.post("/api/registerService", ctx -> {
            JSONObject bodyObject;
            try {
                bodyObject = JSONObject.parseObject(ctx.body());
            } catch (JSONException ignored) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. Incomplete or format error in serviceInfo."));
                return;
            }
            String serviceName = bodyObject.getString("serviceName");
            String serviceVersion = bodyObject.getString("serviceVersion");
            if (serviceName == null || serviceVersion == null || serviceName.trim().isEmpty() || serviceVersion.trim().isEmpty())
            {
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. serviceName or serviceVersion is missed."));
                return;
            }
            String serviceType = bodyObject.getString("serviceType");
            if (serviceType == null || !(serviceType.equalsIgnoreCase("GET") || serviceType.equalsIgnoreCase("POST")))
            {
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. serviceType missed. GET/POST is only two supported serviceType at this time."));
                return;
            }
            String serviceSql = bodyObject.getString("sql");
            if (serviceSql == null || serviceSql.trim().isEmpty())
            {
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. serviceType or sql is missed. SQL is only supported serviceType at this time."));
                return;
            }
            String registryKey = serviceKey(serviceType, serviceName, serviceVersion);
            if (registeredDBService.containsKey(registryKey))
            {
                // 如果已经注册，则不再容许重复注册（要变更定义请先 unRegisterService）
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. [" + serviceName + "#" + serviceVersion + "] is already registered."));
                return;
            }

            DBServiceDefinition dbService = new DBServiceDefinition();
            dbService.serviceName = serviceName;
            dbService.serviceVersion = serviceVersion;
            dbService.category = bodyObject.getString("category");
            dbService.serviceType = serviceType;
            dbService.sql = serviceSql;
            dbService.description = bodyObject.getString("description");
            if (dbService.description == null)
            {
                dbService.description = "NO DESCRIPTION.";
            }
            dbService.searchPath = bodyObject.getString("searchPath");
            if (bodyObject.containsKey("snapshotLimit")) {
                dbService.snapshotLimit =
                        Utils.convertDurationStrToSeconds(bodyObject.getString("snapshotLimit")) * 1000;
            }
            String serviceParametersStr = bodyObject.getString("parameters");
            JSONArray serviceParameters;
            if (serviceParametersStr != null && !serviceParametersStr.trim().isEmpty())
            {
                serviceParameters = JSONArray.parseArray(serviceParametersStr);
                for (Object obj : serviceParameters)
                {
                    JSONObject serviceParameter = (JSONObject)obj;
                    if (serviceParameter.containsKey("name"))
                    {
                        dbService.parameter.put(
                                serviceParameter.getString("name"),
                                serviceParameter.getString("defaultValue"));
                    }
                }
            }
            // 验证category（允许为空：文档中 category 是可选字段，仅作为落盘分组名）
            if (hasIllegalCategoryChars(dbService.category)) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. category contains illegal characters (\\ / : * ? \" < > |)."));
                return;
            }
            registeredDBService.put(registryKey, dbService);

            ctx.json(Map.of("retCode", 0, "retMsg", "successful."));
        });

        // 取消服务注册
        managementApp.unsafe.routes.post("/api/unRegisterService", ctx -> {
            JSONObject bodyObject;
            try {
                bodyObject = JSONObject.parseObject(ctx.body());
            } catch (JSONException ignored) {
                ctx.status(502).result("Rejected. Incomplete or format error in serviceInfo.");
                return;
            }
            String serviceName = bodyObject.getString("serviceName");
            String serviceVersion = bodyObject.getString("serviceVersion");
            if (serviceName == null || serviceVersion == null || serviceName.trim().isEmpty() || serviceVersion.trim().isEmpty())
            {
                ctx.status(502).result("Rejected. serviceName or serviceVersion is missed.");
                return;
            }
            String serviceType = bodyObject.getString("serviceType");
            if (serviceType == null || !(serviceType.equalsIgnoreCase("GET") || serviceType.equalsIgnoreCase("POST")))
            {
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. serviceType missed. GET/POST is only two supported serviceType at this time."));
                return;
            }
            String registryKey = serviceKey(serviceType, serviceName, serviceVersion);
            // 删除之前的注册（不存在时同样返回成功：注销是幂等的）
            registeredDBService.remove(registryKey);
            ctx.json(Map.of("retCode", 0, "retMsg", "successful."));
        });

        // 导出所有的注册信息, 不包含retCode等信息
        managementApp.unsafe.routes.get("/api/listRegisteredService",
                ctx->{
                    JSONArray ret = new JSONArray();
                    for (DBServiceDefinition dbServiceDefinition : registeredDBService.values())
                    {
                        JSONObject item = new JSONObject();
                        item.put("serviceName", dbServiceDefinition.serviceName);
                        item.put("serviceVersion", dbServiceDefinition.serviceVersion);
                        item.put("category", dbServiceDefinition.category);
                        item.put("searchPath", dbServiceDefinition.searchPath);
                        item.put("sql", dbServiceDefinition.sql);
                        item.put("description", dbServiceDefinition.description);
                        item.put("serviceType", dbServiceDefinition.serviceType);
                        item.put("snapshotLimit", Utils.convertSecondsToHumanTime(dbServiceDefinition.snapshotLimit / 1000));
                        item.put("parameter", dbServiceDefinition.parameter);
                        ret.add(item);
                    }
                    ctx.json(ret);
                }
        );

        // 导出注册服务到文件（直接下载）
        managementApp.unsafe.routes.post("/api/dumpRegisteredService", ctx -> {
            try {
                String json = dumpRegisteredServiceAsJson();
                ctx.header("Content-Type", "application/json");
                ctx.header("Content-Disposition", "attachment; filename=\"services.json\"");
                ctx.result(json);
                logger.info("[SERVER-API] Dumped {} registered services for download", registeredDBService.size());
            } catch (Exception e) {
                logger.error("[SERVER-API] Failed to generate registered services JSON: {}", e.getMessage());
                ctx.json(Map.of("retCode", -1, "retMsg", "Failed to generate JSON: " + e.getMessage()));
            }
        });

        // 下载注册服务文件（作为响应体直接返回；不再走"临时文件 + 文件流"，
        // 那样会在 Jetty 还没把流写出去之前就删掉临时文件，实测固定返回 HTTP 500）
        managementApp.unsafe.routes.post("/api/downloadRegisteredService", ctx -> {
            try {
                byte[] payload = dumpRegisteredServiceAsJson().getBytes(StandardCharsets.UTF_8);
                ctx.header("Content-Type", "application/json");
                ctx.header("Content-Disposition", "attachment; filename=\"services.json\"");
                ctx.result(payload);
                logger.info("[SERVER-API] Downloaded {} registered services ({} bytes)",
                        registeredDBService.size(), payload.length);
            } catch (Exception e) {
                logger.error("[SERVER-API] Failed to generate registered services JSON for download", e);
                ctx.json(Map.of("retCode", -1, "retMsg", "Failed to generate JSON: " + e.getMessage()));
            }
        });

        // 保存注册服务到文件（根据data_service_schema配置）
        managementApp.unsafe.routes.post("/api/saveRegisterService", ctx -> {
            String schemaPath = this.dbInstance.serverConfiguration.getData_service_schema();
            if (schemaPath == null || schemaPath.trim().isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. data_service_schema parameter is not configured."));
                return;
            }
            File schemaFile = new File(schemaPath);
            if (schemaFile.isFile()) {
                // 如果配置的是一个具体文件，将所有服务保存到这个文件
                try {
                    String json = dumpRegisteredServiceAsJson();
                    Files.write(schemaFile.toPath(), json.getBytes());
                    logger.info("[SERVER-API] Saved {} registered services to {}", registeredDBService.size(), schemaFile.getAbsolutePath());
                    ctx.json(Map.of("retCode", 0, "retMsg", "Successful", "savedPath", schemaFile.getAbsolutePath()));
                } catch (Exception e) {
                    logger.error("[SERVER-API] Failed to save registered services to {}: {}", schemaFile.getAbsolutePath(), e.getMessage());
                    ctx.json(Map.of("retCode", -1, "retMsg", "Failed to save: " + e.getMessage()));
                }
            } else if (schemaFile.isDirectory()) {
                // 如果配置的是一个目录，按category分组保存，每个category一个文件，文件名为<category>.service
                try {
                    Map<String, JSONArray> categoryMap = generateRegisteredServiceJsonArrayByCategory();
                    int totalFiles = 0;
                    int totalServices = 0;
                    for (Map.Entry<String, JSONArray> entry : categoryMap.entrySet()) {
                        String category = entry.getKey();
                        JSONArray services = entry.getValue();
                        // category已经通过验证，不包含非法字符，直接作为文件名
                        String fileName = category + ".service";
                        File targetFile = new File(schemaFile, fileName);
                        String json = services.toJSONString();
                        Files.write(targetFile.toPath(), json.getBytes());
                        totalFiles++;
                        totalServices += services.size();
                        logger.debug("[SERVER-API] Saved category '{}' with {} services to {}", category, services.size(), targetFile.getAbsolutePath());
                    }
                    logger.info("[SERVER-API] Saved {} registered services ({} categories) to directory {}", totalServices, totalFiles, schemaFile.getAbsolutePath());
                    ctx.json(Map.of("retCode", 0, "retMsg", "Successful", "savedPath", schemaFile.getAbsolutePath(), "categories", totalFiles, "services", totalServices));
                } catch (IllegalArgumentException e) {
                    // category包含非法字符，返回错误
                    logger.error("[SERVER-API] Invalid category detected while saving: {}", e.getMessage());
                    ctx.json(Map.of("retCode", -1, "retMsg", "Failed to save: " + e.getMessage()));
                } catch (Exception e) {
                    logger.error("[SERVER-API] Failed to save registered services to directory {}: {}", schemaFile.getAbsolutePath(), e.getMessage());
                    ctx.json(Map.of("retCode", -1, "retMsg", "Failed to save: " + e.getMessage()));
                }
            } else {
                // 路径不存在，尝试创建目录（如果是目录）或文件？
                // 根据需求，如果路径不存在，我们可能应该拒绝。
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. data_service_schema path does not exist."));
            }
        });

        // 加载注册服务定义（从JSON内容）
        managementApp.unsafe.routes.post("/api/loadRegisterService", ctx -> {
            String serviceContents = ctx.body();
            if (serviceContents.trim().isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. Request body is empty."));
                return;
            }
            try {
                registerServiceDefinitionFromJsonString(serviceContents, "upload");
                logger.info("[SERVER-API] Loaded service definitions from upload");
                ctx.json(Map.of("retCode", 0, "retMsg", "Successful"));
            } catch (JSONException e) {
                logger.error("[SERVER-API] Failed to parse service definition JSON: {}", e.getMessage());
                ctx.json(Map.of("retCode", -1, "retMsg", "Failed to parse JSON: " + e.getMessage()));
            } catch (Exception e) {
                logger.error("[SERVER-API] Failed to load service definitions: {}", e.getMessage());
                ctx.json(Map.of("retCode", -1, "retMsg", "Failed to load: " + e.getMessage()));
            }
        });

        // API的GET请求
        managementApp.unsafe.routes.get("/api/{apiVersion}/{apiName}", ctx -> {
            String apiName = ctx.pathParam("apiName");
            String apiVersion = ctx.pathParam("apiVersion");
            DBServiceDefinition dbServiceDefinition = registeredDBService.get(serviceKey("GET", apiName, apiVersion));
            if (dbServiceDefinition == null)
            {
                // 键名统一为 retMsg（历史实现写成了 regMsg，客户端按 retMsg 取会拿到 null），
                // HTTP 状态码与 retCode 保持一致
                ctx.status(404).json(
                        Map.of(
                                "retCode",404,
                                "retMsg", "GET " + ctx.path() + " is not registered.")
                );
            }
            else {
                handleSqlQuery(
                        ctx,
                        dbServiceDefinition,
                        this.dbInstance.dbDataSourcePool,
                        this.dbInstance.instanceName,
                        queryResultCache,
                        sessionContextMap,
                        this.logger
                );
            }
        });

        // API的POST请求
        managementApp.unsafe.routes.post("/api/{apiVersion}/{apiName}", ctx -> {
            String apiVersion = ctx.pathParam("apiVersion");
            String apiName = ctx.pathParam("apiName");
            DBServiceDefinition dbServiceDefinition = registeredDBService.get(serviceKey("POST", apiName, apiVersion));
            if (dbServiceDefinition == null)
            {
                ctx.status(404).json(
                        Map.of(
                                "retCode",404,
                                "retMsg", "POST " + ctx.path() + " is not registered."));
            }
            else {
                handleSqlQuery(
                        ctx,
                        dbServiceDefinition,
                        this.dbInstance.dbDataSourcePool,
                        this.dbInstance.instanceName,
                        queryResultCache,
                        sessionContextMap,
                        this.logger
                );
            }
        });

    }

    public void stop()
    {
        // 不再有监控线程需要停止
    }

    /**
     * 已注册数据服务的只读视图（供 MCP 工具面复用）。
     *
     * <p>MCP 侧不再维护第二份服务注册表：语义层只有一份真相，
     * agent 看到的服务清单与 HTTP 数据服务始终一致。</p>
     *
     * <p>刻意<b>不</b>暴露 {@code sql}：模型不需要看到实现，
     * 也就不可能被诱导去改写它。它只需要知道"有哪些服务、要什么参数"。</p>
     *
     * @return 每个服务的名字、版本、方法、描述、参数与分类
     */
    public List<Map<String, Object>> describeRegisteredServices() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DBServiceDefinition service : registeredDBService.values()) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("name", service.serviceName);
            item.put("version", service.serviceVersion);
            item.put("method", service.serviceType);
            item.put("description", service.description);
            if (service.category != null && !service.category.isBlank()) {
                item.put("category", service.category);
            }
            if (service.searchPath != null && !service.searchPath.isBlank()) {
                item.put("search_path", service.searchPath);
            }
            if (service.parameter != null && !service.parameter.isEmpty()) {
                List<Map<String, Object>> params = new ArrayList<>();
                for (Map.Entry<String, String> entry : service.parameter.entrySet()) {
                    params.add(Map.of(
                            "name", entry.getKey(),
                            "default", entry.getValue() == null ? "" : entry.getValue()));
                }
                item.put("parameters", params);
            }
            // 调用方式：让模型知道该发哪个 HTTP 请求，而不是猜
            item.put("endpoint", "/api/" + service.serviceVersion + "/" + service.serviceName);
            result.add(item);
        }
        result.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return result;
    }

    /**
     * 生成当前注册的服务定义的JSON数组。
     * @return JSONArray 包含所有服务定义
     */
    private JSONArray generateRegisteredServiceJsonArray() {
        JSONArray serviceArray = new JSONArray();
        for (DBServiceDefinition dbService : registeredDBService.values()) {
            JSONObject serviceObj = new JSONObject();
            serviceObj.put("serviceName", dbService.serviceName);
            serviceObj.put("serviceVersion", dbService.serviceVersion);
            serviceObj.put("category", dbService.category);
            serviceObj.put("serviceType", dbService.serviceType);
            serviceObj.put("sql", dbService.sql);
            serviceObj.put("description", dbService.description);
            if (dbService.searchPath != null && !dbService.searchPath.isEmpty()) {
                serviceObj.put("searchPath", dbService.searchPath);
            } else {
                serviceObj.put("searchPath", "");
            }
            // 将snapshotLimit从毫秒转换为人类可读字符串
            long seconds = dbService.snapshotLimit / 1000;
            serviceObj.put("snapshotLimit", Utils.convertSecondsToHumanTime(seconds));
            // 转换parameters Map为JSON数组
            JSONArray paramArray = new JSONArray();
            for (Map.Entry<String, String> entry : dbService.parameter.entrySet()) {
                JSONObject paramObj = new JSONObject();
                paramObj.put("name", entry.getKey());
                paramObj.put("defaultValue", entry.getValue());
                paramArray.add(paramObj);
            }
            if (!paramArray.isEmpty()) {
                serviceObj.put("parameters", paramArray);
            } else {
                serviceObj.put("parameters", new JSONArray());
            }
            serviceArray.add(serviceObj);
        }
        return serviceArray;
    }

    /**
     * 按category分组生成服务定义的JSON数组。
     *
     * <p>无 category 的服务归入 {@value #UNCATEGORIZED_CATEGORY} 分组：
     * category 在 API 文档里是可选字段，历史实现直接抛异常，导致"允许注册但无法保存"。</p>
     *
     * @return Map key为category（文件名前缀），value为该category下的服务JSON数组
     * @throws IllegalArgumentException 如果category包含非法字符（\/:*?"<>|）
     */
    private Map<String, JSONArray> generateRegisteredServiceJsonArrayByCategory() {
        Map<String, List<DBServiceDefinition>> grouped = new HashMap<>();
        for (DBServiceDefinition dbService : registeredDBService.values()) {
            String category = dbService.category;
            if (category == null || category.trim().isEmpty()) {
                category = UNCATEGORIZED_CATEGORY;
            }
            // 验证category合法性（作为文件名使用）
            if (hasIllegalCategoryChars(category)) {
                throw new IllegalArgumentException("Category '" + category + "' contains illegal characters (\\ / : * ? \" < > |).");
            }
            grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(dbService);
        }
        Map<String, JSONArray> result = new HashMap<>();
        for (Map.Entry<String, List<DBServiceDefinition>> entry : grouped.entrySet()) {
            JSONArray serviceArray = new JSONArray();
            for (DBServiceDefinition dbService : entry.getValue()) {
                JSONObject serviceObj = new JSONObject();
                serviceObj.put("serviceName", dbService.serviceName);
                serviceObj.put("serviceVersion", dbService.serviceVersion);
                serviceObj.put("category", dbService.category);
                serviceObj.put("serviceType", dbService.serviceType);
                serviceObj.put("sql", dbService.sql);
                serviceObj.put("description", dbService.description);
                if (dbService.searchPath != null && !dbService.searchPath.isEmpty()) {
                    serviceObj.put("searchPath", dbService.searchPath);
                } else {
                    serviceObj.put("searchPath", "");
                }
                long seconds = dbService.snapshotLimit / 1000;
                serviceObj.put("snapshotLimit", Utils.convertSecondsToHumanTime(seconds));
                JSONArray paramArray = new JSONArray();
                for (Map.Entry<String, String> paramEntry : dbService.parameter.entrySet()) {
                    JSONObject paramObj = new JSONObject();
                    paramObj.put("name", paramEntry.getKey());
                    paramObj.put("defaultValue", paramEntry.getValue());
                    paramArray.add(paramObj);
                }
                if (!paramArray.isEmpty()) {
                    serviceObj.put("parameters", paramArray);
                } else {
                    serviceObj.put("parameters", new JSONArray());
                }
                serviceArray.add(serviceObj);
            }
            result.put(entry.getKey(), serviceArray);
        }
        return result;
    }

    /**
     * 将当前注册的服务定义导出为JSON字符串，用于直接下载。
     * @return JSON字符串
     */
    public String dumpRegisteredServiceAsJson() {
        JSONArray serviceArray = generateRegisteredServiceJsonArray();
        return serviceArray.toJSONString();
    }

}
