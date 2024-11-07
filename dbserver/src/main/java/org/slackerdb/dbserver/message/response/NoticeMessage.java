package org.slackerdb.dbserver.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;

public class NoticeMessage extends PostgresMessage {
    public NoticeMessage(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) {
        out.write((byte)'N');
    }
}
