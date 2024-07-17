package org.slackerdb.postgres.fsm;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.postgres.dtos.Binding;
import org.slackerdb.postgres.dtos.Parse;
import org.slackerdb.postgres.executor.PostgresExecutor;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;

public class Execute extends PostgresState {
    private static final Logger log = LoggerFactory.getLogger(Execute.class);
    private static int counter = 0;

    public Execute(Class<?>... messages) {
        super(messages);
    }

    @Override
    protected byte getMessageId() {
        return 'E';
    }

    @Override
    protected Iterator<ProtoStep> executeStandardMessage(BBuffer message, NetworkProtoContext protoContext) {
        counter++;
        var postgresContext = (PostgresProtoContext) protoContext;
        var portal = message.getString();
        var maxRecords = message.getInt();
        var bindMessage = (Binding) postgresContext.getValue("PORTAL_" + portal);
        Parse parseMessage = null;
        if (bindMessage == null) {
            bindMessage = new Binding(null, null, new ArrayList<>(), new ArrayList<>());
        }
        parseMessage = (Parse) postgresContext.getValue(bindMessage.getStatement());
        parseMessage.getBinds().remove("PORTAL_" + portal);
        var executor = new PostgresExecutor();


        log.debug("[SERVER][STMTEXEC]: Max:" + maxRecords + " Query:" + parseMessage.getQuery());
        var res = executor.executePortal(
                protoContext, parseMessage, bindMessage, maxRecords,
                bindMessage.isDescribable() || parseMessage.isDescribable(),
                false);
        if (res.isRunNow()) {
            postgresContext.clearSync();
            return res.getReturnMessages();
        } else {
            postgresContext.addSync(res.getReturnMessages());
        }
        return iteratorOfEmpty();
    }


}
