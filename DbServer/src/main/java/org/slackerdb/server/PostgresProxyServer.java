package org.slackerdb.server;

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
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.request.*;
import org.slackerdb.utils.Utils;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class PostgresProxyServer {
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private Logger logger = AppLogger.createLogger("PROXY", "INFO", "CONSOLE");

    long readerIdleTime;
    long writerIdleTime;
    long allIdleTime;
    int nioEventThreads;

    private String bind;
    private int port;
    private boolean portReady = false;

    private final Map<String, Boolean> proxyAlias = new HashMap<>();

    private final Map<String, List<PostgresProxyTarget>> proxyTarget = new HashMap<>();

    /**
     * 启动协议处理
     */
    public void start() {
        // Listener thread
        Thread thread = new Thread(() -> {
            try {
                Thread.currentThread().setName("PROXY");
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
        logger.info("[PROXY] Received stop request.");
        logger.info("[PROXY] Server will stop now.");
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
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
            if (logger.getLevel().levelStr.equals("TRACE")) {
                if (obj instanceof ProxyRequest)
                {
                    ProxyRequest proxyRequest = (ProxyRequest) obj;
                    logger.trace("[PROXY][RX CONTENT ]: {},{} {} {}->{}",
                            obj.getClass().getSimpleName(), proxyRequest.encode().length,
                            proxyRequest.getMessageClass(),
                            proxyRequest.getMessageFrom(), proxyRequest.getMessageTo());
                    for (String dumpMessage : Utils.bytesToHexList(proxyRequest.encode())) {
                        logger.trace("[PROXY][RX CONTENT ]: {}", dumpMessage);
                    }
                }
                else {
                    PostgresRequest postgresRequest = (PostgresRequest) obj;
                    logger.trace("[PROXY][RX CONTENT ]: {},{}",
                            obj.getClass().getSimpleName(), postgresRequest.encode().length);
                    for (String dumpMessage : Utils.bytesToHexList(postgresRequest.encode())) {
                        logger.trace("[PROXY][RX CONTENT ]: {}", dumpMessage);
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
                        SSLRequest sslRequest = new SSLRequest(null);
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
                        logger.trace("[PROXY] Invalid startup message from [{}]. Content header: [{}]. Refused.",
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
                // 处理代理转发消息
                ProxyRequest proxyRequest = new ProxyRequest(null);
                proxyRequest.setMessageType(messageType);
                proxyRequest.setMessageFrom(ctx.channel().remoteAddress().toString());
                if (ctx.channel().hasAttr(AttributeKey.valueOf("ForwardTarget"))) {
                    proxyRequest.setMessageTo((String)ctx.channel().attr(AttributeKey.valueOf("ForwardTarget")).get());
                }
                proxyRequest.decode(data);
                pushMsgObject(out, proxyRequest);
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
        // Netty消息处理
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(nioEventThreads);

        // 处理Handler要能够从Server侧拿到代理转发的规则
        PostgresProxyServer postgresProxyServerHandler = this;

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
                            ch.pipeline().addLast(new PostgresProxyServerHandler(postgresProxyServerHandler, logger));
                        }
                    });
            ChannelFuture future =
                    bootstrap.bind(new InetSocketAddress(bind, port)).sync();
            logger.info("[PROXY] Listening on {}:{}", bind, port);
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

    public synchronized void createAlias(String aliasName, boolean checkHeartBeat) throws ServerException
    {
        if (proxyAlias.containsKey(aliasName))
        {
            throw new ServerException(
                    "SLACKERDB-00013",
                    Utils.getMessage("SLACKERDB-00013"));
        }
        proxyAlias.put(aliasName, checkHeartBeat);
        proxyTarget.put(aliasName, new ArrayList<>());
    }

    public synchronized void addAliasTarget(String aliasName, String host, int port, int weight) throws ServerException
    {
        if (!proxyAlias.containsKey(aliasName))
        {
            throw new ServerException(
                    "SLACKERDB-00018",
                    Utils.getMessage("SLACKERDB-00018", aliasName));
        }
        List<PostgresProxyTarget> proxyTargetList = proxyTarget.get(aliasName);
        for (PostgresProxyTarget target : proxyTargetList)
        {
            if (target.getHost().equalsIgnoreCase(host) && target.getPort() == port)
            {
                throw new ServerException(
                        "SLACKERDB-00014",
                        Utils.getMessage("SLACKERDB-00014", aliasName, host, port));
            }
        }
        proxyTargetList.add(new PostgresProxyTarget(host, port, weight));
        proxyTarget.put(aliasName, proxyTargetList);
    }

    public PostgresProxyTarget getAvailableTarget(String aliasName) throws ServerException
    {
        if (!proxyAlias.containsKey(aliasName))
        {
            throw new ServerException("SLACKERDB-00015", Utils.getMessage("SLACKERDB-00015", aliasName));
        }
        if (proxyTarget.get(aliasName).isEmpty())
        {
            throw new ServerException("SLACKERDB-00016", Utils.getMessage("SLACKERDB-00016", aliasName));
        }
        List<PostgresProxyTarget> proxyTargets = proxyTarget.get(aliasName);
        if (proxyTargets.size() == 1)
        {
            // 如果只有一个候选，则返回候选
            PostgresProxyTarget postgresProxyTarget = proxyTargets.get(0);
            if (postgresProxyTarget.getWeight() != 0) {
                return proxyTargets.get(0);
            }
            else
            {
                throw new ServerException("SLACKERDB-00017", Utils.getMessage("SLACKERDB-00017", aliasName));
            }
        }
        // 计算权重，按照权重返回
        int totalWeight = 0;
        for (PostgresProxyTarget proxyTarget : proxyTargets) {
            totalWeight += proxyTarget.getWeight();
        }
        // 生成 1 到 totalWeight 范围的随机数
        int randomValue = new Random().nextInt(totalWeight) + 1;
        // 根据权重分布选择元素
        for (PostgresProxyTarget proxyTarget : proxyTargets) {
            randomValue -= proxyTarget.getWeight();
            if (randomValue <= 0) {
                return proxyTarget;
            }
        }
        throw new ServerException("SLACKERDB-00017", Utils.getMessage("SLACKERDB-00017", aliasName));
    }
}
