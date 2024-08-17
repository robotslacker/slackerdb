package org.slackerdb.protocol.postgres.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.protocol.postgres.message.PostgresMessage;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class CopyInResponse extends PostgresMessage {
    //  CopyInResponse (B)
    //    Byte1('G')
    //      Identifies the message as a Start Copy In response. The frontend must now send copy-in data (if not prepared to do so, send a CopyFail message).
    //    Int32
    //      Length of message contents in bytes, including self.
    //    Int8
    //      0 indicates the overall copy format is textual (rows separated by newlines, columns separated by separator characters, etc.).
    //      1 indicates the overall copy format is binary (similar to DataRow format). See COPY for more information.
    //    Int16
    //      The number of columns in the data to be copied (denoted N below).
    //    Int16[N]
    //      The format codes to be used for each column.
    //      Each must presently be zero (text) or one (binary).
    //      All must be zero if the overall copy format is textual.

    public short copyColumnCount = 0;

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        out.write((byte)'G');

        out.write(Utils.int32ToBytes(4 + 1 + 2 + 2*copyColumnCount));
        out.write((byte)0);
        out.write(Utils.int16ToBytes(copyColumnCount));
        for (int i = 0; i < copyColumnCount; i++) {
            out.write(Utils.int16ToBytes((short)0));
        }
    }
}
