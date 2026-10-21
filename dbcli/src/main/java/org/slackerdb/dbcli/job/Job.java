package org.slackerdb.dbcli.job;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个作业的运行实例：持有**作业线程**、状态、耗时与退出码。
 *
 * <p>由 {@link JobManager} 创建与推进状态；对外的只读视图是公开的 getter。状态字段是 {@code volatile}，
 * 因为读状态的多半是别的线程（父脚本 {@code _JOB SHOW}/{@code _JOB WAIT}），写状态的是作业线程与
 * 请求停止的那条线程。</p>
 *
 * <p><b>输出落到文件而不是父脚本的 stdout</b>：作业的 stdout 与 stderr 都写
 * {@code <工作目录>/<作业名>.out} 与 {@code .err}（见 {@link JobOutput}）。并发作业因此不会互相交错，
 * 父脚本的 stdout 也仍然是"父脚本的产出"。</p>
 *
 * <p><b>线程卫生</b>：作业线程一律 **daemon**，名字固定 {@code dbcli-job-<作业名>}；管理器
 * {@code close()} 只做"请求停止 + 限时 join"，绝不为了等一个不肯停的作业拖住 JVM 退出。</p>
 */
public final class Job implements JobControl {

    private final JobSpec spec;
    private final Path stdoutFile;

    /** 已经登记过的"取消当前长操作"回调（见 {@link #onStop(Runnable)}）。 */
    private final Set<Runnable> cancellers = ConcurrentHashMap.newKeySet();

    private volatile JobState state = JobState.CREATED;
    private volatile Thread thread;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile int exitCode = Integer.MIN_VALUE;
    private volatile String failureMessage;
    private volatile boolean stopRequested;

    /** 这次停止请求是否要求立刻强制（{@code _JOB ABORT} 与硬超时；{@code _JOB SHUTDOWN} 不是）。 */
    private volatile boolean forcedStop;

    /** 作业带 {@code timeout=<秒>} 且已经到点（终态仍由作业收尾时落地，见 {@link #markFinished(int)}）。 */
    private volatile boolean timeoutRequested;

    /** 进程模式下的子进程 pid（线程模式恒为 -1，{@code _JOB SHOW} 的 thread 列据此显示）。 */
    private volatile long processId = -1L;

    Job(JobSpec spec, Path stdoutFile) {
        this.spec = spec;
        this.stdoutFile = stdoutFile;
    }

    public JobSpec spec() {
        return spec;
    }

    public String name() {
        return spec.name();
    }

    public JobState state() {
        return state;
    }

    public Path stdoutFile() {
        return stdoutFile;
    }


    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    /** 退出码；未结束时为 {@link Integer#MIN_VALUE}。 */
    public int exitCode() {
        return exitCode;
    }

    /** 启动失败或作业线程异常时的原因（正常路径为 null）。 */
    public String failureMessage() {
        return failureMessage;
    }

    /** 作业线程；未启动时为 null。 */
    public Thread thread() {
        return thread;
    }

    /** 作业线程名；未启动时为空串（{@code _JOB SHOW} 的 {@code thread} 列用它）。 */
    public String threadName() {
        Thread current = thread;
        return current == null ? "" : current.getName();
    }

    /**
     * 运行标识（{@code _JOB SHOW} 的 {@code thread} 列）：**线程模式**是线程名
     * （{@code dbcli-job-<作业名>}）；**进程模式**是 {@code pid <n>}；未启动时为空串。
     *
     * <p>判据是**执行层**而不是"有没有线程"：进程模式的作业同样是"在一个作业线程里启动子进程"，
     * 那个线程只是发起者，对使用者没有意义（真正干活的在另一个进程里）。</p>
     */
    public String runtimeIdentity() {
        if (spec.mode() == JobMode.PROCESS) {
            long pid = processId;
            return pid > 0L ? "pid " + pid : "";
        }
        Thread current = thread;
        return current == null ? "" : current.getName();
    }

    /** 进程模式子进程的 pid（线程模式返回 -1）。 */
    public long processId() {
        return processId;
    }

    /** 已运行毫秒数（未启动 0；已结束取结束时刻）。 */
    public long elapsedMillis() {
        Instant started = startedAt;
        if (started == null) {
            return 0L;
        }
        Instant end = finishedAt == null ? Instant.now() : finishedAt;
        return Duration.between(started, end).toMillis();
    }

    /** 只读的行摘要（{@code _JOB SHOW} 用）：{@code name/state/thread/elapsed/exitCode}。 */
    public List<String> describe() {
        return List.of(
                name(),
                state.name(),
                runtimeIdentity(),
                elapsedMillis() + "ms",
                exitCode == Integer.MIN_VALUE ? "" : String.valueOf(exitCode));
    }

    // ------------------------------------------------------------------ 停止控制

    @Override
    public boolean stopRequested() {
        return stopRequested;
    }

    @Override
    public boolean forcedStop() {
        return forcedStop;
    }

    /** 作业是否因为 {@code timeout=<秒>} 到点而被停掉（决定终态是 {@link JobState#TIMEOUT}）。 */
    boolean timeoutRequested() {
        return timeoutRequested;
    }

    /** 到点：只**记下**"这是超时收尾"，不落地终态（终态由作业收尾时落地，见 {@link #markFinished(int)}）。 */
    void requestTimeout() {
        this.timeoutRequested = true;
    }

    @Override
    public AutoCloseable onStop(Runnable canceller) {
        if (canceller == null) {
            return () -> {
            };
        }
        if (stopRequested) {
            runQuietly(canceller);
            return () -> {
            };
        }
        cancellers.add(canceller);
        // 注册与"请求停止"之间有一个窗口：这里复查一次，避免回调永远不被执行
        if (stopRequested && cancellers.remove(canceller)) {
            runQuietly(canceller);
        }
        return () -> cancellers.remove(canceller);
    }

    /**
     * 请求停止（幂等）：置位 + 触发所有"取消当前长操作"的回调。**不**中断线程（由调用方决定）。
     *
     * @param forced {@code true} = 立刻强制（{@code _JOB ABORT}、硬超时）；{@code false} = 优雅
     *               （{@code _JOB SHUTDOWN}、管理器关闭）。执行体据此选"强杀进程树"还是"先优雅再强杀"
     */
    void requestStop(boolean forced) {
        this.forcedStop = forced;
        this.stopRequested = true;
        for (Runnable canceller : List.copyOf(cancellers)) {
            runQuietly(canceller);
        }
    }

    /** 作业收尾：清掉取消回调（作业已经结束，回调再触发没有意义）。 */
    void clearCancellers() {
        cancellers.clear();
    }

    // ------------------------------------------------------------------ 包内状态推进

    void markStarted(Thread startedThread) {
        this.thread = startedThread;
        this.state = JobState.RUNNING;
        this.startedAt = Instant.now();
        this.finishedAt = null;
        this.exitCode = Integer.MIN_VALUE;
        this.failureMessage = null;
    }

    /** 进程模式：记下子进程 pid（{@code _JOB SHOW} 的 {@code thread} 列据此显示 {@code pid <n>}）。 */
    void markProcessStarted(long pid) {
        this.processId = pid;
    }

    /** 启动失败（输出文件都打不开）：作业没跑起来，直接 FAILED。 */
    void markFailedToStart(String message) {
        this.state = JobState.FAILED;
        this.failureMessage = message;
        this.startedAt = Instant.now();
        this.finishedAt = this.startedAt;
        this.exitCode = Integer.MIN_VALUE;
    }

    /**
     * 作业正常返回：按退出码落到 {@code DONE}/{@code FAILED}（带 {@code timeout=} 且已到点则落 {@code TIMEOUT}）。
     *
     * <p><b>顺序上的硬保证</b>：调用本方法的时机一定是"作业输出已经落盘并关闭"**之后**
     * （见 {@code JobManager.runJob}），所以父脚本看到终态就等于输出完整。超时因此也**不**在这里之外
     * 落地终态——看门狗只记下 {@link #requestTimeout()}，真正的终态由作业收尾时落地。</p>
     *
     * <p>已经请求过停止（且不是超时）时**不落地**：那时终态由请求方（{@code abort}/{@code shutdown}/
     * 管理器关闭）决定，否则"被中止的作业"会显示成 DONE/FAILED，与 {@code Job [x] aborted.} 的文案矛盾。</p>
     */
    synchronized void markFinished(int code) {
        if (state != JobState.RUNNING) {
            return;
        }
        if (stopRequested && !timeoutRequested) {
            return;
        }
        this.exitCode = code;
        this.state = timeoutRequested ? JobState.TIMEOUT : (code == 0 ? JobState.DONE : JobState.FAILED);
        this.finishedAt = Instant.now();
    }

    /** 作业抛出异常：记下原因，未请求停止时置 FAILED（超时收尾则置 TIMEOUT）。 */
    synchronized void markFailed(String message) {
        this.failureMessage = message;
        if (state != JobState.RUNNING) {
            return;
        }
        if (stopRequested && !timeoutRequested) {
            return;
        }
        this.state = timeoutRequested ? JobState.TIMEOUT : JobState.FAILED;
        this.finishedAt = Instant.now();
    }

    /**
     * {@code _JOB ABORT}：把**还在跑**的作业落到 ABORTED。
     *
     * <p>已经到终态（DONE / FAILED / TIMEOUT / ABORTED / SHUTDOWN）的作业不再改动：
     * 对一个跑完的作业再 abort 只是"表达意图"，不该把 TIMEOUT 改写成 ABORTED——
     * 那会让 `_JOB SHOW` / `_JOB WAIT` 报告一个与事实不符的结局。</p>
     */
    synchronized void markAborted() {
        if (isTerminal()) {
            return;
        }
        this.state = JobState.ABORTED;
        this.finishedAt = Instant.now();
    }

    /** {@code _JOB SHUTDOWN}：把**还在跑**的作业落到 SHUTDOWN（已经结束的作业不改动，理由同 {@link #markAborted()}）。 */
    synchronized void markShutdown() {
        if (isTerminal()) {
            return;
        }
        this.state = JobState.SHUTDOWN;
        this.finishedAt = Instant.now();
    }

    /** 作业是否已经在终态（不会再变）。 */
    boolean isTerminal() {
        return state == JobState.DONE || state == JobState.FAILED || state == JobState.TIMEOUT
                || state == JobState.ABORTED || state == JobState.SHUTDOWN;
    }

    /** 仍在运行（管理器收尾判定用）。 */
    boolean isRunning() {
        return state == JobState.RUNNING;
    }

    private static void runQuietly(Runnable canceller) {
        try {
            canceller.run();
        } catch (RuntimeException ignored) {
            // 取消动作失败不该影响停止流程本身（它只是"尽力让长操作早点失败"）
        }
    }
}
