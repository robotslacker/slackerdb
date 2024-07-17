package org.slackerdb.postgres.messages;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.messages.NetworkReturnMessage;

public class AuthenticationOk implements NetworkReturnMessage {
    @Override
    public void write(BBuffer resultBuffer) {
        resultBuffer.write((byte) 'R');
        resultBuffer.writeInt(8);
        resultBuffer.writeInt(0);
    }
}
