package org.slackerdb.postgres.fsm;

import org.slackerdb.iterators.IteratorOfLists;
import org.slackerdb.postgres.messages.ErrorResponse;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.descriptor.ProtoDescriptor;
import org.slackerdb.protocol.events.BaseEvent;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slackerdb.protocol.messages.ReturnMessage;
import org.slackerdb.protocol.states.ProtoState;
import org.slackerdb.proxy.ProxyConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class PostgresProtoContext extends NetworkProtoContext {

    private static final Logger log = LoggerFactory.getLogger(PostgresProtoContext.class);
    private static ConcurrentHashMap<Integer, PostgresProtoContext> pids = new ConcurrentHashMap<>();
    private final int pid;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private List<Iterator<ProtoStep>> toSync = new ArrayList<>();

    public PostgresProtoContext(ProtoDescriptor descriptor) {

        super(descriptor);
        pid = getNewPid();
        pids.put(pid, this);
    }

    public static void initializePids() {
        pids = new ConcurrentHashMap<>();
    }

    public static PostgresProtoContext getContextByPid(int pid) {
        return pids.get(pid);
    }

    @Override
    public void disconnect(Object connection) {
        var conn = getValue("CONNECTION");
        var c = ((Connection) ((ProxyConnection) conn).getConnection());
        try {
            if (!c.isValid(1)) {
                c.close();
            }
        } catch (Exception ex) {
            log.trace("Ignorable", ex);
        }
    }

    public int getPid() {
        return pid;
    }

    private int getNewPid() {
        return ProtoDescriptor.getCounter("PID_COUNTER");
    }

    public void addSync(Iterator<ProtoStep> message) {
        toSync.add(message);
    }

    public Iterator<ProtoStep> clearSync() {
        var res = toSync;
        toSync = new ArrayList<>();
        var result = new IteratorOfLists<ProtoStep>();
        for (var it : res) {
            result.addIterator(it);
        }
        return result;
    }

    @Override
    protected List<ReturnMessage> runException(Exception ex, ProtoState state, BaseEvent event) {

        var result = new ArrayList<>(super.runException(ex, state, event));
        log.error(ex.getMessage(), ex);
        result.add(new ErrorResponse(ex.getMessage()));
        return result;
    }

    public void cancel() {
        cancel.set(true);
    }

    public boolean shouldCancel() {
        var result = cancel.get();
        if (result) {
            cancel.set(false);
        }
        return result;
    }
}
