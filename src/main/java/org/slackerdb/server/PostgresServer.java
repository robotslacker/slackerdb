package org.slackerdb.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
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
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.request.*;
import org.slackerdb.utils.Utils;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

/**
 * POSTGRES V3 协议处理
 */
public class PostgresServer {
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    /**
     * Start the server
     */
    public void start() {
        // Listener thread
        Thread thread = new Thread(() -> {
            try {
                run();
            } catch (InterruptedException | ServerException e) {
                throw new RuntimeException(e);
            }
        });
        thread.start();
    }

    public void stop(String clientInfo)
    {
        AppLogger.logger.info("[SERVER] Received stop request from [{}].", clientInfo);
        AppLogger.logger.info("[SERVER] Server will stop now.");
        DBInstance.state = "SHUTTING DOWN";
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        DBInstance.state = "CLOSED";
        AppLogger.logger.info("[SERVER] Server stopped.");
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
            int sessionId = (int)ctx.channel().attr(AttributeKey.valueOf("SessionId")).get();
            String lastRequestCommand = DBInstance.getSession(sessionId).LastRequestCommand;

            // 如果之前没有读取过任何协议，则读取前一个Int。可能是SSLRequest或者StartupMessage
            byte[] data;

            // 在这里进行原始字节数据的解析处理
            // 例如：解析消息头、消息体等
            // 解析后的数据对象添加到 out 列表中，以传递给下一个处理器
            while (in.readableBytes() > 0) {
                // 处理SSLRequest
                if (lastRequestCommand == null || lastRequestCommand.isEmpty()) {
                    // 等待网络请求发送完毕，SSLRequest
                    if (in.readableBytes() < 8) {
                        return;
                    }

                    // 首先推断为SSLRequest，或者是管理客户端的请求
                    data = new byte[8];
                    in.readBytes(data);

                    // 处理消息
                    if (Arrays.equals(data, SSLRequest.SSLRequestHeader))
                    {
                        SSLRequest sslRequest = new SSLRequest();
                        sslRequest.decode(data);
                        pushMsgObject(out, sslRequest);

                        // 标记当前步骤
                        lastRequestCommand = SSLRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                    }
                    else if (Arrays.equals(data, AdminClientRequest.AdminClientRequestHeader))
                    {
                        // 不需要回复Admin的握手请求

                        // 标记当前步骤
                        lastRequestCommand = AdminClientRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                    }
                    else
                    {
                        // 都不是，则重置指针读取位置
                        in.readerIndex(in.readerIndex() - 8);
                    }

                }

                // 处理StartupMessage
                if (lastRequestCommand == null || lastRequestCommand.equalsIgnoreCase(SSLRequest.class.getSimpleName())) {
                    // 等待网络请求发送完毕，StartupMessage
                    if (in.readableBytes() < 4) {
                        return;
                    }

                    // 首字节为消息体的长度
                    data = new byte[4];
                    in.readBytes(data);
                    int messageLen = Utils.bytesToInt32(data);

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
                    lastRequestCommand = StartupRequest.class.getSimpleName();
                    DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
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

                char messageType = (char) byteBuffer.get();
                int messageLen = byteBuffer.getInt();

                // 等待消息体发送结束, 4字节的字节长度也是消息体长度的一部分
                if (in.readableBytes() < (messageLen - 4)) {
                    in.readerIndex(in.readerIndex() - 5); // 重置读取位置
                    return;
                }
                data = new byte[messageLen - 4];
                in.readBytes(data);

                switch (messageType) {
                    case 'P':
                        ParseRequest parseRequest = new ParseRequest();
                        parseRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, parseRequest);

                        // 标记当前步骤
                        lastRequestCommand = ParseRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                        break;
                    case 'B':
                        BindRequest bindRequest = new BindRequest();
                        bindRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, bindRequest);

                        // 标记当前步骤
                        lastRequestCommand = BindRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                        break;
                    case 'E':
                        ExecuteRequest executeRequest = new ExecuteRequest();
                        executeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, executeRequest);

                        // 标记当前步骤
                        lastRequestCommand = ExecuteRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                        break;
                    case 'S':
                        SyncRequest syncRequest = new SyncRequest();
                        syncRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, syncRequest);

                        // 标记当前步骤
                        lastRequestCommand = SyncRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                        break;
                    case 'D':
                        DescribeRequest describeRequest = new DescribeRequest();
                        describeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, describeRequest);

                        // 标记当前步骤
                        lastRequestCommand = DescribeRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                        break;
                    case 'Q':
                        QueryRequest queryRequest = new QueryRequest();
                        queryRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, queryRequest);

                        // 标记当前步骤
                        lastRequestCommand = DescribeRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                        break;
                    case 'd':
                        CopyDataRequest copyDataRequest = new CopyDataRequest();
                        copyDataRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, copyDataRequest);

                        // 标记当前步骤
                        lastRequestCommand = CopyDataRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                        break;
                    case 'c':
                        CopyDoneRequest copyDoneRequest = new CopyDoneRequest();
                        copyDoneRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, copyDoneRequest);

                        // 标记当前步骤
                        lastRequestCommand = CopyDoneRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                        break;
                    case 'C':
                        CloseRequest closeRequest = new CloseRequest();
                        closeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, closeRequest);

                        // 标记当前步骤
                        lastRequestCommand = CloseRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                        break;
                    case 'X':
                        TerminateRequest terminateRequest = new TerminateRequest();
                        terminateRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, terminateRequest);

                        // 清理会话
                        ctx.close();
                        break;
                    case 'F':
                        CancelRequest cancelRequest = new CancelRequest();
                        cancelRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, cancelRequest);

                        // 标记当前步骤
                        lastRequestCommand = CancelRequest.class.getSimpleName();
                        DBInstance.getSession(sessionId).LastRequestCommand = lastRequestCommand;
                        break;
                    case '!':
                        AdminClientRequest adminClientRequest = new AdminClientRequest();
                        adminClientRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, adminClientRequest);
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

    private void run() throws InterruptedException, ServerException {
        // Netty消息处理
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(ServerConfiguration.getMax_Workers());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();

            // 开启Netty服务
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    // 禁用堆外内存的池化以求获得更高的内存使用率
                    .option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.SO_RCVBUF, 4096)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, 128)
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
                            ch.pipeline().addLast(new PostgresServerHandler()
                            );
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