package org.slackerdb.protocol.postgres.message.request;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.protocol.postgres.message.PostgresRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class DescribeRequest  extends PostgresRequest {
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
    private String      portalName = "";

    @Override
    public void decode(byte[] data) {
        portalName = new String(data, StandardCharsets.UTF_8);

        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 无法做任何处理，因为只有在执行的时候才能决定是否要返回RwoDescription
        // 这里需要标记在后续的ExecuteRequest中要回应这个DescribeRequest请求
        ctx.channel().attr(AttributeKey.valueOf("DescribeRequest")).set(Boolean.TRUE);
    }
}
