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
    private final Logger logger;
    private final AtomicLong maxSessionId = new AtomicLong(1000);
    private Channel outboundChannel;
    private final ProxyInstance proxyInstance;

    public PostgresProxyServerHandler(ProxyInstance proxyInstance, Logger pLogger)
    {
        super();
        logger = pLogger;
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

        if (msg instanceof StartupRequest) {
            // Startup消息要转发回复
            StartupRequest startupRequest = (StartupRequest)msg;

            // 获得所有的连接选项
            Map<String, String> connectParameters = startupRequest.getStartupOptions();

            // 对于没有指定database的连接请求，不必转发，也不需要处理
            if (!connectParameters.containsKey("database"))
            {
                return;
            }

            // 根据连接字符串里头的数据库名称决定转发地址
            String aliasName = connectParameters.get("database");
            try {
                // 查找合适的目的地
                PostgresProxyTarget postgresProxyTarget = this.proxyInstance.getAvailableTarget(aliasName);

                // 构建一个目的转发器
                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(inboundChannel.eventLoop())
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new OutboundHandler(inboundChannel, logger));
                            }
                        });
                ChannelFuture future = bootstrap.connect(postgresProxyTarget.getHost(), postgresProxyTarget.getPort());

                // 确保连接成功后，继续处理
                future.addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        outboundChannel = f.channel();

                        // 记录远程端口号，便于日志信息
                        ctx.channel().attr(AttributeKey.valueOf("ForwardTarget"))
                                .set(outboundChannel.remoteAddress().toString());

                        // Startup消息要重新拼接内容, database用新的来覆盖
                        Map<String, String> oldStartupOptions = startupRequest.getStartupOptions();
                        oldStartupOptions.put("database", postgresProxyTarget.getDatabase());
                        byte[] newStartupOption = startupRequest.rebuildData();

                        // 转发Startup消息
                        ByteBuf byteBuf = Unpooled.buffer();
                        byteBuf.writeBytes(Utils.int32ToBytes(newStartupOption.length + 4));
                        byteBuf.writeBytes(newStartupOption);
                        outboundChannel.writeAndFlush(byteBuf);
                    } else {
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

        if (msg instanceof AdminClientRequest)
        {
            AdminClientRequest adminClientRequest = (AdminClientRequest)msg;
            adminClientRequest.process(ctx, null);

            return;
        }

        if (msg instanceof ProxyRequest)
        {
            // 处理代理转发消息
            ProxyRequest proxyRequest = (ProxyRequest)msg;
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

        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
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

    private static class OutboundHandler extends ChannelInboundHandlerAdapter {
        private final Channel inboundChannel;
        private final Logger logger;

        private void closeOnFlush(Channel ch) {
            if (ch.isActive()) {
                ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }

        public OutboundHandler(Channel inboundChannel, Logger logger) {
            this.inboundChannel = inboundChannel;
            this.logger = logger;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (logger.getLevel().levelStr.equals("TRACE")) {
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
