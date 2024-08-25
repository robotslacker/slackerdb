package org.slackerdb.message;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class PostgresMessage {
    public  int getCurrentSessionId(ChannelHandlerContext ctx)
    {
        if (ctx.channel().hasAttr(AttributeKey.valueOf("SessionId")))
        {
            return (int) ctx.channel().attr(AttributeKey.valueOf("SessionId")).get();
        }
        else
        {
            return 0;
        }
    }

    public abstract void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out)
            throws IOException;

    public static void writeAndFlush(ChannelHandlerContext ctx,
                                     String messageTag,
                                     ByteArrayOutputStream out)
    {
        byte[] data = out.toByteArray();
        if (AppLogger.logger.getLevel().levelStr.equals("TRACE")) {
            AppLogger.logger.trace("[SERVER][TX CONTENT ]: {},{}", messageTag, data.length);
            for (String dumpMessage : Utils.bytesToHexList(data)) {
                AppLogger.logger.trace("[SERVER][TX CONTENT ]: {}", dumpMessage);
            }
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(data);
        ctx.writeAndFlush(byteBuffer);
        out.reset();
    }
}
