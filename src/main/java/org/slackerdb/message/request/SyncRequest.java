package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.message.response.ReadyForQuery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class SyncRequest extends PostgresRequest {
    @Override
    public void decode(byte[] data) {
        super.decode(data);
    }
    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ReadyForQuery readyForQuery = new ReadyForQuery();
        readyForQuery.process(ctx, request, out);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out);

        out.close();
    }
}
