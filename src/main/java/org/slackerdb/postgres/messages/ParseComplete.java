package org.slackerdb.postgres.messages;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.messages.NetworkReturnMessage;

public class ParseComplete implements NetworkReturnMessage {
    @Override
    public void write(BBuffer resultBuffer) {
        resultBuffer.write((byte) '1');
        resultBuffer.writeInt(4);
    }
}
