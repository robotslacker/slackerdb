package org.slackerdb.dbproxy.message;

import ch.qos.logback.classic.Logger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.MissingResourceException;

public abstract class PostgresMessage {
    // 数据库代理实例
    protected ProxyInstance proxyInstance;

    public  PostgresMessage(ProxyInstance proxyInstance)
    {
        this.proxyInstance = proxyInstance;
    }

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

    public String getMessage(String code, Object... contents) {
        StringBuilder content;
        String pattern;
        try {
            pattern = this.proxyInstance.resourceBundle.getString(code);
            content = new StringBuilder(MessageFormat.format(pattern, contents));
        } catch (MissingResourceException me)
        {
            content = new StringBuilder("MSG-" + code + ":");
            for (Object object : contents) {
                if (object != null) {
                    content.append(object).append("|");
                }
                else {
                    content.append("null|");
                }
            }
        }
        return content.toString();
    }

    public static void writeAndFlush(ChannelHandlerContext ctx,
                                     String messageTag,
                                     ByteArrayOutputStream out,
                                     Logger logger)
    {
        byte[] data = out.toByteArray();
        if (logger.getLevel().levelStr.equals("TRACE")) {
            logger.trace("[PROXY][TX CONTENT ]: {},{}", messageTag, data.length);
            for (String dumpMessage : Utils.bytesToHexList(data)) {
                logger.trace("[PROXY][TX CONTENT ]: {}", dumpMessage);
            }
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(data);
        ctx.writeAndFlush(byteBuffer);
        out.reset();
    }
}
