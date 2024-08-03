package org.slackerdb.protocol.postgres.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slackerdb.protocol.postgres.message.request.BaseRequest;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.logger.AppLogger;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Properties;

public class PostgresServerHandler extends ChannelInboundHandlerAdapter {
    // 为每个连接创建一个会话ID
    private static int sessionId = 1000;
    private static String backendConnectString = null;

    public static void setBackendConnectString(String backendConnectString)
    {
        PostgresServerHandler.backendConnectString = backendConnectString;
    }

    public static void sessionClose(ChannelHandlerContext ctx)
    {
        if (ctx.channel().hasAttr(AttributeKey.valueOf("Connection"))) {
            Connection conn = (Connection)ctx.channel().attr(AttributeKey.valueOf("Connection")).get();
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
    }

    public static void sessionAbort(ChannelHandlerContext ctx)
    {
        if (ctx.channel().hasAttr(AttributeKey.valueOf("Connection"))) {
            Connection conn = (Connection)ctx.channel().attr(AttributeKey.valueOf("Connection")).get();
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
        ctx.channel().attr(AttributeKey.valueOf("SessionId")).set(currentSessionId);
        ctx.channel().attr(AttributeKey.valueOf("Client")).set(remoteAddress.toString());
        ctx.channel().attr(AttributeKey.valueOf("ConnectedTime")).set(LocalDateTime.now());

        // 获取数据库连接
        Connection backendDBConnection;
        try {
            if (ServerConfiguration.getAccess_mode().equals("READ_ONLY")) {
                Properties readOnlyProperty = new Properties();
                readOnlyProperty.setProperty("duckdb.read_only", "true");
                backendDBConnection = DriverManager.getConnection(backendConnectString, readOnlyProperty);
            } else {
                backendDBConnection = DriverManager.getConnection(backendConnectString);
            }
            ctx.channel().attr(AttributeKey.valueOf("Connection")).set(backendDBConnection);
        }
        catch (SQLException e) {
            AppLogger.logger.error("[SERVER] Init backend connection error. ", e);
            ctx.close();
        }

        // 记录上一个请求的协议类型
        ctx.channel().attr(AttributeKey.valueOf("PreviousRequestProtocol")).set("");
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
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 关闭会话
        sessionAbort(ctx);

        // 关闭会话
        try {
            Connection backendConnection = (Connection) ctx.channel().attr(AttributeKey.valueOf("Connection")).get();
            backendConnection.close();
        }
        catch (SQLException ignored) {}

        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Connection {} error.", remoteAddress.toString(), cause);

        // 关闭连接
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        // 关闭会话
        sessionAbort(ctx);

        // 关闭会话
        try {
            Connection backendConnection = (Connection) ctx.channel().attr(AttributeKey.valueOf("Connection")).get();
            backendConnection.close();
        }
        catch (SQLException ignored) {}

        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                AppLogger.logger.trace("[SERVER] Connection {} error. Read timeout. ", ctx.channel().remoteAddress());
                ctx.close();
            } else if (event.state() == IdleState.WRITER_IDLE) {
                AppLogger.logger.trace("[SERVER] Connection {} error. Write timeout. ", ctx.channel().remoteAddress());
                System.out.println("Write timeout, closing connection: " + ctx.channel().remoteAddress());
                ctx.close();
            } else if (event.state() == IdleState.ALL_IDLE) {
                AppLogger.logger.trace("[SERVER] Connection {} error. all timeout. ", ctx.channel().remoteAddress());
                ctx.close();
            }
        }
    }
}
