package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slackerdb.dbserver.message.PostgresRequest;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
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

    /**
     * 获取远程地址的字符串表示，兼容 TCP (InetSocketAddress) 和 UDS (DomainSocketAddress)
     */
    private String getRemoteAddressString(ChannelHandlerContext ctx) {
        SocketAddress remoteAddress = ctx.channel().remoteAddress();
        if (remoteAddress instanceof InetSocketAddress) {
            return ((InetSocketAddress) remoteAddress).toString();
        } else if (remoteAddress instanceof DomainSocketAddress) {
            return ((DomainSocketAddress) remoteAddress).path();
        } else if (remoteAddress != null) {
            return remoteAddress.toString();
        } else {
            return "unknown";
        }
    }

    /**
     * 安全地获取 SessionId，如果未设置则返回 0
     */
    private int getSessionIdSafe(ChannelHandlerContext ctx) {
        AttributeKey<Integer> sessionKey = AttributeKey.valueOf("SessionId");
        if (ctx.channel().hasAttr(sessionKey)) {
            Integer sessionId = ctx.channel().attr(sessionKey).get();
            return sessionId != null ? sessionId : 0;
        }
        return 0;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception{
        // 获取远端的地址信息（兼容 TCP 和 UDS）
        String remoteAddressStr = getRemoteAddressString(ctx);

        // 创建一个初始会话，并在ctx的信息中进行记录
        DBSession dbSession = new DBSession(dbInstance);
        dbSession.connectedTime = LocalDateTime.now();
        dbSession.status = "connected";
        dbSession.clientAddress = remoteAddressStr;

        // 将SessionId信息记录到CTX中
        int sessionId = dbInstance.newSession(dbSession);
        ctx.channel().attr(AttributeKey.valueOf("SessionId")).set(sessionId);

        // 设置线程名称，并打印调试信息
        Thread.currentThread().setName("Session-" + sessionId);
        logger.trace("[SERVER][PG PROTOCOL]: Accepted connection from {}", remoteAddressStr);

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
        try {
            // 开始处理，标记活跃会话数加一
            dbInstance.activeSessions.incrementAndGet();
            PostgresRequest postgresRequest = (PostgresRequest) msg;
            postgresRequest.process(ctx, msg);
        } catch (Exception e) {
            logger.error("[SERVER][PG PROTOCOL]: Error processing request", e);
        }
        finally {
            // 结束处理，标记活跃会话数减一
            dbInstance.activeSessions.decrementAndGet();
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 关闭会话
        int sessionId = getSessionIdSafe(ctx);
        if (sessionId > 0) {
            dbInstance.abortSession(sessionId);
        }

        // 获取远端的地址信息
        logger.trace("[SERVER][PG PROTOCOL]: Connection {} disconnected.", getRemoteAddressString(ctx));

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
        int sessionId = getSessionIdSafe(ctx);
        if (sessionId > 0) {
            dbInstance.abortSession(sessionId);
        }

        // 获取远端的地址信息
        logger.trace("[SERVER][PG PROTOCOL]: Connection {} error.", getRemoteAddressString(ctx), cause);

        // 释放资源
        super.exceptionCaught(ctx, cause);

        // 清理会话
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 关闭会话
        int sessionId = getSessionIdSafe(ctx);
        if (sessionId > 0) {
            dbInstance.abortSession(sessionId);
        }

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
