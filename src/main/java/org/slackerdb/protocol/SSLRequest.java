package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class SSLRequest extends PostgresRequest {
    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        NoticeMessage noticeMessage = new NoticeMessage();
        noticeMessage.process(ctx, request, out);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, NoticeMessage.class.getSimpleName(), out);
    }
}
