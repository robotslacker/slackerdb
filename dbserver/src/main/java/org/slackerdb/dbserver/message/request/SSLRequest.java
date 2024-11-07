package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.NoticeMessage;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class SSLRequest extends PostgresRequest {
    public static final byte[] SSLRequestHeader = {0x00, 0x00, 0x00, 0x08, 0x04, (byte)0xd2, 0x16, 0x2F};

    public SSLRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        NoticeMessage noticeMessage = new NoticeMessage(this.dbInstance);
        noticeMessage.process(ctx, request, out);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, NoticeMessage.class.getSimpleName(), out, this.dbInstance.logger);
        out.close();
    }
}
