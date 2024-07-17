package org.slackerdb.postgres.messages;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.messages.NetworkReturnMessage;

public class CloseComplete implements NetworkReturnMessage {
    @Override
    public void write(BBuffer resultBuffer) {
        resultBuffer.write((byte) '3');
        resultBuffer.writeInt(4);
    }
}
