package org.slackerdb.common.utils;

/**
 * No thread lock wait
 */
@SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
public class Sleeper {
    /**
     * Runs a synchronized based wait mechanism instead of sleep
     *
     * @param timeoutMillis Timeout in ms
     */
    public static void sleep(long timeoutMillis) throws InterruptedException {
        Object obj = new Object();
        synchronized (obj) {
            obj.wait(timeoutMillis);
        }
    }
}
