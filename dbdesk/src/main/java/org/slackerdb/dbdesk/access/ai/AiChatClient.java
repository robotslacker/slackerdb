package org.slackerdb.dbdesk.access.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code /aichat} 的 WebSocket 客户端。
 *
 * <p>只有服务端配置了 LLM（{@code capabilities.features.aiChat=true}）时才应由页面创建。
 * 回调发生在 JDK HTTP 客户端线程上，UI 必须自己切回 Vaadin 线程。</p>
 */
public final class AiChatClient implements AutoCloseable {

    /** 页面监听器。 */
    public interface Listener {
        void onConnected();

        void onMessage(AiChatMessage message);

        void onError(String message);

        void onClosed();
    }

    private final String wsUrl;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private ScheduledExecutorService heartbeat;

    private volatile WebSocket socket;
    private volatile Listener listener;
    private final StringBuilder fragment = new StringBuilder();

    public AiChatClient(String managementBaseUrl) {
        this.wsUrl = toWebSocketUrl(managementBaseUrl);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String url() {
        return wsUrl;
    }

    public synchronized void connect(Listener listener) {
        closeSocket();
        this.listener = listener;
        fragment.setLength(0);
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        socket = webSocket;
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        synchronized (fragment) {
                            fragment.append(data);
                            if (last) {
                                String message = fragment.toString();
                                fragment.setLength(0);
                                dispatch(message);
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        socket = null;
                        stopHeartbeat();
                        Listener current = listener;
                        if (current != null) {
                            current.onClosed();
                        }
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        socket = null;
                        stopHeartbeat();
                        Listener current = listener;
                        if (current != null) {
                            current.onError(error.getMessage() == null
                                    ? error.getClass().getSimpleName() : error.getMessage());
                        }
                    }
                })
                .whenComplete((webSocket, error) -> {
                    if (error != null) {
                        Listener current = listener;
                        if (current != null) {
                            current.onError("连接 AI Chat 失败：" + error.getMessage());
                        }
                        return;
                    }
                    startHeartbeat();
                });
    }

    public synchronized void send(String content) {
        WebSocket current = socket;
        if (current == null || current.isOutputClosed()) {
            throw new IllegalStateException("AI Chat 未连接");
        }
        try {
            current.sendText(json.writeValueAsString(Map.of("type", "chat", "content", content)), true);
        } catch (RuntimeException e) {
            throw new IllegalStateException("发送 AI Chat 消息失败：" + e.getMessage(), e);
        }
    }

    public synchronized void clearHistory() {
        WebSocket current = socket;
        if (current == null || current.isOutputClosed()) {
            return;
        }
        try {
            current.sendText(json.writeValueAsString(Map.of("type", "clear_history")), true);
        } catch (RuntimeException ignored) {
            // 连接已断时清空本地消息即可，下一次连接会重新开始
        }
    }

    public boolean connected() {
        WebSocket current = socket;
        return current != null && !current.isOutputClosed() && !current.isInputClosed();
    }

    private void dispatch(String raw) {
        String type = "info";
        String content = raw;
        try {
            JsonNode root = json.readTree(raw);
            type = root.path("type").asText("info");
            if ("connected".equals(type) || "info".equals(type)) {
                content = root.path("message").asText("");
            } else if ("response".equals(type)) {
                content = root.path("content").asText("");
            } else if ("error".equals(type)) {
                content = root.path("message").asText("未知错误");
            } else if ("pong".equals(type)) {
                return;
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 消息按纯文本展示，保持可诊断性
        }
        Listener current = listener;
        if (current == null) {
            return;
        }
        if ("connected".equals(type)) {
            current.onConnected();
        }
        current.onMessage(new AiChatMessage(type, content));
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dbdesk-ai-chat-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            WebSocket current = socket;
            if (current == null || current.isOutputClosed()) {
                return;
            }
            try {
                current.sendText("ping", true);
            } catch (RuntimeException ignored) {
                // 连接断开由 onError/onClose 处理
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService current = heartbeat;
        heartbeat = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    private synchronized void closeSocket() {
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "client close");
            } catch (RuntimeException ignored) {
                current.abort();
            }
        }
        stopHeartbeat();
    }

    @Override
    public void close() {
        closeSocket();
        http.close();
    }

    private static String toWebSocketUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.startsWith("https://")) {
            base = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            base = "ws://" + base.substring("http://".length());
        } else if (base.startsWith("wss://") || base.startsWith("ws://")) {
            // 已经是 WebSocket 地址
        } else {
            base = "ws://" + base;
        }
        return base + "/aichat";
    }

    /** 便于测试/诊断。 */
    public static String websocketUrlFor(String managementBaseUrl) {
        return toWebSocketUrl(managementBaseUrl);
    }
}
