package org.slackerdb.protocol.postgres.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.protocol.postgres.message.PostgresMessage;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class CopyDone extends PostgresMessage {
    //  CopyDone (F & B)
    //    Byte1('c')
    //      Identifies the message as a COPY-complete indicator.
    //    Int32(4)
    //      Length of message contents in bytes, including self.

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        out.write((byte)'c');
        out.write(Utils.int32ToBytes(4));
    }
}
