package org.slackerdb.protocol.descriptor;

import org.slackerdb.buffers.BBuffer;
import org.slackerdb.buffers.BBufferEndianness;
import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.context.ProtoContext;
import org.slackerdb.proxy.Proxy;
import org.slackerdb.server.ClientServerChannel;

/**
 * Descriptor for network protocol
 */
public abstract class NetworkProtoDescriptor extends ProtoDescriptor {

    /**
     * Proxy instance
     */
    private Proxy<?> proxyInstance;

    /**
     * Whether should use the proxy
     */
    private boolean proxy;

    /**
     * Initialize all (and the proxy)
     */
    @Override
    public void initialize() {
        if (hasProxy()) {
            proxyInstance.initialize();
        }
        super.initialize();
    }

    /**
     * Default for greetings
     *
     * @return
     */
    public boolean sendImmediateGreeting() {
        return false;
    }

    /**
     * If the protocol is big endian
     *
     * @return
     */
    public abstract boolean isBe();

    /**
     * If the protocol is Little Endian
     *
     * @return
     */
    public boolean isLe() {
        return !isBe();
    }

    /**
     * The default serving port
     *
     * @return
     */
    public abstract int getPort();

    /**
     * Check if has porxy
     *
     * @return
     */
    public boolean hasProxy() {
        return proxy;
    }

    public Proxy getProxy() {
        return this.proxyInstance;
    }

    /**
     * Set the proxy instance
     *
     * @param proxyInstance
     */
    public void setProxy(Proxy proxyInstance) {
        this.proxyInstance = proxyInstance;
        this.proxyInstance.setProtocol(this);
        this.proxy = true;
    }

    /**
     * Create a network context and eventually connect with the proxy
     *
     * @param client
     * @return
     */
    public ProtoContext buildContext(ClientServerChannel client) {
        var context = (NetworkProtoContext) createContext(this);
        context.setClient(client);
        if (hasProxy()) {
            var conn = proxyInstance.connect(context);
            context.setValue("CONNECTION", conn);
            context.setProxy(proxyInstance);
        }
        return context;
    }

    /**
     * Create the buffer
     *
     * @return
     */
    public BBuffer buildBuffer() {
        return new BBuffer(isBe() ? BBufferEndianness.BE : BBufferEndianness.LE);
    }
}
