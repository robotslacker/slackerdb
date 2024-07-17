package org.slackerdb.protocol.events;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.context.ProtoContext;

/**
 * Default "bytes" kind event
 */
public class BytesEvent extends BaseEvent {
    private final BBuffer buffer;

    public BytesEvent(ProtoContext context, Class<?> prevState, BBuffer buffer) {
        super(context, prevState);
        this.buffer = buffer;
    }

    public BBuffer getBuffer() {
        return buffer;
    }
}
