package org.slackerdb.postgres.messages;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.messages.NetworkReturnMessage;

public class BindComplete implements NetworkReturnMessage {
    @Override
    public void write(BBuffer resultBuffer) {
        resultBuffer.write((byte) '2');
        resultBuffer.writeInt(4);
    }
}
