package org.slackerdb.dbserver.server;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global admission gate for user SQL execution.
 *
 * <p>DuckDB runs inside this JVM and every concurrent analytical query allocates its
 * hash tables and sort buffers from the same {@code memory_limit} budget. Unbounded
 * concurrency therefore does not buy throughput: once RSS reaches the limit DuckDB
 * starts spilling to disk, CPU utilisation collapses and every request misses its
 * latency budget. This gate caps the number of queries actually executing and queues
 * the rest, so the process stays inside its memory budget.</p>
 *
 * <p>{@code max <= 0} disables the gate (legacy behaviour, no queueing).</p>
 */
public final class QueryConcurrencyGate {
    /** Default time a request may spend waiting for a free slot before it is rejected. */
    public static final long DEFAULT_WAIT_MS = 30_000L;

    private final int max;
    private final long waitMs;
    private final Semaphore slots;
    private final AtomicInteger waiting = new AtomicInteger();

    public QueryConcurrencyGate(int max) {
        this(max, DEFAULT_WAIT_MS);
    }

    public QueryConcurrencyGate(int max, long waitMs) {
        this.max = Math.max(0, max);
        this.waitMs = Math.max(0, waitMs);
        this.slots = this.max == 0 ? null : new Semaphore(this.max, true);
    }

    public boolean isEnabled() {
        return slots != null;
    }

    public int getMax() {
        return max;
    }

    /** Upper bound on how long a caller should queue before giving up. */
    public long getWaitMs() {
        return waitMs;
    }

    public int getActive() {
        return slots == null ? 0 : max - slots.availablePermits();
    }

    public int getWaiting() {
        return waiting.get();
    }

    /**
     * Acquire a slot for one query.
     *
     * @param waitMillis maximum time to wait for a free slot
     * @return true when a slot was acquired, or when the gate is disabled
     */
    public boolean tryAcquire(long waitMillis) throws InterruptedException {
        if (slots == null) {
            return true;
        }
        if (slots.tryAcquire()) {
            return true;
        }
        waiting.incrementAndGet();
        try {
            return slots.tryAcquire(Math.max(0, waitMillis), TimeUnit.MILLISECONDS);
        } finally {
            waiting.decrementAndGet();
        }
    }

    public void release() {
        if (slots != null) {
            slots.release();
        }
    }

    /**
     * Execution budget left after queueing for a slot.
     *
     * <p>The caller's {@code maxExecutionTime} is an end-to-end promise, so time spent
     * waiting for admission must be deducted from it. A non-positive result means the
     * request should fail fast instead of running with a meaningless timeout.</p>
     */
    public static long remainingBudgetMs(long budgetMs, long waitedMs) {
        return budgetMs - Math.max(0, waitedMs);
    }
}
