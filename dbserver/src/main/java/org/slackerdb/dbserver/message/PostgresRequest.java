package org.slackerdb.dbserver.message;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.MissingResourceException;

public abstract class PostgresRequest {
    // 数据库实例
    protected final DBInstance dbInstance;

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

    public  PostgresRequest(DBInstance pDbInstance)
    {
        this.dbInstance = pDbInstance;
    }

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

    public  int getCurrentSessionId(ChannelHandlerContext ctx)
    {
        AttributeKey<Integer> sessionKey = AttributeKey.valueOf("SessionId");
        if (ctx.channel().hasAttr(sessionKey))
        {
            Integer sessionId = ctx.channel().attr(sessionKey).get();
            return sessionId != null ? sessionId : 0;
        }
        else
        {
            return 0;
        }
    }
}
