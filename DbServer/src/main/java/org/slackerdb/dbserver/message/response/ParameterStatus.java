package org.slackerdb.dbserver.message.response;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ParameterStatus extends PostgresMessage {
    private String parameterKey;
    private String parameterValue;

    public ParameterStatus(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    // ParameterStatus (B)
    //   Byte1('S')
    //     Identifies the message as a run-time parameter status report.
    //   Int32
    //     Length of message contents in bytes, including self.
    //   String
    //     The name of the run-time parameter being reported.
    //   String
    //     The current value of the parameter.
    public void setKeyValue(String key, String value) {
        parameterKey = key;
        parameterValue = value;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        byte[] keyByte = parameterKey.getBytes(StandardCharsets.UTF_8);
        byte[] valueByte = parameterValue.getBytes(StandardCharsets.UTF_8);

        out.write((byte) 'S');
        out.write(Utils.int32ToBytes(3 + 1 + keyByte.length + 1 + valueByte.length + 1));
        out.write(keyByte);
        out.write((byte) 0);
        out.write(valueByte);
        out.write((byte) 0);
    }
}
