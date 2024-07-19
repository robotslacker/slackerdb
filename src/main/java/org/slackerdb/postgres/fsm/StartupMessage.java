package org.slackerdb.postgres.fsm;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.buffers.BBufferUtils;
import org.slackerdb.iterators.ProcessId;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.postgres.messages.AuthenticationOk;
import org.slackerdb.postgres.messages.BackendKeyData;
import org.slackerdb.postgres.messages.ParameterStatus;
import org.slackerdb.postgres.messages.ReadyForQuery;
import org.slackerdb.protocol.events.BytesEvent;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slackerdb.protocol.states.ProtoState;
import org.slackerdb.utils.Utils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Map;

public class StartupMessage extends ProtoState {
    public static final byte[] STARTUP_MESSAGE_MARKER = BBufferUtils.toByteArray(0x00, 0x03, 0x00, 0x00);
    private static final int FIXED_SECRET = 5678;

    public StartupMessage(Class<?>... messages) {
        super(messages);
    }

    public boolean canRun(BytesEvent event) {
        var inputBuffer = event.getBuffer();
        if (inputBuffer.size() == 0) return false;
        var hasStartup = inputBuffer.contains(STARTUP_MESSAGE_MARKER, 4);
        var length = inputBuffer.getInt(0);
        return hasStartup && inputBuffer.size() == length;

    }

    public Iterator<ProtoStep> execute(BytesEvent event) {
        var inputBuffer = event.getBuffer();
        var protoContext = event.getContext();

        var postgresContext = (PostgresProtoContext) protoContext;
        var pid = (ProcessId) protoContext.getValue("PG_PID");
        if (pid == null) {
            pid = new ProcessId(postgresContext.getPid());
            protoContext.setValue("PG_PID", pid);
        }
        var pidValue = pid.getPid();
        var length = inputBuffer.getInt(0);
        byte[] parameterBytes = inputBuffer.getBytes(8, inputBuffer.size() - 8);
        inputBuffer.truncate(length);

        // 将Startup中的参数信息分解后送入连接的Context中
        String[] parameterPairList = new String(parameterBytes, StandardCharsets.UTF_8).split("\0");
        for (int i = 0; i < parameterPairList.length - 1; i += 2) {
            String key = parameterPairList[i];
            String value = parameterPairList[i + 1];
            protoContext.setValue(key, value);
        }

        // 返回固定的信息
        return iteratorOfList(
                new AuthenticationOk(),
                new ParameterStatus("server_version", "15"),
                new ParameterStatus("server_type", "JANUS"),
                new ParameterStatus("client_encoding", "UTF8"),
                new ParameterStatus("DateStyle", "ISO, YMD"),
                new ParameterStatus("TimeZone", "CET"),
                new ParameterStatus("is_superuser", "on"),
                new ParameterStatus("integer_datetimes", "on"),
                new BackendKeyData(pidValue, FIXED_SECRET),
                new ReadyForQuery(protoContext.getValue("TRANSACTION", false)));
    }

    public Iterator<ProtoStep> execute(BytesEvent event, ChannelHandlerContext channelHandlerContext) {
        return execute(event);
    }

}
