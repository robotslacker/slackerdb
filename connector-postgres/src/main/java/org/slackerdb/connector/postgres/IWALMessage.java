package org.slackerdb.connector.postgres;

public interface IWALMessage {
    default void consumeMessage(String t)
    {
        System.out.println("Not implemented.");
    }
}
