package org.slackerdb.common.utils;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RingBuffer<T> {
    private Object[] buffer;
    private int head = 0;
    private int tail = 0;
    private int size = 0;
    private final int capacity;

    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    public RingBuffer(int capacity) {
        this.buffer = new Object[capacity];
        this.capacity = capacity;
    }

    public int size()
    {
        return this.size;
    }

    public void clear()
    {
        this.head = 0;
        this.tail = 0;
        this.size = 0;
        this.buffer = new Object[this.capacity];

    }
    public void put(T item) {
        lock.lock();
        try {
            while (size == buffer.length) {
                try {
                    notFull.await();
                } catch (InterruptedException ignored) {}
            }
            buffer[tail] = item;
            tail = (tail + 1) % buffer.length;
            size++;
            notEmpty.signal(); // 通知消费者
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        return this.take(Long.MAX_VALUE);
    }

    // timeoutMillis 如果为0，表示不等待
    public T take(long timeoutMillis) throws InterruptedException {
        lock.lock();
        try {
            long nanos;

            // 避免参数过大，导致的timeout变成0的现象
            if (timeoutMillis > (Long.MAX_VALUE / 1_000_000L))
            {
                nanos = Long.MAX_VALUE;
            }
            else
            {
                nanos = timeoutMillis * 1_000_000L; // 将毫秒转换为纳秒
            }
            while (size == 0) {
                if (nanos <= 0) {
                    return null; // 超时返回
                }
                nanos = notEmpty.awaitNanos(nanos); // 等待指定时间
            }
            @SuppressWarnings("unchecked")
            T item = (T) buffer[head];
            buffer[head] = null; // 帮助垃圾回收
            head = (head + 1) % buffer.length;
            size--;
            notFull.signal(); // 通知生产者
            return item;
        } finally {
            lock.unlock();
        }
    }
}
