package org.slackerdb.protocol.postgres.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.protocol.postgres.message.PostgresMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class NoticeMessage extends PostgresMessage {
    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) {
        out.write((byte)'N');
    }
}
