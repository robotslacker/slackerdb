package org.slackerdb.dbserver.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ReadyForQuery extends PostgresMessage {
    public ReadyForQuery(DBInstance pDbInstance) {
        super(pDbInstance);
    }

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

        out.write((byte) (this.dbInstance.getSession(getCurrentSessionId(ctx)).inTransaction ? 'T' : 'I'));
    }
}
