package org.slackerdb.common.utils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全、有最大容量限制的 FIFO 队列。
 *
 * <p>与标准 {@link BlockingQueue} 的关键差异：<b>入队永不阻塞</b>。队列已满时直接丢弃元素并计数。</p>
 *
 * <p>之所以不使用阻塞式入队：本队列的生产者运行在 Netty EventLoop 等关键线程上
 * （SQL/API 历史记录的入队点见 {@code ExecuteRequest}、{@code ParseRequest}、{@code BindRequest}），
 * 而历史记录本身是"可丢失"的审计数据。一旦消费端跟不上、或者消费线程已经异常退出，
 * 阻塞式入队会把业务线程永久挂住，进而导致整个服务停止响应。
 * 丢弃 + 计数则是一种可观测、可告警的降级行为。</p>
 *
 * @param <T> 队列中元素的类型
 */
public class BoundedQueue<T> {
    private final BlockingQueue<T> queue;

    // 累计尝试入队的元素数量
    private final AtomicLong offeredTotal = new AtomicLong(0);
    // 累计因队列已满而被丢弃的元素数量
    private final AtomicLong droppedTotal = new AtomicLong(0);

    public BoundedQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * 非阻塞入队：队列已满时丢弃该元素并累加丢弃计数，绝不阻塞调用线程。
     *
     * @param item 要插入的元素
     * @return {@code true} 表示入队成功；{@code false} 表示队列已满，该元素已被丢弃
     */
    public boolean offer(T item) {
        offeredTotal.incrementAndGet();
        if (queue.offer(item)) {
            return true;
        }
        droppedTotal.incrementAndGet();
        return false;
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

    /**
     * 累计尝试入队的元素数量（含被丢弃的部分）。
     */
    public long getOfferedTotal() {
        return offeredTotal.get();
    }

    /**
     * 累计因队列已满而被丢弃的元素数量。该值持续增长说明消费端已经跟不上生产速度。
     */
    public long getDroppedTotal() {
        return droppedTotal.get();
    }
}
