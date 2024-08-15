package org.slackerdb.protocol.postgres.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.protocol.postgres.message.PostgresRequest;


public class CloseRequest extends PostgresRequest {
    //  Close (F)
    //    Byte1('C')
    //      Identifies the message as a Close command.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    Byte1
    //      'S' to close a prepared statement; or 'P' to close a portal.
    //    String
    //      The name of the prepared statement or portal to close
    //      (an empty string selects the unnamed prepared statement or portal).
    @Override
    public void decode(byte[] data) {
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) {
        // 目前标准JDBC并未实现该协议
    }
}
