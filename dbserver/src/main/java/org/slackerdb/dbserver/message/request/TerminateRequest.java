package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.server.DBInstance;

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
            this.dbInstance.logger.trace("[SERVER] Error closing session 1", e);
        } catch (Exception e)
        {
            this.dbInstance.logger.trace("[SERVER] Error closing session 2", e);
        }
    }
}
