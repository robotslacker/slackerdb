package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.server.DBInstance;

import java.nio.charset.StandardCharsets;

public class DescribeRequest  extends PostgresRequest {

    @Override
    public void decode(byte[] data) {
        //  Describe (F)
        //    Byte1('D')
        //      Identifies the message as a Describe command.
        //    Int32
        //      Length of message contents in bytes, including self.
        //    Byte1
        //      'S' to describe a prepared statement; or 'P' to describe a portal.
        //    String
        //       The name of the prepared statement or portal
        //       to describe (an empty string selects the unnamed prepared statement or portal).

        //        String portalName = new String(data, StandardCharsets.UTF_8);

        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) {
        // 无法做任何处理，因为只有在执行的时候才能决定是否要返回RwoDescription
        // 这里需要标记在后续的ExecuteRequest中要回应这个DescribeRequest请求
        DBInstance.getSession(getCurrentSessionId(ctx)).hasDescribeRequest = true;
    }
}
