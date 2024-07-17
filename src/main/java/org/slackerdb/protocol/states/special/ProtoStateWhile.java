package org.slackerdb.protocol.states.special;

import org.slackerdb.protocol.states.ProtoState;

public class ProtoStateWhile extends SpecialProtoState {

    public ProtoStateWhile(ProtoState... states) {
        super(states);
    }
}
