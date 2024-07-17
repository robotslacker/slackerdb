package org.slackerdb.postgres.fsm;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.exceptions.AskMoreDataException;
import org.slackerdb.postgres.fsm.events.PostgresPacket;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slackerdb.protocol.states.ProtoState;

import java.util.Iterator;

public abstract class PostgresState extends ProtoState {
    public PostgresState(Class<?>... messages) {
        super(messages);
    }

    protected int getLength(BBuffer inputBuffer) {
        return inputBuffer.getInt(1);
    }


    public boolean canRun(PostgresPacket event) {
        var inputBuffer = event.getBuffer();
        if (inputBuffer.size() < 5 || inputBuffer.get(0) != getMessageId()) {
            return false;
        }
        var length = inputBuffer.getInt(1);
        if (inputBuffer.size() >= length) {
            return true;
        }
        throw new AskMoreDataException();
    }

    protected abstract byte getMessageId();


    public Iterator<ProtoStep> execute(PostgresPacket event) {
        var inputBuffer = event.getBuffer();
        var protoContext = (NetworkProtoContext) event.getContext();
        inputBuffer.setPosition(5);
        var res = executeStandardMessage(inputBuffer, protoContext);
        var length = inputBuffer.getInt(1);
        inputBuffer.truncate(length + 1);
        return res;
    }

    protected abstract Iterator<ProtoStep> executeStandardMessage(BBuffer inputBuffer, NetworkProtoContext protoContext);
}
