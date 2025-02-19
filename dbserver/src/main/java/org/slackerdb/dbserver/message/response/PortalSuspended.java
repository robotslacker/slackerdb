package org.slackerdb.dbserver.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class
PortalSuspended  extends PostgresMessage {
    public PortalSuspended(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        //  PortalSuspended (B)
        //    Byte1('s')
        //      Identifies the message as a portal-suspended indicator.
        //      Note this only appears if an Executed message's row-count limit was reached.
        //    Int32(4)
        //      Length of message contents in bytes, including self.
        out.write((byte)'s');
        out.write(Utils.int32ToBytes(4));
    }
}
