package org.slackerdb.protocol.postgres.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slackerdb.protocol.postgres.message.request.BaseRequest;
import org.slackerdb.logger.AppLogger;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.Map;

public class PostgresServerHandler extends ChannelInboundHandlerAdapter {
    // 为每个连接创建一个会话ID
    private static int sessionId = 1000;

    public static void sessionClose(ChannelHandlerContext ctx)
    {
        for (String attrName : PostgresServer.channelAttributeManager.listAttributes(ctx.channel()))
        {
            Object obj = PostgresServer.channelAttributeManager.getAttribute(ctx.channel(), attrName);
            if (obj instanceof Connection)
            {
                Connection conn = (Connection)PostgresServer.channelAttributeManager.getAttribute(ctx.channel(), "Connection");
                if (conn != null) {
                    try {
                        if (!conn.isClosed() && !conn.isReadOnly()) {
                            conn.commit();
                            conn.close();
                        }
                    }
                    catch (SQLException e) {
                        // 后端数据库无法提交多次COMMIT操作
                        if (!e.getMessage().contains("no transaction is active"))
                        {
                            AppLogger.logger.error("[SERVER] Can't close connection", e);
                        }
                    }
                }
            }
            else if (obj instanceof PreparedStatement)
            {
                try {
                    PreparedStatement ps = (PreparedStatement) PostgresServer.channelAttributeManager.getAttribute(ctx.channel(), attrName);
                    ps.close();
                }
                catch (SQLException ignored) {}
            }
            else if (obj instanceof ResultSet)
            {
                try {
                    ResultSet rs = (ResultSet) PostgresServer.channelAttributeManager.getAttribute(ctx.channel(), attrName);
                    rs.close();
                }
                catch (SQLException ignored) {}
            }
            else if (obj instanceof Map)
            {
                ((Map<?, ?>) obj).clear();
            }
        }
    }

    public static void sessionAbort(ChannelHandlerContext ctx)
    {
        for (String attrName : PostgresServer.channelAttributeManager.listAttributes(ctx.channel()))
        {
            Object obj = PostgresServer.channelAttributeManager.getAttribute(ctx.channel(), attrName);
            if (obj instanceof Connection)
            {
                Connection conn = (Connection)PostgresServer.channelAttributeManager.getAttribute(ctx.channel(), "Connection");
                if (conn != null) {
                    try {
                        if (!conn.isClosed() && !conn.isReadOnly()) {
                            conn.rollback();
                            conn.close();
                        }
                    }
                    catch (SQLException e) {
                        // 后端数据库无法提交多次COMMIT操作
                        if (!e.getMessage().contains("no transaction is active"))
                        {
                            AppLogger.logger.error("[SERVER] Can't close connection", e);
                        }
                    }
                }
            }
            else if (obj instanceof PreparedStatement)
            {
                try {
                    PreparedStatement ps = (PreparedStatement) PostgresServer.channelAttributeManager.getAttribute(ctx.channel(), attrName);
                    ps.close();
                }
                catch (SQLException ignored) {}
            }
            else if (obj instanceof ResultSet)
            {
                try {
                    ResultSet rs = (ResultSet) PostgresServer.channelAttributeManager.getAttribute(ctx.channel(), attrName);
                    rs.close();
                }
                catch (SQLException ignored) {}
            }
            else if (obj instanceof Map)
            {
                ((Map<?, ?>) obj).clear();
            }
        }
        PostgresServer.channelAttributeManager.clear(ctx.channel());
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Accepted connection from {}", remoteAddress.toString());

        // 初始化会话信息
        int currentSessionId;
        synchronized (PostgresServerHandler.class) {
            sessionId++;
            currentSessionId = sessionId;
        }
        PostgresServer.channelAttributeManager.setAttribute(ctx.channel(), "SessionId", currentSessionId);
        PostgresServer.channelAttributeManager.setAttribute(ctx.channel(), "Connected", new Timestamp(System.currentTimeMillis()));

        // 记录上一个请求的协议类型
        PostgresServer.channelAttributeManager.setAttribute(ctx.channel(), "PreviousRequestProtocol", null);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        BaseRequest.processMessage(ctx, msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 关闭会话
        sessionAbort(ctx);

        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Connection {} disconnected.", remoteAddress.toString());

        // 释放资源
        super.channelInactive(ctx);

        // 清理会话
        PostgresServer.channelAttributeManager.clear(ctx.channel());
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception{
        // 关闭会话
        sessionAbort(ctx);

        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Connection {} error.", remoteAddress.toString(), cause);

        // 释放资源
        super.exceptionCaught(ctx, cause);

        // 清理会话
        PostgresServer.channelAttributeManager.clear(ctx.channel());
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 关闭会话
        sessionAbort(ctx);

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
            PostgresServer.channelAttributeManager.clear(ctx.channel());
            ctx.close();
        }
    }
}
