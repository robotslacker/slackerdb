package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.server.DBInstance;

import java.nio.charset.StandardCharsets;

public class DescribeRequest  extends PostgresRequest {

    public DescribeRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    public char describeType;
    public String portalName;

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
        portalName = new String(data, StandardCharsets.UTF_8);
        describeType = portalName.charAt(0);
        portalName = portalName.substring(1);

        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) {
        // 无法做任何处理，因为只有在执行的时候才能决定是否要返回RwoDescription
        // 这里需要标记在后续的ExecuteRequest中要回应这个DescribeRequest请求
        this.dbInstance.getSession(getCurrentSessionId(ctx)).hasDescribeRequest = true;
    }
}
