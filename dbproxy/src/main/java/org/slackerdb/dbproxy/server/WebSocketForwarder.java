package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Logger;
import io.javalin.Javalin;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletionStage;

/**
 * WebSocket 转发：{@code /<alias>/aichat} → 实例的 {@code /aichat}。
 *
 * <p>管理口的普通 HTTP 已经由 {@link ManagementForwarder} 透传；WebSocket 是升级协议，
 * 走不了那条路，必须单独做双向管道。当前只代理 dbserver 唯一的管理口 WS 端点
 * （AI Chat），路径固定为 {@code /aichat}。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>连接建立时先解析别名；实例不在运行就直接以 1011 关闭并带原因；</li>
 *   <li>上游未连上时客户端消息先入队，连上后按序补发（客户端不会丢首帧）；</li>
 *   <li>分片消息按 {@code last} 聚合后再转发，避免把半个 JSON 送给对端；</li>
 *   <li>关闭码做归一化：1005/1006/1015 是保留码，不能出现在 {@code sendClose} 里。</li>
 * </ul>
 */
public final class WebSocketForwarder {

    private static final String ATTRIBUTE = "slackerdb.ws.session";
    private static final int CLOSE_UPSTREAM_FAILED = 1011;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ProxyInstance proxy;
    private final Logger logger;

    private WebSocketForwarder(ProxyInstance proxy) {
        this.proxy = proxy;
        this.logger = proxy.logger;
    }

    public static void register(ProxyInstance proxy, Javalin app) {
        WebSocketForwarder forwarder = new WebSocketForwarder(proxy);
        app.unsafe.routes.ws("/{alias}/aichat", ws -> {
            ws.onConnect(forwarder::onConnect);
            ws.onMessage(ctx -> {
                Session session = session(ctx);
                if (session != null) {
                    session.fromClient(ctx.message());
                }
            });
            ws.onBinaryMessage(ctx -> {
                Session session = session(ctx);
                if (session != null) {
                    session.fromClientBinary(ctx.data());
                }
            });
            ws.onClose(ctx -> {
                Session session = session(ctx);
                if (session != null) {
                    session.clientClosed(ctx.status(), ctx.reason());
                }
            });
            ws.onError(ctx -> {
                Session session = session(ctx);
                if (session != null) {
                    session.abort("client error: " + message(ctx.error()));
                }
                if (forwarder.logger.isDebugEnabled()) {
                    forwarder.logger.debug("[PROXY][WS         ] Client error: {}", message(ctx.error()));
                }
            });
        });
    }

    private static Session session(WsContext ctx) {
        return ctx.attribute(ATTRIBUTE);
    }

    private void onConnect(WsConnectContext ctx) {
        String alias = ctx.pathParam("alias");
        ProxyInstance.ManagementTarget target = proxy.resolveManagementTarget(alias);
        if (target == null || target.baseUrl() == null) {
            logger.info("[PROXY][WS         ] Rejecting websocket for [{}]: database is not running.", alias);
            ctx.closeSession(CLOSE_UPSTREAM_FAILED,
                    "Database [" + alias + "] is not running");
            return;
        }

        String wsUrl = toWebSocketUrl(target.baseUrl()) + "/aichat";
        String query = ctx.queryString();
        if (query != null && !query.isEmpty()) {
            wsUrl = wsUrl + "?" + query;
        }

        Session session = new Session(ctx, alias, wsUrl);
        ctx.attribute(ATTRIBUTE, session);
        session.open();
    }

    private static String toWebSocketUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.startsWith("https://")) {
            return "wss://" + base.substring("https://".length());
        }
        if (base.startsWith("http://")) {
            return "ws://" + base.substring("http://".length());
        }
        return base;
    }

    private static int safeCloseCode(int code) {
        // 1005/1006/1015 是保留码，sendClose 会抛 IllegalArgumentException
        if (code == 0 || code == 1005 || code == 1006 || code == 1015) {
            return 1000;
        }
        return code;
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** 一条客户端连接的完整状态。 */
    private final class Session implements WebSocket.Listener {
        private final WsConnectContext client;
        private final String alias;
        private final String upstreamUrl;

        private final Deque<String> pendingText = new ArrayDeque<>();
        private final Deque<ByteBuffer> pendingBinary = new ArrayDeque<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

        private volatile WebSocket upstream;
        private volatile boolean closed;

        private Session(WsConnectContext client, String alias, String upstreamUrl) {
            this.client = client;
            this.alias = alias;
            this.upstreamUrl = upstreamUrl;
        }

        private void open() {
            CLIENT.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(upstreamUrl), this)
                    .whenComplete((webSocket, error) -> {
                        if (error != null) {
                            logger.warn("[PROXY][WS         ] Cannot open upstream websocket {} for [{}]: {}",
                                    upstreamUrl, alias, message(error));
                            abort("Cannot connect to database [" + alias + "]: " + message(error));
                            return;
                        }
                        this.upstream = webSocket;
                        if (closed) {
                            // 客户端在上游连上之前就断了：不要再补发队列里的消息
                            webSocket.sendClose(1000, "client closed");
                            return;
                        }
                        flushPending();
                    });
        }

        private void fromClient(String text) {
            if (closed || text == null) {
                return;
            }
            WebSocket target = upstream;
            if (target == null) {
                synchronized (pendingText) {
                    pendingText.add(text);
                }
                return;
            }
            sendText(target, text);
        }

        private void fromClientBinary(ByteBuffer data) {
            if (closed || data == null) {
                return;
            }
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data.duplicate());
            copy.flip();

            WebSocket target = upstream;
            if (target == null) {
                synchronized (pendingBinary) {
                    pendingBinary.add(copy);
                }
                return;
            }
            sendBinary(target, copy);
        }

        private void flushPending() {
            WebSocket target = upstream;
            if (target == null || closed) {
                return;
            }
            Deque<String> texts;
            Deque<ByteBuffer> binaries;
            synchronized (pendingText) {
                texts = new ArrayDeque<>(pendingText);
                pendingText.clear();
            }
            synchronized (pendingBinary) {
                binaries = new ArrayDeque<>(pendingBinary);
                pendingBinary.clear();
            }
            texts.forEach(text -> sendText(target, text));
            binaries.forEach(data -> sendBinary(target, data));
        }

        private void sendText(WebSocket target, String text) {
            try {
                target.sendText(text, true);
            } catch (RuntimeException sendFailure) {
                abort("send to database failed: " + message(sendFailure));
            }
        }

        private void sendBinary(WebSocket target, ByteBuffer data) {
            try {
                target.sendBinary(data.duplicate(), true);
            } catch (RuntimeException sendFailure) {
                abort("send to database failed: " + message(sendFailure));
            }
        }

        private void clientClosed(int status, String reason) {
            closed = true;
            WebSocket target = upstream;
            if (target == null) {
                return;
            }
            try {
                target.sendClose(safeCloseCode(status), reason == null ? "" : reason);
            } catch (RuntimeException ignored) {
                target.abort();
            }
        }

        private void abort(String reason) {
            if (closed) {
                return;
            }
            closed = true;
            WebSocket target = upstream;
            if (target != null) {
                target.abort();
            }
            try {
                client.closeSession(CLOSE_UPSTREAM_FAILED, reason);
            } catch (RuntimeException ignored) {
                // 客户端可能已经断开
            }
        }

        // ---------------------------------------------------------- WebSocket.Listener

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!closed) {
                synchronized (textBuffer) {
                    textBuffer.append(data);
                    if (last) {
                        String message = textBuffer.toString();
                        textBuffer.setLength(0);
                        try {
                            client.send(message);
                        } catch (RuntimeException sendFailure) {
                            abort("send to client failed: " + message(sendFailure));
                        }
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (!closed) {
                synchronized (binaryBuffer) {
                    byte[] chunk = new byte[data.remaining()];
                    data.duplicate().get(chunk);
                    binaryBuffer.writeBytes(chunk);
                    if (last) {
                        ByteBuffer message = ByteBuffer.wrap(binaryBuffer.toByteArray());
                        binaryBuffer.reset();
                        try {
                            client.send(message);
                        } catch (RuntimeException sendFailure) {
                            abort("send to client failed: " + message(sendFailure));
                        }
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            try {
                client.closeSession(safeCloseCode(statusCode), reason == null ? "" : reason);
            } catch (RuntimeException ignored) {
                // 客户端可能已经断开
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            abort("database websocket error: " + message(error));
        }
    }
}
