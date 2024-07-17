package org.slackerdb.proxy;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.events.BaseEvent;
import org.slackerdb.protocol.events.BytesEvent;
import org.slackerdb.protocol.messages.NetworkReturnMessage;
import org.slackerdb.protocol.messages.ProtoStep;
import org.slackerdb.protocol.messages.ReturnMessage;
import org.slackerdb.protocol.states.ProtoState;
import org.slackerdb.utils.JsonMapper;
import org.slackerdb.utils.Sleeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public abstract class NetworkProxySocket {
    protected static final JsonMapper mapper = new JsonMapper();
    private static final Logger log = LoggerFactory.getLogger(NetworkProxySocket.class);

    protected final ConcurrentLinkedDeque<BytesEvent> inputQueue = new ConcurrentLinkedDeque<>();
    private final AsynchronousSocketChannel channel;
    private final NetworkProtoContext context;
    private final Semaphore semaphore = new Semaphore(1);
    private final Semaphore readSemaphore = new Semaphore(1);
    private final List<BytesEvent> received = new ArrayList<>();

    public NetworkProxySocket(NetworkProtoContext context, InetSocketAddress inetSocketAddress, AsynchronousChannelGroup group) {
        this.context = context;
        try {
            channel = AsynchronousSocketChannel.open(group);
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);


            BBuffer tempBuffer = context.buildBuffer();
            channel.connect(inetSocketAddress, channel, new CompletionHandler<>() {
                @Override
                public void completed(Void result, AsynchronousSocketChannel channel) {
                    //start to read message
                    final ByteBuffer buffer = ByteBuffer.allocate(4096);

                    channel.read(buffer, 30000, TimeUnit.MILLISECONDS, buffer, new CompletionHandler<>() {
                        //FLW01 RECEIVING DATA
                        @Override
                        public void completed(Integer result, ByteBuffer attachment) {
                            context.setActive();
                            try (final MDC.MDCCloseable mdc = MDC.putCloseable("connection", context.getContextId() + "")) {
                                Iterator<ProtoStep> stepsToInvoke = null;
                                ProtoState possible = null;
                                //message is read from server
                                attachment.flip();
                                if (result != -1 || attachment.remaining() > 0) {
                                    //FLW02 RETRIEVE THE DATA
                                    var byteArray = new byte[attachment.remaining()];
                                    attachment.get(byteArray);
                                    var bb = new BBuffer();
                                    bb.write(byteArray);

                                    try {
                                        semaphore.acquire();
                                        tempBuffer.setPosition(tempBuffer.size());
                                        //FLW03 APPEND TO EXISTING BUFFER
                                        tempBuffer.write(byteArray);
                                        tempBuffer.setPosition(0);

                                        log.trace("[PROXY ][RX] Bytes: " + byteArray.length);
                                        //FLW04 GENERICFRAME AN EXPECTED RESPONSE
                                        var gf = getStateToRetrieveOneSingleMessage();
                                        //FLW05 BYTESEVENT from tmpBuffer (response specific to this flow)

                                        var be = new BytesEvent(context, null, tempBuffer);
                                        boolean run = true;
                                        while (run && tempBuffer.size() > 0) {
                                            context.setActive();
                                            run = false;

                                            for (int i = 0; i < availableStates().size(); i++) {
                                                possible = availableStates().get(i);
                                                //Check if can run with a bytes event
                                                if (possible.canRunEvent(be)) {

                                                    stepsToInvoke = possible.executeEvent(be);
                                                    tempBuffer.truncate();
                                                    //FLW08 run the steps (sending back data)
                                                    context.runSteps(stepsToInvoke, possible, be);
                                                    log.debug("[PROXY ][RX][1]: " + possible.getClass().getSimpleName());
                                                    run = true;
                                                    break;
                                                }
                                                if (run) {
                                                    break;
                                                }
                                            }
                                            //FLW11 IF NOTHING FOUND (build a new bytesevent to send back)
                                            if (!run && gf.canRunEvent(be)) {
                                                var event = gf.split(be);
                                                var internalRun = false;
                                                for (var item : buildPossibleEvents(context, event.getBuffer())) {
                                                    for (int i = 0; i < availableStates().size(); i++) {
                                                        possible = availableStates().get(i);
                                                        if (possible.canRunEvent(item)) {

                                                            stepsToInvoke = possible.executeEvent(item);
                                                            tempBuffer.truncate();
                                                            //FLW08 run the steps (sending back data)
                                                            context.runSteps(stepsToInvoke, possible, item);
                                                            log.debug("[PROXY ][RX][5]: " + possible.getClass().getSimpleName());
                                                            internalRun = true;
                                                            break;
                                                        }
                                                    }
                                                    if (internalRun == true) {
                                                        break;
                                                    }
                                                }
                                                //This bytes event is one containing exactly one frame

                                                if (internalRun == false) {
                                                    log.debug("[PROXY ][RX][3]: " + gf.getClass().getSimpleName());
                                                    inputQueue.add(event);
                                                    tempBuffer.truncate();
                                                }

                                                run = true;
                                            }
                                        }
                                        semaphore.release();

                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                }

                                attachment.clear();
                                if (!channel.isOpen()) {
                                    return;
                                }
                                channel.read(buffer, 30000, TimeUnit.MILLISECONDS, buffer, this);
                            }

                        }

                        @Override
                        public void failed(Throwable exc, ByteBuffer buffer) {
                            try (final MDC.MDCCloseable mdc = MDC.putCloseable("connection", context.getContextId() + "")) {
                                log.trace("[PROXY ][RX] Fail to read message from server", exc);
                            }
                        }

                    });
                }

                @Override
                public void failed(Throwable exc, AsynchronousSocketChannel channel) {
                    try (final MDC.MDCCloseable mdc = MDC.putCloseable("connection", context.getContextId() + "")) {
                        log.error("[PROXY ] Fail to connect to server", exc);
                    }
                }

            });

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected abstract NetworkProxySplitterState getStateToRetrieveOneSingleMessage();

    protected abstract List<ProtoState> availableStates();

    public void write(BBuffer buffer) {
        context.setActive();
        buffer.setPosition(0);
        channel.write(ByteBuffer.wrap(buffer.getAll()));
    }

    public void write(ReturnMessage rm, BBuffer buffer) {
        context.setActive();
        var returnMessage = (NetworkReturnMessage) rm;
        buffer.setPosition(0);
        buffer.truncate(0);
        returnMessage.write(buffer);
        write(buffer);
        log.debug("[PROXY ][TX]: " + returnMessage.getClass().getSimpleName());
    }


    public List<ReturnMessage> read(ProtoState protoState, boolean optional) {

        log.debug("[SERVER][EX]: " + protoState.getClass().getSimpleName());
        BaseEvent founded = null;
        try {
            long maxCount = System.currentTimeMillis() + 1000;
            //FLW13 SEEK A SPECIFIC MESSAGE
            while (founded == null && maxCount > System.currentTimeMillis()) {
                context.setActive();
                readSemaphore.acquire();
                //FLW14 EMPTY THE INPUT QUEUE
                while (!inputQueue.isEmpty()) {
                    var toAdd = inputQueue.poll();
                    if (toAdd == null) break;
                    received.add(toAdd);
                }
                //FLW15 GET THE MESSAGE TO RUN
                for (int i = 0; i < received.size(); i++) {
                    BytesEvent fr = received.get(i);
                    var eventsToTry = new ArrayList<BaseEvent>();
                    eventsToTry.add((BaseEvent) new BytesEvent(context, null, fr.getBuffer()));
                    eventsToTry.addAll(buildPossibleEvents(context, fr.getBuffer()));
                    //If can run the proto state
                    for (var evt : eventsToTry) {
                        if (protoState.canRunEvent(evt)) {
                            founded = evt;
                            received.remove(i);
                            break;
                        }
                    }
                    if (founded != null) {
                        break;
                    }

                }
                readSemaphore.release();
                Sleeper.yield();
            }

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        var returnMessage = new ArrayList<ReturnMessage>();
        if (founded == null) {
            throw new RuntimeException("UNABLE TO FIND Contains STILL (" + received.size() + ")");
        } else {
            //FLW16 RUN THE FOUNDED MESSAGE
            Iterator<ProtoStep> it = protoState.executeEvent(founded);
            while (it.hasNext()) {
                returnMessage.add(it.next().run());
            }
        }
        log.debug("[PROXY ][RX]: " + protoState.getClass().getSimpleName());
        return returnMessage;
    }

    protected abstract List<? extends BaseEvent> buildPossibleEvents(NetworkProtoContext context, BBuffer buffer);

    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            log.trace("Ignorable", e);
        }
    }
}
