package org.slackerdb.dbcli.job;

import org.slackerdb.dbcli.common.DbcliPaths;
import org.slackerdb.dbcli.common.UserNumber;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 作业管理器（门面）：**不持有可变作业状态**，只做"校验 + 写库 + 启动监督者 + 等待 + 收尾"。
 *
 * <h2>一切协调状态都在 {@link SqliteJobStore}</h2>
 * <p>注册表、状态机、停止意图、超时、认领、定时点全部落库；线程作业与进程作业走同一条编排路径，
 * 区别只在于注入的 {@link JobExecutor}。管理器内存里只剩两样东西：</p>
 * <ul>
 *   <li>{@code definitions}：作业定义（脚本/变量/选项）。只有启动它的进程需要，因此留在内存；</li>
 *   <li>{@code runtimes}：本地执行句柄（停止镜像、取消回调、线程/进程句柄）。它不是状态来源。</li>
 * </ul>
 *
 * <h2>终态的唯一写者</h2>
 * <p>{@link JobSupervisor} 在作业真正结束后写终态（CAS 在 RUNNING 上）；
 * {@link #abort}/{@link #shutdown}/超时/{@link #close()} 只写"意图"
 * （{@code stop_terminal}/{@code stop_forced}）。因此不会出现"请求方先把状态改成 ABORTED、
 * 监督者又想改成 DONE"的双写竞态。</p>
 *
 * <h2>scope</h2>
 * <p>根进程 {@code root}，进程作业的子进程 {@code root/<作业名>}。作业按 scope 隔离，
 * 保持既有可见性；定时点不分 scope，跨进程屏障仍然成立。</p>
 */
public final class JobManager implements AutoCloseable {

    /** {@code shutdown} 的宽限期（毫秒）：先请求退出，超过它还没停才升级为强制。 */
    public static final long STOP_GRACE_MILLIS = 1_000L;

    /** {@link #close()} 的总预算（毫秒）：超过就放弃等待（监督线程都是 daemon）。 */
    public static final long CLOSE_TIMEOUT_MILLIS = 3_000L;

    /** 监督线程的名字前缀（{@code _JOB SHOW} 的 {@code thread} 列与线程卫生排查都认它）。 */
    public static final String THREAD_NAME_PREFIX = "dbcli-job-";

    /** {@code _JOB WAIT} 里"强制当作业名解析"的前缀（大小写不敏感）。 */
    private static final String JOB_PREFIX = "JOB:";

    /** {@code _JOB WAIT} 里"强制当定时点名解析"的前缀（大小写不敏感）。 */
    private static final String TIMER_PREFIX = "TIMER:";

    /** 父脚本进程的参与者名字前缀：完整取值是 {@code MAIN@<pid>}。 */
    public static final String MAIN_PARTICIPANT = "MAIN";

    /** 收尾轮询间隔（毫秒）。 */
    private static final long CLOSE_POLL_MILLIS = 10L;

    private static final System.Logger LOG = System.getLogger("org.slackerdb.dbcli.job");

    private final JobSpecFactory specFactory;
    private final JobExecutor executor;
    private final DbcliPaths paths;
    private final Path logDirectory;
    private final Path workingDirectory;
    private final SqliteJobStore store;

    /** 作业定义：只有本进程需要（变量/选项可能含连接信息，刻意不落库）。 */
    private final Map<Long, JobSpec> definitions = new ConcurrentHashMap<>();

    /** 本地执行句柄：停止镜像与线程/进程句柄；保留到管理器关闭，供测试与排错观察。 */
    private final Map<Long, LocalJobRuntime> runtimes = new ConcurrentHashMap<>();

    /** 关闭后供 {@link Job} 视图读取的最终快照（只读诊断，不参与任何判定）。 */
    private final Map<Long, JobRecord> closedRecords = new ConcurrentHashMap<>();

    private final JobTimeoutScheduler timeoutScheduler = new JobTimeoutScheduler();

    private volatile boolean started;
    private volatile boolean closed;

    public JobManager(JobSpecFactory specFactory, JobExecutor executor, Path logDirectory,
                      Path workingDirectory) {
        this(specFactory, executor, logDirectory, workingDirectory,
                DbcliPaths.forDirectory(workingDirectory), JobStoreLocator.open());
    }

    /** 生产入口：OUTPUT/jobs 存 .out，WORK 是 cwd，作业库在 WORK/.dbcli/jobs。 */
    public JobManager(JobSpecFactory specFactory, JobExecutor executor, DbcliPaths paths) {
        this(specFactory, executor,
                paths.legacy() ? paths.output() : paths.output().resolve("jobs"),
                paths.work(), paths, JobStoreLocator.open(paths));
    }

    JobManager(JobSpecFactory specFactory, JobExecutor executor, Path logDirectory,
               Path workingDirectory, SqliteJobStore store) {
        this(specFactory, executor, logDirectory, workingDirectory,
                DbcliPaths.forDirectory(workingDirectory), store);
    }

    JobManager(JobSpecFactory specFactory, JobExecutor executor, Path logDirectory,
               Path workingDirectory, DbcliPaths paths, SqliteJobStore store) {
        this.specFactory = specFactory;
        this.executor = executor;
        this.paths = paths;
        this.logDirectory = logDirectory;
        this.workingDirectory = workingDirectory;
        this.store = store;
    }

    public DbcliPaths paths() {
        return paths;
    }

    // ------------------------------------------------------------------ 管理器开关

    /** {@code _JOB JOBMANAGER ON}。重复打开是幂等的。 */
    public void start() {
        this.started = true;
    }

    /** {@code _JOB JOBMANAGER OFF}：停止接受新作业，不影响正在运行的作业。 */
    public void stop() {
        this.started = false;
    }

    public boolean isStarted() {
        return started;
    }

    // ------------------------------------------------------------------ 作业：定义

    /** {@code _JOB CREATE <名字> key=value...}：定义作业（不启动）。 */
    public Job create(String name, Map<String, String> parameters) {
        return create(name, parameters, workingDirectory);
    }

    public Job create(String name, Map<String, String> parameters, Path definitionDirectory) {
        return create(name, parameters, definitionDirectory, Map.of());
    }

    public Job create(String name, Map<String, String> parameters, Path definitionDirectory,
                      Map<String, String> sessionVariables) {
        requireStarted();
        String jobName = requireUsableName(name);
        // 先查一次给出可读文案；真正的唯一性由 (scope,name) 唯一约束兜底（并发下也成立）
        if (store.find(jobName) != null) {
            throw new JobException("Job [" + jobName + "] already exists.");
        }
        JobSpec spec = define(jobName, parameters, definitionDirectory)
                .withSessionVariables(sessionVariables);
        JobRecord created = store.create(jobName, spec.mode(), timeoutMillisOf(spec));
        definitions.put(created.jobId(), spec);
        return view(created);
    }

    /** {@code _JOB SET <名字> key=value...}：更新定义（运行中不允许改）。 */
    public Job setParameters(String name, Map<String, String> parameters) {
        return setParameters(name, parameters, workingDirectory);
    }

    public Job setParameters(String name, Map<String, String> parameters, Path definitionDirectory) {
        requireStarted();
        String jobName = requireUsableName(name);
        JobRecord current = require(jobName);
        if (current.state() == JobState.RUNNING) {
            throw new JobException("Job [" + jobName + "] is running; parameters cannot be changed.");
        }
        JobSpec spec = define(jobName, parameters, definitionDirectory);
        JobRecord updated = store.redefine(jobName, spec.mode(), timeoutMillisOf(spec));
        if (updated == null) {
            throw new JobException("Job [" + jobName + "] is running; parameters cannot be changed.");
        }
        definitions.put(updated.jobId(), spec);
        return view(updated);
    }

    /** {@code _JOB START <名字>}：启动监督线程。 */
    public Job start(String name) {
        return start(name, null);
    }

    /**
     * @param sessionVariables 父 job 在 fork（START）时的 SESSION 快照；null = 沿用定义里的
     */
    public Job start(String name, Map<String, String> sessionVariables) {
        requireStarted();
        JobRecord current = require(name);
        if (current.state() != JobState.CREATED) {
            throw new JobException("Job [" + name + "] is already " + current.state() + ".");
        }
        JobSpec spec = definitions.get(current.jobId());
        if (spec == null) {
            throw new JobException("Job [" + name + "] has no definition to run.");
        }
        if (sessionVariables != null) {
            spec = spec.withSessionVariables(sessionVariables);
            definitions.put(current.jobId(), spec);
        }
        JobOutput output;
        try {
            // 作业输出落 OUTPUT/jobs：这时才真正需要 OUTPUT（普通运行不会建它，见 DbcliPaths）
            paths.ensureOutput();
            Files.createDirectories(logDirectory);
            // 进程模式：输出由子进程自己重定向写入，父进程不能同时持有写句柄（Windows 共享冲突）
            output = current.mode() == JobMode.PROCESS
                    ? JobOutput.discard(logFile(name, ".out"))
                    : JobOutput.open(logFile(name, ".out"));
        } catch (IOException | RuntimeException startFailure) {
            store.markFailedToStart(current.jobId(), startFailure.getMessage(), spec.script());
            // 这条错误已经由本次 START 报过一次，且作业**根本没跑起来**：不再让收尾补报
            throw new JobException("Job [" + name + "] failed to start: " + startFailure.getMessage(),
                    startFailure);
        }

        String runtimeId = current.mode() == JobMode.THREAD ? THREAD_NAME_PREFIX + name : "";
        boolean acquired = store.markRunning(current.jobId(), current.definitionVersion(), runtimeId,
                currentPid(), processStartMillis());
        if (!acquired) {
            output.close();
            JobRecord latest = store.findById(current.jobId());
            throw new JobException("Job [" + name + "] is already "
                    + (latest == null ? "changed." : latest.state() + "."));
        }

        LocalJobRuntime runtime = new LocalJobRuntime(current.jobId(), name);
        runtimes.put(current.jobId(), runtime);
        Job view = view(current);
        Thread worker = new Thread(
                new JobSupervisor(this, view, runtime, output, executor), THREAD_NAME_PREFIX + name);
        // 必须 daemon：作业线程绝不能拖住 JVM 退出
        worker.setDaemon(true);
        runtime.setThread(worker);
        worker.start();
        scheduleTimeout(current.jobId(), spec);
        return view;
    }

    // ------------------------------------------------------------------ 作业：等待/查询

    /**
     * {@code _JOB WAIT <名字> [timeout=N]}：等作业结束，或在定时点上等待。
     *
     * <p>名字解析顺序：<b>先按作业名</b>（区分大小写），找不到再按定时点名（**不区分大小写**）；
     * 可用前缀强制指定（{@code JOB:<名字>} / {@code TIMER:<点名>}）。</p>
     */
    public WaitResult waitFor(String rawName, Long timeoutMillis) {
        long timeout = timeoutMillis == null ? SqliteJobStore.NO_TIMEOUT : timeoutMillis;
        String name = rawName == null ? "" : rawName.strip();
        String upper = name.toUpperCase(Locale.ROOT);

        if (upper.startsWith(TIMER_PREFIX)) {
            String point = name.substring(TIMER_PREFIX.length()).strip();
            if (point.isEmpty()) {
                throw new JobException("Timer point name is missing: write _JOB WAIT TIMER:<point>.");
            }
            return waitTimer(point, timeout);
        }
        if (upper.startsWith(JOB_PREFIX)) {
            return waitJob(name.substring(JOB_PREFIX.length()).strip(), timeout);
        }
        if (store.find(name) != null) {
            return waitJob(name, timeout);
        }
        if (store.knows(name)) {
            return waitTimer(name, timeout);
        }
        throw new JobException("Job or timer point [" + name + "] not found.");
    }

    private WaitResult waitJob(String name, long timeout) {
        JobRecord current = require(name);
        if (current.state() == JobState.CREATED) {
            // 没启动过的作业永远不会"结束"：等它就是等到超时（或永远等下去），当场报错更诚实
            throw new JobException("Job [" + name + "] was never started (use _JOB START first).");
        }
        if (!current.state().isTerminal()) {
            try {
                current = store.awaitTerminal(current.jobId(), timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new JobException("Interrupted while waiting for job [" + name + "].", interrupted);
            }
        }
        return new WaitResult(view(current), false, !current.state().isTerminal(), name);
    }

    private WaitResult waitTimer(String point, long timeout) {
        // 到达记录只用于观察/排错，不改变放行语义
        store.arrive(point, currentParticipant());
        boolean released;
        try {
            released = store.await(point, timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new JobException("Interrupted while waiting for timer point [" + point + "].", interrupted);
        }
        // 回显原样名字：调用方写 `timer:go`，文案里就是 `go`，不是 `GO`
        return new WaitResult(null, released, !released, point);
    }

    /** {@code _JOB TIMER <点名>}：放行该点名（首次调用同时创建，幂等；点名不区分大小写）。 */
    public String releaseTimer(String point) {
        store.release(point);
        return point;
    }

    /** {@code _JOB SHOW <名字>}：读当前状态。 */
    public Job show(String name) {
        return view(require(name));
    }

    /** 当前管理器里的全部作业（含已结束；{@code _JOB SHOW} 无参数时按名字排序展示）。 */
    public Collection<Job> jobs() {
        if (closed) {
            return closedRecords.values().stream().map(this::view).toList();
        }
        List<Job> jobs = new ArrayList<>();
        for (JobRecord record : store.list()) {
            jobs.add(view(record));
        }
        return jobs;
    }

    /** 当前的作业库路径（测试与排错用；关闭后仍返回原路径）。 */
    public Path jobDatabase() {
        return store.database();
    }

    /** 全部作业执行审计（含进程作业的子进程；按完成顺序）。 */
    public List<JobAuditRecord> audit() {
        return store.listAudit();
    }

    // ------------------------------------------------------------------ 认领与失败

    /** 认领一个作业的非 DONE 终态：此后脚本收尾不再把它算作失败。 */
    public void acknowledge(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        JobRecord record = store.find(name);
        if (record != null) {
            store.acknowledge(record.jobId(), record.definitionVersion());
        }
    }

    /** 认领一个具体定义（{@code _JOB SET} 之后旧认领不会跟到新定义上）。 */
    public void acknowledge(Job job) {
        if (job == null) {
            return;
        }
        JobRecord record = recordForView(job.jobId(), job.name());
        store.acknowledge(record.jobId(), record.definitionVersion());
    }

    /** 有没有"失败或超时、且没有被认领"的作业。 */
    public boolean hasUnacknowledgedFailures() {
        return !unacknowledgedFailureMessages().isEmpty();
    }

    /** 认领所有当前失败的作业（引擎补报之后调用，避免同一件事被算两次）。 */
    public void acknowledgeFailures() {
        if (!store.isClosed()) {
            store.acknowledgeFailures();
        }
    }

    /** "没被检查过的失败作业"的可读说明（一条一个作业），按作业名排序。 */
    public List<String> unacknowledgedFailureMessages() {
        if (store.isClosed()) {
            return List.of();
        }
        List<String> messages = new ArrayList<>();
        for (JobRecord record : store.unacknowledgedFailures()) {
            if (record.state() == JobState.TIMEOUT) {
                messages.add("Job [" + record.name() + "] timed out "
                        + "(it was started but never checked by _JOB WAIT).");
            } else if (record.state() == JobState.FAILED) {
                int exitCode = record.exitCodeOrMin();
                messages.add("Job [" + record.name() + "] failed"
                        + (exitCode == JobRecord.NO_EXIT_CODE ? "" : " with exit code " + exitCode)
                        + " (it was started but never checked by _JOB WAIT).");
            }
        }
        Collections.sort(messages);
        return messages;
    }

    // ------------------------------------------------------------------ 停止

    /**
     * {@code _JOB ABORT <名字>}：写强制停止意图并同步派发本地停止信号，
     * 然后最多等 {@value #STOP_GRACE_MILLIS} 毫秒拿终态。
     */
    public Job abort(String name) {
        JobRecord current = require(name);
        acknowledge(current.jobId(), current.definitionVersion());
        if (current.state() == JobState.CREATED) {
            // 没有监督者可以写终态：调用方 CAS（与旧行为一致）
            return view(store.finishCreated(current.jobId(), JobState.ABORTED));
        }
        if (current.state().isTerminal()) {
            // 对已经结束的作业再 abort 只是表达意图，不能改写结局
            return view(current);
        }
        store.requestStop(current.jobId(), JobState.ABORTED, true);
        signalLocal(current.jobId(), true);
        return view(awaitQuietly(current.jobId(), STOP_GRACE_MILLIS));
    }

    /**
     * {@code _JOB SHUTDOWN <名字>}：先只请求（优雅），宽限期后仍在跑才升级为强制。
     * 终态仍由监督者在作业真正结束后写。
     */
    public Job shutdown(String name) {
        JobRecord current = require(name);
        acknowledge(current.jobId(), current.definitionVersion());
        if (current.state() == JobState.CREATED) {
            return view(store.finishCreated(current.jobId(), JobState.SHUTDOWN));
        }
        if (current.state().isTerminal()) {
            return view(current);
        }
        store.requestStop(current.jobId(), JobState.SHUTDOWN, false);
        signalLocal(current.jobId(), false);
        JobRecord after = awaitQuietly(current.jobId(), STOP_GRACE_MILLIS);
        if (!after.state().isTerminal()) {
            // 只升级 forced，不改已经写下的停止意图（并发的 ABORT 不能被降级成 SHUTDOWN）
            store.escalateStop(current.jobId());
            signalLocal(current.jobId(), true);
            after = awaitQuietly(current.jobId(), STOP_GRACE_MILLIS);
        }
        return view(after);
    }

    // ------------------------------------------------------------------ 收尾

    /**
     * 等待所有仍在运行的作业**自然结束**，不发出任何停止请求
     * （最外层脚本退出前收口；不写 {@code timeout=} 的作业会让它一直等）。
     */
    public void awaitAll() {
        while (true) {
            if (closed || store.isClosed()) {
                return;
            }
            List<JobRecord> running;
            try {
                running = store.listRunning();
            } catch (RuntimeException storeGone) {
                return;
            }
            if (running.isEmpty()) {
                return;
            }
            boolean waited = false;
            for (JobRecord record : running) {
                try {
                    store.awaitTerminal(record.jobId(), SqliteJobStore.NO_TIMEOUT);
                    waited = true;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            // 等待期间作业还可能再拉起孙作业：再扫一遍，直到没有 RUNNING
            if (!waited) {
                return;
            }
        }
    }

    /**
     * 停掉所有仍在运行的作业（引擎退出时调用）。
     *
     * <p>顺序：先对所有作业写 SHUTDOWN 意图并**并行**派发本地停止信号（进程作业的优雅停止
     * 可能要等约 2 秒，串行会把预算吃光）；预算一半后仍在跑就升级为强制；最后放弃等待
     * （监督线程是 daemon，不会拖住 JVM）。仍 RUNNING 的行保持 RUNNING——状态说真话。</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        long startNanos = System.nanoTime();
        long deadline = startNanos + TimeUnit.MILLISECONDS.toNanos(CLOSE_TIMEOUT_MILLIS);
        long half = startNanos + TimeUnit.MILLISECONDS.toNanos(CLOSE_TIMEOUT_MILLIS / 2);
        try {
            if (!store.isClosed() && !store.listRunning().isEmpty()) {
                store.requestStopAll();
                signalAll(false, half);
                awaitNoRunning(half);
                store.escalateAll();
                signalAll(true, deadline);
                awaitNoRunning(deadline);
            }
        } catch (RuntimeException storeGone) {
            LOG.log(System.Logger.Level.DEBUG, "Job manager close could not reach the store", storeGone);
        } finally {
            snapshotClosedRecords();
            timeoutScheduler.close();
            store.close();
            runtimes.clear();
            definitions.clear();
            started = false;
        }
    }

    // ------------------------------------------------------------------ 供监督者/视图使用（包内）

    SqliteJobStore store() {
        return store;
    }

    JobTimeoutScheduler timeoutScheduler() {
        return timeoutScheduler;
    }

    /** 监督者收尾：库已关闭时静默放弃（终态已无观察者）。 */
    void finish(long jobId, int exitCode, String failure) {
        if (store.isClosed()) {
            return;
        }
        JobSpec spec = definitions.get(jobId);
        store.finish(jobId, exitCode, failure, spec == null ? null : spec.script());
    }

    /** 监督者收尾写库失败时的诊断（绝不冒到父脚本/别的作业）。 */
    void logSupervisorFailure(String name, RuntimeException failure) {
        LOG.log(System.Logger.Level.DEBUG, "Cannot write the terminal state of job [" + name + "]", failure);
    }

    /** 定义一个作业：走工厂 + 补上管理器的工作目录（编排参数在这里统一校验）。 */
    JobSpec define(String name, Map<String, String> parameters) {
        return define(name, parameters, workingDirectory);
    }

    JobSpec define(String name, Map<String, String> parameters, Path definitionDirectory) {
        JobSpec spec = specFactory.specFor(name, parameters);
        if (spec == null || spec.script() == null || spec.script().isBlank()) {
            throw new JobException("Job [" + name + "] has no script to run.");
        }
        validateOrchestration(name, spec);
        return spec.withWorkingDirectory(workingDirectory)
                .withDefinitionDirectory(definitionDirectory == null ? workingDirectory : definitionDirectory);
    }

    /** 进程作业的子进程环境：同一个库 + 自己的子 scope + 三目录 + 子进程标记。 */
    public Map<String, String> childEnvironment(String jobName) {
        java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put(SqliteJobStore.ENV_JOB_DB, store.database().toAbsolutePath().toString());
        environment.put(SqliteJobStore.ENV_JOB_SCOPE, store.scope() + "/" + jobName);
        environment.put(ProcessJobExecutor.ENV_JOB_NAME, jobName);
        environment.put(DbcliPaths.ENV_HOME, paths.home().toString());
        environment.put(DbcliPaths.ENV_WORK, paths.work().toString());
        environment.put(DbcliPaths.ENV_OUTPUT, paths.output().toString());
        environment.put(DbcliPaths.ENV_RUN_CHILD, "1");
        environment.put(DbcliPaths.ENV_RUN_ID, paths.runId());
        if (paths.legacy()) {
            environment.put(DbcliPaths.ENV_TEST_LEGACY, "1");
        }
        if (!paths.isLocked()) {
            environment.put(DbcliPaths.ENV_NO_LOCK, "1");
        }
        return Map.copyOf(environment);
    }

    /** 作业定义；不存在返回 null。 */
    JobSpec definition(long jobId) {
        return definitions.get(jobId);
    }

    Thread localThread(long jobId) {
        LocalJobRuntime runtime = runtimes.get(jobId);
        return runtime == null ? null : runtime.thread();
    }

    long localProcessId(long jobId) {
        LocalJobRuntime runtime = runtimes.get(jobId);
        Process process = runtime == null ? null : runtime.process();
        return process == null ? -1L : process.pid();
    }

    synchronized void processStarted(long jobId, Process process) {
        LocalJobRuntime runtime = runtimes.get(jobId);
        if (runtime != null) {
            runtime.setProcess(process);
        }
        if (!store.isClosed()) {
            try {
                store.setRuntimeId(jobId, "pid " + process.pid());
            } catch (RuntimeException storeGone) {
                LOG.log(System.Logger.Level.DEBUG, "Cannot record the pid of job #" + jobId, storeGone);
            }
        }
    }

    /** {@link Job} 视图读取当前行；管理器关闭后读最终快照。 */
    JobRecord recordForView(long jobId, String name) {
        JobRecord snapshot = closedRecords.get(jobId);
        if (closed) {
            if (snapshot != null) {
                return snapshot;
            }
            throw new JobException("Job [" + name + "] is no longer available (job manager closed).");
        }
        if (store.isClosed()) {
            if (snapshot != null) {
                return snapshot;
            }
            throw new JobException("Job [" + name + "] is no longer available (job database closed).");
        }
        JobRecord current = store.findById(jobId);
        if (current == null) {
            throw new JobException("Job [" + name + "] not found.");
        }
        return current;
    }

    // ------------------------------------------------------------------ 内部

    private void requireStarted() {
        if (!started) {
            throw new JobException("Job manager is not started (use _JOB JOBMANAGER ON).");
        }
    }

    private JobRecord require(String name) {
        JobRecord record = store.find(name);
        if (record == null) {
            throw new JobException("Job [" + name + "] not found.");
        }
        return record;
    }

    private Job view(JobRecord record) {
        return new Job(this, record.jobId(), record.name(), logFile(record.name(), ".out"));
    }

    private Path logFile(String name, String suffix) {
        return logDirectory.resolve(name + suffix);
    }

    private void acknowledge(long jobId, long definitionVersion) {
        if (!store.isClosed()) {
            store.acknowledge(jobId, definitionVersion);
        }
    }

    private void signalLocal(long jobId, boolean forced) {
        LocalJobRuntime runtime = runtimes.get(jobId);
        if (runtime != null) {
            runtime.signal(forced);
        }
    }

    private JobRecord awaitQuietly(long jobId, long timeoutMillis) {
        try {
            return store.awaitTerminal(jobId, timeoutMillis);
        } catch (InterruptedException interrupted) {
            // 停止动作本身是"尽力"，被外部打断时恢复中断位并如实返回当前状态
            Thread.currentThread().interrupt();
            return store.findById(jobId);
        } catch (RuntimeException storeGone) {
            JobRecord fallback = closedRecords.get(jobId);
            if (fallback != null) {
                return fallback;
            }
            throw storeGone;
        }
    }

    /** 并行派发本地停止信号（单个进程作业的优雅停止可能要等约 2 秒）。 */
    private void signalAll(boolean forced, long deadlineNanos) {
        List<Thread> stoppers = new ArrayList<>();
        for (LocalJobRuntime runtime : List.copyOf(runtimes.values())) {
            Thread stopper = new Thread(() -> runtime.signal(forced), "dbcli-job-stop-" + runtime.name());
            stopper.setDaemon(true);
            stopper.start();
            stoppers.add(stopper);
        }
        for (Thread stopper : stoppers) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remaining <= 0L) {
                return;
            }
            try {
                stopper.join(remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 等到本 scope 没有 RUNNING 行，或到截止时间。 */
    private void awaitNoRunning(long deadlineNanos) {
        while (System.nanoTime() < deadlineNanos) {
            if (store.isClosed()) {
                return;
            }
            try {
                if (store.listRunning().isEmpty()) {
                    return;
                }
            } catch (RuntimeException storeGone) {
                return;
            }
            try {
                Thread.sleep(CLOSE_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void snapshotClosedRecords() {
        try {
            for (JobRecord record : store.list()) {
                closedRecords.put(record.jobId(), record);
            }
        } catch (RuntimeException storeGone) {
            LOG.log(System.Logger.Level.DEBUG, "Cannot snapshot the job store on close", storeGone);
        }
    }

    private void scheduleTimeout(long jobId, JobSpec spec) {
        Long timeoutMillis = timeoutMillisOf(spec);
        if (timeoutMillis == null) {
            return;
        }
        timeoutScheduler.schedule(jobId, timeoutMillis, () -> {
            if (store.isClosed()) {
                return;
            }
            try {
                if (store.markTimeout(jobId)) {
                    // 先落库（意图），再让执行层尽快停下
                    signalLocal(jobId, true);
                }
            } catch (RuntimeException storeGone) {
                LOG.log(System.Logger.Level.DEBUG, "Cannot mark the timeout of job #" + jobId, storeGone);
            }
        });
    }

    /** 运行时限（毫秒，已夹紧）；null = 不限时。 */
    private static Long timeoutMillisOf(JobSpec spec) {
        Double seconds = spec.timeoutSeconds();
        if (seconds == null) {
            return null;
        }
        double millis = seconds * 1000.0;
        if (millis >= Long.MAX_VALUE / 2.0) {
            // 打字错误级别的超大值等价于"没有时限"，不能回绕成立即超时
            return Long.MAX_VALUE / 2L;
        }
        return Math.max((long) millis, 1L);
    }

    /**
     * 校验编排参数 {@code mode} / {@code timeout}。
     *
     * <p>放在这里而不是工厂里：{@link JobSpec} 是作业定义的唯一入口，任何工厂（含测试用的）
     * 产出的定义都会经过本方法，错误文案也就只有一份。</p>
     */
    private static void validateOrchestration(String name, JobSpec spec) {
        try {
            spec.mode();
        } catch (JobException badMode) {
            throw new JobException("Job [" + name + "] " + badMode.getMessage());
        }
        try {
            spec.timeoutSeconds();
        } catch (UserNumber.InvalidNumberException invalidTimeout) {
            throw new JobException(invalidTimeout.getMessage());
        }
    }

    /** 作业名的**唯一约束**：它同时是输出文件名与 scope 的一段，不能带路径语义。 */
    private static String requireUsableName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            throw new JobException("Job name is missing.");
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new JobException("Job name [" + name + "] cannot contain a path separator or '..': "
                    + "the name is also the log file name (<name>.out).");
        }
        return trimmed;
    }

    /**
     * 当前参与者（写进到达记录，**只用于观察/排错**）：监督线程 = 作业名；
     * 作业进程主线程 = 环境变量里的作业名；其余 = {@code MAIN@<pid>}。
     */
    private static String currentParticipant() {
        String threadName = Thread.currentThread().getName();
        if (threadName != null && threadName.startsWith(THREAD_NAME_PREFIX)) {
            return threadName.substring(THREAD_NAME_PREFIX.length());
        }
        String processName = System.getenv(ProcessJobExecutor.ENV_JOB_NAME);
        if (processName != null && !processName.isBlank()) {
            return processName;
        }
        return MAIN_PARTICIPANT + "@" + ProcessHandle.current().pid();
    }

    private static long currentPid() {
        return ProcessHandle.current().pid();
    }

    private static long processStartMillis() {
        return ProcessHandle.current().info().startInstant()
                .map(java.time.Instant::toEpochMilli).orElse(0L);
    }

    /**
     * 等待结果。
     *
     * @param job           作业视图（等定时点时为 null）
     * @param timerReleased 定时点是否已被放行
     * @param timedOut      本次等待是否超时（**作业状态不变**：作业可能还在跑）
     * @param resolvedName  解析后的名字（去掉 {@code JOB:}/{@code TIMER:} 前缀），供文案使用
     */
    public record WaitResult(Job job, boolean timerReleased, boolean timedOut, String resolvedName) {

        /** 供日志/调试：人类可读的耗时说明。 */
        public String describe() {
            if (job == null) {
                return timerReleased ? "timer released" : "timer wait timed out";
            }
            return job.name() + " " + job.state() + (timedOut ? " (wait timed out)" : "")
                    + " after " + Duration.ofMillis(job.elapsedMillis());
        }
    }
}
