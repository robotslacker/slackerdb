package org.slackerdb.dbsyncer.engine;

import org.slackerdb.dbsyncer.model.ChangeBatch;
import org.slackerdb.dbsyncer.model.ChangeEvent;
import org.slackerdb.dbsyncer.model.TxPosition;

import java.util.ArrayList;
import java.util.List;

/** 跨多个源事务聚合事件；flush 时把最后一个事务位点一起提交。 */
final class BatchBuffer {

    private final List<ChangeEvent> events = new ArrayList<>();
    private TxPosition position;
    private long bytes;

    synchronized void add(ChangeBatch batch) {
        for (ChangeEvent event : batch.events()) {
            events.add(event);
            bytes += estimate(event);
        }
        position = batch.position();
    }

    /** 丢弃缓冲：写入持续失败并暂停作业时使用，重放交给检查点。 */
    synchronized void clear() {
        events.clear();
        position = null;
        bytes = 0;
    }

    synchronized Pending take() {
        if (events.isEmpty()) {
            return null;
        }
        Pending pending = new Pending(new ArrayList<>(events), position);
        events.clear();
        position = null;
        bytes = 0;
        return pending;
    }

    synchronized int size() {
        return events.size();
    }

    synchronized long sizeBytes() {
        return bytes;
    }

    private static long estimate(ChangeEvent event) {
        long total = 64;
        total += estimate(event.before());
        total += estimate(event.after());
        return total;
    }

    private static long estimate(java.util.Map<String, Object> row) {
        long total = 0;
        for (java.util.Map.Entry<String, Object> entry : row.entrySet()) {
            total += 32L + entry.getKey().length() * 2L;
            Object value = entry.getValue();
            if (value instanceof byte[] bytes) {
                total += bytes.length;
            } else if (value != null) {
                total += String.valueOf(value).length() * 2L;
            }
        }
        return total;
    }

    record Pending(List<ChangeEvent> events, TxPosition position) {
    }
}
