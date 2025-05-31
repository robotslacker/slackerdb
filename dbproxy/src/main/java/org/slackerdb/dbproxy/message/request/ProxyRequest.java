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
        return switch ((char) messageType) {
            case 'P' -> "ParseRequest";
            case 'B' -> "BindRequest";
            case 'E' -> "ExecuteRequest";
            case 'S' -> "SyncRequest";
            case 'D' -> "DescribeRequest";
            case 'Q' -> "QueryRequest";
            case 'd' -> "CopyDataRequest";
            case 'c' -> "CopyDoneRequest";
            case 'C' -> "CloseRequest";
            case 'X' -> "TerminateRequest";
            case 'F' -> "CancelRequest";
            case '!' -> "AdminClientRequest";
            default -> "Unknown";
        };
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
