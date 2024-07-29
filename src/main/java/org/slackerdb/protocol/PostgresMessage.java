package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class PostgresMessage {
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
