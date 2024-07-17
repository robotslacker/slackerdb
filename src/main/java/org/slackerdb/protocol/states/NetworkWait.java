package org.slackerdb.protocol.states;

import org.slackerdb.protocol.events.BytesEvent;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slackerdb.utils.Sleeper;

import java.util.Iterator;

/**
 * State to wait for bytes
 */
public class NetworkWait extends ProtoState {
    public NetworkWait(Class<?>... messages) {
        super(messages);
    }

    public boolean canRun(BytesEvent event) {
        return event.getBuffer().size() == 0;
    }

    public Iterator<ProtoStep> execute(BytesEvent event) {
        Sleeper.sleep(10);
        return iteratorOfEmpty();
    }
}
