package org.slackerdb.dbproxy.message;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbproxy.server.ProxyInstance;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.util.MissingResourceException;
import java.text.MessageFormat;

public abstract class PostgresRequest {
    // 数据库实例
    protected ProxyInstance proxyInstance;

    public  PostgresRequest(ProxyInstance pProxyInstance)
    {
        this.proxyInstance = pProxyInstance;
    }

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
}