package org.slackerdb.protocol.messages;

/**
 * Runnable producing a single return message
 */
public interface ProtoStep {
    ReturnMessage run();
}
