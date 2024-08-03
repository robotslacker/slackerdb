package org.slackerdb.protocol.postgres.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.protocol.postgres.message.PostgresRequest;
import org.slackerdb.protocol.postgres.server.PostgresServerHandler;

import java.io.IOException;

public class TerminateRequest extends PostgresRequest {
    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        PostgresServerHandler.sessionClose(ctx);
    }
}
