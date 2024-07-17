package org.slackerdb.proxy;

import org.slackerdb.protocol.context.NetworkProtoContext;
import org.slackerdb.protocol.descriptor.NetworkProtoDescriptor;
import org.slackerdb.storage.Storage;

/**
 * Base proxy implementation
 *
 * @param <T>
 */
public abstract class Proxy<T extends Storage> {
    /**
     * Descriptor (of course network like)
     */
    public NetworkProtoDescriptor protocol;
    protected boolean replayer;
    /**
     * (Eventual) storage
     */
    protected T storage;

    public boolean isReplayer() {
        return replayer;
    }

    /**
     * Retrieve the protocol data
     *
     * @return
     */
    public NetworkProtoDescriptor getProtocol() {
        return protocol;
    }

    /**
     * Set the protocol data
     *
     * @param protocol
     */
    public void setProtocol(NetworkProtoDescriptor protocol) {
        this.protocol = protocol;
    }

    /**
     * Implementation specific when connecting to a real server
     *
     * @param context
     * @return
     */
    public abstract ProxyConnection connect(NetworkProtoContext context);

    /**
     * Initialize the proxy
     */
    public abstract void initialize();

    /**
     * Get the storage
     *
     * @return
     */
    public T getStorage() {
        return storage;
    }

    /**
     * Set and initialize the storage
     *
     * @param storage
     */
    public void setStorage(T storage) {
        this.storage = storage;
        this.storage.initialize();
    }
}
