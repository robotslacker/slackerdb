package org.slackerdb.dbserver.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ParseComplete extends PostgresMessage {

    public ParseComplete(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        // ParseComplete (B)
        //   Byte1('1')
        //     Identifies the message as a Parse-complete indicator.

        //   Int32(4)
        //     Length of message contents in bytes, including self.
        out.write((byte)'1');
        out.write(Utils.int32ToBytes(4));
    }
}
