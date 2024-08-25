package org.slackerdb.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slackerdb.message.request.BaseRequest;
import org.slackerdb.logger.AppLogger;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;

public class PostgresServerHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Accepted connection from {}", remoteAddress.toString());

        // 创建一个初始会话，并在ctx的信息中进行记录
        DBSession dbSession = new DBSession();
        dbSession.connectedTime = LocalDateTime.now();
        dbSession.status = "connected";
        dbSession.clientAddress = remoteAddress.toString();

        int sessionId = DBInstance.newSession(dbSession);
        ctx.channel().attr(AttributeKey.valueOf("SessionId")).set(sessionId);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            BaseRequest.processMessage(ctx, msg);
        } catch (Exception e) {
            AppLogger.logger.error("[SERVER] Error processing request", e);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 关闭会话
        DBInstance.abortSession((int)ctx.channel().attr(AttributeKey.valueOf("SessionId")).get());

        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Connection {} disconnected.", remoteAddress.toString());

        // 释放资源
        super.channelInactive(ctx);

        // 清理会话
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception{
        // 关闭会话
        DBInstance.abortSession((int)ctx.channel().attr(AttributeKey.valueOf("SessionId")).get());

        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Connection {} error.", remoteAddress.toString(), cause);

        // 释放资源
        super.exceptionCaught(ctx, cause);

        // 清理会话
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 关闭会话
        DBInstance.abortSession((int)ctx.channel().attr(AttributeKey.valueOf("SessionId")).get());

        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                AppLogger.logger.trace("[SERVER] Connection {} error. Read timeout. ", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.WRITER_IDLE) {
                AppLogger.logger.trace("[SERVER] Connection {} error. Write timeout. ", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.ALL_IDLE) {
                AppLogger.logger.trace("[SERVER] Connection {} error. all timeout. ", ctx.channel().remoteAddress());
            }

            // 释放资源
            super.userEventTriggered(ctx, evt);

            // 清理会话
            ctx.close();
        }
    }
}
