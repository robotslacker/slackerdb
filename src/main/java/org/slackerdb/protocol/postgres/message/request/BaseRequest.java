package org.slackerdb.protocol.postgres.message.request;


import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.postgres.message.PostgresRequest;

import java.io.IOException;


public class BaseRequest {
    public static void processMessage(ChannelHandlerContext ctx, Object obj)
    {
        try {
            PostgresRequest postgresRequest = (PostgresRequest) obj;
            postgresRequest.process(ctx, obj);
        }
        catch (IOException e) {
            AppLogger.logger.error("[SERVER] processMessage error:", e);
        }
    }
}
