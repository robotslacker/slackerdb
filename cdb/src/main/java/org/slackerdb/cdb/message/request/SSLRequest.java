package org.slackerdb.cdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.cdb.message.PostgresMessage;
import org.slackerdb.cdb.message.PostgresRequest;
import org.slackerdb.cdb.message.response.NoticeMessage;
import org.slackerdb.cdb.server.CDBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class SSLRequest extends PostgresRequest {
    public static final byte[] SSLRequestHeader = {0x00, 0x00, 0x00, 0x08, 0x04, (byte)0xd2, 0x16, 0x2F};

    public SSLRequest(CDBInstance pCDBInstance) {
        super(pCDBInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        NoticeMessage noticeMessage = new NoticeMessage(this.CDBInstance);
        noticeMessage.process(ctx, request, out);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, NoticeMessage.class.getSimpleName(), out, this.CDBInstance.logger);
        out.close();
    }
}
