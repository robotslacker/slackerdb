package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 未识别的前端报文：回一个明确的错误并关闭连接。
 *
 */
public class UnsupportedMessageRequest extends PostgresRequest {
    private final String description;

    public UnsupportedMessageRequest(DBInstance pDbInstance, String description) {
        super(pDbInstance);
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
        // 08P01 = protocol_violation
        errorResponse.setErrorResponse("08P01", description);
        errorResponse.setErrorSeverity("FATAL");
        errorResponse.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

        out.close();

        // 协议状态机已脱节，关闭连接让客户端得到确定性结果（而不是挂住）
        ctx.close();
    }
}
