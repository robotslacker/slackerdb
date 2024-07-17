package org.slackerdb.postgres.messages;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.messages.NetworkReturnMessage;

public class BackendKeyData implements NetworkReturnMessage {
    private final int pid;
    private final int secret;

    public BackendKeyData(int pid, int secret) {

        this.pid = pid;
        this.secret = secret;
    }

    @Override
    public void write(BBuffer buffer) {
        buffer.write((byte) 'K');
        buffer.writeInt(12);
        buffer.writeInt(pid);
        buffer.writeInt(secret);
    }
}
