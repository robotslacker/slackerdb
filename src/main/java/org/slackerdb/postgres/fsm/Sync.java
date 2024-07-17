package org.slackerdb.postgres.fsm;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.postgres.messages.ReadyForQuery;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.messages.ProtoStep;

import java.util.Iterator;

public class Sync extends PostgresState {
    public Sync(Class<?>... messages) {
        super(messages);
    }

    @Override
    protected byte getMessageId() {
        return 'S';
    }

    @Override
    protected Iterator<ProtoStep> executeStandardMessage(BBuffer inputBuffer, NetworkProtoContext protoContext) {

        var postgresContext = (PostgresProtoContext) protoContext;
        postgresContext.addSync(iteratorOfList(new ReadyForQuery(protoContext.getValue("TRANSACTION", false))));
        return postgresContext.clearSync();
    }
}
