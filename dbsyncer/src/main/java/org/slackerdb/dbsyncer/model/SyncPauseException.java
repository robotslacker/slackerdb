package org.slackerdb.dbsyncer.model;

import java.io.Serial;

/** 作业进入 PAUSED，而不是 FAILED/重连。 */
public class SyncPauseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SyncPauseException(String message) {
        super(message);
    }
}
