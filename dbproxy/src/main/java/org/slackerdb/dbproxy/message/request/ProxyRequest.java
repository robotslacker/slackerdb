package org.slackerdb.dbproxy.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbproxy.message.PostgresRequest;
import org.slackerdb.dbproxy.server.ProxyInstance;

import java.io.IOException;

public class ProxyRequest  extends PostgresRequest {
    private byte messageType;
    private String messageFrom;
    private String messageTo;

    @Override
    public void decode(byte[] data) {
        super.decode(data);
    }

    public ProxyRequest(ProxyInstance pProxyInstance) {
        super(pProxyInstance);
    }

    public void setMessageType(byte messageType){
        this.messageType = messageType;
    }

    public byte getMessageType()
    {
        return messageType;
    }

    public String getMessageClass() {
        switch ((char) messageType) {
            case 'P':
                return "ParseRequest";
            case 'B':
                return "BindRequest";
            case 'E':
                return "ExecuteRequest";
            case 'S':
                return "SyncRequest";
            case 'D':
                return "DescribeRequest";
            case 'Q':
                return "QueryRequest";
            case 'd':
                return "CopyDataRequest";
            case 'c':
                return "CopyDoneRequest";
            case 'C':
                return "CloseRequest";
            case 'X':
                return "TerminateRequest";
            case 'F':
                return "CancelRequest";
            case '!':
                return "AdminClientRequest";
            default:
                return "Unknown";
        }
    }

    public byte[] getRequestContent()
    {
        return this.requestContent;
    }

    public void setMessageFrom(String messageFrom)
    {
        this.messageFrom = messageFrom;
    }

    public void setMessageTo(String messageTo)
    {
        this.messageTo = messageTo;
    }

    public String getMessageFrom()
    {
        return this.messageFrom;
    }
    public String getMessageTo()
    {
        return this.messageTo;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {

    }
}
