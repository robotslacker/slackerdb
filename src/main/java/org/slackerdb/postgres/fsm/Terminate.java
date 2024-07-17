package org.slackerdb.postgres.fsm;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slackerdb.protocol.states.Stop;

import java.util.Iterator;

public class Terminate extends PostgresState {
    public Terminate(Class<?>... messages) {
        super(messages);
    }

    @Override
    protected byte getMessageId() {

        return 'X';
    }

    @Override
    public Iterator<ProtoStep> executeStandardMessage(BBuffer inputBuffer, NetworkProtoContext protoContext) {

        return iteratorOfRunner(new Stop());
    }
}
