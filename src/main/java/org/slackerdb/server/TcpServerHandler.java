package org.slackerdb.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slackerdb.Protocol;
import org.slackerdb.ProtocolType;
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.PostgresRequest;
import org.slackerdb.protocol.SSLRequest;
import org.slackerdb.protocol.StartupRequest;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.descriptor.NetworkProtoDescriptor;
import org.slackerdb.protocol.events.BytesEvent;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.regex.Pattern;


public class TcpServerHandler extends ChannelInboundHandlerAdapter {
    // Create the execution context
    public static NetworkProtoDescriptor protoDescriptor;
    private NetworkProtoContext context;

    // 为每个连接创建一个会话ID
    private static int sessionId = 1000;
    private static String backendConnectString = "";

    // 根据配置信息获取连接字符串信息
    public static void setBackendConnectString() throws ServerException{
        // 获得连接字符串信息
        backendConnectString = "jdbc:duckdb:";

        String instanceName = ServerConfiguration.getData().trim();
        // 检查是否包含路径分隔符
        if (instanceName.contains("/") || instanceName.contains("\\")) {
            throw new ServerException(999,
                    "Invalid instance name [" + instanceName + "]");
        }
        // 检查是否包含不合法字符
        if (Pattern.compile("[\\\\/:*?\"<>|]").matcher(instanceName).find()) {
            throw new ServerException(999,
                    "Invalid instance name [" + instanceName + "]");
        }
        // 检查文件名长度（假设文件系统限制为255字符）
        if (instanceName.isEmpty() || instanceName.length() > 255) {
            throw new ServerException(999,
                    "Invalid instance name [" + instanceName + "]");
        }
        if (ServerConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:"))
        {
            backendConnectString = backendConnectString + ":memory:" + instanceName;
        }
        else
        {
            if (!new File(ServerConfiguration.getData_Dir()).isDirectory())
            {
                throw new ServerException(999,
                        "Data directory [" + ServerConfiguration.getData_Dir() + "] does not exist!");
            }
            File dataFile = new File(ServerConfiguration.getData_Dir(), instanceName + ".db");
            if (!dataFile.canRead() && ServerConfiguration.getAccess_mode().equalsIgnoreCase("READ_ONLY"))
            {
                throw new ServerException(999,
                        "Data [" + dataFile.getAbsolutePath() + "] can't be read!!");
            }
            if (!dataFile.canRead() && ServerConfiguration.getAccess_mode().equalsIgnoreCase("READ_WRITE"))
            {
                throw new ServerException(999,
                        "Data [" + dataFile.getAbsolutePath() + "] can't be write!!");
            }
        }
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        // 获取远端的 IP 地址和端口号
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        AppLogger.logger.trace("[SERVER] Accepted connection from {}", remoteAddress.toString());

        // Create the execution context
        context = (NetworkProtoContext) protoDescriptor.buildContext(null);

        // 初始化会话信息
        int currentSessionId;
        synchronized (TcpServerHandler.class) {
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
        Protocol.processMessage(ctx, msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 关闭会话
        try {
            Connection backendConnection = (Connection) ctx.channel().attr(AttributeKey.valueOf("Connection")).get();
            backendConnection.close();
        }
        catch (SQLException ignored) {}

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
