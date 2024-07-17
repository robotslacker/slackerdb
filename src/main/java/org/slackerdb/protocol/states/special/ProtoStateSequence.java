package org.slackerdb.protocol.states.special;

import org.slackerdb.protocol.states.ProtoState;

public class ProtoStateSequence extends SpecialProtoState {

    public ProtoStateSequence(ProtoState... states) {
        super(states);
    }
}
