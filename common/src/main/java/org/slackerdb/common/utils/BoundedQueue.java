package org.slackerdb.common.utils;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * 无锁、线程安全、有最大容量限制的FIFO队列
 * @param <T> 队列中元素的类型
 */
public class BoundedQueue<T> {
    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    private final int capacity;

    public BoundedQueue(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void offer(T item) {
        while (true)
        {
            if (queue.size() < capacity)
            {
                queue.offer(item);
                break;
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException ignored) {}
        }
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
