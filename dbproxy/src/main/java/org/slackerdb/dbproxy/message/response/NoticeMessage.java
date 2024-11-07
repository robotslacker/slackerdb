package org.slackerdb.dbproxy.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbproxy.message.PostgresMessage;
import org.slackerdb.dbproxy.server.ProxyInstance;

import java.io.ByteArrayOutputStream;

public class NoticeMessage extends PostgresMessage {
    public NoticeMessage(ProxyInstance pProxyInstance) {
        super(pProxyInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) {
        out.write((byte)'N');
    }
}
