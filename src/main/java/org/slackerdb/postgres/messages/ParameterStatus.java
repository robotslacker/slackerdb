package org.slackerdb.postgres.messages;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.messages.NetworkReturnMessage;

import java.nio.charset.StandardCharsets;

public class ParameterStatus implements NetworkReturnMessage {
    private final String key;
    private final String value;

    public ParameterStatus(String key, String value) {

        this.key = key;
        this.value = value;
    }

    @Override
    public void write(BBuffer resultBuffer) {
        var k = key.getBytes(StandardCharsets.UTF_8);
        var v = value.getBytes(StandardCharsets.UTF_8);
        resultBuffer.write((byte) 'S');
        resultBuffer.writeInt(0);
        resultBuffer.write(k);
        resultBuffer.write((byte) 0);
        resultBuffer.write(v);
        resultBuffer.write((byte) 0);

        var length = resultBuffer.size() - 1; //Ignore start
        resultBuffer.writeInt(length, 1);
    }
}
