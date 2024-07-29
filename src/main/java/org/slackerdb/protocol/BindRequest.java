package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.logger.AppLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class BindRequest extends PostgresRequest {
    @Override
    public void decode(byte[] data) {
        super.decode(data);
    }
    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 取出上次解析的SQL，如果为空语句，则直接返回
        String executeSQL = (String)ctx.channel().attr(AttributeKey.valueOf("SQL")).get();
        if (executeSQL.isEmpty()) {
            BindComplete bindComplete = new BindComplete();
            bindComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, BindComplete.class.getSimpleName(), out);

            return;
        }

        BindComplete bindComplete = new BindComplete();
        bindComplete.process(ctx, request, out);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, BindComplete.class.getSimpleName(), out);
    }
}
