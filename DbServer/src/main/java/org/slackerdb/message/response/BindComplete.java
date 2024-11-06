package org.slackerdb.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class BindComplete extends PostgresMessage {
    public BindComplete(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        // BindComplete (B)
        //   Byte1('2')
        //     Identifies the message as a Bind-complete indicator.
        //
        //   Int32(4)
        //     Length of message contents in bytes, including self.
        out.write((byte)'2');
        out.write(Utils.int32ToBytes(4));
    }
}
