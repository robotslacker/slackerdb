package org.slackerdb.postgres.fsm;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.postgres.messages.CloseComplete;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.messages.ProtoStep;

import java.util.Iterator;

public class Close extends PostgresState {
    public Close(Class<?>... bytesEventClass) {
        super(bytesEventClass);
    }

    private static void cleanupStuffs(PostgresProtoContext postgresContext, boolean isStatement, String toCloseName) {
        var cacheName = isStatement ? "STATEMENT_" + toCloseName : "PORTAL_" + toCloseName;
        var oldSt = postgresContext.getValue(cacheName);
        if (oldSt != null) {
            //Cleanup
            postgresContext.setValue(cacheName, null);
            postgresContext.setValue(cacheName + "_PARSED", null);
            if (isStatement) {
                if (((org.slackerdb.postgres.dtos.Parse) oldSt).getBinds() != null) {
                    for (var item : ((org.slackerdb.postgres.dtos.Parse) oldSt).getBinds().keySet()) {
                        postgresContext.setValue(item, null);
                    }
                }
            }
        }
    }

    @Override
    protected byte getMessageId() {
        return 'C';
    }

    @Override
    protected Iterator<ProtoStep> executeStandardMessage(BBuffer inputBuffer, NetworkProtoContext protoContext) {
        var postgresContext = (PostgresProtoContext) protoContext;
        var closeId = (char) inputBuffer.get();
        var toCloseName = inputBuffer.getString();
        cleanupStuffs(postgresContext, closeId == 'S', toCloseName);
        return iteratorOfList(
                new CloseComplete()
        );
    }
}
