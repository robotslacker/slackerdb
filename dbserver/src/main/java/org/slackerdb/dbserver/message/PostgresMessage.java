package org.slackerdb.dbserver.message;

import ch.qos.logback.classic.Logger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.MissingResourceException;

public abstract class PostgresMessage {
    // 数据库实例
    protected final DBInstance dbInstance;


    public  PostgresMessage(DBInstance pDbInstance)
    {
        this.dbInstance = pDbInstance;
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
            pattern = this.dbInstance.resourceBundle.getString(code);
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
        if (logger.getLevel() != null && logger.getLevel().levelStr.equals("TRACE")) {
            logger.trace("[SERVER][TX CONTENT ]: {},{}", messageTag, data.length);
            for (String dumpMessage : Utils.bytesToHexList(data)) {
                logger.trace("[SERVER][TX CONTENT ]: {}", dumpMessage);
            }
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(data);
        ctx.writeAndFlush(byteBuffer);
        out.reset();
    }

    /**
     * 批量发送结果集数据行时的刷出阈值（单位：字节）。
     *
     * <p>结果集行循环里不再逐行 flush，而是累计写入量达到该阈值后再调用一次
     * {@link ChannelHandlerContext#flush()}。这样可以同时避免两个问题：</p>
     * <ul>
     *   <li>逐行 flush：每一行都触发一次 TCP 写系统调用（配合 TCP_NODELAY 就是一行一个网络包）；</li>
     *   <li>完全不 flush：整个结果集都堆在 Netty 出站缓冲区里，大结果集会撑爆内存。</li>
     * </ul>
     */
    public static final int FLUSH_THRESHOLD_BYTES = 64 * 1024;

    /**
     * 仅写入缓冲区但不刷新，用于批量发送多个消息时减少 TCP 系统调用。
     * 调用者需要在合适的时机（累计字节数达到 {@link #FLUSH_THRESHOLD_BYTES} 时，
     * 以及整批消息写完时）手动调用 {@code ctx.flush()}。
     *
     * @return 本次实际写入的字节数，调用者用它累计待刷出的数据量。
     */
    public static int write(ChannelHandlerContext ctx,
                            String messageTag,
                            ByteArrayOutputStream out,
                            Logger logger)
    {
        byte[] data = out.toByteArray();
        if (logger.getLevel() != null && logger.getLevel().levelStr.equals("TRACE")) {
            logger.trace("[SERVER][TX CONTENT ]: {},{}", messageTag, data.length);
            for (String dumpMessage : Utils.bytesToHexList(data)) {
                logger.trace("[SERVER][TX CONTENT ]: {}", dumpMessage);
            }
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(data);
        ctx.write(byteBuffer);
        out.reset();
        return data.length;
    }
}
