package org.slackerdb.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slackerdb.postgres.fsm.*;
import org.slackerdb.postgres.fsm.events.PostgresPacket;
import org.slackerdb.protocol.context.ProtoContext;
import org.slackerdb.protocol.descriptor.NetworkProtoDescriptor;
import org.slackerdb.protocol.descriptor.ProtoDescriptor;
import org.slackerdb.protocol.events.BytesEvent;
import org.slackerdb.protocol.states.special.ProtoStateSequence;
import org.slackerdb.protocol.states.special.ProtoStateSwitchCase;
import org.slackerdb.protocol.states.special.ProtoStateWhile;
import org.slackerdb.sql.jdbc.DataTypesConverter;
import org.slackerdb.sql.parser.SqlStringParser;
import org.slackerdb.utils.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresProtocol extends NetworkProtoDescriptor {
    private static final Logger log = LoggerFactory.getLogger(PostgresProtocol.class);
    private static final int PORT = 5432;
    private static final boolean IS_BIG_ENDIAN = true;
    private static final SqlStringParser parser = new SqlStringParser("$");
    private static DataTypesConverter dataTypesConverter;

    static {
        try {
            var om = new JsonMapper();
            String text = new String(PostgresProtocol.class.getResourceAsStream("/postgresdtt.json")
                    .readAllBytes());
            dataTypesConverter = new DataTypesConverter(om.deserialize(text, new TypeReference<>() {
            }));
        } catch (Exception e) {
            log.trace("Ignorable", e);
        }
    }

    private final int port;

    public PostgresProtocol() {
        this(PORT);
    }

    public PostgresProtocol(int port) {
        this.port = port;
    }

    public static DataTypesConverter getDataTypesConverter() {
        return dataTypesConverter;
    }

    @Override
    public ProtoContext createContext(ProtoDescriptor protoDescriptor) {
        var result = new PostgresProtoContext(this);
        result.setValue("PARSER", parser);
        return result;
    }


    @Override
    protected void initializeProtocol() {
        PostgresProtoContext.initializePids();
        addInterruptState(new CancelRequest(BytesEvent.class));
        addInterruptState(new PostgresPacketTranslator(BytesEvent.class));
        initialize(
                new ProtoStateSequence(
                        new SSLRequest(BytesEvent.class).asOptional(),
                        new StartupMessage(BytesEvent.class),
                        new ProtoStateWhile(
                                new ProtoStateSwitchCase(
                                        new Query(PostgresPacket.class),
                                        new ProtoStateSequence(
                                                new Parse(PostgresPacket.class),
                                                new ProtoStateSequence(
                                                        new Bind(PostgresPacket.class).asOptional(),
                                                        new Describe(PostgresPacket.class).asOptional(),
                                                        new Execute(PostgresPacket.class)
                                                ).asOptional()
                                        ),
                                        new ProtoStateSequence(
                                                new Bind(PostgresPacket.class),
                                                new Execute(PostgresPacket.class)
                                        ),
                                        new Sync(PostgresPacket.class),
                                        new Close(PostgresPacket.class),
                                        new Terminate(PostgresPacket.class)
                                )
                        ),
                        new Terminate(PostgresPacket.class)
                )

        );
    }

    @Override
    public boolean isBe() {
        return IS_BIG_ENDIAN;
    }

    @Override
    public int getPort() {
        return port;
    }
}
