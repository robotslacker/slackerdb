package org.slackerdb.dbcli.job;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 作业超时调度器：每个 {@link JobManager} 一个 daemon 线程，取代原来"每个带 timeout 的作业一个看门狗"。
 *
 * <p>到点动作只做两件事：在库里记录超时意图（{@link SqliteJobStore#markTimeout(long)}），
 * 然后给本地执行句柄发强制停止信号。到点判定用 {@link System#nanoTime()}，
 * 不受系统时钟调整影响；库里的 {@code started_at_ms + timeout_ms} 只用于展示。</p>
 */
final class JobTimeoutScheduler implements AutoCloseable {

    /** 调度线程名（**不是**作业线程，刻意不共享 {@link JobManager#THREAD_NAME_PREFIX}）。 */
    private static final String THREAD_NAME = "dbcli-job-timeout";

    private final ScheduledThreadPoolExecutor executor;
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private volatile boolean closed;

    JobTimeoutScheduler() {
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        // 取消后的任务立刻从队列移除：长跑脚本里反复启动同名/多个作业时不留下"已取消但还排着"的任务
        this.executor.setRemoveOnCancelPolicy(true);
    }

    /**
     * 给一个作业排一次到点任务（同一作业重复排队时先取消旧的）。
     *
     * @param delayMillis 从现在起多少毫秒到点；超大值夹紧（等价于"没有时限"）
     */
    void schedule(long jobId, long delayMillis, Runnable action) {
        if (closed) {
            return;
        }
        cancel(jobId);
        long delay = delayMillis <= 0L ? 0L : delayMillis;
        ScheduledFuture<?> future = executor.schedule(() -> {
            tasks.remove(jobId);
            if (!closed) {
                action.run();
            }
        }, delay, TimeUnit.MILLISECONDS);
        tasks.put(jobId, future);
    }

    void cancel(long jobId) {
        ScheduledFuture<?> future = tasks.remove(jobId);
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public void close() {
        closed = true;
        tasks.clear();
        executor.shutdownNow();
    }
}
