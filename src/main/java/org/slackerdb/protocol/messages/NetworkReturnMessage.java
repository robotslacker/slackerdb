package org.slackerdb.protocol.messages;

import org.slackerdb.buffers.BBuffer;

/**
 * Default interface for messages returned by proto step that should be sent
 * through network
 */
public interface NetworkReturnMessage extends ReturnMessage {
    void write(BBuffer resultBuffer);
}
