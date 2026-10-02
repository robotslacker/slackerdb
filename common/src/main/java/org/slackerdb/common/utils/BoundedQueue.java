package org.slackerdb.common.utils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全、有最大容量限制的 FIFO 队列。
 *
 * <p><b>审计数据任何情况下都不能丢。</b>
 * 因此队列满时 {@link #offer(Object)} <b>阻塞</b>等待消费端腾出空位
 *
 * @param <T> 队列中元素的类型
 */
public class BoundedQueue<T> {
    private final BlockingQueue<T> queue;
    private final int capacity;

    // 累计入队的元素数量
    private final AtomicLong offeredTotal = new AtomicLong(0);
    // 累计"因队列已满而等待空位"的入队次数（背压强度）
    private final AtomicLong blockedTotal = new AtomicLong(0);
    // 累计等待空位的总时长（毫秒）
    private final AtomicLong blockedMillisTotal = new AtomicLong(0);

    public BoundedQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * 阻塞式入队：队列已满时等待消费端腾出空位，<b>绝不丢弃元素</b>。
     *
     * <p>等待期间被 {@link Thread#interrupt()} 打断时不会放弃入队：
     * 记住中断状态、继续等待，入队完成后再还原中断标记，交由调用方处理。</p>
     *
     * @param item 要插入的元素
     * @return 恒为 {@code true}（本队列没有失败/丢弃路径，保留返回值只为调用方书写方便）
     */
    public boolean offer(T item) {
        offeredTotal.incrementAndGet();
        if (queue.offer(item)) {
            return true;
        }

        blockedTotal.incrementAndGet();
        long startNano = System.nanoTime();
        // 先清掉并记住进入时的中断状态，否则 put 会立刻抛出而不是真正等待
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                try {
                    queue.put(item);
                    break;
                }
                catch (InterruptedException e) {
                    // 被中断也不能丢审计：记下来，继续等空位
                    interrupted = true;
                }
            }
        }
        finally {
            blockedMillisTotal.addAndGet((System.nanoTime() - startNano) / 1_000_000L);
            if (interrupted) {
                // 还原中断状态，让调用方仍能感知到"有人要求我停"
                Thread.currentThread().interrupt();
            }
        }
        return true;
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

    /** 队列的最大容量。 */
    public int getCapacity() {
        return capacity;
    }

    /** 还能容纳多少个元素（队列满时为 0）。 */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    /**
     * 累计入队的元素数量。
     */
    public long getOfferedTotal() {
        return offeredTotal.get();
    }

    /**
     * 累计"因队列已满而等待空位"的入队次数。持续增长说明消费端已经跟不上生产速度
     * （此时生产端被背压拖慢，但数据没有丢）。
     */
    public long getBlockedTotal() {
        return blockedTotal.get();
    }

    /**
     * 累计等待空位的总时长（毫秒）。配合 {@link #getBlockedTotal()} 可以看出单次等待的平均时长。
     */
    public long getBlockedMillis() {
        return blockedMillisTotal.get();
    }
}
