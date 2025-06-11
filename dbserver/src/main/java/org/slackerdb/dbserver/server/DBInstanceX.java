package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slf4j.LoggerFactory;

public class DBInstanceX {
    private final Logger logger;
    private Javalin managementApp = null;

    public DBInstanceX(Logger logger)
    {
        this.logger = logger;
    }

    private String getClientIp(Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        return (xff != null && !xff.isEmpty())
                ? xff.split(",")[0].trim()
                : ctx.ip();
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
