package org.slackerdb.protocol.postgres.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.protocol.postgres.message.PostgresMessage;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class CloseComplete extends PostgresMessage {

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        //  CloseComplete (B)
        //    Byte1('3')
        //      Identifies the message as a Close-complete indicator.
        //    Int32(4)
        //      Length of message contents in bytes, including self.
        out.write((byte)'3');
        out.write(Utils.int32ToBytes(4));
    }
}
