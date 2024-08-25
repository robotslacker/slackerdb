package org.slackerdb.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class BackendKeyData extends PostgresMessage {
    private static final int FIXED_SECRET = 5678;
    // BackendKeyData (B) #
    //   Byte1('K')
    //     Identifies the message as cancellation key data.
    //     The frontend must save these values if it wishes to be able to issue CancelRequest messages later.
    //   Int32(12)
    //     Length of message contents in bytes, including self.
    //   Int32
    //     The process ID of this backend.
    //   Int32
    //     The secret key of this backend.
    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        out.write((byte) 'K');
        out.write(Utils.int32ToBytes(12));

        // 写入会话的ID信息
        out.write(Utils.int32ToBytes(getCurrentSessionId(ctx)));

        // 写入密钥， 这里写入一个固定值
        out.write(Utils.int32ToBytes(FIXED_SECRET));
    }
}
