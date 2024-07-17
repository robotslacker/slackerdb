package org.slackerdb.protocol.states.special;

import org.slackerdb.protocol.states.ProtoState;

public class ProtoStateSwitchCase extends SpecialProtoState {

    public ProtoStateSwitchCase(ProtoState... states) {
        super(states);
    }
}

