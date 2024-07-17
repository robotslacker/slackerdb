package org.slackerdb.postgres.messages;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.messages.NetworkReturnMessage;

public class NoticeReponse implements NetworkReturnMessage {

    public NoticeReponse() {

    }

    @Override
    public void write(BBuffer resultBuffer) {

        resultBuffer.write((byte) 'N');
    }
}
