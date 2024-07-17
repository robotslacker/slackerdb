package org.slackerdb.protocol.states;

import org.slackerdb.protocol.events.BaseEvent;
import org.slackerdb.protocol.messages.ProtoStep;

import java.util.Iterator;

/**
 * Helper interface to manage handled events. It is not used to understand what
 * events can be handled by the state
 *
 * @param <T>
 */
public interface HandlerDefinition<T extends BaseEvent> {
    boolean canRun(T event);

    Iterator<ProtoStep> execute(T event);
}
