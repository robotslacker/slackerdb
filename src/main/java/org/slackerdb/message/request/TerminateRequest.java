package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.server.DBInstance;

import java.sql.SQLException;
import java.time.LocalDateTime;

public class TerminateRequest extends PostgresRequest {
    @Override
    public void process(ChannelHandlerContext ctx, Object request)  {
        // 记录会话的开始时间，以及业务类型
        DBInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        DBInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        // 关闭连接
        try {
            DBInstance.closeSession(getCurrentSessionId(ctx));
        } catch (SQLException e) {
            AppLogger.logger.error("[SERVER] Error closing session", e);
        }
    }
}
