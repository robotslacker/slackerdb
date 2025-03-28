package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slackerdb.dbserver.message.PostgresRequest;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;

public class PostgresServerHandler extends ChannelInboundHandlerAdapter {
    private final DBInstance dbInstance;
    private final Logger logger;

    public PostgresServerHandler(DBInstance pDbInstance, Logger pLogger)
    {
        super();
        logger = pLogger;
        dbInstance = pDbInstance;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception{
        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();

        // 创建一个初始会话，并在ctx的信息中进行记录
        DBSession dbSession = new DBSession(dbInstance);
        dbSession.connectedTime = LocalDateTime.now();
        dbSession.status = "connected";
        dbSession.clientAddress = remoteAddress.toString();

        // 将SessionId信息记录到CTX中
        int sessionId = dbInstance.newSession(dbSession);
        ctx.channel().attr(AttributeKey.valueOf("SessionId")).set(sessionId);

        // 设置线程名称，并打印调试信息
        Thread.currentThread().setName("Session-" + sessionId);
        logger.trace("[SERVER][PG PROTOCOL]: Accepted connection from {}", remoteAddress.toString());

        // 传递消息
        super.channelRegistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception{
        logger.trace("[SERVER][PG PROTOCOL]: Connection has been activated.");
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 开始处理，标记活跃会话数加一
        dbInstance.activeSessions.incrementAndGet();
        try {
            PostgresRequest postgresRequest = (PostgresRequest) msg;
            postgresRequest.process(ctx, msg);
        } catch (Exception e) {
            logger.error("[SERVER][PG PROTOCOL]: Error processing request", e);
        }
        // 结束处理，标记活跃会话数减一
        dbInstance.activeSessions.decrementAndGet();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 关闭会话
        dbInstance.abortSession((int)ctx.channel().attr(AttributeKey.valueOf("SessionId")).get());

        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        logger.trace("[SERVER][PG PROTOCOL]: Connection {} disconnected.", remoteAddress.toString());

        // 释放资源
        super.channelInactive(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception
    {
        logger.trace("[SERVER][PG PROTOCOL]: Connection channel has been unregistered.");

        // 释放资源
        super.channelUnregistered(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception{
        // 关闭会话
        dbInstance.abortSession((int)ctx.channel().attr(AttributeKey.valueOf("SessionId")).get());

        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        logger.trace("[SERVER][PG PROTOCOL]: Connection {} error.", remoteAddress.toString(), cause);

        // 释放资源
        super.exceptionCaught(ctx, cause);

        // 清理会话
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 关闭会话
        dbInstance.abortSession((int)ctx.channel().attr(AttributeKey.valueOf("SessionId")).get());

        if (evt instanceof IdleStateEvent event) {
            if (event.state() == IdleState.READER_IDLE) {
                logger.trace("[SERVER][PG PROTOCOL]: Connection {} error. Read timeout. ", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.WRITER_IDLE) {
                logger.trace("[SERVER][PG PROTOCOL]: Connection {} error. Write timeout. ", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.ALL_IDLE) {
                logger.trace("[SERVER][PG PROTOCOL]: Connection {} error. All timeout. ", ctx.channel().remoteAddress());
            }

            // 释放资源
            super.userEventTriggered(ctx, evt);

            // 清理会话
            ctx.close();
        }
    }
}
