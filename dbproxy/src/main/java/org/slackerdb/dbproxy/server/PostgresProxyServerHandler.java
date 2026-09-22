package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Logger;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbproxy.message.PostgresMessage;
import org.slackerdb.dbproxy.message.request.AdminClientRequest;
import org.slackerdb.dbproxy.message.request.CancelRequest;
import org.slackerdb.dbproxy.message.request.ProxyRequest;
import org.slackerdb.dbproxy.message.request.SSLRequest;
import org.slackerdb.dbproxy.message.request.StartupRequest;
import org.slackerdb.dbproxy.message.response.ErrorResponse;
import org.slackerdb.dbproxy.message.response.NoticeMessage;
import org.slackerdb.dbproxy.message.response.ProxyResponse;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PostgresProxyServerHandler  extends ChannelInboundHandlerAdapter {
    // 取消转发用的短连接建立超时：上游不可达时不能让事件循环一直等
    private static final int CANCEL_CONNECT_TIMEOUT_MS = 5_000;

    private final Logger logger;
    private final AtomicLong maxSessionId = new AtomicLong(1000);
    private Channel outboundChannel;
    private final ProxyInstance proxyInstance;

    // 本会话转发到的上游信息（握手期填充）。
    // 仅由本连接的 EventLoop 线程访问：入向链路的回调与出向链路（同一 EventLoop）的回调，
    // 因此不需要同步。
    private CancelRoute pendingRouteContext;

    // 本会话在转发 StartupMessage 时使用的"上游真实库名"
    private String forwardedDatabase;

    public PostgresProxyServerHandler(Logger pLogger, ProxyInstance proxyInstance)
    {
        super();
        this.logger = pLogger;
        this.proxyInstance = proxyInstance;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();

        // 为每个会话创建一个sessionId，记录之前的路由选项
        long sessionId = maxSessionId.incrementAndGet();
        ctx.channel().attr(AttributeKey.valueOf("SessionId")).set(sessionId);

        // 设置线程名称，并打印调试信息
        Thread.currentThread().setName("Proxy-" + sessionId);
        logger.trace("[PROXY] Accepted connection from {}", remoteAddress.toString());
    }

    /** 本会话的代理侧会话号（通道属性在 channelRegistered 中写入）。 */
    private long currentSessionId(ChannelHandlerContext ctx) {
        Object value = ctx.channel().attr(AttributeKey.valueOf("SessionId")).get();
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * 握手期从后端 BackendKeyData 学到 pid 后，登记"pid → 上游"的取消路由。
     *
     * <p>只在 EventLoop 线程上被调用（出向链路与入向链路共用同一个 EventLoop），
     * 且必须发生在 pendingRouteContext 就绪之后——顺序由 Netty 保证：
     * 入向先 writeAndFlush(StartupMessage)，后端才可能回包。</p>
     */
    void registerRouteFromBackendKeyData(int backendPid, int backendSecret) {
        CancelRoute context = this.pendingRouteContext;
        if (context == null) {
            logger.warn("[PROXY][CANCEL     ] Backend key data received before routing context is ready. pid={}. Cancel will not work for this session.", backendPid);
            return;
        }

        CancelRoute route = new CancelRoute();
        route.backendPid = backendPid;
        route.backendSecret = backendSecret;
        route.upstreamHost = context.upstreamHost;
        route.upstreamPort = context.upstreamPort;
        route.database = context.database;
        route.alias = context.alias;
        route.clientSessionId = context.clientSessionId;
        route.createdTime = java.time.LocalDateTime.now();

        this.proxyInstance.cancelRouter.register(route);

        // 绑定到出向连接，供连接关闭时精确摘除
        if (this.outboundChannel != null) {
            this.outboundChannel.attr(AttributeKey.valueOf("CancelRoute")).set(route);
        }

        logger.debug("[PROXY][CANCEL     ] Cancel route registered: {}", route);
    }

    /** 出向连接关闭时摘除取消路由（只按 pid 摘除会误删被复用 pid 的新会话映射）。 */
    private void unregisterRoute() {
        if (this.outboundChannel == null) {
            return;
        }
        Object bound = this.outboundChannel.attr(AttributeKey.valueOf("CancelRoute")).get();
        if (bound instanceof CancelRoute route) {
            this.proxyInstance.cancelRouter.unregister(route.backendPid, route.clientSessionId);
            logger.debug("[PROXY][CANCEL     ] Cancel route removed: {}", route);
        }
    }

    /**
     * 回复一个 ErrorResponse 并关闭连接。
     *
     * <p>顺序保证：先把错误响应 writeAndFlush 排入出站队列，再调用 {@code ctx.close()}。
     * Netty 关闭通道时会先写出出站队列中已排入的数据，因此客户端能收到具体错误，
     * 而不是只看到"连接被关闭"。</p>
     */
    private void sendErrorAndClose(ChannelHandlerContext ctx, String errorCode, String errorMessage) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ErrorResponse errorResponse = new ErrorResponse(null);
            errorResponse.setErrorResponse(errorCode, errorMessage);
            errorResponse.process(ctx, null, out);
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, logger);
        }
        catch (IOException ioe) {
            logger.warn("[PROXY][STARTUP    ] Failed to build error response [{}]: {}", errorCode, ioe.getMessage());
            ctx.close();
            return;
        }
        finally {
            try {
                out.close();
            }
            catch (IOException ignored) {
                // ByteArrayOutputStream.close() 不会抛，这里只是保持结构完整
            }
        }

        // 错误响应已排入出站队列，这里关闭连接；
        // 不能改用 writeAndFlush(EMPTY_BUFFER)+CLOSE —— 空 ByteBuf 不是
        // RawMessageEncoder(MessageToByteEncoder<ByteBuffer>) 接受的类型。
        ctx.close();
    }

    /**
     * 处理取消报文：按 pid 找到上游，新建一条**短连接**把 16 字节原样送达。
     *
     * <p>要点：① 必须新建连接，不能借用正在传输结果的数据通道；
     * ② 不给客户端任何响应字节（PG 规范），处理完直接关闭；
     * ③ 找不到目标（会话已结束等）属正常时序，静默忽略。</p>
     */
    private void handleCancelRequest(ChannelHandlerContext ctx, CancelRequest cancelRequest) {
        final int processId = cancelRequest.processId;
        try {
            CancelRoute route = this.proxyInstance.cancelRouter.find(processId);
            if (route == null) {
                logger.info("[PROXY][CANCEL     ] Cancel request for unknown backend pid [{}] from [{}]. Ignored.",
                        processId, ctx.channel().remoteAddress());
                return;
            }

            logger.debug("[PROXY][CANCEL     ] Forwarding cancel for pid [{}] to {}:{}/{}",
                    processId, route.upstreamHost, route.upstreamPort, route.database);

            final byte[] frame = CancelRequest.buildFrame(processId, cancelRequest.secretKey);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(ctx.channel().eventLoop())
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CANCEL_CONNECT_TIMEOUT_MS)
                    .handler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                        }
                    });

            ChannelFuture connectFuture = bootstrap.connect(route.upstreamHost, route.upstreamPort);
            connectFuture.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    // 上游不可达：只记日志，客户端侧仍然是"无响应 + 关闭"
                    logger.warn("[PROXY][CANCEL     ] Failed to connect upstream {}:{} for cancel of pid [{}]. {}",
                            route.upstreamHost, route.upstreamPort, processId, f.cause() == null ? "" : f.cause().getMessage());
                    return;
                }
                Channel cancelChannel = f.channel();
                ByteBuf buffer = cancelChannel.alloc().buffer(frame.length);
                buffer.writeBytes(frame);
                cancelChannel.writeAndFlush(buffer).addListener(ChannelFutureListener.CLOSE);
            });
        }
        catch (Exception e) {
            logger.warn("[PROXY][CANCEL     ] Error while forwarding cancel for pid [{}]: {}",
                    processId, e.getMessage());
        }
        finally {
            // PG 协议规定：取消不产生任何响应，直接关闭客户端这条连接
            ctx.close();
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws IOException {
        final Channel inboundChannel = ctx.channel();

       if (msg instanceof SSLRequest) {
            // SSLRequest请求不需要转发，直接回复即可
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // Notice的回复并不需要原始信息
            NoticeMessage noticeMessage = new NoticeMessage(null);
            noticeMessage.process(ctx, null, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, NoticeMessage.class.getSimpleName(), out, logger);
            out.close();
            return;
        }

        if (msg instanceof StartupRequest startupRequest) {
            // Startup消息要转发回复

            // 获得所有的连接选项
            Map<String, String> connectParameters = startupRequest.getStartupOptions();

            // 连接串里没有指定 database（或指定为空）时无法选路：必须**明确拒绝**并关闭连接。
            //
            // 改造前这里是 `return` —— 既不回复也不关闭，客户端会一直挂到空闲超时，
            // 表现为"连上了但没有任何反应"，极难排查。这里与 dbserver 的行为对齐：
            // 回一个 ErrorResponse(SLACKERDB-00001) 然后关闭连接。
            String requestedDatabase = connectParameters.get("database");
            if (requestedDatabase == null || requestedDatabase.trim().isEmpty())
            {
                logger.info("[PROXY][STARTUP    ] Connection from [{}] refused: no database specified in the startup packet.",
                        ctx.channel().remoteAddress());
                sendErrorAndClose(ctx, "SLACKERDB-00001", Utils.getMessage("SLACKERDB-00001"));
                return;
            }

            // 根据连接字符串里头的数据库名称决定转发地址
            String aliasName = connectParameters.get("database");
            try {
                // 查找合适的目的地
                if (!this.proxyInstance.proxyTarget.containsKey(aliasName))
                {
                    // 没有指定的服务，直接返回Error
                    // 通过统一的 sendErrorAndClose 保证"先写出响应、再关闭连接"
                    sendErrorAndClose(ctx, "SLACKER-0099",
                            "Connect refused. Database [" + aliasName + "] does not exist!");
                    return;
                }
                PostgresProxyTarget postgresProxyTarget = this.proxyInstance.proxyTarget.get(aliasName);

                // 构建一个目的转发器
                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(inboundChannel.eventLoop())
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new OutboundHandler(inboundChannel, logger, PostgresProxyServerHandler.this));
                            }
                        });
                ChannelFuture future = bootstrap.connect(postgresProxyTarget.host, postgresProxyTarget.port);

                // 记录握手上下文：后端回 BackendKeyData 时据此登记取消路由。
                // 必须在 writeAndFlush(StartupMessage) 之前设置好（同一个 EventLoop 上顺序执行）。
                CancelRoute routeContext = new CancelRoute();
                routeContext.upstreamHost = postgresProxyTarget.host;
                routeContext.upstreamPort = postgresProxyTarget.port;
                routeContext.database = postgresProxyTarget.database;
                routeContext.alias = aliasName;
                routeContext.clientSessionId = this.currentSessionId(ctx);
                this.pendingRouteContext = routeContext;

                // 确保连接成功后，继续处理
                future.addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        outboundChannel = f.channel();

                        // 记录远程端口号，便于日志信息
                        ctx.channel().attr(AttributeKey.valueOf("ForwardTarget"))
                                .set(outboundChannel.remoteAddress().toString());

                        // Startup消息要重新拼接内容, database用新的来覆盖
                        Map<String, String> oldStartupOptions = startupRequest.getStartupOptions();
                        oldStartupOptions.put("database", postgresProxyTarget.database);
                        byte[] newStartupOption = startupRequest.rebuildData();
                        this.forwardedDatabase = postgresProxyTarget.database;

                        // 转发Startup消息
                        ByteBuf byteBuf = Unpooled.buffer();
                        byteBuf.writeBytes(Utils.int32ToBytes(newStartupOption.length + 4));
                        byteBuf.writeBytes(newStartupOption);
                        outboundChannel.writeAndFlush(byteBuf);
                    } else {
                        this.pendingRouteContext = null;
                        ctx.channel().close();
                    }
                });
            }
            catch (ServerException se)
            {
                // 转发错误
                ByteArrayOutputStream out = new ByteArrayOutputStream();

                // ErrorResponse并不需要知道请求的request信息
                ErrorResponse errorResponse = new ErrorResponse(null);
                errorResponse.setErrorResponse(se.getErrorCode(), se.getMessage());
                errorResponse.process(ctx, null, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, logger);

                // 关闭连接
                out.close();
                ctx.close();
            }
            return;
        }

        // 处理取消请求：按 pid 查路由表并回送到上游
        if (msg instanceof CancelRequest cancelRequest)
        {
            this.handleCancelRequest(ctx, cancelRequest);
            return;
        }

        // 处理各种管理指令要求
        if (msg instanceof AdminClientRequest adminClientRequest)
        {
            adminClientRequest.process(ctx, null);
            return;
        }

        // 处理各种转发请求
        if (msg instanceof ProxyRequest proxyRequest)
        {
            // 处理代理转发消息
            ByteBuf byteBuf = Unpooled.buffer();
            byteBuf.writeByte(proxyRequest.getMessageType());
            byteBuf.writeBytes(Utils.int32ToBytes(proxyRequest.getRequestContent().length + 4));
            byteBuf.writeBytes(proxyRequest.getRequestContent());
            outboundChannel.writeAndFlush(byteBuf);
            return;
        }

        if (outboundChannel != null && outboundChannel.isActive()) {
            // 转发后续的消息
            outboundChannel.writeAndFlush(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 设置线程名称
        long sessionId = 0;
        if (ctx.channel().hasAttr(AttributeKey.valueOf("SessionId"))) {
            sessionId = (long) ctx.channel().attr(AttributeKey.valueOf("SessionId")).get();
        }
        Thread.currentThread().setName("Session-" + sessionId);

        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        logger.trace("[PROXY] Connection {} disconnected.", remoteAddress.toString());

        // 释放资源
        super.channelInactive(ctx);

        // 清理会话
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception{
        // 设置线程名称
        long sessionId = 0;
        if (ctx.channel().hasAttr(AttributeKey.valueOf("SessionId"))) {
            sessionId = (long) ctx.channel().attr(AttributeKey.valueOf("SessionId")).get();
        }
        Thread.currentThread().setName("Session-" + sessionId);

        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        logger.trace("[PROXY] Connection {} error.", remoteAddress.toString(), cause);

        // 释放资源
        super.exceptionCaught(ctx, cause);

        // 清理会话
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 设置线程名称
        int sessionId = 0;
        if (ctx.channel().hasAttr(AttributeKey.valueOf("SessionId"))) {
            sessionId = (int) ctx.channel().attr(AttributeKey.valueOf("SessionId")).get();
        }
        Thread.currentThread().setName("Session-" + sessionId);

        if (evt instanceof IdleStateEvent event) {
            if (event.state() == IdleState.READER_IDLE) {
                logger.trace("[PROXY] Connection {} error. Read timeout. ", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.WRITER_IDLE) {
                logger.trace("[PROXY] Connection {} error. Write timeout. ", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.ALL_IDLE) {
                logger.trace("[PROXY] Connection {} error. all timeout. ", ctx.channel().remoteAddress());
            }

            // 释放资源
            super.userEventTriggered(ctx, evt);

            // 清理会话
            ctx.close();
        }
    }

    /**
     * 握手期嗅探器（方案 A：只作用于握手阶段的轻量帧状态机）。
     *
     * <p>代理需要知道后端分配给客户端的会话号（BackendKeyData 的 pid），才能把之后到达的
     * CancelRequest 送回正确的上游。这里的做法是：**在握手期按 PG 报文框架逐帧切分**
     * （类型字节 + Int32 长度），遇到 'K' 就取出 pid/secret，遇到 ReadyForQuery('Z') 或
     * ErrorResponse('E') 就停止解析、回到纯透传。</p>
     *
     * <p><b>为什么不能简单扫描字节</b>：'K'(0x4B) 作为字节可能出现在任何数据里，盲扫会误判；
     * 握手期的帧是明确的，按长度切分零歧义。</p>
     *
     * <p><b>降级保证</b>：解析失败、帧不合法、缓冲区超限，一律停止解析并记日志，
     * <b>绝不影响任何转发行为</b> —— 取消是增强功能，不能拖垮主链路。</p>
     */
    static final class BackendHandshakeSniffer {
        // 握手期允许的最大累积字节数（正常握手远小于此值）
        private static final int MAX_BUFFER_BYTES = 64 * 1024;

        private final Logger logger;
        private final PostgresProxyServerHandler owner;

        private byte[] pending = new byte[0];
        private boolean finished = false;
        private boolean failed = false;

        BackendHandshakeSniffer(PostgresProxyServerHandler owner, Logger logger) {
            this.owner = owner;
            this.logger = logger;
        }

        boolean isFinished() {
            return finished || failed;
        }

        /**
         * 处理一段后端回包。只读不改，调用方照常把数据透传给客户端。
         */
        void process(ByteBuf msg) {
            if (isFinished() || msg == null || msg.readableBytes() == 0) {
                return;
            }
            try {
                int readable = msg.readableBytes();
                if (pending.length + readable > MAX_BUFFER_BYTES) {
                    this.failed = true;
                    logger.warn("[PROXY][CANCEL     ] Handshake buffer exceeded {} bytes. Stop parsing backend key data; cancel will not work for this session.", MAX_BUFFER_BYTES);
                    return;
                }

                // 追加到待解析缓冲（不消费入参，保证透传不受影响）
                byte[] merged = new byte[pending.length + readable];
                System.arraycopy(pending, 0, merged, 0, pending.length);
                msg.getBytes(msg.readerIndex(), merged, pending.length, readable);

                parse(merged);
            }
            catch (Exception e) {
                this.failed = true;
                logger.warn("[PROXY][CANCEL     ] Unexpected error while sniffing backend key data, parsing disabled for this session: {}", e.getMessage());
            }
        }

        private void parse(byte[] buffer) {
            int pos = 0;
            while (!finished && pos + 5 <= buffer.length) {
                char messageType = (char) (buffer[pos] & 0xFF);
                int messageLen = ((buffer[pos + 1] & 0xFF) << 24)
                        | ((buffer[pos + 2] & 0xFF) << 16)
                        | ((buffer[pos + 3] & 0xFF) << 8)
                        | (buffer[pos + 4] & 0xFF);

                // 长度域非法：说明当前偏移已经不是帧边界，停止解析（降级）
                if (messageLen < 4) {
                    this.failed = true;
                    logger.warn("[PROXY][CANCEL     ] Invalid frame length {} during handshake sniffing (type='{}'). Stop parsing; cancel will not work for this session.", messageLen, messageType);
                    return;
                }

                int frameTotal = messageLen + 1;
                if (pos + frameTotal > buffer.length) {
                    // 帧尚未收全，保留剩余字节等待下一批
                    break;
                }

                if (messageType == 'K') {
                    // BackendKeyData: Int32 长度(12) + Int32 pid + Int32 secret
                    if (messageLen != 12) {
                        this.failed = true;
                        logger.warn("[PROXY][CANCEL     ] Unexpected BackendKeyData length {}. Stop parsing; cancel will not work for this session.", messageLen);
                        return;
                    }
                    int pid = ((buffer[pos + 5] & 0xFF) << 24)
                            | ((buffer[pos + 6] & 0xFF) << 16)
                            | ((buffer[pos + 7] & 0xFF) << 8)
                            | (buffer[pos + 8] & 0xFF);
                    int secret = ((buffer[pos + 9] & 0xFF) << 24)
                            | ((buffer[pos + 10] & 0xFF) << 16)
                            | ((buffer[pos + 11] & 0xFF) << 8)
                            | (buffer[pos + 12] & 0xFF);

                    owner.registerRouteFromBackendKeyData(pid, secret);
                    pos += frameTotal;
                    continue;
                }

                pos += frameTotal;

                // 握手结束：ReadyForQuery 或 首次错误响应
                if (messageType == 'Z' || messageType == 'E') {
                    this.finished = true;
                    return;
                }
            }

            // 保留未消费的尾部，等待下一批数据补齐
            if (pos < buffer.length) {
                byte[] rest = new byte[buffer.length - pos];
                System.arraycopy(buffer, pos, rest, 0, rest.length);
                this.pending = rest;
            }
            else {
                this.pending = new byte[0];
            }
        }
    }

    private static class OutboundHandler extends ChannelInboundHandlerAdapter {
        private final Channel inboundChannel;
        private final Logger logger;
        private final PostgresProxyServerHandler owner;
        private final BackendHandshakeSniffer handshakeSniffer;

        private void closeOnFlush(Channel ch) {
            if (ch.isActive()) {
                ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }

        public OutboundHandler(Channel inboundChannel, Logger logger, PostgresProxyServerHandler owner) {
            this.inboundChannel = inboundChannel;
            this.logger = logger;
            this.owner = owner;
            this.handshakeSniffer = owner == null ? null : new BackendHandshakeSniffer(owner, logger);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // 握手期嗅探 BackendKeyData，用于登记取消路由。
            // 只读不改：无论解析成功或失败，下面都原样透传给客户端。
            if (handshakeSniffer != null && !handshakeSniffer.isFinished() && msg instanceof ByteBuf) {
                handshakeSniffer.process((ByteBuf) msg);
            }

            if (logger.getLevel() != null && logger.getLevel().levelStr.equals("TRACE")) {
                // 打印发送日志
                ByteBuf byteBuf = (ByteBuf) msg;
                byte[] data = new byte[byteBuf.readableBytes()];
                byteBuf.copy().getBytes(0, data);
                logger.trace("[PROXY][TX CONTENT ]: {},{} {} {}->{}",
                        (char)data[0], data.length,
                        ProxyResponse.getMessageClass(data[0]),
                        ctx.channel().remoteAddress().toString(),
                        inboundChannel.remoteAddress().toString());
                    for (String dumpMessage : Utils.bytesToHexList(data)) {
                        logger.trace("[PROXY][TX CONTENT ]: {}", dumpMessage);
                    }
            }
            inboundChannel.writeAndFlush(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // 出向连接结束 = 该会话结束，摘除它的取消路由
            if (owner != null) {
                owner.unregisterRoute();
            }
            closeOnFlush(inboundChannel);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }
    }
}
