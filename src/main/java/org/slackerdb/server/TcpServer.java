package org.slackerdb.server;

import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.descriptor.NetworkProtoDescriptor;
import org.slackerdb.protocol.events.BytesEvent;
import org.slackerdb.utils.Sleeper;
import org.slackerdb.utils.Utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multithreaded asynchronous server
 */
public class TcpServer {

    /**
     * Default host
     */
    private final NetworkProtoDescriptor protoDescriptor;

    /**
     * Listener thread
     */
    private Thread thread;

    /**
     * Listener socket
     */
    private AsynchronousServerSocketChannel server;
    private boolean callDurationTimes;

    public TcpServer(NetworkProtoDescriptor protoDescriptor) {
        this.protoDescriptor = protoDescriptor;
    }

    /**
     * Stop the server
     */
    public void stop() {
        try {
            server.close();
            while (server.isOpen()) {
                Sleeper.sleep(200);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            var proxy = protoDescriptor.getProxy();
            if (proxy != null && !proxy.isReplayer()) {
                var storage = protoDescriptor.getProxy().getStorage();
                if (storage != null) {
                    storage.optimize();
                }
            }
        }
    }

    /**
     * Start the server
     */
    public void start() {
        this.thread = new Thread(() -> {
            try {
                run();
            } catch (IOException | ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        this.thread.start();
    }

    private void run() throws IOException, ExecutionException, InterruptedException {
        //Executor for the asynchronous requests
        ExecutorService executor = Executors.newCachedThreadPool();
        AsynchronousChannelGroup group = AsynchronousChannelGroup.withThreadPool(executor);

        try (AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open(group)) {
            this.server = server;
            //Setup buffer and listening
            server.setOption(StandardSocketOptions.SO_RCVBUF, 4096);
            server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            server.bind(new InetSocketAddress(protoDescriptor.getPort()));
            AppLogger.logger.info("[SERVER] Listening on {}:{}",
                    ServerConfiguration.getBindHost(),
                    protoDescriptor.getPort());
            if (ServerConfiguration.getData().isEmpty()) {
                AppLogger.logger.info("[SERVER] Data will saved in MEMORY.");
            }
            else
            {
                AppLogger.logger.info("[SERVER] Data will saved at [{}].", ServerConfiguration.getData());
            }

            //noinspection InfiniteLoopStatement
            while (true) {
                //Accept request
                Future<AsynchronousSocketChannel> future = server.accept();
                try {
                    //Initialize client wrapper
                    var client = new TcpServerChannel(future.get());
                    AppLogger.logger.trace("[SERVER] Accepted connection from {}", client.getRemoteAddress());
                    //Prepare the native buffer
                    ByteBuffer buffer = ByteBuffer.allocate(4096);
                    //Create the execution context
                    var context = (NetworkProtoContext) protoDescriptor.buildContext(client);
                    //Send the greetings
                    if (protoDescriptor.sendImmediateGreeting()) {
                        context.sendGreetings();
                    }
                    context.setValue("ClientAddress", client.getRemoteAddress());
                    //Start reading
                    client.read(buffer, ServerConfiguration.getClientTimeout(), TimeUnit.SECONDS, buffer, new CompletionHandler<>() {
                        @Override
                        public void completed(Integer result, ByteBuffer attachment) {
                            try {
                                attachment.flip();
                                if (result != -1 || attachment.remaining() > 0) {
                                    //If there is something
                                    byte[] byteArray = new byte[attachment.remaining()];
                                    attachment.get(byteArray);

                                    // 打印所有收到的字节内容（16进制）
                                    if (AppLogger.logger.getLevel().levelStr.equals("TRACE")) {
                                        for (String dumpMessage : Utils.bytesToHexList(byteArray)) {
                                            AppLogger.logger.trace("[SERVER][RX CONTENT ]: {}", dumpMessage);
                                        }
                                    }

                                    var bb = context.buildBuffer();
                                    context.setUseCallDurationTimes(callDurationTimes);
                                    bb.write(byteArray);
                                    BytesEvent bytesEvent = new BytesEvent(context, null, bb);

                                    //Generate a BytesEvent and send it
                                    context.send(bytesEvent);

                                }
                                attachment.clear();
                                if (!client.isOpen()) {
                                    return;
                                }
                                //Restart reading again
                                client.read(attachment, ServerConfiguration.getClientTimeout(), TimeUnit.SECONDS, attachment, this);
                            } catch (Exception ex) {
                                context.handleExceptionInternal(ex);
                                throw ex;
                            }
                        }

                        @Override
                        public void failed(Throwable exc, ByteBuffer attachment) {
                            if (exc instanceof InterruptedByTimeoutException) {
                                AppLogger.logger.trace("Read operation timed out. Closing connection {}.", context.getValue("ClientAddress"));
                            } else {
                                AppLogger.logger.trace("Read operation timed out. Closing connection {} .", context.getValue("ClientAddress"), exc);
                            }
                            try {
                                client.close();
                            } catch (IOException e) {
                                AppLogger.logger.trace("Closing connection failed. ", exc);
                            }
                        }
                    });

                } catch (ExecutionException e) {
                    AppLogger.logger.trace("Execution exception", e);
                }
            }
        }
    }

    public boolean isRunning() {
        if (this.server == null) return false;
        return this.server.isOpen();
    }

    public void useCallDurationTimes(boolean callDurationTimes) {

        this.callDurationTimes = callDurationTimes;
    }
}