package org.slackerdb.dbcli.job;

import org.slackerdb.dbcli.common.UserNumber;

import org.slackerdb.dbcli.rendezvous.LatchRendezvous;
import org.slackerdb.dbcli.rendezvous.RendezvousBackend;
import org.slackerdb.dbcli.rendezvous.SqliteRendezvous;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 作业管理器（**线程模型**）：每个作业 = 同一 JVM 里的一个 daemon 线程，跑"另一份脚本"。
 *
 * <h2>为什么是线程而不是子进程</h2>
 * <p>脚本执行确实会改连接、会话、工作目录这些"会话级"状态，所以每个作业仍然要有**自己的一份**
 * {@code ScriptContext}（独立连接、独立变量、独立输出）——这一点没有妥协。但"作业"不必因此变成一个
 * 独立的 JVM：{@code engine.ScriptJobExecutor} 在作业线程里新建一份上下文即可完成隔离，
 * 而换来的能力是子进程模型给不了的：</p>
 * <ul>
 *   <li><b>同步屏障可用</b>：定时点交给 {@link RendezvousBackend}（默认 {@link LatchRendezvous}，进程内闸门），
 *       父脚本与任意深度的作业线程共享同一个管理器，于是"作业等、父放行""父等、作业放行"
 *       "多个作业对齐后一起继续"都成立，**不需要任何 IPC**；</li>
 *   <li>启动开销从"几十毫秒到一秒（起一个 JVM）"降到"创建一个线程"；</li>
 *   <li>退出码、超时、中止语义与子进程模型完全一致（状态机不变），脚本里看到的东西不变。</li>
 * </ul>
 * <p>代价是：并发作业共享一个 JVM，因此共享结构必须线程安全（本类的 {@code jobs}/{@code timerPoints}
 * 都是并发容器），并且**作业线程必须一律 daemon**——否则一个不肯停的作业会把 JVM 一起拖住。</p>
 *
 * <h2>输出去哪儿</h2>
 * <p>作业的 stdout 与 stderr 都写 {@code <日志目录>/<作业名>.out}（只有这一个文件，见 {@link JobOutput}）：
 * 并发作业**绝不**交错写父脚本的 stdout，父脚本的 stdout 只由父脚本产生。</p>
 *
 * <h2>定时点（同步屏障）</h2>
 * <ul>
 *   <li>{@code _JOB TIMER <点名>} = **放行**该点名（首次调用同时创建）；</li>
 *   <li>{@code _JOB WAIT <点名> [timeout=秒]} = 在该点等待，等待前会隐式创建该点（"我在这里等，
 *       直到有人放行"）；若该点已被放行则立即返回；<b>不写 {@code timeout=} 就一直等</b>；</li>
 *   <li><b>点名不区分大小写</b>：{@code _JOB TIMER GO} 与 {@code _JOB WAIT timer:go} 是同一个点
 *       （规范化在会合点后端里做，见 {@link RendezvousBackend#normalizePoint(String)}；
 *       本类的 {@code timerPoints} 也只存规范形）。<b>作业名仍然区分大小写</b>——它是脚本里的标识符；</li>
 *   <li>回显文案里给出的是**调用方写的原样名字**（{@code Timer point [go] released.}），
 *       不是规范化后的名字；</li>
 *   <li>需要"等不到就走"的场景由脚本显式给 {@code timeout=}；超时是正常结果（返回 TIMEOUT），
 *       不会把作业停掉。</li>
 * </ul>
 *
 * <h2>会合点后端：进程内 / 跨进程</h2>
 * <p>定时点不自己实现，交给 {@link RendezvousBackend}：</p>
 * <ul>
 *   <li><b>默认</b>（线程模式）：{@link LatchRendezvous}，进程内闸门，零 IPC；</li>
 *   <li><b>出现 {@code mode=process} 作业时</b>：本管理器在**脚本工作目录**下建
 *       {@link SqliteRendezvous#SYNC_DB_FILE_NAME}（同步库），生成一个**运行代号**，
 *       并把"库路径 + 代号"通过环境变量交给作业进程（{@link #rendezvousEnvironment()}）；
 *       作业进程（以及它再起的孙作业）读同一组环境变量，于是跨进程的"父放行、子等待"
 *       "子到达、父等待"都成立；</li>
 *   <li>管理器正常关闭时尽力清理自己建的同步库。</li>
 * </ul>
 *
 * <h2>运行时限（{@code _JOB CREATE <名字> … timeout=<秒>}）</h2>
 * <p>带时限的作业会多一个 daemon 看门狗线程：到点发出**强制**停止请求（进程模式下即"杀整棵进程树"）
 * 并中断作业线程；终态由作业收尾时落地为 {@link JobState#TIMEOUT}——这样"先落盘再置终态"的硬保证
 * 对超时同样成立（见 {@link Job#markFinished(int)}）。</p>
 *
 * <h2>中止（协作式 / 进程模式 OS 级）</h2>
 * <ul>
 *   <li>{@code abort} = 请求**强制**停止 + 中断作业线程：线程模式里 {@code _SLEEP} 立刻醒来、
 *       JDBC 语句被 {@code Statement.cancel()} 取消、{@code _HOST} 的子进程被销毁；
 *       进程模式里整个子进程树被强杀；状态落到 {@link JobState#ABORTED}；</li>
 *   <li>{@code shutdown} = 先**只请求**（优雅），{@value #STOP_GRACE_MILLIS} 毫秒宽限期内没结束才中断；
 *       状态落到 {@link JobState#SHUTDOWN}；</li>
 *   <li>{@link #close()} = 请求停止 + 总预算 {@value #CLOSE_TIMEOUT_MILLIS} 毫秒的限时 join，
 *       超时**放弃**（作业线程是 daemon，绝不拖住 JVM 退出）。</li>
 * </ul>
 *
 * <h2>生命周期</h2>
 * <p>{@code jobmanager off} 只停止"接受新作业"，**不会**停掉正在跑的作业；要终止请用
 * {@code abort}/{@code shutdown}，或在管理器关闭（{@link #close()}）时统一收掉。</p>
 */
public final class JobManager implements AutoCloseable {

    /** {@code shutdown} 的宽限期（毫秒）：先请求退出，超过它还在跑才中断线程。 */
    public static final long STOP_GRACE_MILLIS = 1_000L;

    /** {@link #close()} 的总预算（毫秒）：超过就放弃等待（线程是 daemon，不会拖住 JVM）。 */
    public static final long CLOSE_TIMEOUT_MILLIS = 3_000L;

    /** 作业线程的名字前缀（{@code _JOB SHOW} 的 {@code thread} 列与线程卫生排查都认它）。 */
    public static final String THREAD_NAME_PREFIX = "dbcli-job-";

    /** 超时看门狗线程的名字前缀（**刻意**不用 {@link #THREAD_NAME_PREFIX}：它不是作业线程）。 */
    public static final String TIMEOUT_THREAD_NAME_PREFIX = "dbcli-job-timeout-";

    /** {@code _JOB WAIT} 里"强制当作业名解析"的前缀（大小写不敏感）。 */
    private static final String JOB_PREFIX = "JOB:";

    /** {@code _JOB WAIT} 里"强制当定时点名解析"的前缀（大小写不敏感）。 */
    private static final String TIMER_PREFIX = "TIMER:";

    /** 父脚本进程的参与者名字前缀：完整取值是 {@code MAIN@<pid>}（便于在多进程下指名道姓）。 */
    public static final String MAIN_PARTICIPANT = "MAIN";

    private final JobSpecFactory specFactory;
    private final JobExecutor executor;
    private final Path logDirectory;
    private final Path workingDirectory;

    /** 作业注册表：作业线程与父脚本会**并发**读写，因此用并发容器（不再需要外部锁）。 */
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    /**
     * 已经被"认领"的作业**实例**：它们的非 DONE 终态**不再**在脚本收尾时算作失败。
     *
     * <p>存实例而不是名字：{@code _JOB SET} 会用新的 {@link Job} 顶掉同名的旧作业，
     * 按名字记会让新作业继承旧作业的认领，于是"重新定义之后又失败、且没 WAIT"被静默放过。</p>
     *
     * <p>为什么要认领：{@code _JOB WAIT x}、{@code _JOB ABORT}/{@code _JOB SHUTDOWN}
     * 都是"我知道这个作业不会成功，而且是故意的"。这三个动作已经在调用点表达过结论
     * （WAIT 会报错，ABORT/SHUTDOWN 本身是显式操作），
     * 收尾时再算一次失败就会把"故意停掉一个作业"变成脚本失败。</p>
     *
     * <p>反过来，**没有**被认领的失败作业（跑失败了但脚本没 WAIT 也没 ABORT）会在
     * {@link #hasUnacknowledgedFailures()} 里被发现，由引擎收尾时算一次失败——
     * 这正是"子作业失败不能静默通过"的落地点。</p>
     */
    private final Set<Job> acknowledgedJobs = ConcurrentHashMap.newKeySet();

    /**
     * 已经出现过的定时点名（并发 Set）：只用于**名字解析**（"先作业名、再定时点名"）与隐式创建，
     * 真正的等待/放行状态在 {@link #rendezvous} 里。
     *
     * <p>存的是 {@link RendezvousBackend#normalizePoint(String)} 之后的**规范形**，与会合点后端
     * 用的规范化是同一份实现——否则"这个点见过没有"的判断会与"能不能等到它"不一致。
     * 作业注册表 {@link #jobs} 刻意**不**规范化：作业名是标识符，仍然区分大小写。</p>
     */
    private final Set<String> timerPoints = ConcurrentHashMap.newKeySet();

    /** 会合点后端：默认进程内闸门；出现 {@code mode=process} 作业时切到 SQLite 同步库。 */
    private volatile RendezvousBackend rendezvous;

    /** 进程模式用的同步库路径（未使用进程模式时为 null）。 */
    private volatile Path syncDatabase;

    /** 本次运行的代号（GUID）：识别"同步库是不是上一次被强杀留下的"。 */
    private volatile String syncGeneration;

    /** 同步库是不是**本进程**建的（只有建它的进程才负责清理）。 */
    private volatile boolean ownsSyncDatabase;

    private final Object rendezvousLock = new Object();

    private volatile boolean started;

    public JobManager(JobSpecFactory specFactory, JobExecutor executor, Path logDirectory,
                      Path workingDirectory) {
        this(specFactory, executor, logDirectory, workingDirectory, fromEnvironment(workingDirectory));
    }

    /**
     * @param inherited 从环境变量继承来的跨进程会合点（作业进程用它；父进程传 null 用进程内闸门）
     */
    public JobManager(JobSpecFactory specFactory, JobExecutor executor, Path logDirectory,
                      Path workingDirectory, RendezvousBackend inherited) {
        this.specFactory = specFactory;
        this.executor = executor;
        this.logDirectory = logDirectory;
        this.workingDirectory = workingDirectory;
        this.rendezvous = inherited != null ? inherited : new LatchRendezvous();
        if (inherited != null) {
            // 继承来的后端可能已经绑着一个同步库：把它记进字段，否则
            // ① rendezvousEnvironment() 是空表（孙作业不知道用哪个库），
            // ② prepareRendezvousFor() 会以为"还没切到进程模式"，用新代号在同一个库上重建，
            //    而 SqliteRendezvous.claimRun 会把父进程已放行的点当成"上一代遗留"清空。
            this.syncDatabase = inherited.database();
            this.syncGeneration = inherited.generation();
        }
    }

    /**
     * 作业进程的会合点：环境变量里有"同步库路径 + 运行代号"就用 SQLite（跨进程），否则 null。
     *
     * <p>这两个变量由父进程在启动 {@code mode=process} 作业时设置
     * （见 {@link #rendezvousEnvironment()}），并由子进程继续传给孙作业，所以**任意深度**都一样。</p>
     */
    private static RendezvousBackend fromEnvironment(Path workingDirectory) {
        String database = System.getenv(SqliteRendezvous.ENV_SYNC_DB);
        String generation = System.getenv(SqliteRendezvous.ENV_SYNC_GENERATION);
        if (database == null || database.isBlank() || generation == null || generation.isBlank()) {
            return null;
        }
        return SqliteRendezvous.open(Path.of(database), generation);
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

    // ------------------------------------------------------------------ 作业

    /**
     * {@code _JOB CREATE <名字> key=value...}：定义作业（不启动线程）。
     *
     * @throws JobException 管理器未启动、重名、或参数里没有可跑的脚本
     */
    public Job create(String name, Map<String, String> parameters) {
        requireStarted();
        String jobName = requireUsableName(name);
        if (jobs.containsKey(jobName)) {
            throw new JobException("Job [" + jobName + "] already exists.");
        }
        JobSpec spec = define(jobName, parameters);
        prepareRendezvousFor(spec);
        Job job = new Job(spec, logFile(jobName, ".out"));
        // 登记必须**原子**：并发（父脚本 + 作业线程）同名创建时，先检查后写入会让两个线程都成功，
        // 后一个覆盖前一个，于是有一个作业谁也 WAIT/ABORT 不到，两个线程还写同一个 <名字>.out。
        if (jobs.putIfAbsent(jobName, job) != null) {
            throw new JobException("Job [" + jobName + "] already exists.");
        }
        return job;
    }

    /** {@code _JOB SET <名字> key=value...}：更新定义（运行中不允许改）。 */
    public Job setParameters(String name, Map<String, String> parameters) {
        String jobName = requireUsableName(name);
        Job job = require(jobName);
        if (job.state() == JobState.RUNNING) {
            throw new JobException("Job [" + jobName + "] is running; parameters cannot be changed.");
        }
        JobSpec spec = define(jobName, parameters);
        prepareRendezvousFor(spec);
        Job updated = new Job(spec, job.stdoutFile());
        jobs.put(jobName, updated);
        return updated;
    }

    /**
     * {@code _JOB START <名字>}：启动作业线程。
     *
     * @throws JobException 管理器未启动、作业不存在、作业已经跑过、或输出文件打不开
     */
    public Job start(String name) {
        requireStarted();
        Job job = require(name);
        if (job.state() != JobState.CREATED) {
            throw new JobException("Job [" + name + "] is already " + job.state() + ".");
        }
        JobOutput output;
        try {
            Files.createDirectories(logDirectory);
            // 进程模式：输出由**子进程自己**重定向写入，父进程不能同时持有写句柄（Windows 共享冲突）
            output = job.spec().mode() == JobMode.PROCESS
                    ? JobOutput.discard(job.stdoutFile())
                    : JobOutput.open(job.stdoutFile());
        } catch (IOException startFailure) {
            job.markFailedToStart(startFailure.getMessage());
            // 这条错误已经由本次 START 报过一次，且作业**根本没跑起来**：再让收尾的
            // "it was started but never checked by _JOB WAIT" 报一次，会把同一个故障算成两个错误。
            acknowledge(name);
            throw new JobException("Job [" + name + "] failed to start: " + startFailure.getMessage(),
                    startFailure);
        }
        Thread thread = new Thread(() -> runJob(job, output), THREAD_NAME_PREFIX + name);
        // 必须 daemon：作业线程绝不能拖住 JVM 退出（否则 surefire 一类宿主会等到超时并写 dump）
        thread.setDaemon(true);
        if (!job.startThread(thread)) {
            // 发布 RUNNING 与 thread.start() 之间被 abort/shutdown 抢先：这个作业不该再跑
            closeQuietly(output);
            throw new JobException("Job [" + name + "] is already " + job.state() + ".");
        }
        startTimeoutWatchdog(job);
        return job;
    }

    /**
     * {@code _JOB WAIT <名字> [timeout=N]}：等作业结束，或在定时点上等待。
     *
     * <p>名字解析顺序：<b>先按作业名</b>（区分大小写），找不到再按定时点名（**不区分大小写**）；
     * 可用前缀强制指定（{@code JOB:<名字>} / {@code TIMER:<点名>}，前缀本身也不区分大小写）。
     * 两者都不是时报错，**不会**静默挂住。</p>
     *
     * <p>回显用的 {@link WaitResult#resolvedName()} 是**调用方写的原样名字**（去掉前缀之后），
     * 不是规范化后的名字：使用者应当看到自己写的那一份。</p>
     *
     * @param timeoutMillis 超时；{@code null} 表示**一直等**（脚本没写 {@code timeout=}）
     * @return 等待后的作业（定时点等待返回 null，结果看 {@link WaitResult#timerReleased()}
     */
    public WaitResult waitFor(String rawName, Long timeoutMillis) {
        long timeout = timeoutMillis == null ? RendezvousBackend.NO_TIMEOUT : timeoutMillis;
        String name = rawName == null ? "" : rawName.strip();
        // 前缀大小写不敏感：`_JOB WAIT TIMER:x` 与 `_JOB WAIT timer:x` 必须等价（命令关键字一律大小写不敏感）
        String upper = name.toUpperCase(Locale.ROOT);

        if (upper.startsWith(TIMER_PREFIX)) {
            String point = name.substring(TIMER_PREFIX.length()).strip();
            if (point.isEmpty()) {
                // `_JOB WAIT TIMER:`：前缀后面没有点名。不挡住的话会等一个"空点名"的点，
                // 而这个点永远不会被放行（= 静默挂住），违反"两者都不是时报错"的约定。
                throw new JobException("Timer point name is missing: write _JOB WAIT TIMER:<point>.");
            }
            return waitTimer(point, timeout);
        }
        if (upper.startsWith(JOB_PREFIX)) {
            // 后缀同样 strip：`_JOB WAIT JOB: j1` 与 `_JOB WAIT JOB:j1` 必须等价
            return waitJob(name.substring(JOB_PREFIX.length()).strip(), timeout);
        }
        if (jobs.containsKey(name)) {
            // 作业名区分大小写：`_JOB WAIT job1` 找不到 `JOB1`（标识符语义，刻意不跟着点名一起放宽）
            return waitJob(name, timeout);
        }
        if (knowsTimerPoint(name)) {
            return waitTimer(name, timeout);
        }
        throw new JobException("Job or timer point [" + name + "] not found.");
    }

    /**
     * 这个点名是否已经出现过（决定 {@code _JOB WAIT <名字>} 的第二种解释）。
     *
     * <p>本进程的 {@code timerPoints} 只记得"本进程见过"的点；进程模式作业里等父进程放行的点
     * 只存在于跨进程后端（SQLite 同步库）里，所以必须同时问后端——否则同一份脚本在 thread 模式下
     * 通过、在 process 模式下报 {@code Job or timer point [GO] not found.}。</p>
     */
    private boolean knowsTimerPoint(String name) {
        String normalized = RendezvousBackend.normalizePoint(name);
        return timerPoints.contains(normalized) || rendezvous.knows(name);
    }

    private WaitResult waitJob(String name, long timeout) {
        Job job = require(name);
        if (job.state() == JobState.CREATED) {
            // 没启动过的作业永远不会"结束"：等它就是等到超时（或永远等下去）。
            // 这多半是漏写了 _JOB START，因此当场报错，而不是给一个看起来成功的空结果。
            throw new JobException("Job [" + name + "] was never started (use _JOB START first).");
        }
        if (job.isRunning()) {
            boolean finished;
            try {
                finished = awaitThread(job, timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new JobException("Interrupted while waiting for job [" + name + "].", interrupted);
            }
            if (!finished) {
                // 超时：**不改作业状态**（作业可能还在跑），只把"这次等待没等到"报告给调用方
                return new WaitResult(job, false, true, name);
            }
        }
        return new WaitResult(job, false, false, name);
    }

    private WaitResult waitTimer(String point, long timeout) {
        // 隐式创建：作业先到就先建点、在这里等，主脚本随后 TIMER 放行。
        // 登记的是规范形（与会合点后端同一份规范化），因此"点见过没有"和"能不能等到"永远一致。
        timerPoints.add(RendezvousBackend.normalizePoint(point));
        // 到达记录（同步库里的 sync_arrive）：只用于观察/排错，不改变放行语义
        rendezvous.arrive(point, currentParticipant());
        boolean released;
        try {
            released = rendezvous.await(point, timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new JobException("Interrupted while waiting for timer point [" + point + "].", interrupted);
        }
        // 回显原样名字：调用方写 `timer:go`，文案里就是 `go`，不是 `GO`
        return new WaitResult(null, released, !released, point);
    }

    /** {@code _JOB TIMER <点名>}：放行该点名（首次调用同时创建，幂等；点名不区分大小写）。 */
    public String releaseTimer(String point) {
        timerPoints.add(RendezvousBackend.normalizePoint(point));
        rendezvous.release(point);
        // 返回值用于回显：原样交回调用方写的名字
        return point;
    }

    /**
     * 当前参与者的名字（写进同步库的到达记录，**只用于观察/排错**，不参与同步语义）：
     * 作业线程 = 作业名；作业进程的主线程 = 环境变量里的作业名；其余（父脚本进程）= {@code MAIN@<pid>}。
     *
     * <p>带上 pid 是为了让"谁到了"在多进程场景下不留歧义：{@code MAIN} 在父进程与每个作业进程里
     * 都存在，而 {@code MAIN@1234} 只可能是一个进程。</p>
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

    /**
     * {@code _JOB SHOW <名字>}：读当前状态。
     *
     * <p>线程模型下状态由作业线程自己推进（结束前一定已经落好终态），因此这里不需要"补状态"，
     * 只需读一次 {@link JobState}（字段是 volatile，跨线程可见）。</p>
     */
    public Job show(String name) {
        return require(name);
    }

    /** 尚未结束的作业（引擎收尾时用）。 */
    public Collection<Job> jobs() {
        return List.copyOf(jobs.values());
    }

    /**
     * 认领一个作业的非 DONE 终态：此后脚本收尾不再把它算作失败（见 {@link #acknowledgedJobs}）。
     *
     * <p>幂等；不存在的作业名也可以认领（{@code _JOB WAIT} 之后作业可能已被释放）。</p>
     */
    public void acknowledge(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        Job job = jobs.get(name);
        if (job != null) {
            acknowledgedJobs.add(job);
        }
    }

    /** 认领一个具体实例（{@code _JOB SET} 之后旧实例的认领不会跟到新实例上，见字段注释）。 */
    public void acknowledge(Job job) {
        if (job != null) {
            acknowledgedJobs.add(job);
        }
    }

    /**
     * 有没有"失败或超时、且没有被认领"的作业。
     *
     * <p>引擎用它把"脚本没检查的失败"补成一条**错误行**并计入错误数（{@code _JOB WAIT} 没写过、
     * 也没有显式停掉它时）：这条文案是"并发作业不能静默失败"的最后一道网。</p>
     *
     * <p><b>它不直接改进程退出码</b>：错误计数只决定 {@code _WHENEVER ERROR} 的行为，而最终的
     * 进程退出码由 {@code ScriptContext.finalExitCode()} 裁决——脚本内部错误默认**不**让进程失败
     * （README §9.3）。要让"子作业失败"变成非 0，脚本里写 {@code _WHENEVER ERROR EXIT 1} 或
     * {@code _EXIT 1}。</p>
     */
    public boolean hasUnacknowledgedFailures() {
        return !unacknowledgedFailureMessages().isEmpty();
    }

    /**
     * 认领所有当前失败的作业（引擎补报之后调用，避免同一件事被算两次）。
     *
     * <p>与 {@link #acknowledge(String)} 的区别只是"一次全认"，供收尾路径使用。</p>
     */
    public void acknowledgeFailures() {
        for (Job job : jobs.values()) {
            JobState state = job.state();
            if (state == JobState.FAILED || state == JobState.TIMEOUT) {
                acknowledge(job);
            }
        }
    }

    /**
     * "没被检查过的失败作业"的可读说明（一条一个作业），按作业名排序保证输出稳定。
     *
     * <p>为什么由管理器生成文案：它同时掌握终态、退出码与"有没有被认领"三件事；
     * 引擎只负责把这几行打出来并计一次错误（见 {@code ScriptRunner#reportUnacknowledgedJobFailures}）。</p>
     */
    public List<String> unacknowledgedFailureMessages() {
        List<String> messages = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (acknowledgedJobs.contains(job)) {
                continue;
            }
            JobState state = job.state();
            if (state == JobState.TIMEOUT) {
                messages.add("Job [" + job.name() + "] timed out "
                        + "(it was started but never checked by _JOB WAIT).");
            } else if (state == JobState.FAILED) {
                int exitCode = job.exitCode();
                messages.add("Job [" + job.name() + "] failed"
                        + (exitCode == Integer.MIN_VALUE ? "" : " with exit code " + exitCode)
                        + " (it was started but never checked by _JOB WAIT).");
            }
        }
        Collections.sort(messages);
        return messages;
    }

    /**
     * {@code _JOB ABORT <名字>}：请求**强制**停止 + 中断线程。
     *
     * <p>终态只在线程**确实结束**之后才落（{@code ABORTED}）；1 秒宽限内没停下时状态保持
     * {@code RUNNING}（{@code _JOB SHOW} 如实显示），线程真正退出时按已记下的停止意图落终态。</p>
     */
    public Job abort(String name) {
        Job job = require(name);
        // 显式操作即结论：这个作业不会成功是使用者要求的，收尾时不再算失败
        acknowledge(name);
        job.requestStop(true, JobState.ABORTED);
        Thread thread = job.thread();
        if (thread != null) {
            thread.interrupt();
            if (joinQuietly(thread, STOP_GRACE_MILLIS)) {
                job.markAborted();
            }
        } else {
            // 还没启动就被 abort：没有线程可等，直接落终态
            job.markAborted();
        }
        return job;
    }

    /**
     * {@code _JOB SHUTDOWN <名字>}：先只请求（优雅），宽限期后仍在跑才中断。
     *
     * <p>与 {@link #abort} 同样的终态纪律：只有确认线程结束才落 {@code SHUTDOWN}。</p>
     */
    public Job shutdown(String name) {
        Job job = require(name);
        // 同上：shutdown 是显式操作，不是"作业自己失败了"
        acknowledge(name);
        job.requestStop(false, JobState.SHUTDOWN);
        Thread thread = job.thread();
        if (thread == null) {
            job.markShutdown();
            return job;
        }
        boolean stopped = joinQuietly(thread, STOP_GRACE_MILLIS);
        if (!stopped) {
            thread.interrupt();
            stopped = joinQuietly(thread, STOP_GRACE_MILLIS);
        }
        if (stopped) {
            job.markShutdown();
        }
        return job;
    }

    // ------------------------------------------------------------------ 跨进程会合点

    /**
     * 跨进程会合点的环境变量（**空**表示本次运行不需要跨进程会合）。
     *
     * <p>由 {@code ProcessJobExecutor} 灌进子进程的环境：作业进程用同一个同步库与运行代号，
     * 于是"父放行、子等待"与"子到达、父等待"都成立；作业进程再起孙作业时会继续往下一层传。</p>
     */
    public Map<String, String> rendezvousEnvironment() {
        Path database = syncDatabase;
        String generation = syncGeneration;
        if (database == null || generation == null) {
            return Map.of();
        }
        Map<String, String> environment = new HashMap<>();
        environment.put(SqliteRendezvous.ENV_SYNC_DB, database.toAbsolutePath().toString());
        environment.put(SqliteRendezvous.ENV_SYNC_GENERATION, generation);
        return environment;
    }

    /** 当前的会合点后端（测试与诊断用）。 */
    public RendezvousBackend rendezvous() {
        return rendezvous;
    }

    /** 同步库路径；没有跨进程会合时为 null。 */
    public Path syncDatabase() {
        return syncDatabase;
    }

    /** 本次运行的代号；没有跨进程会合时为 null。 */
    public String syncGeneration() {
        return syncGeneration;
    }

    /**
     * 作业定义里带 {@code mode=process} 时，把会合点从"进程内闸门"切成"SQLite 同步库"。
     *
     * <p>切换发生在**创建**作业的时候（{@code _JOB CREATE/SET}）：这样父脚本此后的
     * {@code _JOB TIMER}/{@code _JOB WAIT} 都落在同步库上，与作业进程看得见的是同一份状态。
     * 已经用进程内闸门放行过的点名**不会**迁移（换后端之后是另一套状态）——这是刻意的、
     * 也是可预期的：脚本应当先定义作业、再编排屏障。</p>
     */
    private void prepareRendezvousFor(JobSpec spec) {
        if (spec.mode() != JobMode.PROCESS || syncDatabase != null) {
            return;
        }
        synchronized (rendezvousLock) {
            if (syncDatabase != null) {
                return;
            }
            Path database = workingDirectory.resolve(SqliteRendezvous.SYNC_DB_FILE_NAME);
            String generation = UUID.randomUUID().toString();
            RendezvousBackend previous = rendezvous;
            rendezvous = SqliteRendezvous.create(database, generation);
            syncDatabase = database;
            syncGeneration = generation;
            ownsSyncDatabase = true;
            previous.close();
        }
    }

    /** 超时看门狗：到点发出**强制**停止请求（进程模式 = 杀整棵进程树）并中断作业线程。 */
    private void startTimeoutWatchdog(Job job) {
        Double seconds = job.spec().timeoutSeconds();
        if (seconds == null) {
            return;
        }
        long timeoutMillis = (long) (seconds * 1000.0);
        Thread watchdog = new Thread(() -> {
            // 溢出安全：timeout 写得极大时（打字错误）等价于"没有时限"，绝不能回绕成"已到点"
            long deadline = System.nanoTime() + RendezvousBackend.nanosForMillis(timeoutMillis);
            while (System.nanoTime() < deadline) {
                if (!job.isRunning()) {
                    return;
                }
                try {
                    Thread.sleep(Math.min(50L, Math.max((deadline - System.nanoTime()) / 1_000_000L, 1L)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!job.isRunning()) {
                return;
            }
            // 顺序要紧：先记下"这是超时收尾"，再请求强制停止（回调会杀进程树），最后中断线程。
            // 终态由作业收尾（或请求方确认线程结束后）落地为 TIMEOUT。
            job.requestTimeout();
            job.requestStop(true, JobState.TIMEOUT);
            Thread worker = job.thread();
            if (worker != null) {
                worker.interrupt();
            }
        }, TIMEOUT_THREAD_NAME_PREFIX + job.name());
        watchdog.setDaemon(true);
        watchdog.start();
    }

    // ------------------------------------------------------------------ 收尾

    /**
     * 停掉所有仍在运行的作业（引擎退出时调用）。
     *
     * <p>顺序：先**并行**对所有作业请求停止（进程模式下单个请求可能等子进程约 2 秒，串行会把预算吃光），
     * 再按总预算 {@value #CLOSE_TIMEOUT_MILLIS} 毫秒（扣除已用时间）限时 join；超时才对还没停的线程中断
     * 并再等一会儿。仍然不停就**放弃**：作业线程是 daemon，不会拖住 JVM；它的状态保持
     * {@code RUNNING}（说真话），真正退出时按已记下的停止意图落 {@code SHUTDOWN}。</p>
     */
    @Override
    public void close() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_TIMEOUT_MILLIS);
        List<Job> running = new ArrayList<>();
        for (Job job : new ArrayList<>(jobs.values())) {
            if (job.isRunning()) {
                running.add(job);
            }
        }
        // 1) 并行发出停止请求（每个请求可能阻塞：进程模式要等子进程退出）
        List<Thread> stoppers = new ArrayList<>(running.size());
        for (Job job : running) {
            Thread stopper = new Thread(() -> job.requestStop(false, JobState.SHUTDOWN),
                    "dbcli-job-stop-" + job.name());
            stopper.setDaemon(true);
            stopper.start();
            stoppers.add(stopper);
        }
        for (Thread stopper : stoppers) {
            joinQuietly(stopper, remainingMillis(deadline));
        }
        // 2) 限时 join 作业线程
        for (Job job : running) {
            joinQuietly(job.thread(), remainingMillis(deadline));
        }
        // 3) 还没停的：中断再等一会儿
        for (Job job : running) {
            if (!job.isRunning()) {
                continue;
            }
            Thread thread = job.thread();
            if (thread != null) {
                thread.interrupt();
                joinQuietly(thread, remainingMillis(deadline));
            }
        }
        // 4) 已经自己跑完的作业保持它的终态（DONE/FAILED）；被停掉的作业由收尾落了 SHUTDOWN。
        //    仍然活着的保持 RUNNING：它的 daemon 线程会在退出时按停止意图落终态，不谎报"已关闭"。
        started = false;
        // 同步库由**建它的那个进程**尽力清理；作业进程只是关自己的连接（删了会把父进程正在用的库抽走）
        rendezvous.close();
    }

    // ------------------------------------------------------------------ 内部工具

    private void requireStarted() {
        if (!started) {
            throw new JobException("Job manager is not started (use _JOB JOBMANAGER ON).");
        }
    }

    /**
     * 作业名的**唯一约束**：它同时被当作日志文件名（{@code <名字>.out}），因此不能带路径语义。
     *
     * <p>为什么必须挡：{@code _JOB CREATE ../../evil} 会把输出写到日志目录之外，
     * 名字里带 {@code ..} 还能覆盖同目录的任意 {@code .out}。这里只做最小限制
     * （分隔符与 {@code ..}），不替使用者规定命名风格。</p>
     *
     * @throws JobException 名字为空或带路径语义
     */
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

    private Job require(String name) {
        Job job = jobs.get(name);
        if (job == null) {
            throw new JobException("Job [" + name + "] not found.");
        }
        return job;
    }

    /** 定义作业：走工厂 + 补上管理器的工作目录（编排参数在这里统一校验）。 */
    private JobSpec define(String name, Map<String, String> parameters) {
        JobSpec spec = specFactory.specFor(name, parameters);
        if (spec == null || spec.script() == null || spec.script().isBlank()) {
            throw new JobException("Job [" + name + "] has no script to run.");
        }
        validateOrchestration(name, spec);
        return spec.withWorkingDirectory(workingDirectory);
    }

    /**
     * 校验编排参数 {@code mode} / {@code timeout}。
     *
     * <p>放在这里而不是工厂里：{@link JobSpec} 是作业定义的唯一入口，任何工厂（含测试用的）
     * 产出的定义都会经过本方法，错误文案也就只有一份。{@code mode=process} 的取值大小写不敏感。</p>
     */
    private static void validateOrchestration(String name, JobSpec spec) {
        try {
            spec.mode();
        } catch (JobException badMode) {
            throw new JobException("Job [" + name + "] " + badMode.getMessage());
        }
        try {
            // 数值解析、正数/有限性校验与文案都在 UserNumber 里（NaN/Infinity 也在这里挡住：
            // `(long) (NaN * 1000) == 0` 会让看门狗把"deadline 已到"当成真的）。
            spec.timeoutSeconds();
        } catch (UserNumber.InvalidNumberException invalidTimeout) {
            throw new JobException(invalidTimeout.getMessage());
        }
    }

    private Path logFile(String name, String suffix) {
        return logDirectory.resolve(name + suffix);
    }

    /** 作业线程主体：跑执行体、落地状态、写失败原因，**绝不**把异常抛给别的线程。 */
    private void runJob(Job job, JobOutput output) {
        int exitCode = Integer.MIN_VALUE;
        Throwable failure = null;
        try {
            exitCode = executor.execute(new JobContext(this, job, output));
        } catch (Throwable caught) {
            failure = caught;
            writeFailure(output, caught);
        } finally {
            // **先落盘并关闭作业输出，再置终态**：父脚本 `_JOB WAIT` 看到 DONE/FAILED/TIMEOUT 就代表
            // `<作业名>.out` 已经完整。反过来的话，并发时父进程可能在文件 flush 之前就读它，
            // 尾部行会丢（这正是"作业输出必须完整且保持顺序"这条契约的落地点）。
            // 进程模式下的 output 是丢弃型的：真正的输出由子进程直接写文件，它退出时已经落盘。
            closeQuietly(output);
            job.clearCancellers();
            if (failure == null) {
                job.markFinished(exitCode);
            } else {
                job.markFailed(messageOf(failure));
            }
        }
    }

    /** 作业线程抛出异常时的可读错误：写进 {@code <作业名>.out}（作业的错不该冒到父脚本线程）。 */
    private static void writeFailure(JobOutput output, Throwable failure) {
        if (output == null) {
            return;
        }
        try {
            output.stderr().print("ERROR: " + messageOf(failure));
            output.stderr().print('\n');
            output.stderr().flush();
        } catch (RuntimeException ignored) {
            // 错误文件写不进去也无法补救：作业状态已经记在 failureMessage 上
        }
    }

    private static String messageOf(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /**
     * 等作业线程结束。
     *
     * <p>三种取值：{@link RendezvousBackend#NO_TIMEOUT}（负）表示一直等；
     * {@code 0} 表示"只看一眼当前状态"（与既有的 {@code waitFor(0)} 一致）；
     * 正数表示最多等这么多毫秒。</p>
     */
    private static boolean awaitThread(Job job, long timeout) throws InterruptedException {
        Thread thread = job.thread();
        if (thread == null) {
            return true;
        }
        if (timeout < 0L) {
            thread.join();
        } else if (timeout > 0L) {
            thread.join(timeout);
        }
        // 判据是"线程活没活"，不是"状态是不是 RUNNING"：被 ABORT/SHUTDOWN/管理器关闭"停掉"的作业，
        // 线程可能已经结束而终态由请求方稍后落地（Job.markFinished 在 stopRequested 时提前返回）。
        // 那时看状态就会把"已经跑完的作业"报成 wait timed out —— 一次假的超时与假的失败。
        return !thread.isAlive();
    }

    /** 限时 join；返回是否已经结束（被打断时恢复中断位并如实返回 false）。 */
    private static boolean joinQuietly(Thread thread, long timeoutMillis) {
        if (thread == null || !thread.isAlive()) {
            return true;
        }
        if (timeoutMillis <= 0) {
            return !thread.isAlive();
        }
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    private static long remainingMillis(long deadlineNanos) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return Math.max(remaining, 0L);
    }

    private static void closeQuietly(JobOutput output) {
        if (output != null) {
            output.close();
        }
    }

    /**
     * 等待结果。
     *
     * @param job           作业（等定时点时为 null）
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
