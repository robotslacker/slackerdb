package org.slackerdb.dbcli.job;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个作业在**监督进程内存里**的执行句柄：停止镜像、取消回调、线程/子进程句柄。
 *
 * <p><b>它不是状态来源</b>：作业状态、退出码、认领全在 {@link SqliteJobStore} 里；
 * 这里只保存"如何尽快响应停止"所需的最小信息。停止动作的纪律是
 * <b>先写库、再派生</b>：调用方（abort/shutdown/超时调度器）先写
 * {@code stop_terminal}/{@code stop_forced}，然后调 {@link #signal(boolean)}
 * 让作业线程/子进程尽快停下。</p>
 *
 * <p>作业的停止只能由创建它的进程发起（作业按 scope 隔离），因此"停止方"与"监督者"
 * 总在同一个 JVM 里，信号可以同步派发，不需要跨进程轮询。</p>
 *
 * <p>线程安全：{@code stopRequested}/{@code forcedStop} 是 volatile；canceller 集合用并发容器；
 * 派发用一次性的原子标志保证每个回调最多执行一次；{@code forced} 单调（只升不降）。</p>
 */
public final class LocalJobRuntime implements JobControl {

    private final long jobId;
    private final String name;

    private final ConcurrentHashMap<Runnable, Boolean> cancellers = new ConcurrentHashMap<>();
    private final AtomicBoolean cancellersDispatched = new AtomicBoolean();
    /** 派发与注册的互斥：保证"注册晚了一步"的回调不会被清空吞掉。 */
    private final Object cancellerLock = new Object();

    private volatile boolean stopRequested;
    private volatile boolean forcedStop;

    private volatile Thread thread;
    private volatile Process process;

    LocalJobRuntime(long jobId, String name) {
        this.jobId = jobId;
        this.name = name;
    }

    public long jobId() {
        return jobId;
    }

    public String name() {
        return name;
    }

    public Thread thread() {
        return thread;
    }

    void setThread(Thread thread) {
        this.thread = thread;
    }

    public Process process() {
        return process;
    }

    void setProcess(Process process) {
        this.process = process;
    }

    @Override
    public boolean stopRequested() {
        return stopRequested;
    }

    @Override
    public boolean forcedStop() {
        return forcedStop;
    }

    /**
     * 发出停止信号（幂等）：置位 + 派发一次取消回调；{@code forced} 时再中断作业线程。
     *
     * <p>{@code forced} 只能从 false 升到 true；后续的优雅请求不会把它降级。
     * 取消回调即使已经派发过，升级为强制时仍会中断线程（进程模式下
     * {@link ProcessTree} 的取消回调本身已包含"优雅后强杀"）。</p>
     */
    public void signal(boolean forced) {
        List<Runnable> toRun = List.of();
        if (forced) {
            this.forcedStop = true;
        }
        this.stopRequested = true;
        synchronized (cancellerLock) {
            if (cancellersDispatched.compareAndSet(false, true)) {
                toRun = List.copyOf(cancellers.keySet());
                cancellers.clear();
            }
        }
        for (Runnable canceller : toRun) {
            runQuietly(canceller);
        }
        if (forced) {
            Thread worker = thread;
            if (worker != null) {
                worker.interrupt();
            }
        }
    }

    @Override
    public AutoCloseable onStop(Runnable canceller) {
        if (canceller == null) {
            return () -> {
            };
        }
        synchronized (cancellerLock) {
            if (!cancellersDispatched.get()) {
                cancellers.put(canceller, Boolean.TRUE);
                return () -> cancellers.remove(canceller);
            }
        }
        // 已经派发过：立即执行一次，不留"注册晚了一步"的窗口
        runQuietly(canceller);
        return () -> {
        };
    }

    /** 作业已经结束：清掉取消回调（再触发没有意义）。 */
    void clearCancellers() {
        cancellers.clear();
    }

    private static void runQuietly(Runnable canceller) {
        try {
            canceller.run();
        } catch (RuntimeException ignored) {
            // 取消动作失败不该影响停止流程本身（它只是"尽力让长操作早点失败"）
        }
    }
}
