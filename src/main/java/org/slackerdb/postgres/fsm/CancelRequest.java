package org.slackerdb.postgres.fsm;

import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.events.BytesEvent;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slackerdb.protocol.states.InterruptProtoState;
import org.slackerdb.protocol.states.ProtoState;
import org.slackerdb.protocol.states.Stop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;

public class CancelRequest extends ProtoState implements InterruptProtoState {
    private static final Logger log = LoggerFactory.getLogger(CancelRequest.class);

    public CancelRequest(Class<?>... messages) {
        super(messages);
    }

    public boolean canRun(BytesEvent event) {
        var inputBuffer = event.getBuffer();
        if (inputBuffer.size() != 16) {
            return false;
        }
        var marker = inputBuffer.getInt(4);
        return marker == 80877102;
    }

    public Iterator<ProtoStep> execute(BytesEvent event) {
        var context = (PostgresProtoContext) event.getContext();
        var inputBuffer = event.getBuffer();
        var pid = inputBuffer.getInt(8);
        var secret = inputBuffer.getInt(12);
        var contextToCancel = PostgresProtoContext.getContextByPid(pid);
        if (contextToCancel == null) {
            log.warn("Missing context to cancel: {}", pid);
            return iteratorOfRunner(new Stop());
        }
        contextToCancel.cancel();
        var statement = (Statement) contextToCancel.getValue("EXECUTING_NOW");
        if (statement != null) {
            try {
                AppLogger.logger.error("Cancel ...");
                statement.cancel();
                AppLogger.logger.error("Cancel ...2");

            } catch (Exception e) {
                AppLogger.logger.error("Cancel ...3  " + e.getClass().getSimpleName());

                log.error("Unable to cancel statement {}", pid);
                return iteratorOfRunner(new Stop());
            }
        }

        return iteratorOfRunner(new Stop());
    }

}
