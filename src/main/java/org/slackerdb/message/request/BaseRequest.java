package org.slackerdb.message.request;


import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.server.DBInstance;

public class BaseRequest {
    public static void processMessage(ChannelHandlerContext ctx, Object obj)
    {
        synchronized (BaseRequest.class) {
            DBInstance.activeSessions++;
        }
        try {
            PostgresRequest postgresRequest = (PostgresRequest) obj;
            postgresRequest.process(ctx, obj);
        }
        catch (Exception e) {
            AppLogger.logger.error("[SERVER] processMessage error:", e);
        }
        synchronized (BaseRequest.class) {
            DBInstance.activeSessions--;
        }
    }
}
