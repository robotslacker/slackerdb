package org.slackerdb.cdb.server;

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
import org.slackerdb.cdb.message.PostgresMessage;
import org.slackerdb.cdb.message.request.AdminClientRequest;
import org.slackerdb.cdb.message.request.ProxyRequest;
import org.slackerdb.cdb.message.request.SSLRequest;
import org.slackerdb.cdb.message.request.StartupRequest;
import org.slackerdb.cdb.message.response.ErrorResponse;
import org.slackerdb.cdb.message.response.NoticeMessage;
import org.slackerdb.cdb.message.response.ProxyResponse;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PostgresProxyServerHandler  extends ChannelInboundHandlerAdapter {
    private final Logger logger;
    private final AtomicLong maxSessionId = new AtomicLong(1000);
    private Channel outboundChannel;
    private final CDBInstance cdbInstance;

    public PostgresProxyServerHandler(CDBInstance CDBInstance, Logger pLogger)
    {
        super();
        logger = pLogger;
        this.cdbInstance = CDBInstance;
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
        logger.trace("[SERVER_CDB] Accepted connection from {}", remoteAddress.toString());
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

            // 对于没有指定database的连接请求，不必转发，也不需要处理
            if (!connectParameters.containsKey("database"))
            {
                return;
            }

            // 根据连接字符串里头的数据库名称决定转发地址
            String aliasName = connectParameters.get("database");
            try {
                if (!this.cdbInstance.proxyTarget.containsKey(aliasName))
                {
                    // 之前不存在这样的数据库，将新建一个数据库，用作连接
                    // 启动一个初始化的DB，并放入到Proxy中
                    // 启动一个数据库, 启动在随机端口上
                    org.slackerdb.dbserver.configuration.ServerConfiguration instanceConfiguration =
                            new org.slackerdb.dbserver.configuration.ServerConfiguration();
                    instanceConfiguration.setAccess_mode(this.cdbInstance.serverConfiguration.getAccess_mode());
                    instanceConfiguration.setStartup_script(this.cdbInstance.serverConfiguration.getStartup_script());
                    instanceConfiguration.setInit_script(this.cdbInstance.serverConfiguration.getInit_script());
                    instanceConfiguration.setSqlHistory(this.cdbInstance.serverConfiguration.getSqlHistory());
                    instanceConfiguration.setMax_workers(this.cdbInstance.serverConfiguration.getMax_Workers());
                    instanceConfiguration.setThreads(this.cdbInstance.serverConfiguration.getThreads());
                    if (this.cdbInstance.serverConfiguration.getAutoClose()) {
                        // 如果设置了自动关闭，则不再保留最小连接信息
                        // 如果设置了自动关闭，最大生命周期不能超过自动关闭的时间
                        instanceConfiguration.setConnection_pool_minimum_idle(0);
                        instanceConfiguration.setConnection_pool_maximum_lifecycle_time(
                                Math.min(this.cdbInstance.serverConfiguration.getAutoCloseTimeout(),
                                        this.cdbInstance.serverConfiguration.getConnection_pool_maximum_lifecycle_time()));
                    }
                    else
                    {
                        instanceConfiguration.setConnection_pool_minimum_idle(this.cdbInstance.serverConfiguration.getConnection_pool_minimum_idle());
                        instanceConfiguration.setConnection_pool_maximum_lifecycle_time(this.cdbInstance.serverConfiguration.getConnection_pool_maximum_lifecycle_time());
                    }
                    instanceConfiguration.setConnection_pool_maximum_idle(this.cdbInstance.serverConfiguration.getConnection_pool_maximum_idle());
                    instanceConfiguration.setLog_level(this.cdbInstance.serverConfiguration.getLog_level());
                    instanceConfiguration.setLog(this.cdbInstance.serverConfiguration.getLog());
                    instanceConfiguration.setExtension_dir(this.cdbInstance.serverConfiguration.getExtension_dir());
                    instanceConfiguration.setMemory_limit(this.cdbInstance.serverConfiguration.getMemory_limit());
                    instanceConfiguration.setClient_timeout(this.cdbInstance.serverConfiguration.getClient_timeout());
                    instanceConfiguration.setTemp_dir(this.cdbInstance.serverConfiguration.getTemp_dir());
                    instanceConfiguration.setMax_connections(this.cdbInstance.serverConfiguration.getMax_connections());
                    instanceConfiguration.setTemplate(this.cdbInstance.serverConfiguration.getTemplate());
                    instanceConfiguration.setBindHost("127.0.0.1");
                    instanceConfiguration.setPort(0);
                    instanceConfiguration.setData(aliasName);
                    DBInstance dbInstance = new DBInstance(instanceConfiguration);
                    dbInstance.start();

                    // 添加转发规则
                    this.cdbInstance.addAlias(dbInstance.instanceName,
                            "127.0.0.1:" +
                                    dbInstance.serverConfiguration.getPort() + "/" +
                                    dbInstance.serverConfiguration.getData(),
                            dbInstance);

                }

                // 查找合适的目的地
                PostgresProxyTarget postgresProxyTarget = this.cdbInstance.proxyTarget.get(aliasName);

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

        if (msg instanceof AdminClientRequest adminClientRequest)
        {
            adminClientRequest.process(ctx, null);
            return;
        }

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
        logger.trace("[SERVER_CDB] Connection {} disconnected.", remoteAddress.toString());

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
        logger.trace("[SERVER_CDB] Connection {} error.", remoteAddress.toString(), cause);

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
                logger.trace("[SERVER_CDB] Connection {} error. Read timeout. ", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.WRITER_IDLE) {
                logger.trace("[SERVER_CDB] Connection {} error. Write timeout. ", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.ALL_IDLE) {
                logger.trace("[SERVER_CDB] Connection {} error. all timeout. ", ctx.channel().remoteAddress());
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
                logger.trace("[SERVER_CDB][TX CONTENT ]: {},{} {} {}->{}",
                        (char)data[0], data.length,
                        ProxyResponse.getMessageClass(data[0]),
                        ctx.channel().remoteAddress().toString(),
                        inboundChannel.remoteAddress().toString());
                    for (String dumpMessage : Utils.bytesToHexList(data)) {
                        logger.trace("[SERVER_CDB][TX CONTENT ]: {}", dumpMessage);
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
