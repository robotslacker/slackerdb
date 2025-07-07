package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DBInstanceX {
    private final Logger logger;
    private Javalin managementApp = null;
    private final Connection backendSqlConnection;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 估计对象的大小
    private static int estimateSize(Map<String, Object> result) {
        try {
            return MAPPER.writeValueAsBytes(result).length;
        } catch (JsonProcessingException e) {
            return 1024;
        }
    }

    private final ConcurrentHashMap<String, Cache<String, Map<String, Object>>> queryResultCacheForAllInstances
            = new ConcurrentHashMap<>();

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
            Connection backendSqlConnection,
            Cache<String, Map<String, Object>> caffeineQueryCache,
            Logger logger
    ) {
        SQLApiRequest req = ctx.bodyAsClass(SQLApiRequest.class);
        String key = null;
        if (caffeineQueryCache != null) {
            key = req.sql + "|" + String.join(",", req.params == null ? List.of() : req.params);
            Map<String, Object> cacheResult = caffeineQueryCache.getIfPresent(key);
            if (cacheResult != null) {
                ctx.json(Map.of("cached", true, "data", cacheResult));
                return;
            }
        }

        Connection conn = null;
        try {
            conn = ((DuckDBConnection)backendSqlConnection).duplicate();
            PreparedStatement preparedStatement = conn.prepareStatement(req.sql);
            for (int nPos=0; nPos < req.params.size(); nPos ++)
            {
                String param = req.params.get(nPos);
                if (param.equalsIgnoreCase("null"))
                {
                    preparedStatement.setString(nPos+1, null);
                }
                else
                {
                    preparedStatement.setString(nPos+1, param);
                }
            }
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
                result.put("data", dataset);
                if (caffeineQueryCache != null) {
                    caffeineQueryCache.put(key, result);
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

        // 提供WEB API的服务查询
        this.managementApp.post("/" + serverConfiguration.getData() + "/api/sql",
                ctx ->
                {
                    if (serverConfiguration.getQuery_result_cache_size() > 0) {
                        Cache<String, Map<String, Object>> queryResultCache;
                        if (!this.queryResultCacheForAllInstances.containsKey(serverConfiguration.getData())) {
                            queryResultCache =
                                    Caffeine.newBuilder()
                                            .maximumWeight(serverConfiguration.getQuery_result_cache_size())
                                            .weigher((String key, Map<String, Object> value) -> estimateSize(value))
                                            .recordStats()
                                            .build();
                            this.queryResultCacheForAllInstances.put(serverConfiguration.getData(), queryResultCache);
                        } else {
                            queryResultCache = this.queryResultCacheForAllInstances.get(serverConfiguration.getData());
                        }
                        handleSqlQuery(
                                ctx,
                                this.backendSqlConnection,
                                queryResultCache,
                                this.logger
                        );
                    }
                    else
                    {
                        handleSqlQuery(
                                ctx,
                                this.backendSqlConnection,
                                null,
                                this.logger
                        );
                    }
                });

        // 异常处理
        this.managementApp.exception(Exception.class, (e, ctx) -> {
            logger.error("Error occurred while processing request: {} {} - {}", ctx.method(), ctx.path(), e.getMessage());
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
