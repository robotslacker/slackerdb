package org.slackerdb.dbsyncer.engine;

public enum JobState {
    STOPPED,
    STARTING,
    SNAPSHOT,
    STREAMING,
    STOPPING,
    PAUSED,
    FAILED,
    COMPLETED;

    public boolean isRunning() {
        return this == STARTING || this == SNAPSHOT
                || this == STREAMING || this == STOPPING;
    }

    public boolean isTerminal() {
        return this == STOPPED || this == FAILED || this == COMPLETED || this == PAUSED;
    }
}
