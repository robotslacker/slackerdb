package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public abstract class PostgresRequest {
    protected byte[] requestContent;
    protected ByteArrayOutputStream out = new ByteArrayOutputStream();

    public void decode(byte[] data)
    {
        requestContent = data;
    }

    public byte[] encode()
    {
        return requestContent;
    }

    public abstract void process(ChannelHandlerContext ctx, Object request) throws IOException;
}
