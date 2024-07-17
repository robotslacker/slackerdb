package org.slackerdb.postgres.fsm;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.postgres.dtos.Binding;
import org.slackerdb.postgres.dtos.Parse;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.messages.ProtoStep;

import java.util.Iterator;

public class Describe extends PostgresState {
    public Describe(Class<?>... messages) {
        super(messages);
    }

    @Override
    protected byte getMessageId() {
        return 'D';
    }

    @Override
    protected Iterator<ProtoStep> executeStandardMessage(BBuffer message, NetworkProtoContext protoContext) {
        var postgresContext = (PostgresProtoContext) protoContext;
        var type = message.get();
        var name = message.getString();
        if (type == 'S') {
            var parseMessage = (Parse) postgresContext.getValue("STATEMENT" + name);
            parseMessage.setDescribable(true);
        } else {
            var bindMessage = (Binding) postgresContext.getValue("PORTAL_" + name);
            bindMessage.setDescribable(true);
        }

        return iteratorOfEmpty();
    }
}
