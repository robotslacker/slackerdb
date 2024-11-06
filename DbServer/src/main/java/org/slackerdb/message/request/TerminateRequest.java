package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.server.DBInstance;

import java.sql.SQLException;
import java.time.LocalDateTime;

public class TerminateRequest extends PostgresRequest {
    public TerminateRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request)  {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        // 关闭连接
        try {
            this.dbInstance.closeSession(getCurrentSessionId(ctx));
        } catch (SQLException e) {
            this.dbInstance.logger.error("[SERVER] Error closing session", e);
        }
    }
}
