package org.slackerdb.postgres.fsm;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.iterators.IteratorOfLists;
import org.slackerdb.postgres.dtos.Binding;
import org.slackerdb.postgres.dtos.Parse;
import org.slackerdb.postgres.executor.PostgresExecutor;
import org.slackerdb.postgres.messages.ReadyForQuery;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;

public class Query extends PostgresState {
    private static final Logger log = LoggerFactory.getLogger(Query.class);

    public Query(Class<?>... messages) {
        super(messages);
    }

    @Override
    protected byte getMessageId() {
        return 'Q';
    }

    @Override
    public Iterator<ProtoStep> executeStandardMessage(BBuffer inputBuffer, NetworkProtoContext protoContext) {
        var postgresContext = (PostgresProtoContext) protoContext;
        var query = inputBuffer.getUtf8String();
        var fakePortalStatement = UUID.randomUUID().toString();
        var executor = new PostgresExecutor();

        var bindMessage = new Binding("STATEMENT_" + fakePortalStatement, "PORTAL_" + fakePortalStatement, new ArrayList<>(), new ArrayList<>());
        var parseMessage = new Parse("STATEMENT_" + fakePortalStatement, query, new ArrayList<>(), new ArrayList<>());

        log.debug("[SERVER][QUERY]:" + parseMessage.getQuery());
        var res = executor.executePortal(
                protoContext, parseMessage, bindMessage, Integer.MAX_VALUE,
                true, true);
        var itol = new IteratorOfLists<ProtoStep>();
        itol.addIterator(res.getReturnMessages());
        itol.addIterator(iteratorOfList(new ReadyForQuery(protoContext.getValue("TRANSACTION", false))));
        postgresContext.clearSync();
        return itol;
    }
}
