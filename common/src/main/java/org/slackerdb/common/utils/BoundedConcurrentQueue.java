package org.slackerdb.common.utils;

import java.util.concurrent.ConcurrentLinkedQueue;

public class BoundedConcurrentQueue<T> {
    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    private final int capacity;

    public BoundedConcurrentQueue(int capacity) {
        this.capacity = capacity;
    }

    public synchronized boolean offer(T item) {
        if (queue.size() < capacity) {
            return queue.offer(item);
        }
        return false; // 超过容量，拒绝插入
    }

    public T poll() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
