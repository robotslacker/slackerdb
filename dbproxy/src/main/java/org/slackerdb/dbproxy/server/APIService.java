package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Logger;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class APIService {
    private final Logger logger;
    private final ProxyInstance proxyInstance;
    private final Javalin managementApp;
    private final HttpClient httpClient;

    public APIService(
            ProxyInstance proxyInstance,
            Javalin managementApp
    )
    {
        this.logger = proxyInstance.logger;
        this.proxyInstance = proxyInstance;
        this.managementApp = managementApp;
        this.httpClient = HttpClient.newHttpClient();

        // 用户登录
        this.managementApp.post("/{instanceName}/api/login",
                ctx -> {
                    String instanceName = ctx.pathParam("instanceName");
                    if (!this.proxyInstance.proxyTarget.containsKey(instanceName))
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not exist!")
                        );
                        return;
                    }
                    PostgresProxyTarget postgresProxyTarget =
                            this.proxyInstance.proxyTarget.get(instanceName);
                    if (postgresProxyTarget.portX <= 0)
                    {
                        URI newURI =
                                URI.create(
                                        "http://" + postgresProxyTarget.host + postgresProxyTarget.portX +
                                                "/api/login"
                                );
                        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(newURI)
                                .POST(HttpRequest.BodyPublishers.ofString(ctx.body()));
                        ctx.headerMap().forEach(builder::header);
                        HttpRequest request = builder.build();
                        HttpResponse<byte[]> resp =
                                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                        // 设置返回状态码
                        ctx.status(resp.statusCode());

                        // 复制 header
                        resp.headers().map().forEach((k, vList) -> {
                            for (String v : vList) {
                                ctx.header(k, v);
                            }
                        });

                        // 设置 body（保持字节数组，不丢编码）
                        ctx.result(resp.body());
                    }
                    else
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not open api service!")
                        );
                    }
                }
        );

        // 用户登出
        this.managementApp.post("/{instanceName}/api/logout",
                ctx -> {
                    String instanceName = ctx.pathParam("instanceName");
                    if (!this.proxyInstance.proxyTarget.containsKey(instanceName))
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not exist!")
                        );
                        return;
                    }
                    PostgresProxyTarget postgresProxyTarget =
                            this.proxyInstance.proxyTarget.get(instanceName);
                    if (postgresProxyTarget.portX <= 0)
                    {
                        URI newURI =
                                URI.create(
                                        "http://" + postgresProxyTarget.host + postgresProxyTarget.portX +
                                                "/api/logout"
                                );
                        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(newURI)
                                .POST(HttpRequest.BodyPublishers.ofString(ctx.body()));
                        ctx.headerMap().forEach(builder::header);
                        HttpRequest request = builder.build();
                        HttpResponse<byte[]> resp =
                                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                        // 设置返回状态码
                        ctx.status(resp.statusCode());

                        // 复制 header
                        resp.headers().map().forEach((k, vList) -> {
                            for (String v : vList) {
                                ctx.header(k, v);
                            }
                        });

                        // 设置 body（保持字节数组，不丢编码）
                        ctx.result(resp.body());
                    }
                    else
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not open api service!")
                        );
                    }
                }
        );

        // 设置用户的会话信息
        this.managementApp.post("/{instanceName}/api/setContext",
                ctx -> {
                    String instanceName = ctx.pathParam("instanceName");
                    if (!this.proxyInstance.proxyTarget.containsKey(instanceName))
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not exist!")
                        );
                        return;
                    }
                    PostgresProxyTarget postgresProxyTarget =
                            this.proxyInstance.proxyTarget.get(instanceName);
                    if (postgresProxyTarget.portX <= 0)
                    {
                        URI newURI =
                                URI.create(
                                        "http://" + postgresProxyTarget.host + postgresProxyTarget.portX +
                                                "/api/setContext"
                                );
                        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(newURI)
                                .POST(HttpRequest.BodyPublishers.ofString(ctx.body()));
                        ctx.headerMap().forEach(builder::header);
                        HttpRequest request = builder.build();
                        HttpResponse<byte[]> resp =
                                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                        // 设置返回状态码
                        ctx.status(resp.statusCode());

                        // 复制 header
                        resp.headers().map().forEach((k, vList) -> {
                            for (String v : vList) {
                                ctx.header(k, v);
                            }
                        });

                        // 设置 body（保持字节数组，不丢编码）
                        ctx.result(resp.body());
                    }
                    else
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not open api service!")
                        );
                    }
                }
        );

        // 设置用户的会话信息
        this.managementApp.post("/{instanceName}/api/removeContext",
                ctx -> {
                    String instanceName = ctx.pathParam("instanceName");
                    if (!this.proxyInstance.proxyTarget.containsKey(instanceName))
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not exist!")
                        );
                        return;
                    }
                    PostgresProxyTarget postgresProxyTarget =
                            this.proxyInstance.proxyTarget.get(instanceName);
                    if (postgresProxyTarget.portX <= 0)
                    {
                        URI newURI =
                                URI.create(
                                        "http://" + postgresProxyTarget.host + postgresProxyTarget.portX +
                                                "/api/removeContext"
                                );
                        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(newURI)
                                .POST(HttpRequest.BodyPublishers.ofString(ctx.body()));
                        ctx.headerMap().forEach(builder::header);
                        HttpRequest request = builder.build();
                        HttpResponse<byte[]> resp =
                                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                        // 设置返回状态码
                        ctx.status(resp.statusCode());

                        // 复制 header
                        resp.headers().map().forEach((k, vList) -> {
                            for (String v : vList) {
                                ctx.header(k, v);
                            }
                        });

                        // 设置 body（保持字节数组，不丢编码）
                        ctx.result(resp.body());
                    }
                    else
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not open api service!")
                        );
                    }
            }
        );

        // 注册服务
        this.managementApp.post("/{instanceName}/api/registerService",
                ctx -> {
                    String instanceName = ctx.pathParam("instanceName");
                    if (!this.proxyInstance.proxyTarget.containsKey(instanceName))
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not exist!")
                        );
                        return;
                    }
                    PostgresProxyTarget postgresProxyTarget =
                            this.proxyInstance.proxyTarget.get(instanceName);
                    if (postgresProxyTarget.portX <= 0)
                    {
                        URI newURI =
                                URI.create(
                                        "http://" + postgresProxyTarget.host + postgresProxyTarget.portX +
                                                "/api/registerService"
                                );
                        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(newURI)
                                .POST(HttpRequest.BodyPublishers.ofString(ctx.body()));
                        ctx.headerMap().forEach(builder::header);
                        HttpRequest request = builder.build();
                        HttpResponse<byte[]> resp =
                                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                        // 设置返回状态码
                        ctx.status(resp.statusCode());

                        // 复制 header
                        resp.headers().map().forEach((k, vList) -> {
                            for (String v : vList) {
                                ctx.header(k, v);
                            }
                        });

                        // 设置 body（保持字节数组，不丢编码）
                        ctx.result(resp.body());
                    }
                    else
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not open api service!")
                        );
                    }
            }
        );

        // 取消服务注册
        this.managementApp.post("/{instanceName}/api/unRegisterService",
                ctx -> {
                    String instanceName = ctx.pathParam("instanceName");
                    if (!this.proxyInstance.proxyTarget.containsKey(instanceName))
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not exist!")
                        );
                        return;
                    }
                    PostgresProxyTarget postgresProxyTarget =
                            this.proxyInstance.proxyTarget.get(instanceName);
                    if (postgresProxyTarget.portX <= 0)
                    {
                        URI newURI =
                                URI.create(
                                        "http://" + postgresProxyTarget.host + postgresProxyTarget.portX +
                                                "/api/unRegisterService"
                                );
                        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(newURI)
                                .POST(HttpRequest.BodyPublishers.ofString(ctx.body()));
                        ctx.headerMap().forEach(builder::header);
                        HttpRequest request = builder.build();
                        HttpResponse<byte[]> resp =
                                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                        // 设置返回状态码
                        ctx.status(resp.statusCode());

                        // 复制 header
                        resp.headers().map().forEach((k, vList) -> {
                            for (String v : vList) {
                                ctx.header(k, v);
                            }
                        });

                        // 设置 body（保持字节数组，不丢编码）
                        ctx.result(resp.body());
                    }
                    else
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not open api service!")
                        );
                    }
            }
        );

        // 导出所有的注册信息, 不包含retCode等信息
        this.managementApp.get("/{instanceName}/api/listRegisteredService",
                ctx->{
                    String instanceName = ctx.pathParam("instanceName");
                    if (!this.proxyInstance.proxyTarget.containsKey(instanceName))
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not exist!")
                        );
                        return;
                    }
                    PostgresProxyTarget postgresProxyTarget =
                            this.proxyInstance.proxyTarget.get(instanceName);
                    if (postgresProxyTarget.portX <= 0)
                    {
                        URI newURI =
                                URI.create(
                                        "http://" + postgresProxyTarget.host + postgresProxyTarget.portX +
                                                "/api/listRegisteredService"
                                );
                        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(newURI)
                                .GET();
                        ctx.headerMap().forEach(builder::header);
                        HttpRequest request = builder.build();
                        HttpResponse<byte[]> resp =
                                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                        // 设置返回状态码
                        ctx.status(resp.statusCode());

                        // 复制 header
                        resp.headers().map().forEach((k, vList) -> {
                            for (String v : vList) {
                                ctx.header(k, v);
                            }
                        });

                        // 设置 body（保持字节数组，不丢编码）
                        ctx.result(resp.body());
                    }
                    else
                    {
                        ctx.json(Map.of(
                                "retCode",404,
                                "regMsg", "Instance [" + instanceName + "] does not open api service!")
                        );
                    }
                }
        );

        // API的GET请求
        this.managementApp.get("/{instanceName}/api/{apiVersion}/{apiName}", ctx -> {
            String apiVersion = ctx.pathParam("apiVersion");
            String apiName = ctx.pathParam("apiName");
            String instanceName = ctx.pathParam("instanceName");
            if (!this.proxyInstance.proxyTarget.containsKey(instanceName))
            {
                ctx.json(Map.of(
                        "retCode",404,
                        "regMsg", "Instance [" + instanceName + "] does not exist!")
                );
                return;
            }
            PostgresProxyTarget postgresProxyTarget =
                    this.proxyInstance.proxyTarget.get(instanceName);
            if (postgresProxyTarget.portX <= 0)
            {
                URI newURI =
                        URI.create(
                                "http://" + postgresProxyTarget.host + postgresProxyTarget.portX +
                                        "/api/" + apiVersion + "/" + apiName
                        );
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(newURI)
                        .GET();
                ctx.headerMap().forEach(builder::header);
                HttpRequest request = builder.build();
                HttpResponse<byte[]> resp =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                // 设置返回状态码
                ctx.status(resp.statusCode());

                // 复制 header
                resp.headers().map().forEach((k, vList) -> {
                    for (String v : vList) {
                        ctx.header(k, v);
                    }
                });

                // 设置 body（保持字节数组，不丢编码）
                ctx.result(resp.body());
            }
            else
            {
                ctx.json(Map.of(
                        "retCode",404,
                        "regMsg", "Instance [" + instanceName + "] does not open api service!")
                );
            }
        });

        // API的POST请求
        this.managementApp.post("/{instanceName}/api/{apiVersion}/{apiName}", ctx -> {
            String apiVersion = ctx.pathParam("apiVersion");
            String apiName = ctx.pathParam("apiName");
            String instanceName = ctx.pathParam("instanceName");
            if (!this.proxyInstance.proxyTarget.containsKey(instanceName))
            {
                ctx.json(Map.of(
                        "retCode",404,
                        "regMsg", "Instance [" + instanceName + "] does not exist!")
                );
                return;
            }
            PostgresProxyTarget postgresProxyTarget =
                    this.proxyInstance.proxyTarget.get(instanceName);
            if (postgresProxyTarget.portX <= 0)
            {
                URI newURI =
                        URI.create(
                                "http://" + postgresProxyTarget.host + postgresProxyTarget.portX +
                                        "/api/" + apiVersion + "/" + apiName
                        );
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(newURI)
                        .POST(HttpRequest.BodyPublishers.ofString(ctx.body()));
                ctx.headerMap().forEach(builder::header);
                HttpRequest request = builder.build();
                HttpResponse<byte[]> resp =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                // 设置返回状态码
                ctx.status(resp.statusCode());

                // 复制 header
                resp.headers().map().forEach((k, vList) -> {
                    for (String v : vList) {
                        ctx.header(k, v);
                    }
                });

                // 设置 body（保持字节数组，不丢编码）
                ctx.result(resp.body());
            }
            else
            {
                ctx.json(Map.of(
                        "retCode",404,
                        "regMsg", "Instance [" + instanceName + "] does not open api service!")
                );
            }
        });
    }
}
