package org.slackerdb.postgres.executor;

import org.slackerdb.postgres.dtos.Binding;
import org.slackerdb.postgres.dtos.Parse;
import org.slackerdb.protocol.context.ProtoContext;
import org.slackerdb.protocol.messages.ProtoStep;

import java.util.Iterator;

public interface BasicHandler {
    boolean isMatching(String query);

    Iterator<ProtoStep> execute(ProtoContext protoContext, Parse parse, Binding binding, int maxRecords, boolean describable);
}
