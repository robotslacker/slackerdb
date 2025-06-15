package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;

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
        // 自定义404假面
        this.managementApp.error(404, ctx ->
        {
            System.out.println("404444");
            ctx.html(
                    """
                            <!DOCTYPE html>
                            <html lang="en">
                            <head>
                                <meta charset="UTF-8">
                                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                <title>404 - Page Not Found</title>
                                <style>
                                    body {
                                        font-family: Arial, sans-serif;
                                        background-color: #f4f4f4;
                                        display: flex;
                                        justify-content: center;
                                        align-items: center;
                                        height: 100vh;
                                        margin: 0;
                                    }
                                    .container {
                                        text-align: center;
                                        background-color: #fff;
                                        padding: 20px;
                                        border-radius: 8px;
                                        box-shadow: 0 0 10px rgba(0, 0, 0, 0.1);
                                    }
                                    .error-code {
                                        font-size: 100px;
                                        color: #ff6347;
                                        margin-bottom: 20px;
                                    }
                                    h1 {
                                        margin: 0;
                                        font-size: 36px;
                                    }
                                    p {
                                        font-size: 18px;
                                        color: #555;
                                        margin: 20px 0;
                                    }
                                    .navigation {
                                        margin-top: 30px;
                                    }
                                </style>
                            </head>
                            <body>
                                <div class="container">
                                    <div class="error-code">404</div>
                                    <h1>Page Not Found</h1>
                                    <p>Sorry, the page you are looking for might have been removed, had its name changed, or is temporarily unavailable.</p>
                                </div>
                            </body>
                            </html>
                            """);
                }
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
