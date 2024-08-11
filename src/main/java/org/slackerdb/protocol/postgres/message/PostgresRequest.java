package org.slackerdb.protocol.postgres.message;

import io.netty.channel.ChannelHandlerContext;

import java.io.IOException;

public abstract class PostgresRequest {
    protected byte[] requestContent;

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
