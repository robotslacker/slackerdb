package org.slackerdb;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.*;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.slackerdb.ProtocolType.SSLRequest;
import static org.slackerdb.ProtocolType.StartupMessage;


public class Protocol {
    public static void processMessage(ChannelHandlerContext ctx, Object obj)
    {
        try {
            PostgresRequest postgresRequest = (PostgresRequest) obj;
            postgresRequest.process(ctx, obj);
        }
        catch (IOException e) {
            AppLogger.logger.error("[SERVER] processMessage error:", e);
        }
    }
}
