package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
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
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbproxy.message.PostgresRequest;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbproxy.message.request.AdminClientRequest;
import org.slackerdb.dbproxy.message.request.CancelRequest;
import org.slackerdb.dbproxy.message.request.ProxyRequest;
import org.slackerdb.dbproxy.message.request.SSLRequest;
import org.slackerdb.dbproxy.message.request.StartupRequest;
import org.slf4j.LoggerFactory;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class PostgresProxyServer {
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private Logger logger;

    long readerIdleTime;
    long writerIdleTime;
    long allIdleTime;
    int nioEventThreads;

    private String bind;
    private int port;
    // volatile：由 "PROXY" 线程写、由 ProxyInstance.start() 的启动线程读（见 waitUntilReady）。
    private volatile boolean portReady = false;
    // 监听器启动失败的原因。非 null 表示线程已经退出，启动方必须立刻失败，而不是傻等 portReady。
    private volatile Exception startupError = null;
    private ProxyInstance proxyInstance;

    public void setProxyInstance(ProxyInstance pProxyInstance)
    {
        this.proxyInstance = pProxyInstance;
    }
    /**
     * 启动协议处理。
     *
     * <p>启动跑在独立的 "PROXY" 线程上（不是调用线程），因此监听器启动失败
     * <b>不会</b>从这里抛出：失败原因记录在 {@link #getStartupError()}，由调用方通过
     * {@link #waitUntilReady(long)} 观察到。</p>
     */
    public void start() {
        // Listener thread
        Thread thread = new Thread(() -> {
            Thread.currentThread().setName("PROXY");
            try {
                run();
            } catch (Exception e) {
                // 不要把异常抛出到线程外（那只会打印一行 "Exception in thread PROXY" 然后什么也不发生）
                startupError = e;
                logger.error("[PROXY] Listener failed to start. Proxy will not accept connections.", e);
            }
        }, "PROXY");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 等待监听器就绪。
     *
     * <p>三种退出方式：就绪（返回，正常）、启动失败（抛 {@link ServerException}）、超时
     * （抛 {@link ServerException}）。<b>任何一条路径都不会无限等待</b>，否则调用方
     * （{@code ProxyInstance.start()}）会永久挂住 —— 端口被占用时旧行为正是如此。</p>
     *
     * @param timeoutMillis 最长等待时间（毫秒），必须 &gt; 0
     */
    public void waitUntilReady(long timeoutMillis) throws ServerException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!portReady) {
            Exception error = startupError;
            if (error != null) {
                throw new ServerException("Listener startup failed: " + error.getMessage(), error);
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new ServerException("Listener startup timed out after [" + timeoutMillis
                        + "]ms. Expected listener on " + bind + ":" + port + ".");
            }
            Thread.sleep(50);
        }
    }


    /**
     * 关闭协议处理
     */
    public void stop()
    {
        logger.info("[PROXY] Received stop request.");
        logger.info("[PROXY] Server will stop now.");
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        logger.info("[PROXY] Server stopped.");
    }

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

    // 自定义解码器，处理原始字节数据
    class RawMessageDecoder extends ByteToMessageDecoder {
        void pushMsgObject(List<Object> out, Object obj)
        {
            // 打印所有收到的字节内容（16进制）
            if (logger.getLevel() != null && logger.getLevel().levelStr.equals("TRACE")) {
                if (obj instanceof ProxyRequest proxyRequest)
                {
                    if (logger.isTraceEnabled()) {
                        logger.trace("[PROXY][RX CONTENT ]: {},{} {} {}->{}",
                                obj.getClass().getSimpleName(), proxyRequest.encode().length,
                                proxyRequest.getMessageClass(),
                                proxyRequest.getMessageFrom(), proxyRequest.getMessageTo());
                    }
                    for (String dumpMessage : Utils.bytesToHexList(proxyRequest.encode()))
                        if (logger.isTraceEnabled()) {
                            logger.trace("[PROXY][RX CONTENT ]: {}", dumpMessage);
                        }
                }
                else {
                    PostgresRequest postgresRequest = (PostgresRequest) obj;
                    if (logger.isTraceEnabled()) {
                        logger.trace("[PROXY][RX CONTENT ]: {},{}",
                                obj.getClass().getSimpleName(), postgresRequest.encode().length);
                    }
                    for (String dumpMessage : Utils.bytesToHexList(postgresRequest.encode())) {
                        if (logger.isTraceEnabled()) {
                            logger.trace("[PROXY][RX CONTENT ]: {}", dumpMessage);
                        }
                    }
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
            // SSLRequest 直接回复
            // StartupRequest 开始转发
            // 其他消息一律不回
            while (in.readableBytes() > 0) {
                // 处理"前导报文"（没有消息类型字节，位于连接最开头）：
                //   SSLRequest / CancelRequest / StartupMessage
                if (lastRequestCommand == null || lastRequestCommand.isEmpty()) {
                    // 等待网络请求发送完毕，SSLRequest
                    if (in.readableBytes() < 8) {
                        return;
                    }

                    // 首先推断为SSLRequest，或者是取消请求
                    data = new byte[8];
                    in.readBytes(data);

                    // 处理消息
                    if (Arrays.equals(data, SSLRequest.SSLRequestHeader))
                    {
                        SSLRequest sslRequest = new SSLRequest(null);
                        sslRequest.decode(data);
                        pushMsgObject(out, sslRequest);

                        // 标记当前步骤
                        lastRequestCommand = SSLRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    else if (Arrays.equals(data, CancelRequest.CancelRequestHeader))
                    {
                        // 标准 CancelRequest（新连接上的首个报文，无消息类型字节）：
                        //   Int32(16) + Int32(80877102) + Int32(pid) + Int32(secret)
                        // 首 8 字节已消费，还差 pid + secret 共 8 字节。
                        // 注意：这里**必须**在"回退指针按 StartupMessage 解析"之前判定，
                        // 否则 16 字节的长度会通过 StartupMessage 的长度校验，
                        // 被当成"缺少 database 的连接请求"而静默丢弃（客户端会一直挂到空闲超时）。
                        if (in.readableBytes() < 8) {
                            in.readerIndex(in.readerIndex() - 8);   // 数据未到齐，回退等待
                            return;
                        }
                        byte[] cancelTail = new byte[8];
                        in.readBytes(cancelTail);

                        CancelRequest cancelRequest = new CancelRequest(proxyInstance);
                        cancelRequest.decodeTail(cancelTail);
                        pushMsgObject(out, cancelRequest);

                        // 标记当前步骤
                        lastRequestCommand = CancelRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);

                        // 取消连接只有这一个报文，处理完即结束
                        return;
                    }
                    else if (Arrays.equals(data, AdminClientRequest.AdminClientRequestHeader))
                    {
                        // 管理握手头：8 字节，没有消息类型字节，后面紧跟 '!' 命令帧。
                        // 绝不能回退去按 StartupMessage 解析 —— 它的长度域是 8，会被当成
                        // "缺少 database 的连接请求"直接拒绝（CLI 的 list/status/create 全部失效）。
                        lastRequestCommand = "ADMIN_HANDSHAKE";
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                        continue;
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

                    // 如果消息体长度超过1024，或者小于0. 明显是一个不合理的消息，直接拒绝
                    if (messageLen <= 0 || messageLen > 1024)
                    {
                        if (logger.isTraceEnabled()) {
                            logger.trace("[PROXY] Invalid startup message from [{}]. Content header: [{}]. Refused.",
                                    ctx.channel().remoteAddress().toString(),
                                    Utils.bytesToHex(data));
                        }
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
                    StartupRequest startupRequest = new StartupRequest(null);
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

                byte messageType = byteBuffer.get();
                int messageLen = byteBuffer.getInt();

                // 等待消息体发送结束, 4字节的字节长度也是消息体长度的一部分
                if (in.readableBytes() < (messageLen - 4)) {
                    in.readerIndex(in.readerIndex() - 5); // 重置读取位置
                    return;
                }
                data = new byte[messageLen - 4];
                in.readBytes(data);

                // 根据指令的头部信息进行处理
                if ((char)messageType == '!')
                {
                    // 特制的管理命令，不能转发下去
                    AdminClientRequest adminClientRequest = new AdminClientRequest(proxyInstance);
                    adminClientRequest.decode(data);

                    // 处理消息
                    pushMsgObject(out, adminClientRequest);

                    // 标记当前步骤
                    lastRequestCommand = AdminClientRequest.class.getSimpleName();
                    ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                }
                else {
                    // 所有其他命令
                    // 处理代理转发消息
                    ProxyRequest proxyRequest = new ProxyRequest(null);
                    proxyRequest.setMessageType(messageType);
                    proxyRequest.setMessageFrom(ctx.channel().remoteAddress().toString());
                    if (ctx.channel().hasAttr(AttributeKey.valueOf("ForwardTarget"))) {
                        proxyRequest.setMessageTo((String) ctx.channel().attr(AttributeKey.valueOf("ForwardTarget")).get());
                    }
                    proxyRequest.decode(data);
                    pushMsgObject(out, proxyRequest);
                }
                break;
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
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new IdleStateHandler(readerIdleTime, writerIdleTime, allIdleTime,TimeUnit.SECONDS));
                            // 定义消息处理
                            ch.pipeline().addLast(new RawMessageDecoder());
                            ch.pipeline().addLast(new RawMessageEncoder());
                            ch.pipeline().addLast(new PostgresProxyServerHandler(logger, proxyInstance));
                        }
                    });
            ChannelFuture future;
            try {
                future = bootstrap.bind(new InetSocketAddress(bind, port)).sync();
            } catch (Exception bindFailure) {
                // 最常见的一类启动失败：端口已被占用。SO_REUSEADDR 只能复用 TIME_WAIT。
                // Netty 会把 BindException 包成 ChannelException，因此要钻 cause chain 来判断。
                BindException bindException = Utils.findCause(bindFailure, BindException.class);
                if (bindException != null) {
                    throw new ServerException("Cannot bind TCP listener to " + bind + ":" + port
                            + ", the address is already in use. "
                            + "Stop the process listening on it or start the proxy on another port.",
                            bindException);
                }
                throw bindFailure;
            }
            logger.info("[PROXY] Listening on {}:{}", bind, port);
            portReady = true;
            future.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    public boolean isPortReady()
    {
        return portReady;
    }

    /**
     * 监听器启动失败的原因，未失败时为 {@code null}。
     * 非 null 即代表监听线程已经退出，代理不会再接受任何连接。
     */
    public Exception getStartupError()
    {
        return startupError;
    }
}
