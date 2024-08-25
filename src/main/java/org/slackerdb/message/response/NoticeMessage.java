package org.slackerdb.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.message.PostgresMessage;

import java.io.ByteArrayOutputStream;

public class NoticeMessage extends PostgresMessage {
    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) {
        out.write((byte)'N');
    }
}
