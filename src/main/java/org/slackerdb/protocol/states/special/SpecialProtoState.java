package org.slackerdb.protocol.states.special;

import org.slackerdb.protocol.events.BaseEvent;
import org.slackerdb.protocol.states.ProtoState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public abstract class SpecialProtoState extends ProtoState {
    protected final List<ProtoState> children;

    public SpecialProtoState(ProtoState... states) {
        this.children = new ArrayList<>(Arrays.asList(states));
    }

    public List<ProtoState> getChildren() {
        return children;
    }

    public boolean canHandle(BaseEvent event) {
        var result = false;
        for (var child : children) {
            if (child.canHandle(event.getClass())) {
                result = true;
                break;
            }
        }
        return result;
    }

    public boolean canRun(BaseEvent event) {
        return true;
    }

    @Override
    public String toString() {
        return super.toString() + " [ " + children.stream().map(a -> a.getClass().getSimpleName()).collect(Collectors.joining(",")) + "]";
    }
}
