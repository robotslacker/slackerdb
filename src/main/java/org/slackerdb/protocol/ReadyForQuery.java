package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ReadyForQuery extends PostgresMessage {
    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        //  ReadyForQuery (B)
        //    Byte1('Z')
        //      Identifies the message type.
        //      ReadyForQuery is sent whenever the backend is ready for a new query cycle.
        //    Int32(5)
        //      Length of message contents in bytes, including self.
        //    Byte1
        //      Current backend transaction status indicator.
        //      Possible values are 'I' if idle (not in a transaction block);
        //      'T' if in a transaction block;
        //      or 'E' if in a failed transaction block (queries will be rejected until block is ended).
        out.write((byte) 'Z');
        out.write(Utils.int32ToBytes(5));

        boolean inTransaction = false;
        if (ctx.channel().hasAttr(AttributeKey.valueOf("TRANSACTION"))) {
            inTransaction = (boolean) ctx.channel().attr(AttributeKey.valueOf("TRANSACTION")).get();
        }
        out.write((byte) (inTransaction ? 'T' : 'I'));
    }
}
