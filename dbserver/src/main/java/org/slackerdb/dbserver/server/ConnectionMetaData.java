package org.slackerdb.dbserver.server;

public class ConnectionMetaData {
    private int     connectionId;
    private long    createdNanoTime;

    public int getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(int connectionId) {
        this.connectionId = connectionId;
    }

    public long getCreatedNanoTime() {
        return createdNanoTime;
    }

    public void setCreatedNanoTime(long createdNanoTime) {
        this.createdNanoTime = createdNanoTime;
    }
}
