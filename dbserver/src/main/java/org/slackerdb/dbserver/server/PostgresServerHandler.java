package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.request.SyncRequest;
import org.slackerdb.dbserver.message.request.TerminateRequest;
import org.slackerdb.dbserver.message.response.ErrorResponse;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.LocalDateTime;

public class PostgresServerHandler extends ChannelInboundHandlerAdapter {
    private final DBInstance dbInstance;
    private final Logger logger;

    public PostgresServerHandler(DBInstance pDbInstance, Logger pLogger)
    {
        super();
        logger = pLogger;
        dbInstance = pDbInstance;
    }

    /**
     * 获取远程地址的字符串表示，兼容 TCP (InetSocketAddress) 和 UDS (DomainSocketAddress)
     */
    private String getRemoteAddressString(ChannelHandlerContext ctx) {
        SocketAddress remoteAddress = ctx.channel().remoteAddress();
        if (remoteAddress instanceof InetSocketAddress) {
            return ((InetSocketAddress) remoteAddress).toString();
        } else if (remoteAddress instanceof DomainSocketAddress) {
            return ((DomainSocketAddress) remoteAddress).path();
        } else if (remoteAddress != null) {
            return remoteAddress.toString();
        } else {
            return "unknown";
        }
    }

    /**
     * 安全地获取 SessionId，如果未设置则返回 0
     */
    private int getSessionIdSafe(ChannelHandlerContext ctx) {
        AttributeKey<Integer> sessionKey = AttributeKey.valueOf("SessionId");
        if (ctx.channel().hasAttr(sessionKey)) {
            Integer sessionId = ctx.channel().attr(sessionKey).get();
            return sessionId != null ? sessionId : 0;
        }
        return 0;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception{
        // 获取远端的地址信息（兼容 TCP 和 UDS）
        String remoteAddressStr = getRemoteAddressString(ctx);

        // 创建一个初始会话，并在ctx的信息中进行记录
        DBSession dbSession = new DBSession(dbInstance);
        dbSession.connectedTime = LocalDateTime.now();
        dbSession.status = "connected";
        dbSession.clientAddress = remoteAddressStr;
        // 绑定连接：让 CancelRequest / KILL SESSION 能从别的线程安全地关闭本会话。
        // 必须在 newSession()（把会话发布到 dbSessions）之前完成，保证发布时字段已就绪。
        dbSession.channel = ctx.channel();

        // 将SessionId信息记录到CTX中
        int sessionId = dbInstance.newSession(dbSession);
        ctx.channel().attr(AttributeKey.valueOf("SessionId")).set(sessionId);

        // 注意：这里**不能**再去 Thread.currentThread().setName("Session-" + sessionId)。
        // 启用业务线程组后，channelRegistered 跑在业务线程上，而一个业务线程会被多个连接复用，
        // 重命名会把共享线程的名字改来改去（日志里的 [%thread] 反而更有误导性）。
        // 需要在日志里定位会话时，请直接打印 sessionId。
        logger.trace("[SERVER][PG PROTOCOL]: Accepted connection from {} (session {})", remoteAddressStr, sessionId);

        // 传递消息
        super.channelRegistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception{
        logger.trace("[SERVER][PG PROTOCOL]: Connection has been activated.");
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 提到 try 之外：兜底的 catch 里要靠它把错误应答发出去
        PostgresRequest postgresRequest = null;
        try {
            // 开始处理，标记活跃会话数加一
            dbInstance.activeSessions.incrementAndGet();
            postgresRequest = (PostgresRequest) msg;

            // PG 语义：扩展协议（Parse/Bind/Describe/Execute/Close）出错之后，
            // 后端必须**丢弃**后续前端报文直到 Sync，期间不应答任何东西。
            //
            // 拦截必须放在"处理"这一侧而不是解码器里：Netty 的解码器可能已经把同一个
            // TCP 段里的 Parse/Bind/Execute/Sync 全部解出来排队，只有逐条处理时才能看到
            // "上一条已经失败"这个会话状态。
            int sessionId = getSessionIdSafe(ctx);
            DBSession session = sessionId > 0 ? dbInstance.getSession(sessionId) : null;
            if (session != null && session.isSkipUntilSync()) {
                if (msg instanceof SyncRequest) {
                    // Sync 是同步点：恢复正常处理，由 SyncRequest 自己回 ReadyForQuery
                    session.clearSkipUntilSync();
                } else if (!(msg instanceof TerminateRequest)) {
                    logger.debug("[SERVER][PG PROTOCOL]: Discarding {} (session {} is in error state, waiting for Sync).",
                            msg.getClass().getSimpleName(), sessionId);
                    return;
                }
            }

            postgresRequest.process(ctx, msg);
        } catch (Exception e) {
            logger.error("[SERVER][PG PROTOCOL]: Error processing request", e);
            // 兜底：**绝不静默丢弃请求**。
            //
            // 任何从 process() 逃出来的异常都意味着应答是不完整的（通常只发到了 RowDescription），
            // 原先这里只记日志、什么都不回，客户端就会一直等一条永不到来的报文，
            // 表现为"查询永久挂起"直到空闲超时，且没有任何错误码可查 —— 这是最难排查的一类故障。
            // 这正是 DECIMAL[] 查询挂起的成因：行编码抛的 NumberFormatException 从这里被吞掉。
            //
            // 各请求自身应当先把自己的异常收尾成 ErrorResponse（见 QueryRequest 的
            // catch (SQLException) / catch (RuntimeException)）。能走到这里，说明协议状态机
            // 已经无法判断"该补什么报文"，因此按本项目既定约定统一"回错误 + 关闭连接"：
            // 先尽量让客户端拿到一个明确的错误，再断开，让它立刻失败而不是无限等待。
            try {
                if (postgresRequest != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ErrorResponse errorResponse = new ErrorResponse(dbInstance);
                    errorResponse.setErrorFile("PostgresServerHandler");
                    errorResponse.setErrorResponse("XX000",
                            e.getMessage() == null ? e.toString() : e.getMessage());
                    errorResponse.setErrorSeverity("ERROR");
                    errorResponse.process(ctx, msg, out);
                    PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out,
                            dbInstance.logger);
                    out.close();
                }
            } catch (Exception ignored) {
                // 连错误都发不出去（通道已断等）：下面直接关闭连接即可
            } finally {
                ctx.close();
            }
        }
        finally {
            // 结束处理，标记活跃会话数减一
            dbInstance.activeSessions.decrementAndGet();
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 关闭会话
        int sessionId = getSessionIdSafe(ctx);
        if (sessionId > 0) {
            dbInstance.abortSession(sessionId);
        }

        // 获取远端的地址信息
        logger.trace("[SERVER][PG PROTOCOL]: Connection {} disconnected.", getRemoteAddressString(ctx));

        // 释放资源
        super.channelInactive(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception
    {
        logger.trace("[SERVER][PG PROTOCOL]: Connection channel has been unregistered.");

        // 释放资源
        super.channelUnregistered(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception{
        // 关闭会话
        int sessionId = getSessionIdSafe(ctx);
        if (sessionId > 0) {
            dbInstance.abortSession(sessionId);
        }

        // 获取远端的地址信息
        logger.trace("[SERVER][PG PROTOCOL]: Connection {} error.", getRemoteAddressString(ctx), cause);

        // 释放资源
        super.exceptionCaught(ctx, cause);

        // 清理会话
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 关闭会话
        int sessionId = getSessionIdSafe(ctx);
        if (sessionId > 0) {
            dbInstance.abortSession(sessionId);
        }

        if (evt instanceof IdleStateEvent event) {
            if (event.state() == IdleState.READER_IDLE) {
                logger.trace("[SERVER][PG PROTOCOL]: Connection {} error. Read timeout. ", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.WRITER_IDLE) {
                logger.trace("[SERVER][PG PROTOCOL]: Connection {} error. Write timeout. ", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.ALL_IDLE) {
                logger.trace("[SERVER][PG PROTOCOL]: Connection {} error. All timeout. ", ctx.channel().remoteAddress());
            }

            // 释放资源
            super.userEventTriggered(ctx, evt);

            // 清理会话
            ctx.close();
        }
    }
}
