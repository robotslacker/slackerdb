package org.slackerdb.dbproxy.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbproxy.message.PostgresMessage;
import org.slackerdb.dbproxy.server.ProxyInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class AdminClientResp extends PostgresMessage {
    private String returnMsg;

    public AdminClientResp(ProxyInstance pProxyInstance) {
        super(pProxyInstance);
    }

    public void setReturnMsg(String s) {
        returnMsg = s;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        out.write((byte) '!');

        byte[] data = returnMsg.getBytes(StandardCharsets.UTF_8);
        out.write(Utils.int32ToBytes(4 + data.length));
        out.write(data);
    }
}