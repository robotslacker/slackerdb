package org.slackerdb.postgres.executor;

import org.slackerdb.postgres.dtos.Binding;
import org.slackerdb.postgres.dtos.Parse;
import org.slackerdb.postgres.messages.CommandComplete;
import org.slackerdb.protocol.context.ProtoContext;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slackerdb.protocol.states.ProtoState;

import java.util.Iterator;
import java.util.regex.Pattern;

public class SetHandler implements BasicHandler {
    static final Pattern pattern = Pattern.compile(
            "SET([\\s]+)([a-zA-Z_\\-0-9]+)([\\s]+)=([\\s]+)([^\\s]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean isMatching(String query) {
        return pattern.matcher(query.trim()).matches();
    }

    @Override
    public Iterator<ProtoStep> execute(ProtoContext protoContext, Parse parse, Binding binding, int maxRecords, boolean describable) {
        return ProtoState.iteratorOfList(new CommandComplete("SELECT 0 0"));
    }
}
