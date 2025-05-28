package org.slackerdb.cdb.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.cdb.message.PostgresMessage;
import org.slackerdb.cdb.server.CDBInstance;

import java.io.ByteArrayOutputStream;

public class NoticeMessage extends PostgresMessage {
    public NoticeMessage(CDBInstance pCDBInstance) {
        super(pCDBInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) {
        out.write((byte)'N');
    }
}
