package org.slackerdb.postgres.fsm;

import org.slackerdb.exceptions.AskMoreDataException;
import org.slackerdb.postgres.fsm.events.PostgresPacket;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.events.BytesEvent;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slackerdb.protocol.states.InterruptProtoState;
import org.slackerdb.protocol.states.ProtoState;

import java.util.Iterator;

import static org.slackerdb.postgres.fsm.SSLRequest.SSL_MESSAGE_MARKER;
import static org.slackerdb.postgres.fsm.StartupMessage.STARTUP_MESSAGE_MARKER;

public class PostgresPacketTranslator extends ProtoState implements InterruptProtoState {
    public PostgresPacketTranslator(Class<?>... messages) {
        super(messages);
    }

    public boolean canRun(BytesEvent event) {
        var inputBuffer = event.getBuffer();
        if (inputBuffer.size() < 5) {
            return false;
        }

        if (inputBuffer.size() >= 8) {
            var marker = inputBuffer.getInt(4);
            if (marker == 80877102) return false; //Cancel Message
            if (inputBuffer.contains(SSL_MESSAGE_MARKER, 4)) return false; //SSL Request
            if (inputBuffer.contains(STARTUP_MESSAGE_MARKER, 4)) return false; //Startup Message
        }

        var length = inputBuffer.getInt(1);

        if (inputBuffer.size() >= length) {

            return true;
        }


        throw new AskMoreDataException();
    }


    public Iterator<ProtoStep> execute(BytesEvent event) {
        var inputBuffer = event.getBuffer();
        inputBuffer.setPosition(0);
        var length = inputBuffer.getInt(1);
        var bytes = inputBuffer.getBytes(0, length + 1);
        inputBuffer.truncate(length + 1);
        var bf = ((NetworkProtoContext) event.getContext()).buildBuffer();
        bf.write(bytes);
        bf.setPosition(0);
        var pgPacket = new PostgresPacket(event.getContext(), event.getPrevState(),
                bf);
        event.getContext().send(pgPacket);
        return iteratorOfEmpty();
    }

}
