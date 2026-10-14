package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 不支持的（或未识别的）前端报文：回一个明确的错误，可选关闭连接。
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li><b>未识别报文类型</b>（{@link #UnsupportedMessageRequest(DBInstance, String)}）：
 *       协议状态机已脱节，回 {@code 08P01} + {@code FATAL} 并关连接，给客户端确定结果；</li>
 *   <li><b>语法合法但本服务端没实现</b>（例如 fastpath {@code FunctionCall}）：
 *       回普通 {@code ERROR}（不关连接），客户端可以继续用同一连接做别的事。</li>
 * </ul>
 */
public class UnsupportedMessageRequest extends PostgresRequest {
    private final String description;
    private final String sqlState;
    private final boolean fatal;

    public UnsupportedMessageRequest(DBInstance pDbInstance, String description) {
        this(pDbInstance, description, "08P01", true);
    }

    /**
     * @param sqlState SQLSTATE（未实现的功能用 {@code 0A000 feature_not_supported}）
     * @param fatal    {@code true} = 回 FATAL 并关闭连接（协议已脱节时才这么做）
     */
    public UnsupportedMessageRequest(DBInstance pDbInstance, String description,
                                     String sqlState, boolean fatal) {
        super(pDbInstance);
        this.description = description;
        this.sqlState = sqlState;
        this.fatal = fatal;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
        errorResponse.setErrorResponse(sqlState, description);
        errorResponse.setErrorSeverity(fatal ? "FATAL" : "ERROR");
        errorResponse.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

        out.close();

        if (fatal) {
            // 协议状态机已脱节，关闭连接让客户端得到确定性结果（而不是挂住）
            ctx.close();
        }
    }
}
