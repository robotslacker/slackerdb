package org.slackerdb.protocol.states;

import org.slackerdb.protocol.messages.ProtoStep;
import org.slackerdb.protocol.messages.ReturnMessage;

/**
 * Special stop state (internal)
 */
public class Stop implements ProtoStep {

    @Override
    public ReturnMessage run() {
        return null;
    }
}
