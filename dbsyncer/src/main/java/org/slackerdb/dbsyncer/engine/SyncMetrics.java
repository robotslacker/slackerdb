package org.slackerdb.dbsyncer.engine;

import org.slackerdb.dbsyncer.model.ChangeBatch;
import org.slackerdb.dbsyncer.model.ChangeEvent;

import java.util.concurrent.atomic.AtomicLong;

/** 作业计数器；全部为无锁累加，API 只读快照。 */
public final class SyncMetrics {

    public final AtomicLong eventsRead = new AtomicLong();
    public final AtomicLong eventsApplied = new AtomicLong();
    public final AtomicLong inserts = new AtomicLong();
    public final AtomicLong updates = new AtomicLong();
    public final AtomicLong deletes = new AtomicLong();
    public final AtomicLong batches = new AtomicLong();
    public volatile long lastCommitAt;

    public void recordBatch(ChangeBatch batch, long applied) {
        batches.incrementAndGet();
        eventsRead.addAndGet(batch.events().size());
        eventsApplied.addAndGet(applied);
        long insert = 0;
        long update = 0;
        long delete = 0;
        for (ChangeEvent event : batch.events()) {
            switch (event.op()) {
                case INSERT -> insert++;
                case UPDATE -> update++;
                case DELETE -> delete++;
                default -> {
                }
            }
        }
        inserts.addAndGet(insert);
        updates.addAndGet(update);
        deletes.addAndGet(delete);
        lastCommitAt = System.currentTimeMillis();
    }

    public void reset() {
        eventsRead.set(0);
        eventsApplied.set(0);
        inserts.set(0);
        updates.set(0);
        deletes.set(0);
        batches.set(0);
        lastCommitAt = 0;
    }
}
