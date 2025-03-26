package org.slackerdb.dbserver.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class NoDataResp  extends PostgresMessage {
    public NoDataResp(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        out.write((byte)'n');
        out.write(Utils.int32ToBytes(4));
    }
}
