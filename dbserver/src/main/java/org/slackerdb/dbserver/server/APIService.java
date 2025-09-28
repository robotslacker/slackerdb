package org.slackerdb.dbserver.server;

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

import java.io.File;
import java.io.IOException;
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
        public String serviceType;
        public String searchPath;
        public String sql;
        public String description;
        public Long snapshotLimit = 300*1000L;
        public Map<String, String> parameter;
    }
    private final Logger logger;
    private final DBInstance dbInstance;
    private final Javalin managementApp;

    private final ConcurrentHashMap<String, DBServiceDefinition> registeredDBService = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void handleSqlQuery(
            Context ctx,
            DBServiceDefinition dbServiceDefinition,
            DBDataSourcePool dbDataSourcePool,
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
            cacheKey = sql + "|" + sqlParameters;

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

        // 处理传递的SQL语句
        if (!sqlParameters.isEmpty()) {
            Pattern pattern = Pattern.compile("\\$\\{([^}]+)}");
            Matcher matcher = pattern.matcher(sql);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String key = matcher.group(1); // 取出 ${xxx} 中的 xxx
                matcher.appendReplacement(sb, Matcher.quoteReplacement(sqlParameters.get(key)));
            }
            matcher.appendTail(sb);
            sql = sb.toString();
        }
        // 执行数据库查询
        Connection conn = null;
        try {
            conn = dbDataSourcePool.getConnection();
            if (dbServiceDefinition.searchPath != null && !dbServiceDefinition.searchPath.isEmpty())
            {
                Statement statement = conn.createStatement();
                statement.execute("SET search_path = '" + dbServiceDefinition.searchPath + "'");
                statement.close();
            }
            PreparedStatement preparedStatement = conn.prepareStatement(sql);
            boolean hasResultSet  = preparedStatement.execute();
            if (hasResultSet) {
                ResultSet rs = preparedStatement.getResultSet();
                ResultSetMetaData resultSetMetaData = rs.getMetaData();
                List<String> columnNames = new ArrayList<>();
                List<String> columnTypes = new ArrayList<>();
                List<List<Object>> dataset = new ArrayList<>();
                for (int nPos=0; nPos<resultSetMetaData.getColumnCount(); nPos++)
                {
                    columnNames.add(resultSetMetaData.getColumnName(nPos+1));
                    columnTypes.add(resultSetMetaData.getColumnTypeName(nPos+1));
                }
                while (rs.next())
                {
                    List<Object> row = new ArrayList<>();
                    for (int nPos=0; nPos<resultSetMetaData.getColumnCount(); nPos++) {
                        row.add(rs.getObject(nPos+1));
                    }
                    dataset.add(row);
                }
                rs.close();
                Map<String, Object> result = new HashMap<>();
                result.put("columnTypes", columnTypes);
                result.put("columnNames", columnNames);
                result.put("timestamp", System.currentTimeMillis());
                result.put("dataset", dataset);
                result.put("affectedRows", dataset.size());
                if (cacheKey!= null) {
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
            else
            {
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
            preparedStatement.close();
            dbDataSourcePool.releaseConnection(conn);
        } catch (Exception e) {
            logger.trace("[SERVER-API] Query failed: ", e);
            ctx.attribute("cached", false);
            ctx.attribute("affectedRows", -1);
            dbDataSourcePool.releaseConnection(conn);
            ctx.json(
                    Map.of(
                            "retCode", -1,
                            "retMsg", e.getMessage(),
                            "description", dbServiceDefinition.description,
                            "cached", false
                    )
            );
        }
    }

    // 估计对象的大小
    private static int estimateSize(Map<String, Object> result) {
        try {
            return MAPPER.writeValueAsBytes(result).length;
        } catch (JsonProcessingException e) {
            return 1024;
        }
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
            registeredDBService.put(dbService.serviceType.toUpperCase() + "#" +
                    dbService.serviceName + "#" + dbService.serviceVersion, dbService);
        }
    }

    public APIService(
            DBInstance dbInstance,
            Javalin managementApp
    )
    {
        this.logger = dbInstance.logger;
        this.dbInstance = dbInstance;
        this.managementApp = managementApp;

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
            if (new File(this.dbInstance.serverConfiguration.getData_service_schema()).isFile()) {
                serviceDefinitionFiles.add(new File(this.dbInstance.serverConfiguration.getData_service_schema()).getAbsolutePath());
            } else if (new File(this.dbInstance.serverConfiguration.getData_service_schema()).isDirectory()) {
                File[] files = new File(this.dbInstance.serverConfiguration.getData_service_schema()).listFiles();
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
        this.managementApp.post("/api/login", ctx -> {
            // 保存用户的Token
            String userToken = UUID.randomUUID().toString();
            userToken = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(userToken.getBytes());
            sessionContextMap.put(userToken, new HashMap<>());
            ctx.json(Map.of("retCode", 0, "token", userToken, "retMsg", "Login successful."));
        });

        // 用户登出
        this.managementApp.post("/api/logout", ctx -> {
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
        this.managementApp.post("/api/setContext", ctx -> {
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
        this.managementApp.post("/api/removeContext", ctx -> {
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
        this.managementApp.post("/api/registerService", ctx -> {
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
            if (registeredDBService.containsKey((serviceType + "#" + serviceName + "#" + serviceVersion).toUpperCase()))
            {
                // 如果已经注册，则不再容许重复注册
                ctx.json(Map.of("retCode", -1, "retMsg", "Rejected. [" + serviceName + "#" + serviceVersion + "] is already registered."));
                return;
            }

            DBServiceDefinition dbService = new DBServiceDefinition();
            dbService.serviceName = serviceName;
            dbService.serviceVersion = serviceVersion;
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
            registeredDBService.put(serviceType.toUpperCase() + "#" + serviceName + "#" + serviceVersion, dbService);

            ctx.json(Map.of("retCode", 0, "retMsg", "successful."));
        });

        // 取消服务注册
        this.managementApp.post("/api/unRegisterService", ctx -> {
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
            if (registeredDBService.containsKey((serviceType.toUpperCase() + "#" + serviceName + "#" + serviceVersion)))
            {
                // 删除之前的注册
                registeredDBService.remove(serviceType.toUpperCase() + "#" + serviceName + "#" + serviceVersion);
            }
            ctx.json(Map.of("retCode", 0, "retMsg", "successful."));
        });

        // 导出所有的注册信息, 不包含retCode等信息
        this.managementApp.get("/api/listRegisteredService",
                ctx->{
                    JSONArray ret = new JSONArray();
                    for (DBServiceDefinition dbServiceDefinition : registeredDBService.values())
                    {
                        JSONObject item = new JSONObject();
                        item.put("serviceName", dbServiceDefinition.serviceName);
                        item.put("serviceVersion", dbServiceDefinition.serviceVersion);
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

        // API的GET请求
        this.managementApp.get("/api/{apiVersion}/{apiName}", ctx -> {
            String apiName = ctx.pathParam("apiName");
            String apiVersion = ctx.pathParam("apiVersion");
            if (!registeredDBService.containsKey(("GET#" + apiName + "#" + apiVersion)))
            {
                ctx.json(Map.of("retCode",404, "regMsg", ctx.path() + " is not registered."));
            }
            else {
                DBServiceDefinition dbServiceDefinition = registeredDBService.get(("GET#" + apiName + "#" + apiVersion));
                handleSqlQuery(
                        ctx,
                        dbServiceDefinition,
                        this.dbInstance.dbDataSourcePool,
                        queryResultCache,
                        sessionContextMap,
                        this.logger
                );
            }
        });

        // API的POST请求
        this.managementApp.post("/api/{apiVersion}/{apiName}", ctx -> {
            String apiVersion = ctx.pathParam("apiVersion");
            String apiName = ctx.pathParam("apiName");
            if (!registeredDBService.containsKey(("POST#" + apiName + "#" + apiVersion)))
            {
                ctx.json(Map.of("retCode",404, "regMsg", ctx.path() + " is not registered."));
            }
            else {
                DBServiceDefinition dbServiceDefinition = registeredDBService.get(("POST#" + apiName + "#" + apiVersion));
                handleSqlQuery(
                        ctx,
                        dbServiceDefinition,
                        this.dbInstance.dbDataSourcePool,
                        queryResultCache,
                        sessionContextMap,
                        this.logger
                );
            }
        });
    }
}
