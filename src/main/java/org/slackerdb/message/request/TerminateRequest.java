package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.server.DBInstance;

import java.sql.SQLException;

public class TerminateRequest extends PostgresRequest {
    @Override
    public void process(ChannelHandlerContext ctx, Object request)  {
        // 关闭连接
        try {
            DBInstance.closeSession(getCurrentSessionId(ctx));
        } catch (SQLException e) {
            AppLogger.logger.error("[SERVER] Error closing session", e);
        }
    }
}
