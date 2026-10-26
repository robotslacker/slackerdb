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
 * {@code <日志目录>/<作业名>.out}（只有这一个文件，见 {@link JobOutput}）。
 * 并发作业因此不会互相交错，父脚本的 stdout 也仍然是"父脚本的产出"。</p>
 *
 * <p><b>线程卫生</b>：作业线程一律 **daemon**，名字固定 {@code dbcli-job-<作业名>}；管理器
 * {@code close()} 只做"请求停止 + 限时 join"，绝不为了等一个不肯停的作业拖住 JVM 退出。</p>
 */
public final class Job implements JobControl {

    private final JobSpec spec;
    private final Path stdoutFile;

    /** 已经登记过的"取消当前长操作"回调（见 {@link #onStop(Runnable)}）。 */
    private final Set<Runnable> cancellers = ConcurrentHashMap.newKeySet();

    /** 取消回调是否已经派发过（保证每个回调最多执行一次；见 {@link #requestStop}）。 */
    private final java.util.concurrent.atomic.AtomicBoolean cancellersDispatched =
            new java.util.concurrent.atomic.AtomicBoolean();

    private volatile JobState state = JobState.CREATED;
    private volatile Thread thread;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile int exitCode = Integer.MIN_VALUE;
    private volatile String failureMessage;
    private volatile boolean stopRequested;

    /**
     * 停止请求希望落到的终态（{@code ABORTED}/{@code SHUTDOWN}/{@code TIMEOUT}）。
     *
     * <p>终态由**确认作业线程已经结束**的一方落地：请求方 join 成功就自己落，join 不到就交给
     * 作业线程在收尾时按这里记下的意图落。这样"状态说真话"——线程还在跑时不会显示 ABORTED，
     * 而线程真正退出时也不会永远停在 RUNNING。</p>
     */
    private volatile JobState pendingStopState;

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
        // 已经停止（且回调已派发过）：立即执行一次，不留"注册晚了一步"的窗口
        if (stopRequested && cancellersDispatched.get()) {
            runQuietly(canceller);
            return () -> {
            };
        }
        cancellers.add(canceller);
        // 注册与"请求停止"之间有一个窗口：这里复查一次。
        // 用 remove 的结果作为"我拿到了这次执行权"的凭据——派发方会把集合清空，
        // 因此同一个回调不可能既被派发方执行、又被这里执行。
        if (stopRequested && cancellers.remove(canceller)) {
            runQuietly(canceller);
        }
        return () -> cancellers.remove(canceller);
    }

    /**
     * 请求停止（幂等）：置位 + 触发所有"取消当前长操作"的回调。**不**中断线程（由调用方决定）。
     *
     * <p>回调只派发一次：{@code abort} 之后管理器关闭再请求一次不会让它们重复执行。
     * {@code forced} 是单调的——一旦要求过强制，后续的优雅请求不会把它降级。</p>
     *
     * @param forced   {@code true} = 立刻强制（{@code _JOB ABORT}、硬超时）；{@code false} = 优雅
     *                 （{@code _JOB SHUTDOWN}、管理器关闭）
     * @param terminal 这次停止希望落到的终态（{@code ABORTED}/{@code SHUTDOWN}/{@code TIMEOUT}）；
     *                 {@code null} 表示不改变既有意图
     */
    void requestStop(boolean forced, JobState terminal) {
        if (forced) {
            this.forcedStop = true;
        }
        if (terminal != null && (pendingStopState == null || forced)) {
            this.pendingStopState = terminal;
        }
        this.stopRequested = true;
        if (cancellersDispatched.compareAndSet(false, true)) {
            for (Runnable canceller : List.copyOf(cancellers)) {
                runQuietly(canceller);
            }
            cancellers.clear();
        }
    }

    /** 作业收尾：清掉取消回调（作业已经结束，回调再触发没有意义）。 */
    void clearCancellers() {
        cancellers.clear();
    }

    // ------------------------------------------------------------------ 包内状态推进

    /**
     * 在**同一把锁内**发布 RUNNING 并启动作业线程。
     *
     * <p>为什么不能分成"发布 RUNNING"与 {@code thread.start()} 两步：中间那个窗口里
     * 另一个线程可以 {@code _JOB ABORT} 落 ABORTED、或 {@code _JOB WAIT} 去 join 一个**还没启动**的
     * 线程（{@code join} 对未启动线程立即返回，于是 WAIT 得到"已完成"的假结果）。
     * 现在两者要么都还没发生，要么都已发生。</p>
     *
     * @return {@code false} 表示作业已经不在 CREATED（被并发 abort/shutdown 了），调用方不应再启动
     */
    synchronized boolean startThread(Thread worker) {
        if (state != JobState.CREATED) {
            return false;
        }
        this.thread = worker;
        this.state = JobState.RUNNING;
        this.startedAt = Instant.now();
        this.finishedAt = null;
        this.exitCode = Integer.MIN_VALUE;
        this.failureMessage = null;
        worker.start();
        return true;
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
     * 作业正常返回：按退出码落到 {@code DONE}/{@code FAILED}；请求过停止则落到请求的终态。
     *
     * <p><b>顺序上的硬保证</b>：调用本方法的时机一定是"作业输出已经落盘并关闭"**之后**
     * （见 {@code JobManager.runJob}），所以父脚本看到终态就等于输出完整。</p>
     *
     * <p><b>终态由谁落地</b>：请求方 join 到线程结束时自己落（{@code markAborted}/{@code markShutdown}）；
     * join 不到时由这里落——按 {@link #pendingStopState} 记下的意图。否则一个不响应停止的线程
     * 退出后会永远停在 RUNNING。</p>
     */
    synchronized void markFinished(int code) {
        if (state != JobState.RUNNING) {
            return;
        }
        JobState terminal = terminalFor(code);
        if (terminal == null) {
            // 请求过停止但调用方没给意图（理论上不会发生）：交给请求方，维持旧行为
            return;
        }
        this.exitCode = code;
        this.state = terminal;
        this.finishedAt = Instant.now();
    }

    /** 作业抛出异常：记下原因，按退出码/停止意图落终态。 */
    synchronized void markFailed(String message) {
        this.failureMessage = message;
        if (state != JobState.RUNNING) {
            return;
        }
        JobState terminal = terminalFor(1);
        if (terminal == null) {
            return;
        }
        this.state = terminal;
        this.finishedAt = Instant.now();
    }

    /** 收尾时的终态：停止意图 > 超时 > 退出码；{@code null} 表示"交给请求方"。 */
    private JobState terminalFor(int code) {
        JobState pending = pendingStopState;
        if (pending != null) {
            return pending;
        }
        if (timeoutRequested) {
            return JobState.TIMEOUT;
        }
        if (stopRequested) {
            return null;
        }
        return code == 0 ? JobState.DONE : JobState.FAILED;
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
