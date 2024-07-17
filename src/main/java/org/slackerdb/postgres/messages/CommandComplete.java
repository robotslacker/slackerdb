package org.slackerdb.postgres.messages;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.messages.NetworkReturnMessage;

import java.nio.charset.StandardCharsets;

public class CommandComplete implements NetworkReturnMessage {

    private final String tag;

    public CommandComplete(String tag) {

        this.tag = tag;
    }

    @Override
    public void write(BBuffer buffer) {
        var length = 4 + tag.length() + 1;
        buffer.write((byte) 'C');
        buffer.writeInt(length);
        buffer.write(tag.getBytes(StandardCharsets.US_ASCII));
        buffer.write((byte) 0);
    }
}
