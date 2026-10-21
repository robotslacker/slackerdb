package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.common.CommandOutcome;
import org.slackerdb.dbcli.compare.CompareCommandDispatcher;
import org.slackerdb.dbcli.compare.LineRules;
import org.slackerdb.dbcli.job.DbcliJobSpecFactory;
import org.slackerdb.dbcli.job.JobCommandDispatcher;
import org.slackerdb.dbcli.job.JobManager;
import org.slackerdb.dbcli.job.ModeJobExecutor;
import org.slackerdb.dbcli.job.ProcessJobExecutor;
import org.slackerdb.dbcli.sql.SqlExecutor;
import org.slackerdb.dbcli.ssh.SshClient;
import org.slackerdb.dbcli.ssh.SshCommandDispatcher;
import org.slackerdb.dbcli.ssh.SshSessionRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "IR → 要打印的结果" 的统一入口：把 SQL 会话命令与三个既有分派器（{@code _COMPARE} /
 * {@code _JOB} / {@code _SSH}）收在一条路上。
 *
 * <p>为什么不直接把它们写在 {@link ScriptRunner} 里：这三组命令的产物形状完全一样
 * （{@link CommandOutcome} 三态），输出纪律（状态行 → stdout、
 * 结果集 → 表格、错误 → stdout 并计错误数）是**引擎级**的统一规则。集中到本类之后，
 * {@code ScriptRunner} 只认识 {@code List<CommandOutcome>}，不必知道每条命令是谁实现的。</p>
 *
 * <p>{@code _CONNECT}/{@code _DISCONNECT}/{@code _SESSION} 也走这里：它们由
 * {@link SqlExecutor} 实现，产物同样是三态。</p>
 *
 * <p><b>懒加载</b>：{@code _COMPARE}/{@code _JOB}/{@code _SSH} 的实现对象只在脚本真的用到时才创建
 * ——一个只跑 SQL 的脚本不该因为它们而被牵连。</p>
 *
 * <p><b>{@code _JOB} 的管理器可以被"外部传入"</b>（{@link #CommandDispatcher(SqlExecutor, Path, JobManager)}）：
 * 作业线程新建自己的 {@link ScriptContext} 时就是这么做的——会话状态独立，但作业管理器与父脚本**同一个**，
 * 于是定时点（屏障）在父脚本与任意深度的作业之间都成立。</p>
 */
public final class CommandDispatcher implements AutoCloseable {

    /** 由本分派器处理的 IR 名字。 */
    private static final Set<String> SUPPORTED = Set.of(
            "CONNECT", "DISCONNECT", "SESSION", "COMPARE", "JOB", "SSH");

    private final SqlExecutor sqlExecutor;
    private final Path workingDirectory;

    /** 外部传入的作业管理器（作业线程复用父脚本的那一个）；null 表示"由本分派器自建并自己关闭"。 */
    private final JobManager sharedJobManager;

    private CompareCommandDispatcher compare;

    /** {@code _SET DIFF_REPORT_HTML}：有差异时是否另外写 HTML（DIF 总是写）。 */
    private boolean diffReportHtml;
    private SshSessionRegistry sshRegistry;
    private SshCommandDispatcher ssh;
    private JobManager jobManager;
    private JobCommandDispatcher job;

    public CommandDispatcher(SqlExecutor sqlExecutor, Path workingDirectory) {
        this(sqlExecutor, workingDirectory, null);
    }

    /**
     * @param sharedJobManager 复用的作业管理器（{@code null} 表示本分派器自建一个，并在 {@link #close()} 时关掉它）
     */
    public CommandDispatcher(SqlExecutor sqlExecutor, Path workingDirectory, JobManager sharedJobManager) {
        this.sqlExecutor = sqlExecutor;
        this.workingDirectory = workingDirectory;
        this.sharedJobManager = sharedJobManager;
    }

    /** 这条 IR 是否由本分派器处理。 */
    public static boolean supports(String name) {
        return name != null && SUPPORTED.contains(name.toUpperCase(Locale.ROOT));
    }

    /**
     * 执行一条命令。
     *
     * @param ir {@code SqlAnalyzer} 给出的 IR（必须有 {@code name}）
     */
    public List<CommandOutcome> dispatch(Map<String, Object> ir) {
        String name = ir.get("name") == null ? "" : String.valueOf(ir.get("name")).toUpperCase(Locale.ROOT);
        List<CommandOutcome> outcomes = new ArrayList<>();
        try {
            switch (name) {
                case "CONNECT" -> {
                    sqlExecutor.connect(ir);
                    outcomes.add(new CommandOutcome.Status("Database connected."));
                }
                case "DISCONNECT" -> {
                    if (!sqlExecutor.isConnected()) {
                        outcomes.add(new CommandOutcome.Error("Not connected."));
                    } else {
                        sqlExecutor.disconnect();
                        outcomes.add(new CommandOutcome.Status("Database disconnected."));
                    }
                }
                case "SESSION" -> outcomes.addAll(sqlExecutor.session(
                        ir.get("action") == null ? null : String.valueOf(ir.get("action")),
                        ir.get("sessionName") == null ? null : String.valueOf(ir.get("sessionName"))));
                case "COMPARE" -> outcomes.addAll(compare().execute(ir));
                case "JOB" -> outcomes.addAll(job().execute(ir));
                case "SSH" -> outcomes.addAll(ssh().execute(ir));
                default -> outcomes.add(new CommandOutcome.Error("Unsupported command [" + name + "]"));
            }
        } catch (RuntimeException failure) {
            // 分派器的实现里也有运行期异常路径（如 _SSH 未连接、作业管理器不可用）：
            // 一律收敛成一条错误输出，不让异常冒到引擎外面（§3.7 未捕获异常 255 是最后一道网）
            outcomes.add(new CommandOutcome.Error(messageOf(failure)));
        }
        return outcomes;
    }

    /**
     * {@code _SET DIFF_REPORT_HTML ON|OFF}（默认 OFF）：{@code _COMPARE} 有差异时是否**另外**写一份 HTML。
     *
     * <p>{@code .dif} 永远写，HTML 只是"想用人看"时的附加产物。开关可能在 {@code _COMPARE} 之前或
     * 之后设置，所以这里既记下当前值，也立刻转给（可能还不存在的）比较分派器。</p>
     */
    public void setDiffReportHtml(boolean enabled) {
        this.diffReportHtml = enabled;
        if (compare != null) {
            compare.setDiffReportHtml(enabled);
        }
    }

    private CompareCommandDispatcher compare() {
        if (compare == null) {
            compare = new CompareCommandDispatcher(workingDirectory);
            compare.setDiffReportHtml(diffReportHtml);
        }
        return compare;
    }

    /**
     * 脚本里声明的 mask/skip 规则（{@code _COMPARE MASKLINE/SKIPLINE}），供 {@code --ref} 的收尾比对继承。
     *
     * <p>没有用过 {@code _COMPARE} 时返回**空规则**（不是 null）：调用方不必分叉，
     * 语义就是"没有任何放宽"。这里刻意不创建 compare 实例——只跑 SQL 的脚本不该因为收尾比对
     * 而被牵连出一个分派器对象。</p>
     */
    public LineRules compareRules() {
        // 每次给一个新实例：LineRules 是可变的，共享一个空实例迟早有人 add() 进去
        return compare == null ? new LineRules() : compare.rules();
    }

    private SshCommandDispatcher ssh() {
        if (ssh == null) {
            sshRegistry = new SshSessionRegistry();
            ssh = new SshCommandDispatcher(sshRegistry, SshClient::new);
        }
        return ssh;
    }

    private JobCommandDispatcher job() {
        if (job == null) {
            // 默认执行层是"同 JVM 线程"（ScriptJobExecutor）；`_JOB CREATE … mode=process` 的作业
            // 由 ModeJobExecutor 分给 ProcessJobExecutor（再起一个 dbcli 进程），见 §3.6.9
            jobManager = sharedJobManager != null ? sharedJobManager
                    : new JobManager(new DbcliJobSpecFactory(),
                            new ModeJobExecutor(new ScriptJobExecutor(), new ProcessJobExecutor()),
                            workingDirectory, workingDirectory);
            job = new JobCommandDispatcher(jobManager);
        }
        return job;
    }

    /**
     * 作业管理器（{@code _JOB} 真正用到过才有；否则为 null）。
     *
     * <p>引擎收尾与测试用它观察作业注册表与线程（例如"管理器关闭后没有作业线程还活着"）。</p>
     */
    public JobManager jobManager() {
        if (sharedJobManager != null) {
            return sharedJobManager;
        }
        return jobManager;
    }

    /**
     * 有没有"失败或超时、且脚本没检查过"的作业（引擎收尾时把它算成一次失败）。
     *
     * <p>为什么放在这里而不是让 {@code _JOB WAIT} 一处兜住：脚本完全可能启动一个作业就结束、
     * 从头到尾不 WAIT（漏写、或以为作业失败不影响结论）。那种情况下这条是唯一的发现路径，
     * 它让"并发作业失败"不可能静默通过。</p>
     *
     * <p>没有用到 {@code _JOB} 时分派器不会创建管理器，这里返回 false（不产生任何副作用）。</p>
     */
    public boolean hasUnacknowledgedJobFailures() {
        JobManager manager = jobManager();
        return manager != null && manager.hasUnacknowledgedFailures();
    }

    /** "没被检查过的失败作业"的可读说明（引擎收尾补报用；没有 {@code _JOB} 时为空）。 */
    public List<String> unacknowledgedJobFailureMessages() {
        JobManager manager = jobManager();
        return manager == null ? List.of() : manager.unacknowledgedFailureMessages();
    }

    /** 认领当前所有失败作业（引擎补报之后调用，避免同一件事被算两次）。 */
    public void acknowledgeJobFailures() {
        JobManager manager = jobManager();
        if (manager != null) {
            manager.acknowledgeFailures();
        }
    }

    /**
     * 断开可能已经建立的 SSH 连接（作业被中止时用）。
     *
     * <p>作业线程可能正阻塞在远端命令的读上：{@code channel}/{@code session} 一旦断开，那次读会立刻失败，
     * 于是"停掉一个正在跑远端命令的作业"不必等远端自己结束——这是 {@code _SSH} 那条长操作的可中断实现。</p>
     */
    public void closeSshSessions() {
        if (sshRegistry != null) {
            sshRegistry.close();
        }
    }

    /** 关闭被真正创建过的实现对象（自建的作业管理器会收掉仍在跑的作业；复用的那个不归本分派器管）。 */
    @Override
    public void close() {
        if (jobManager != null && sharedJobManager == null) {
            jobManager.close();
        }
        if (sshRegistry != null) {
            sshRegistry.close();
        }
    }

    private static String messageOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }
}
