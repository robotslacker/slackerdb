package org.slackerdb.postgres.fsm.events;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.context.ProtoContext;
import org.slackerdb.protocol.events.BaseEvent;

public class PostgresPacket extends BaseEvent {
    private final BBuffer buffer;

    public PostgresPacket(ProtoContext context, Class<?> prevState, BBuffer buffer) {
        super(context, prevState);
        this.buffer = buffer;
    }

    public BBuffer getBuffer() {
        return buffer;
    }
}
