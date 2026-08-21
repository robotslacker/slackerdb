package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.unix.DomainSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.request.*;
import org.slackerdb.common.utils.OSUtil;
import org.slackerdb.common.utils.Utils;

import java.io.File;
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
    private EventLoopGroup udsBossGroup;
    private EventLoopGroup udsWorkerGroup;

    private Logger logger;
    private boolean portReady = false;

    long readerIdleTime;
    long writerIdleTime;
    long allIdleTime;
    int nioEventThreads;

    private String bind;
    private int port;
    private String socketPath;
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

    public void setSocketPath(String pSocketPath)
    {
        this.socketPath = pSocketPath;
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
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        if (udsWorkerGroup != null) {
            udsWorkerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        if (udsBossGroup != null) {
            udsBossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        logger.info("[SERVER] Server stopped.");
    }

    // 自定义解码器，处理原始字节数据
    class RawMessageDecoder extends ByteToMessageDecoder {
        void pushMsgObject(List<Object> out, Object obj)
        {
            // 打印所有收到的字节内容（16进制）
            if (logger.getLevel() != null && logger.getLevel().levelStr.equals("TRACE")) {
                PostgresRequest postgresRequest = (PostgresRequest)obj;
                logger.trace("[SERVER][RX CONTENT ]: {},{}",
                        obj.getClass().getSimpleName(),postgresRequest.encode().length);
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

                // 处理各种消息
                switch (messageType) {
                    case 'P' -> {
                        ParseRequest parseRequest = new ParseRequest(dbInstance);
                        parseRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, parseRequest);

                        // 标记当前步骤
                        lastRequestCommand = ParseRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'B' -> {
                        BindRequest bindRequest = new BindRequest(dbInstance);
                        bindRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, bindRequest);

                        // 标记当前步骤
                        lastRequestCommand = BindRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'E' -> {
                        ExecuteRequest executeRequest = new ExecuteRequest(dbInstance);
                        executeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, executeRequest);

                        // 标记当前步骤
                        lastRequestCommand = ExecuteRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'S' -> {
                        SyncRequest syncRequest = new SyncRequest(dbInstance);
                        syncRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, syncRequest);

                        // 标记当前步骤
                        lastRequestCommand = SyncRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'D' -> {
                        DescribeRequest describeRequest = new DescribeRequest(dbInstance);
                        describeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, describeRequest);

                        // 标记当前步骤
                        lastRequestCommand = DescribeRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'Q' -> {
                        QueryRequest queryRequest = new QueryRequest(dbInstance);
                        queryRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, queryRequest);

                        // 标记当前步骤
                        lastRequestCommand = QueryRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'd' -> {
                        CopyDataRequest copyDataRequest = new CopyDataRequest(dbInstance);
                        copyDataRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, copyDataRequest);

                        // 标记当前步骤
                        lastRequestCommand = CopyDataRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'c' -> {
                        CopyDoneRequest copyDoneRequest = new CopyDoneRequest(dbInstance);
                        copyDoneRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, copyDoneRequest);

                        // 标记当前步骤
                        lastRequestCommand = CopyDoneRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'C' -> {
                        CloseRequest closeRequest = new CloseRequest(dbInstance);
                        closeRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, closeRequest);

                        // 标记当前步骤
                        lastRequestCommand = CloseRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'X' -> {
                        TerminateRequest terminateRequest = new TerminateRequest(dbInstance);
                        terminateRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, terminateRequest);

                        // 清理会话
                        ctx.close();
                    }
                    case 'F' -> {
                        CancelRequest cancelRequest = new CancelRequest(dbInstance);
                        cancelRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, cancelRequest);

                        // 标记当前步骤
                        lastRequestCommand = CancelRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case '!' -> {
                        AdminClientRequest adminClientRequest = new AdminClientRequest(dbInstance);
                        adminClientRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, adminClientRequest);

                        // 标记当前步骤
                        lastRequestCommand = AdminClientRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    default -> logger.error("[SERVER] Unknown message type: {}", messageType);
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
            // 启动TCP服务（如果端口不为-1）
            if (port != -1) {
                ServerBootstrap tcpBootstrap = new ServerBootstrap();

                // 开启Netty TCP服务
                tcpBootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        // 禁用堆外内存的池化以求获得更高的内存使用率
                        .option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                        // 接收缓冲区大小
                        .option(ChannelOption.SO_RCVBUF, 65536)
                        // 允许绑定处于 TIME_WAIT 状态的端口，快速重启服务
                        .option(ChannelOption.SO_REUSEADDR, true)
                        // 定义操作系统未完成连接队列的最大长度
                        .option(ChannelOption.SO_BACKLOG, 1024)
                        // 每个客户端连接禁用Nagle算法（PostgreSQL协议标准要求，避免小请求延迟累积）
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        // 启用TCP KeepAlive，配合IdleStateHandler保持连接稳定
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        // 扩大客户端连接的收发缓冲区，承载大结果集并减少TCP分片
                        .childOption(ChannelOption.SO_RCVBUF, 262144)
                        .childOption(ChannelOption.SO_SNDBUF, 262144)
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
                var ignored = tcpBootstrap.bind(new InetSocketAddress(bind, port)).sync();
                portReady = true;
                logger.info("[SERVER] TCP listener started on {}:{}", bind, port);
            }

            // 启动UDS服务（如果socketPath不为空）
            if (socketPath != null && !socketPath.isEmpty()) {
                // Windows不支持Unix Domain Socket，忽略该参数并记录日志
                if (OSUtil.isWindows()) {
                    logger.warn("[SERVER] UDS is not supported on Windows. Socket parameter '{}' will be ignored.", socketPath);
                } else if (!Epoll.isAvailable()) {
                    // epoll 不可用时（如 macOS），记录警告并忽略 UDS 参数
                    logger.warn("[SERVER] UDS is not supported on this platform (epoll not available). Socket parameter '{}' will be ignored.", socketPath);
                } else {
                    // 如果socket文件已存在，先删除
                    File socketFile = new File(socketPath);
                    if (socketFile.exists()) {
                        var ignored = socketFile.delete();
                    }

                    // 确保父目录存在
                    File socketDir = socketFile.getParentFile();
                    if (socketDir != null && !socketDir.exists()) {
                        var ignored = socketDir.mkdirs();
                    }

                    // UDS 使用独立的 EpollEventLoopGroup（不能与 NioEventLoopGroup 混用）
                    udsBossGroup = new EpollEventLoopGroup(1);
                    udsWorkerGroup = new EpollEventLoopGroup(nioEventThreads);

                    ServerBootstrap udsBootstrap = new ServerBootstrap();

                    udsBootstrap.group(udsBossGroup, udsWorkerGroup)
                            .channel(EpollServerDomainSocketChannel.class)
                            .option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                            .option(ChannelOption.SO_BACKLOG, 1024)
                            .childHandler(new ChannelInitializer<DomainSocketChannel>() {
                                @Override
                                protected void initChannel(DomainSocketChannel ch) {
                                    // 定义超时处理机制
                                    ch.pipeline().addLast(new IdleStateHandler(readerIdleTime, writerIdleTime, allIdleTime,TimeUnit.SECONDS));
                                    // 定义消息处理
                                    ch.pipeline().addLast(new RawMessageDecoder());
                                    ch.pipeline().addLast(new RawMessageEncoder());
                                    // 定义消息处理
                                    ch.pipeline().addLast(new PostgresServerHandler(dbInstance, logger));
                                }
                            });
                    var ignored = udsBootstrap.bind(new DomainSocketAddress(socketPath)).sync();
                    logger.info("[SERVER] UDS listener started on {}", socketPath);
                }
            }

            // 等待任意一个Channel关闭（如果没有启动任何服务则直接返回）
            if (port == -1 && (socketPath == null || socketPath.isEmpty())) {
                logger.warn("[SERVER] No listener (TCP or UDS) configured. Server will not accept any connections.");
                return;
            }

            // 保持主线程运行，直到被中断
            while (true) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    public boolean isPortReady()
    {
        return portReady;
    }
}