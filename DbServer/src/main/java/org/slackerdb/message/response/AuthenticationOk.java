package org.slackerdb.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class AuthenticationOk extends PostgresMessage {
    public AuthenticationOk(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        //  AuthenticationOk (B)
        //    Byte1('R')
        //      Identifies the message as an authentication request.
        //    Int32(8)
        //      Length of message contents in bytes, including self.
        //    Int32(0)
        //      Specifies that the authentication was successful.

        out.write((byte) 'R');
        out.write(Utils.int32ToBytes(8));
        out.write(Utils.int32ToBytes(0));
    }
}
