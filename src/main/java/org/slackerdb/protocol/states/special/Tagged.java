package org.slackerdb.protocol.states.special;

import org.slackerdb.protocol.states.ProtoState;

import java.util.List;

public class Tagged extends SpecialProtoState {

    private final List<String> tags;

    public Tagged(List<String> tags, ProtoState state) {
        super(state);
        this.tags = tags;
    }

    public List<String> getTags() {
        return tags;
    }
}
