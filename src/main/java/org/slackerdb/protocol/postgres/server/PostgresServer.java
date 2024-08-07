package org.slackerdb.protocol.postgres.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.postgres.message.*;
import org.slackerdb.protocol.postgres.message.request.*;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Multithreaded asynchronous server
 */
public class PostgresServer {
    private static String backendConnectString = null;
    protected static Connection backendSysConnection;

    private void initBackendConnectString() throws ServerException {
        if (backendConnectString == null) {
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
                backendConnectString = backendConnectString + dataFile.getAbsolutePath();
            }
        }
    }

    public static String getBackendConnectString()
    {
        return backendConnectString;
    }

    /**
     * Start the server
     */
    public void start() throws ServerException {
         // 初始化服务处理程序的后端数据库连接字符串
        initBackendConnectString();

        // Listener thread
        Thread thread = new Thread(() -> {
            try {
                run();
            } catch (IOException | ExecutionException | InterruptedException | ServerException e) {
                throw new RuntimeException(e);
            }
        });
        thread.start();
    }

    // 自定义解码器，处理原始字节数据
    static class RawMessageDecoder extends ByteToMessageDecoder {
        static void pushMsgObject(List<Object> out, Object obj)
        {
            // 打印所有收到的字节内容（16进制）
            if (AppLogger.logger.getLevel().levelStr.equals("TRACE")) {
                PostgresRequest postgresRequest = (PostgresRequest)obj;
                AppLogger.logger.trace("[SERVER][RX CONTENT ]: {},{}",
                        obj.getClass().getSimpleName(), postgresRequest.encode().length);
                for (String dumpMessage : Utils.bytesToHexList(postgresRequest.encode())) {
                    AppLogger.logger.trace("[SERVER][RX CONTENT ]: {}", dumpMessage);
                }
            }
            out.add(obj);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            // 如果之前没有读取过任何协议，则读取前一个Int。可能是SSLRequest或者StartupMessage
            String previousRequestProtocol;
            previousRequestProtocol = (String) ctx.channel().attr(AttributeKey.valueOf("PreviousRequestProtocol")).get();
            byte[] data;

            // 在这里进行原始字节数据的解析处理
            // 例如：解析消息头、消息体等
            // 解析后的数据对象添加到 out 列表中，以传递给下一个处理器
            while (in.readableBytes() > 0) {
                // 处理SSLRequest
                if (previousRequestProtocol.isEmpty()) {
                    // 等待网络请求发送完毕，StartupMessage
                    if (in.readableBytes() < 8) {
                        return;
                    }

                    // SSLRequest
                    data = new byte[8];
                    in.readBytes(data);

                    // 处理消息
                    SSLRequest sslRequest = new SSLRequest();
                    sslRequest.decode(data);
                    pushMsgObject(out, sslRequest);

                    // 标记当前步骤
                    ctx.channel().attr(AttributeKey.valueOf("PreviousRequestProtocol")).set(SSLRequest.class.getSimpleName());
                    previousRequestProtocol = SSLRequest.class.getSimpleName();
                }

                // 处理StartupMessage
                if (previousRequestProtocol.equalsIgnoreCase(SSLRequest.class.getSimpleName())) {
                    // 等待网络请求发送完毕，StartupMessage
                    if (in.readableBytes() < 4) {
                        return;
                    }

                    // 首字节为消息体的长度
                    data = new byte[4];
                    in.readBytes(data);
                    ByteBuffer byteBuffer = ByteBuffer.wrap(data);
                    int messageLen = byteBuffer.getInt();

                    // 等待消息体发送结束, 4字节的字节长度也是消息体长度的一部分
                    if (in.readableBytes() < (messageLen - 4)) {
                        in.readerIndex(in.readerIndex() - 4); // 重置读取位置
                        return;
                    }
                    data = new byte[messageLen - 4];
                    in.readBytes(data);

                    // 处理消息
                    StartupRequest startupRequest = new StartupRequest();
                    startupRequest.decode(data);
                    pushMsgObject(out, startupRequest);

                    // 标记当前步骤
                    ctx.channel().attr(AttributeKey.valueOf("PreviousRequestProtocol")).set(StartupRequest.class.getSimpleName());
                    previousRequestProtocol = StartupRequest.class.getSimpleName();
                    continue;
                }

                // 处理其他消息
                // 前5个字节为消息体的类别以及消息体的长度
                if (in.readableBytes() < 5) {
                    return;
                }
                data = new byte[5];
                in.readBytes(data);
                ByteBuffer byteBuffer = ByteBuffer.wrap(data);

                char messageType = (char)byteBuffer.get();
                int messageLen = byteBuffer.getInt();

                // 等待消息体发送结束, 4字节的字节长度也是消息体长度的一部分
                if (in.readableBytes() < (messageLen - 4)) {
                    in.readerIndex(in.readerIndex() - 5); // 重置读取位置
                    return;
                }
                data = new byte[messageLen - 4];
                in.readBytes(data);

                switch (messageType)
                {
                    case 'P':
                        ParseRequest parseRequest = new ParseRequest();
                        parseRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, parseRequest);

                        // 标记当前步骤
                        ctx.channel().attr(AttributeKey.valueOf("PreviousRequestProtocol")).set(ParseRequest.class.getSimpleName());
                        previousRequestProtocol = ParseRequest.class.getSimpleName();
                        break;
                    case 'B':
                        BindRequest bindRequest = new BindRequest();
                        bindRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, bindRequest);

                        // 标记当前步骤
                        ctx.channel().attr(AttributeKey.valueOf("PreviousRequestProtocol")).set(BindRequest.class.getSimpleName());
                        previousRequestProtocol = BindRequest.class.getSimpleName();
                        break;
                    case 'E':
                        ExecuteRequest executeRequest = new ExecuteRequest();
                        executeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, executeRequest);

                        // 标记当前步骤
                        ctx.channel().attr(AttributeKey.valueOf("PreviousRequestProtocol")).set(ExecuteRequest.class.getSimpleName());
                        previousRequestProtocol = ExecuteRequest.class.getSimpleName();
                        break;
                    case 'S':
                        SyncRequest syncRequest = new SyncRequest();
                        syncRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, syncRequest);

                        // 标记当前步骤
                        ctx.channel().attr(AttributeKey.valueOf("PreviousRequestProtocol")).set(SyncRequest.class.getSimpleName());
                        previousRequestProtocol = SyncRequest.class.getSimpleName();
                        break;
                    case 'D':
                        DescribeRequest describeRequest = new DescribeRequest();
                        describeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, describeRequest);

                        // 标记当前步骤
                        ctx.channel().attr(AttributeKey.valueOf("PreviousRequestProtocol")).set(DescribeRequest.class.getSimpleName());
                        previousRequestProtocol = DescribeRequest.class.getSimpleName();
                        break;
                    case 'X':
                        TerminateRequest terminateRequest = new TerminateRequest();
                        terminateRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, terminateRequest);

                        // 关闭连接
                        ctx.close();
                        break;
                    default:
                        AppLogger.logger.error("[SERVER] Unknown message type: {}", messageType);
                }
            }
        }
    }

    // 自定义编码器，处理原始字节数据
    static class RawMessageEncoder extends MessageToByteEncoder<ByteBuffer> {
        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuffer msg, ByteBuf out) {
            out.writeBytes(msg);
        }
    }

    // 自定义处理器示例
    static class CustomLogHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // 处理接收到的消息
            AppLogger.logger.info("[NETTY] Received message: {}", msg);

            // 传递给下一个处理器
            ctx.fireChannelRead(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // 异常处理逻辑
            AppLogger.logger.error("[NETTY] Exception caught", cause);
            ctx.close();
        }
    }

    private void run() throws IOException, ExecutionException, InterruptedException, ServerException {
        // 初始化一个DB连接，以保证即使所有客户端都断开连接，服务端会话仍然会继续存在
        try {
            if (ServerConfiguration.getAccess_mode().equals("READ_ONLY")) {
                Properties readOnlyProperty = new Properties();
                readOnlyProperty.setProperty("duckdb.read_only", "true");
                backendSysConnection = DriverManager.getConnection(backendConnectString, readOnlyProperty);
            } else {
                backendSysConnection = DriverManager.getConnection(backendConnectString);
            }
            AppLogger.logger.info("[SERVER] Backend database [{}] opened.", backendConnectString);
        }
        catch (SQLException e) {
            DBInstance.state = "STARTUP FAILED";
            AppLogger.logger.error("[SERVER] Init backend connection error. ", e);
            throw new ServerException(e);
        }

        // Netty消息处理
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(ServerConfiguration.getMax_Network_Workers());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_RCVBUF, 4096)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(
                            new LoggingHandler(LogLevel.valueOf(ServerConfiguration.getLog_level().levelStr))
                    )
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 定义超时处理机制
                            ch.pipeline().addLast(new IdleStateHandler(
                                    ServerConfiguration.getClientTimeout(),
                                    ServerConfiguration.getClientTimeout(),
                                    ServerConfiguration.getClientTimeout(),
                                    TimeUnit.SECONDS));
                            // 定义消息处理
                            ch.pipeline().addLast(new RawMessageDecoder());
                            ch.pipeline().addLast(new RawMessageEncoder());
                            // 定义消息处理
                            ch.pipeline().addLast(
                                    new PostgresServerHandler()
                            );
                            // 添加日志处理
                            ch.pipeline().addLast(new CustomLogHandler());
                        }
                    });
            ChannelFuture future =
                    bootstrap.bind(new InetSocketAddress(ServerConfiguration.getBindHost(), ServerConfiguration.getPort())).sync();
            AppLogger.logger.info("[SERVER] Listening on {}:{}",
                    ServerConfiguration.getBindHost(),
                    ServerConfiguration.getPort());
            DBInstance.state = "RUNNING";
            future.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
 }