package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.response.ReadyForQuery;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * CopyFail ('f') —— 客户端在 COPY IN 期间放弃本次导入。
 *
 * <pre>
 *   Byte1('f')
 *   Int32   长度（含自身）
 *   String  客户端给出的失败原因（CString）
 * </pre>
 *
 */
public class CopyFailRequest extends PostgresRequest {
    /** 客户端给出的失败原因（可能为空串）。 */
    private String failReason = "";

    public CopyFailRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    /** 客户端给出的失败原因，便于日志与测试断言。 */
    public String getFailReason() {
        return failReason;
    }

    @Override
    public void decode(byte[] data) {
        if (data != null && data.length > 0) {
            // 消息体是 CString：去掉结尾的 0
            int length = data.length;
            if (data[length - 1] == 0) {
                length--;
            }
            failReason = new String(data, 0, length, StandardCharsets.UTF_8);
        }
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 先算出放弃原因：审计记录要带上同一个原因（与回给客户端的 SQLSTATE 57014 对应）
        String message = (failReason == null || failReason.isEmpty())
                ? "COPY from stdin failed: client aborted the copy"
                : "COPY from stdin failed: " + failReason;

        DBSession session = this.dbInstance.getSession(getCurrentSessionId(ctx));
        if (session != null) {
            session.executingFunction = this.getClass().getSimpleName();

            // 审计收尾：客户端主动放弃也是一次有结果的执行。
            // 必须在 discardUncommittedCopy() 之前调用 —— 那个方法会用更笼统的原因收尾（幂等，先到先得）。
            session.closeCopySqlHistory(0, "57014: " + message);

            // 丢弃本次 COPY 已经写入但尚未提交的部分（幂等：没有进行中的 COPY 时是空操作）
            session.discardUncommittedCopy();

            // 清掉会话上残留的 COPY 状态（含"已判失败"标记），避免影响下一条语句
            session.resetCopyState();

            session.executingFunction = "";
            session.executingTime = null;
        }

        this.dbInstance.logger.info("[SERVER][COPY       ] Client aborted COPY: {}", message);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
        // 57014 = query_canceled：与 PostgreSQL 处理 CopyFail 的做法一致
        errorResponse.setErrorResponse("57014", message);
        errorResponse.setErrorSeverity("ERROR");
        errorResponse.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

        // 必须再回一个 ReadyForQuery 来结束 COPY 子协议。
        //
        // 这一条不能省：驱动放弃 COPY 时有两种路径，其中 cancelCopy() 的收尾循环
        // （QueryExecutorImpl.cancelCopy → processCopyResults，见 :1019-1053）会一直读到
        // "copy 操作被解锁"为止，而解锁条件就是收到 ReadyForQuery。该路径**不会**再发 Sync，
        // 所以只回 ErrorResponse 会让客户端永远等下去。
        // 简单查询路径（processResults 的 'G' 分支）随后发 Sync，会再多收一个 Z —— 多出的
        // ReadyForQuery 被下一次查询消费（等价于一次空回合），不影响协议正确性，已由测试覆盖。
        ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
        readyForQuery.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

        out.close();
    }
}
