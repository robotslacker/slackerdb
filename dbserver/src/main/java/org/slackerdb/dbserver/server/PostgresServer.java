package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Level;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
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
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.request.*;
import org.slackerdb.common.utils.OSUtil;
import org.slackerdb.common.utils.Utils;

import java.io.File;
import java.net.BindException;
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
    // 业务处理线程组：把业务处理从 Netty I/O 线程上摘下来。
    // 恒不为 null —— 业务线程数由配置解析而来，最小也会取到 1（见 resolveBusiness_threads）。
    private EventExecutorGroup businessGroup;

    private Logger logger;
    // volatile：由 "Listener" 线程写、由 DBInstance.start() 的启动线程读（见 waitUntilReady）。
    private volatile boolean portReady = false;
    // 监听器启动失败的原因。非 null 表示 Listener 线程已经退出，DBInstance.start() 必须立刻失败，
    // 而不是傻等 portReady —— 历史上 bind 失败（端口被占用）会让启动线程死循环 1 秒一轮，永远不返回。
    private volatile Exception startupError = null;

    long readerIdleTime;
    long writerIdleTime;
    long allIdleTime;
    int nioEventThreads;
    int businessThreads;

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

    /**
     * 设置业务处理线程数，取值必须 &gt; 0。
     * 配置里的 0 表示"自动跟随 max_connections"，应由
     * {@link org.slackerdb.dbserver.configuration.ServerConfiguration#resolveBusiness_threads()} 解析后再传入。
     */
    public void setBusinessThreads(int pBusinessThreads)
    {
        this.businessThreads = pBusinessThreads;
    }

    /**
     * 把 PostgresServerHandler 挂到业务线程组上。
     *
     * <p>Netty 会为每个 channel 从这个组里固定分配一个 executor，因此
     * <b>同一连接内的消息仍然串行有序</b>（这也是不自己做排队的原因），
     * 而 I/O 线程不再被 JDBC 阻塞。注意副作用：分到同一个 executor 的连接之间仍会互相排队，
     * 被拖累的连接数约为 (连接数 / 业务线程数 - 1)，因此业务线程数应取到与并发会话数同量级。</p>
     *
     * <p><b>断连即取消</b>：这里额外挂一个 closeFuture 监听（跑在 I/O 线程上），
     * 连接一断就取消该会话正在执行的语句 —— 见 {@link #cancelRunningStatementOfDeadSession}。</p>
     */
    private void addBusinessHandler(ChannelPipeline pipeline)
    {
        pipeline.addLast(businessGroup, new PostgresServerHandler(dbInstance, logger));

        // 关键：这个监听器必须挂在 channel 自己的 I/O eventLoop 上（closeFuture 天然如此），
        // **不能**放进 PostgresServerHandler 的任何回调 —— 那些回调跑在业务线程上，
        // 而"客户端被强杀时业务线程正卡在长语句里"恰恰是我们要处理的场景：
        // channelInactive 会被排在长语句后面，等它执行就已经晚了。
        Channel channel = pipeline.channel();
        channel.closeFuture().addListener(future -> cancelRunningStatementOfDeadSession(channel));
    }

    /**
     * 连接断开后立刻取消该会话正在执行的语句。
     *
     * <p><b>为什么必须做</b>：客户端被强杀（或任何非 Terminate 断开）时，服务端只在 I/O 线程上
     * 看到 socket 关闭；清理动作（{@code channelInactive → abortSession → 回滚 + 归还连接}）
     * 却排在业务线程上。若那条业务线程正卡在一条长 SQL 里，清理要等 SQL 自然跑完 ——
     * 期间会话/连接/未提交事务一直悬着（实测 2 亿行 INSERT 约 8 秒，语句越长拖得越久），
     * 占满连接池与业务线程后新客户端连启动握手都没人处理。</p>
     *
     * <p>这里从 I/O 线程调用 {@link DBSession#cancelRunningStatements()}（与 CancelRequest /
     * KILL SESSION 同一条跨线程取消路径），让正在执行的语句尽快抛错退出，业务线程随即执行
     * 既有清理逻辑。**不能**改用"再建一条连接发 CancelRequest"：那条连接的握手同样要占业务线程，
     * 池/线程被占满时取消请求自己就送不进去（实测 79 秒无任何效果）。</p>
     *
     * <p>幂等且允许目标不存在：会话可能早就正常收尾了，此时静默返回。</p>
     */
    private void cancelRunningStatementOfDeadSession(Channel channel)
    {
        AttributeKey<Integer> sessionKey = AttributeKey.valueOf("SessionId");
        if (!channel.hasAttr(sessionKey)) {
            return;
        }
        Integer sessionId = channel.attr(sessionKey).get();
        if (sessionId == null || sessionId <= 0) {
            return;
        }
        DBSession session = dbInstance.getSession(sessionId);
        if (session == null) {
            // 会话已经正常收尾（Terminate / 之前的清理已完成），无需处理
            return;
        }
        logger.debug("[SERVER][PG PROTOCOL]: Connection of session {} is gone. " +
                "Cancelling its running statement so cleanup is not blocked behind it.", sessionId);
        session.cancelRunningStatements();
    }

    public void setDBInstance(DBInstance pDbInstance)
    {
        this.dbInstance = pDbInstance;
    }

    /**
     * 启动协议处理。
     *
     * <p>启动跑在独立的 "Listener" 线程上（不是调用线程），因此监听器启动失败
     * <b>不会</b>从这里抛出：失败原因记录在 {@link #getStartupError()}，由调用方通过
     * {@link #waitUntilReady(long)} 观察到。</p>
     */
    public void start() {
        // Listener thread
        Thread thread = new Thread(() -> {
            Thread.currentThread().setName("Listener");
            try {
                run();
            } catch (Exception e) {
                // 关键：不要把异常抛出到线程外（那只会打印一行 "Exception in thread Listener"
                // 然后什么也不发生）。记录下来，让启动线程能立刻失败并走清理流程。
                startupError = e;
                logger.error("[SERVER] Listener failed to start. Server will not accept connections.", e);
            }
        }, "Listener");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 等待监听器就绪。
     *
     * <p>三种退出方式：就绪（返回，正常）、启动失败（抛 {@link ServerException}）、超时
     * （抛 {@link ServerException}）。<b>任何一条路径都不会无限等待</b>，否则调用方
     * （{@code DBInstance.start()}）会永久挂住 —— 这正是端口被占用时旧行为的表现。</p>
     *
     * @param timeoutMillis 最长等待时间（毫秒），必须 &gt; 0
     */
    @SuppressWarnings("BusyWait")
    public void waitUntilReady(long timeoutMillis) throws ServerException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!portReady) {
            Exception error = startupError;
            if (error != null) {
                // 已经记录过日志了，这里只负责把失败传给调用方
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
        // 先停 I/O 再停业务组：避免"业务组已经在关闭、I/O 还在往里派任务"导致任务被拒绝
        if (businessGroup != null) {
            businessGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        logger.info("[SERVER] Server stopped.");
    }

    // 自定义解码器，处理原始字节数据
    class RawMessageDecoder extends ByteToMessageDecoder {

        /**
         * GSSENCRequest 的 8 字节前导：{@code Int32(8) + Int32(80877104)}。
         *
         * <p>PG 前导报文的三个魔法值：SSLRequest {@code 80877103}、
         * CancelRequest {@code 80877102}、GSSENCRequest {@code 80877104}。</p>
         */
        private static final byte[] GSSENC_REQUEST_HEADER =
                {0x00, 0x00, 0x00, 0x08, 0x04, (byte) 0xD2, 0x16, 0x30};

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
                // 处理"前导报文"（没有消息类型字节，位于连接最开头）：
                //   SSLRequest / CancelRequest / AdminClient 握手 / StartupMessage
                if (lastRequestCommand == null || lastRequestCommand.isEmpty()) {
                    // 等待网络请求发送完毕，SSLRequest
                    if (in.readableBytes() < 8) {
                        return;
                    }

                    // 首先推断为SSLRequest，或者是管理客户端的请求，或者是取消请求
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
                    else if (Arrays.equals(data, GSSENC_REQUEST_HEADER))
                    {
                        // GSSENCRequest：客户端询问能否用 GSSAPI 加密。不支持就回单个字节 'N'，
                        // 客户端随后发普通 StartupMessage（这也是 PG 后端的行为）。
                        //
                        // 必须在这里显式判定：否则这 8 字节会被回退指针当成 StartupMessage 解析，
                        // 结果是客户端收到 3D000 "Database alias does not exist" 并被断连 ——
                        // 一个纯粹的"不支持加密"被报成"库不存在"，排查方向完全跑偏。
                        SSLRequest gssEncReject = new SSLRequest(dbInstance);
                        pushMsgObject(out, gssEncReject);

                        // 与 SSLRequest 同一状态：下一个报文仍按 StartupMessage 解析
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
                    else if (Arrays.equals(data, CancelRequest.CancelRequestHeader))
                    {
                        // 标准 CancelRequest（新连接上的首个报文，无消息类型字节）：
                        //   Int32(16) + Int32(80877102) + Int32(pid) + Int32(secret)
                        // 首 8 字节已消费，还差 pid + secret 共 8 字节。
                        // 注意：这里**必须**在"回退指针按 StartupMessage 解析"之前判定，
                        // 否则 16 字节的长度会通过 StartupMessage 的长度校验，
                        // 被当成缺少 database 参数的启动包而被拒绝——取消功能将完全失效。
                        if (in.readableBytes() < 8) {
                            in.readerIndex(in.readerIndex() - 8);   // 数据未到齐，回退等待
                            return;
                        }
                        byte[] cancelTail = new byte[8];
                        in.readBytes(cancelTail);

                        CancelRequest cancelRequest = new CancelRequest(dbInstance);
                        cancelRequest.decodeTail(cancelTail);
                        pushMsgObject(out, cancelRequest);

                        // 标记当前步骤
                        lastRequestCommand = CancelRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);

                        // 取消连接只有这一个报文，处理完即结束
                        return;
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
                        // FunctionCall（fastpath，已废弃）本服务端不实现。
                        //
                        // 关键区分：'F' 只在**连接首个报文**的位置才是 CancelRequest 的别名；
                        // 带类型字节的 'F' 是 FunctionCall。改造前这里被当成 CancelRequest 处理，
                        // 客户端只看到连接被静默关闭（连错误都收不到），完全无从排查。
                        logger.warn("[SERVER] FunctionCall (fastpath) is not supported; answering 0A000.");
                        pushMsgObject(out, new UnsupportedMessageRequest(dbInstance,
                                "FunctionCall (fastpath) is not supported by SlackerDB", "0A000", false));

                        // 标记当前步骤
                        lastRequestCommand = UnsupportedMessageRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'f' -> {
                        // CopyFail：客户端在 COPY IN 期间放弃本次导入。
                        // 必须回 ErrorResponse（随后由客户端的 Sync 触发 ReadyForQuery），
                        // 否则客户端会一直等一个永不到来的报文。
                        CopyFailRequest copyFailRequest = new CopyFailRequest(dbInstance);
                        copyFailRequest.decode(data);

                        // 处理消息
                        pushMsgObject(out, copyFailRequest);

                        // 标记当前步骤
                        lastRequestCommand = CopyFailRequest.class.getSimpleName();
                        ctx.channel().attr(AttributeKey.valueOf("SessionLastRequestCommand")).set(lastRequestCommand);
                    }
                    case 'H' -> {
                        // Flush：只把已缓冲的输出推出去，不产生任何响应报文，
                        // 也不推进协议阶段（因此不更新 SessionLastRequestCommand）。
                        FlushRequest flushRequest = new FlushRequest(dbInstance);
                        flushRequest.decode(data);
                        pushMsgObject(out, flushRequest);
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
                    default -> {
                        // 未识别的消息类型：绝不静默丢弃。
                        // 静默丢弃会让客户端等一条永不到来的报文，最终挂到空闲超时（默认 600s），
                        // 这是最难排查的一类故障。这里统一"回错误 + 关闭连接"：
                        // 能走到 default 说明客户端与服务端的协议状态机已经脱节，重连是唯一安全的恢复方式。
                        logger.warn("[SERVER] Unsupported message type '{}' (0x{}), closing connection. "
                                        + "The protocol state machine is out of sync.",
                                messageType, Integer.toHexString(messageType));
                        pushMsgObject(out, new UnsupportedMessageRequest(dbInstance,
                                "Unsupported frontend message type '" + messageType + "'"));
                    }
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

        // 业务处理线程组：把 JDBC 调用从 I/O 线程上摘下来。
        // Netty 的线程是首次派发任务时才真正创建，因此实际线程数约等于并发连接数，而非配置值。
        // 另外 Netty 会关闭这些线程上的 FastThreadLocal，无需额外维护。
        businessGroup = new DefaultEventExecutorGroup(businessThreads, new DefaultThreadFactory("pg-biz"));

        try {
            // 启动TCP服务（如果端口不为-1）
            if (port != -1) {
                ServerBootstrap tcpBootstrap = new ServerBootstrap();

                // 开启Netty TCP服务
                tcpBootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        // 这里**不要**用 option(ChannelOption.ALLOCATOR, ...)：option() 只作用于监听 socket，
                        // 对 accept 出来的客户端连接无效（客户端连接的选项必须用 childOption）。
                        // 历史上这里写的是 option(ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)，声称"禁用池化以
                        // 提高内存使用率"，但它既没作用到连接上，对监听 socket 也没有意义（监听 socket 不承载数据缓冲），
                        // 只会让人误以为连接用的是非池化分配器。
                        // 客户端连接实际使用 Netty 默认的 ByteBufAllocator.DEFAULT，在 Netty 4.1 上即池化的
                        // PooledByteBufAllocator —— 对"按 64KB 阈值批量刷出 + 大结果集"的场景这是更合适的选择。
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
                                // 定义消息处理（业务处理挂在业务线程组上）
                                addBusinessHandler(ch.pipeline());
                            }
                        });
                try {
                    var ignored = tcpBootstrap.bind(new InetSocketAddress(bind, port)).sync();
                } catch (Exception bindFailure) {
                    // 最常见的一类启动失败：端口已被占用（另一个 SlackerDB 实例、上一次没退干净的测试
                    // JVM）。SO_REUSEADDR 只能复用 TIME_WAIT，救不了"已有进程正在 LISTEN"。
                    // Netty 会把 BindException 包成 ChannelException，因此这里要钻 cause chain 来判断，
                    // 并给出可操作的错误信息 —— 而不是让启动线程挂死在一段等不到结果的循环里。
                    BindException bindException = Utils.findCause(bindFailure, BindException.class);
                    if (bindException != null) {
                        throw new ServerException("Cannot bind TCP listener to " + bind + ":" + port
                                + ", the address is already in use. "
                                + "Stop the process listening on it or start this instance on another port.",
                                bindException);
                    }
                    throw bindFailure;
                }
                portReady = true;
                logger.info("[SERVER][STARTUP    ] TCP listener started on {}:{}", bind, port);
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
                            // 同 TCP：不在此处设置 ALLOCATOR。option() 只作用于监听 socket，
                            // 客户端连接的分配器由 childOption 决定，未设置时即 Netty 默认的池化分配器。
                            .option(ChannelOption.SO_BACKLOG, 1024)
                            .childHandler(new ChannelInitializer<DomainSocketChannel>() {
                                @Override
                                protected void initChannel(DomainSocketChannel ch) {
                                    // 定义超时处理机制
                                    ch.pipeline().addLast(new IdleStateHandler(readerIdleTime, writerIdleTime, allIdleTime,TimeUnit.SECONDS));
                                    // 定义消息处理
                                    ch.pipeline().addLast(new RawMessageDecoder());
                                    ch.pipeline().addLast(new RawMessageEncoder());
                                    // 定义消息处理（业务处理挂在业务线程组上）
                                    addBusinessHandler(ch.pipeline());
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

    /**
     * 监听器启动失败的原因，未失败时为 {@code null}。
     * 非 null 即代表 Listener 线程已经退出，服务不会再接受任何连接。
     */
    public Exception getStartupError()
    {
        return startupError;
    }
}