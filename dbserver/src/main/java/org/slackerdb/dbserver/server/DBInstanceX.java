package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
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
import io.javalin.http.staticfiles.Location;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.duckdb.DuckDBConnection;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DBInstanceX {
    static class DBServiceDefinition {
        public String serviceName;
        public String serviceVersion;
        public String serviceType;
        public String sql;
        public String description;
        public Long snapshotLimit = 300*1000L;
        public Map<String, String> parameter;
    }

    private final Logger logger;
    private Javalin managementApp = null;
    private final Connection backendSqlConnection;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConcurrentHashMap<String, DBServiceDefinition> registeredDBService = new ConcurrentHashMap<>();

    // 估计对象的大小
    private static int estimateSize(Map<String, Object> result) {
        try {
            return MAPPER.writeValueAsBytes(result).length;
        } catch (JsonProcessingException e) {
            return 1024;
        }
    }

    private Cache<String, Map<String, Object>> queryResultCache =  null;

    public DBInstanceX(Connection sqlConn, Logger logger)
    {
        this.backendSqlConnection = sqlConn;
        this.logger = logger;
    }

    private String getClientIp(Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        return (xff != null && !xff.isEmpty())
                ? xff.split(",")[0].trim()
                : ctx.ip();
    }

    private static void handleSqlQuery(
            Context ctx,
            DBServiceDefinition dbServiceDefinition,
            Connection backendSqlConnection,
            Cache<String, Map<String, Object>> caffeineQueryCache,
            Logger logger
    ) {
        String sql = dbServiceDefinition.sql;

        Map<String, String> sqlParameters = new HashMap<>();
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

        // cookie
        String cacheKey = null;
        if (caffeineQueryCache != null && dbServiceDefinition.snapshotLimit > 0) {
            cacheKey = sql + "|" + sqlParameters;

            Map<String, Object> cacheResult = caffeineQueryCache.getIfPresent(cacheKey);
            if (cacheResult != null) {
                Long lastQueriedTimeStamp = (Long) cacheResult.get("timestamp");
                if (System.currentTimeMillis() - lastQueriedTimeStamp <= dbServiceDefinition.snapshotLimit) {
                    // 数据还没有过时，从缓存中获得
                    ctx.json(Map.of("cached", true, "data", cacheResult));
                    return;
                }
            }
        }

        // 处理传递的SQL语句
        if (dbServiceDefinition.parameter != null && !dbServiceDefinition.parameter.isEmpty()) {
            Map<String, String> bindParameters = new HashMap<>(dbServiceDefinition.parameter);
            for (String name : bindParameters.keySet())
            {
                if (sqlParameters.containsKey(name))
                {
                    bindParameters.put(name, sqlParameters.get("name"));
                }
            }
            Pattern pattern = Pattern.compile("\\$\\{([^}]+)}");
            Matcher matcher = pattern.matcher(sql);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String key = matcher.group(1); // 取出 ${xxx} 中的 xxx
                String replacement = bindParameters.get(key); // 如果没找到就替换为空
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            sql = sb.toString();
        }

        Connection conn = null;
        try {
            conn = ((DuckDBConnection)backendSqlConnection).duplicate();
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
                if (cacheKey!= null) {
                    caffeineQueryCache.put(cacheKey, result);
                }
                ctx.json(Map.of("cached", false, "data", result));
            }
            else
            {
                ctx.json(Map.of("cached", false));
            }
            preparedStatement.close();
            conn.close();
        } catch (Exception e) {
            logger.trace("[SERVER-API] Query failed: ", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException ignored) {}
        }
    }

    public void start(ServerConfiguration serverConfiguration)
    {
        // 初始化查询结果缓存
        if (serverConfiguration.getQuery_result_cache_size() > 0) {
            queryResultCache = Caffeine.newBuilder()
                    .maximumWeight(serverConfiguration.getQuery_result_cache_size())
                    .weigher((String key, Map<String, Object> value) -> estimateSize(value))
                    .recordStats()
                    .build();
        }

        // 关闭Javalin, 如果不是在trace下
        Logger javalinLogger = (Logger) LoggerFactory.getLogger("io.javalin.Javalin");
        Logger jettyLogger = (Logger) LoggerFactory.getLogger("org.eclipse.jetty");
        if (!this.logger.getLevel().equals(Level.TRACE)) {
            javalinLogger.setLevel(Level.OFF);
            jettyLogger.setLevel(Level.OFF);
        }

        ClassPathResource page404Resource = new ClassPathResource("web/404.html");

        this.managementApp = Javalin
                .create(config->
                        {
                            // 不显示Javalin的启动提示信息
                            config.showJavalinBanner = false;
                            // 添加静态文件
                            config.staticFiles.add("/web", Location.CLASSPATH);
                            // 支持跨域
                            config.bundledPlugins.enableCors(
                                    cors -> cors.addRule(CorsPluginConfig.CorsRule::anyHost));
                        }
                )
                .start(
                    serverConfiguration.getBindHost(),
                    serverConfiguration.getPortX()
                );

        // 自定义404假面
        this.managementApp.error(404, ctx -> ctx.html(Files.readString(Path.of(page404Resource.getURI()))));

        // 需要在记录器之前添加的过滤器
        this.managementApp.before(ctx -> {
            // 设置请求开始时间作为属性
            ctx.attribute("startTime", System.currentTimeMillis());
        });

        // 在请求结束后记录响应信息
        this.managementApp.after(ctx -> {
            Long startTime = ctx.attribute("startTime");
            long duration = -1;
            if (startTime != null) {
                duration = System.currentTimeMillis() - startTime;
            }
            logger.trace("Response: {} {} from {} - Status: {} - Time: {}ms",
                    ctx.method(), ctx.path(), this.getClientIp(ctx), ctx.status(), duration);
        });

        // 默认页面
        ClassPathResource indexResource = new ClassPathResource("web/index.html");
        this.managementApp.get("/" + serverConfiguration.getData() + "/",
                ctx -> {
                    ctx.contentType("text/html");
                    ctx.result(Files.readString(Path.of(indexResource.getURI())));
                }
        );
        this.managementApp.get("/",ctx -> {
                    ctx.contentType("text/html");
                    ctx.result(Files.readString(Path.of(indexResource.getURI())));
                }
        );

        // 注册服务
        this.managementApp.post("/registerService", ctx -> {
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
            if (bodyObject.containsKey("snapshotLimit")) {
                dbService.snapshotLimit = Long.parseLong(bodyObject.getString("snapshotLimit"));
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
        this.managementApp.post("/unRegisterService", ctx -> {
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

        // 列出当前服务列表
        this.managementApp.get("/listRegisteredService", ctx-> ctx.json(registeredDBService));

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
                        this.backendSqlConnection,
                        queryResultCache,
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
                        this.backendSqlConnection,
                        queryResultCache,
                        this.logger
                );
            }
        });

        // 异常处理
        this.managementApp.exception(Exception.class, (e, ctx) -> {
            logger.error("Error occurred while processing request: {} {} - {}", ctx.method(), ctx.path(), e.getMessage(), e);
            ctx.status(500).result("Internal Server Error");
        });

        logger.info("[SERVER][STARTUP    ] Management server listening on {}:{}.",
                serverConfiguration.getBindHost(), serverConfiguration.getPortX());
    }

    public void stop()
    {
        this.managementApp.stop();
    }
}
