package org.slackerdb.dbproxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Main2 {
    static URI resolveRedirectUrl(URI originalUri, String location) {
        URI locationUri = URI.create(location);
        if (locationUri.isAbsolute()) {
            return locationUri;
        }
        // 保留原始查询参数
        String newQuery = originalUri.getQuery();
        return originalUri.resolve(location).normalize();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        HttpClient CLIENT = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        // 构建目标URL
        String targetUrl = "http://127.0.0.1:65386/";
        URI originalUri = URI.create(targetUrl);

        // 构建转发请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
//                .headers(createHeadersString(ctx))
                .timeout(Duration.ofSeconds(15))
                .build();

        // 发送请求并获取响应
        HttpResponse<byte[]> response = CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofByteArray()
        );
        if (response.statusCode() == 302) {
            String location = response.headers()
                    .firstValue("Location")
                    .orElseThrow();
            URI newUri = resolveRedirectUrl(originalUri, location);
            HttpRequest newRequest = HttpRequest.newBuilder()
                    .uri(newUri)
                    .build();
            response = CLIENT.send(newRequest, HttpResponse.BodyHandlers.ofByteArray());
        }
        System.out.println(response.statusCode());
    }
}
