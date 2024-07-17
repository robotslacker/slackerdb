package org.slackerdb.proxy;

import org.slackerdb.protocol.events.BaseEvent;
import org.slackerdb.protocol.events.BytesEvent;

public interface NetworkProxySplitterState {
    BytesEvent split(BytesEvent input);

    boolean canRunEvent(BaseEvent event);
}
