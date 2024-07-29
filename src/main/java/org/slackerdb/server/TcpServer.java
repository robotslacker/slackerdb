package org.slackerdb.server;

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
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.*;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.descriptor.NetworkProtoDescriptor;
import org.slackerdb.protocol.events.BytesEvent;
import org.slackerdb.utils.Sleeper;
import org.slackerdb.utils.Utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Multithreaded asynchronous server
 */
public class TcpServer {

    /**
     * Default host
     */
    private final NetworkProtoDescriptor protoDescriptor;

    /**
     * Listener thread
     */
    private Thread thread;

    private boolean systemRunning = false;

    /**
     * Listener socket
     */
    private AsynchronousServerSocketChannel server;

    public TcpServer(NetworkProtoDescriptor protoDescriptor) {
        this.protoDescriptor = protoDescriptor;
    }

    /**
     * Stop the server
     */
    public void stop() {
        try {
            server.close();
            while (server.isOpen()) {
                Sleeper.sleep(200);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            var proxy = protoDescriptor.getProxy();
            if (proxy != null && !proxy.isReplayer()) {
                var storage = protoDescriptor.getProxy().getStorage();
                if (storage != null) {
                    storage.optimize();
                }
            }
        }
    }

    /**
     * Start the server
     */
    public void start() throws ServerException {
        // 根据内存模式何文件模式打印日志信息
        if (ServerConfiguration.getData().isEmpty()) {
            AppLogger.logger.info("[SERVER] Data will saved in MEMORY.");
        }
        else
        {
            AppLogger.logger.info("[SERVER] Data will saved at [{}].", ServerConfiguration.getData());
        }

        // 初始化服务处理程序的后端数据库连接字符串
        TcpServerHandler.setBackendConnectString();

        this.thread = new Thread(() -> {
            try {
                run();
            } catch (IOException | ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        this.thread.start();
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
            AppLogger.logger.info("[NETTY] Received message: " + msg);

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

    private void run() throws IOException, ExecutionException, InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(2);
        EventLoopGroup workerGroup = new NioEventLoopGroup(10);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_RCVBUF, 4096)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws ServerException {
                            // 定义超时处理机制
                            ch.pipeline().addLast(new IdleStateHandler(60, 30, 0, TimeUnit.SECONDS));
                            // 定义消息处理
                            ch.pipeline().addLast(new RawMessageDecoder());
                            ch.pipeline().addLast(new RawMessageEncoder());
                            // 定义消息处理
                            ch.pipeline().addLast(new TcpServerHandler());
                            // 添加日志处理
                            ch.pipeline().addLast(new CustomLogHandler());
                        }
                    });
            ChannelFuture future = bootstrap.bind(new InetSocketAddress(ServerConfiguration.getBindHost(), ServerConfiguration.getPort())).sync();
            AppLogger.logger.info("[SERVER] Listening on {}:{}",
                    ServerConfiguration.getBindHost(),
                    ServerConfiguration.getPort());
            systemRunning = true;
            future.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private void run2() throws IOException, ExecutionException, InterruptedException {
        // 创建异步线程处理
        AsynchronousChannelGroup group = AsynchronousChannelGroup.withThreadPool(Executors.newFixedThreadPool(3));

        AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open(group);
        this.server = server;

        // 开始监听
        server.setOption(StandardSocketOptions.SO_RCVBUF, 4096);
        server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        server.bind(new InetSocketAddress(protoDescriptor.getPort()));
        AppLogger.logger.info("[SERVER] Listening on {}:{}",
                ServerConfiguration.getBindHost(),
                protoDescriptor.getPort());
        systemRunning = true;

        // 无限循环
        while (true) {
            // 接受到请求
            Future<AsynchronousSocketChannel> future = server.accept();
            try {
                // Initialize client wrapper
                var client = new TcpServerChannel(future.get());
                AppLogger.logger.trace("[SERVER] Accepted connection from {}", client.getRemoteAddress());
                // Prepare the native buffer
                ByteBuffer buffer = ByteBuffer.allocate(4096);

                // Create the execution context
                var context = (NetworkProtoContext) protoDescriptor.buildContext(client);

                // Send the greetings
                if (protoDescriptor.sendImmediateGreeting()) {
                    context.sendGreetings();
                }
                context.setValue("ClientAddress", client.getRemoteAddress());

                // Start reading
                client.read(buffer, ServerConfiguration.getClientTimeout(), TimeUnit.SECONDS, buffer, new CompletionHandler<>() {
                    @Override
                    public void completed(Integer result, ByteBuffer attachment) {
                        try {
                            if (result == -1)
                            {
                                AppLogger.logger.trace("Connection has been reset. Closing connection {}.", context.getValue("ClientAddress"));
                                try {
                                    client.close();
                                }
                                catch (IOException ignored) {}
                                return;
                            }

                            attachment.flip();
                            if (attachment.remaining() > 0) {
                                //If there is something
                                byte[] byteArray = new byte[attachment.remaining()];
                                attachment.get(byteArray);

                                // 打印所有收到的字节内容（16进制）
                                if (AppLogger.logger.getLevel().levelStr.equals("TRACE")) {
                                    for (String dumpMessage : Utils.bytesToHexList(byteArray)) {
                                        AppLogger.logger.trace("[SERVER][RX CONTENT ]: {}", dumpMessage);
                                    }
                                }

                                var bb = context.buildBuffer();
                                bb.write(byteArray);
                                BytesEvent bytesEvent = new BytesEvent(context, null, bb);

                                //Generate a BytesEvent and send it
                                context.send(bytesEvent);
                            }
                            attachment.clear();
                            if (!client.isOpen()) {
                                try {
                                    client.close();
                                }
                                catch (IOException e) {
                                    AppLogger.logger.trace("Complete connection failed. ", e);
                                }
                                System.out.println("[SERVER] Connection closed. Client disconnected.");
                                Thread.currentThread().setName("COMPLETED " + Thread.currentThread().getId() + " 222");

                                return;
                            }
                            //Restart reading again
                            client.read(attachment, ServerConfiguration.getClientTimeout(), TimeUnit.SECONDS, attachment, this);
                        } catch (Exception ex) {
                            try {
                                client.close();
                            }
                            catch (IOException e) {
                                AppLogger.logger.trace("Complete connection failed. ", e);
                            }
                            context.handleExceptionInternal(ex);
                            throw ex;
                        }
                    }

                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment) {
                        if (exc instanceof InterruptedByTimeoutException) {
                            AppLogger.logger.trace("Read operation timed out. Closing connection {}.", context.getValue("ClientAddress"));
                        } else {
                            AppLogger.logger.trace("Read operation failed. Closing connection {} .", context.getValue("ClientAddress"), exc);
                        }
                        try {
                            client.close();
                        } catch (IOException e) {
                            AppLogger.logger.trace("Closing connection failed. ", exc);
                        }
                    }
                });

            } catch (Exception e) {
                AppLogger.logger.trace("Execution exception", e);
            }
        }
    }

    public boolean isRunning() {
        return systemRunning;
    }
 }