package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.request.*;
import org.slackerdb.common.utils.Utils;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;

/**
 * POSTGRES V3 协议处理
 */
public class PostgresServer {
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private Logger logger;
    private boolean portReady = false;

    long readerIdleTime;
    long writerIdleTime;
    long allIdleTime;
    int nioEventThreads;

    private String bind;
    private int port;
    private DBInstance dbInstance;

    // 设置日志的句柄
    public void setLogger(Logger pLogger)
    {
        this.logger = pLogger;
    }

    public void setBindHostAndPort(String pBind, int pPort)
    {
        this.bind = pBind;
        this.port = pPort;
    }

    public void setServerTimeout(long pReaderIdleTime, long pWriterIdleTime, long pAllIdleTime)
    {
        this.readerIdleTime = pReaderIdleTime;
        this.writerIdleTime = pWriterIdleTime;
        this.allIdleTime = pAllIdleTime;
    }

    public void setNioEventThreads(int pNioEventThreads)
    {
        this.nioEventThreads = pNioEventThreads;
    }

    public void setDBInstance(DBInstance pDbInstance)
    {
        this.dbInstance = pDbInstance;
    }

    /**
     * 启动协议处理
     */
    public void start() {
        // Listener thread
        Thread thread = new Thread(() -> {
            try {
                Thread.currentThread().setName("Listener");
                run();
            } catch (InterruptedException | ServerException e) {
                throw new RuntimeException(e);
            }
        });
        thread.start();
    }

    /**
     * 关闭协议处理
     */
    public void stop()
    {
        logger.info("[SERVER] Received stop request.");
        logger.info("[SERVER] Server will stop now.");
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        logger.info("[SERVER] Server stopped.");
    }

    // 自定义解码器，处理原始字节数据
    class RawMessageDecoder extends ByteToMessageDecoder {
        void pushMsgObject(List<Object> out, Object obj)
        {
            // 打印所有收到的字节内容（16进制）
            if (logger.getLevel().levelStr.equals("TRACE")) {
                PostgresRequest postgresRequest = (PostgresRequest)obj;
                logger.trace("[SERVER][RX CONTENT ]: {},{}",
                        obj.getClass().getSimpleName(), postgresRequest.encode().length);
                for (String dumpMessage : Utils.bytesToHexList(postgresRequest.encode())) {
                    logger.trace("[SERVER][RX CONTENT ]: {}", dumpMessage);
                }
            }
            out.add(obj);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            // 获取上一次的处理指令，本次处理可能和上次相关
            String lastRequestCommand = (String)ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).get();

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
                        SSLRequest sslRequest = new SSLRequest(dbInstance);
                        sslRequest.decode(data);
                        pushMsgObject(out, sslRequest);

                        // 标记当前步骤
                        lastRequestCommand = SSLRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    else if (Arrays.equals(data, AdminClientRequest.AdminClientRequestHeader))
                    {
                        // 不需要回复Admin的握手请求

                        // 标记当前步骤
                        lastRequestCommand = AdminClientRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    else
                    {
                        // 都不是，则重置指针读取位置
                        // 有可能没有SSL请求，直接是StartupRequest (PG ODBC)
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

                    // 如果消息体长度超过1024，或者小于0. 明显是一个不合理的消息，直接拒绝
                    if (messageLen <= 0 || messageLen > 1024)
                    {
                        logger.trace("[SERVER] Invalid startup message from [{}]. Content header: [{}]. Refused.",
                                ctx.channel().remoteAddress().toString(),
                                Utils.bytesToHex(data));
                        in.clear();
                        ctx.close();
                        return;
                    }

                    // 等待消息体发送结束, 4字节的字节长度也是消息体长度的一部分
                    if (in.readableBytes() < (messageLen - 4)) {
                        in.readerIndex(in.readerIndex() - 4); // 重置读取位置
                        return;
                    }
                    data = new byte[messageLen - 4];
                    in.readBytes(data);

                    // 处理消息
                    StartupRequest startupRequest = new StartupRequest(dbInstance);
                    startupRequest.decode(data);
                    pushMsgObject(out, startupRequest);

                    // 标记当前步骤
                    lastRequestCommand = StartupRequest.class.getSimpleName();
                    ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
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

                // 如果消息体长度小于0. 明显是一个不合理的消息，直接拒绝
                if (messageLen <= 0 )
                {
                    logger.trace("[SERVER] Invalid package message from [{}]. Content header: [{}]. Refused.",
                            ctx.channel().remoteAddress().toString(),
                            Utils.bytesToHex(data));
                    in.clear();
                    ctx.close();
                    return;
                }

                // 等待消息体发送结束, 4字节的字节长度也是消息体长度的一部分
                if (in.readableBytes() < (messageLen - 4)) {
                    in.readerIndex(in.readerIndex() - 5); // 重置读取位置
                    return;
                }
                data = new byte[messageLen - 4];
                in.readBytes(data);

                switch (messageType) {
                    case 'P':
                        ParseRequest parseRequest = new ParseRequest(dbInstance);
                        parseRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, parseRequest);

                        // 标记当前步骤
                        lastRequestCommand = ParseRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    case 'B':
                        BindRequest bindRequest = new BindRequest(dbInstance);
                        bindRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, bindRequest);

                        // 标记当前步骤
                        lastRequestCommand = BindRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    case 'E':
                        ExecuteRequest executeRequest = new ExecuteRequest(dbInstance);
                        executeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, executeRequest);

                        // 标记当前步骤
                        lastRequestCommand = ExecuteRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    case 'S':
                        SyncRequest syncRequest = new SyncRequest(dbInstance);
                        syncRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, syncRequest);

                        // 标记当前步骤
                        lastRequestCommand = SyncRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    case 'D':
                        DescribeRequest describeRequest = new DescribeRequest(dbInstance);
                        describeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, describeRequest);

                        // 标记当前步骤
                        lastRequestCommand = DescribeRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    case 'Q':
                        QueryRequest queryRequest = new QueryRequest(dbInstance);
                        queryRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, queryRequest);

                        // 标记当前步骤
                        lastRequestCommand = DescribeRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    case 'd':
                        CopyDataRequest copyDataRequest = new CopyDataRequest(dbInstance);
                        copyDataRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, copyDataRequest);

                        // 标记当前步骤
                        lastRequestCommand = CopyDataRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    case 'c':
                        CopyDoneRequest copyDoneRequest = new CopyDoneRequest(dbInstance);
                        copyDoneRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, copyDoneRequest);

                        // 标记当前步骤
                        lastRequestCommand = CopyDoneRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    case 'C':
                        CloseRequest closeRequest = new CloseRequest(dbInstance);
                        closeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, closeRequest);

                        // 标记当前步骤
                        lastRequestCommand = CloseRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    case 'X':
                        TerminateRequest terminateRequest = new TerminateRequest(dbInstance);
                        terminateRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, terminateRequest);

                        // 清理会话
                        ctx.close();
                        break;
                    case 'F':
                        CancelRequest cancelRequest = new CancelRequest(dbInstance);
                        cancelRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, cancelRequest);

                        // 标记当前步骤
                        lastRequestCommand = CancelRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    case '!':
                        AdminClientRequest adminClientRequest = new AdminClientRequest(dbInstance);
                        adminClientRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, adminClientRequest);

                        // 标记当前步骤
                        lastRequestCommand = AdminClientRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        break;
                    default:
                        logger.error("[SERVER] Unknown message type: {}", messageType);
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

    public int getRegisteredConnectionsCount()
    {
        int registeredConnectionsCount = 0;
        for (EventExecutor eventExecutor : workerGroup) {
            SingleThreadEventLoop eventLoop = (SingleThreadEventLoop) eventExecutor;

            if (eventLoop.registeredChannels() != -1) {
                registeredConnectionsCount = registeredConnectionsCount + eventLoop.registeredChannels();
            }
        }
        return registeredConnectionsCount;
    }

    private void run() throws InterruptedException, ServerException {
        // 关闭Netty的日志, 如果不是在trace下
        Logger nettyLogger = (Logger) LoggerFactory.getLogger("io.netty");
        if (!this.logger.getLevel().equals(Level.TRACE)) {
            nettyLogger.setLevel(Level.OFF);
        }

        // Netty消息处理
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(nioEventThreads);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();

            // 开启Netty服务
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    // 禁用堆外内存的池化以求获得更高的内存使用率
                    .option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.SO_RCVBUF, 4096)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 定义超时处理机制
                            ch.pipeline().addLast(new IdleStateHandler(readerIdleTime, writerIdleTime, allIdleTime,TimeUnit.SECONDS));
                            // 定义消息处理
                            ch.pipeline().addLast(new RawMessageDecoder());
                            ch.pipeline().addLast(new RawMessageEncoder());
                            // 定义消息处理
                            ch.pipeline().addLast(new PostgresServerHandler(dbInstance, logger));
                        }
                    });
            ChannelFuture future =
                    bootstrap.bind(new InetSocketAddress(bind, port)).sync();
                    logger.info("[SERVER] Listening on {}:{}", bind, port);
            portReady = true;
            future.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public boolean isPortReady()
    {
        return portReady;
    }
}