package org.slackerdb.common.utils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 线程安全、有最大容量限制的FIFO队列
 * 使用LinkedBlockingQueue实现，提供阻塞插入和非阻塞取出
 * @param <T> 队列中元素的类型
 */
public class BoundedQueue<T> {
    private final BlockingQueue<T> queue;

    public BoundedQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * 将元素插入队列，如果队列已满则阻塞直到有空间可用
     * 如果线程在等待时被中断，会恢复中断状态并继续等待
     * @param item 要插入的元素
     */
    public void offer(T item) {
        while (true) {
            try {
                queue.put(item);
                break; // 成功插入，退出循环
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 恢复中断状态
                // 继续重试，直到成功插入
                // 原来的实现忽略中断并继续等待，这里保持相同行为
            }
        }
    }

    /**
     * 检索并移除队列的头部，如果队列为空则返回null
     * @return 队列的头部元素，如果队列为空则返回null
     */
    public T poll() {
        return queue.poll();
    }

    /**
     * 返回队列中的元素数量
     * @return 队列中的元素数量
     */
    public int size() {
        return queue.size();
    }

    /**
     * 检查队列是否为空
     * @return 如果队列为空则返回true，否则返回false
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
