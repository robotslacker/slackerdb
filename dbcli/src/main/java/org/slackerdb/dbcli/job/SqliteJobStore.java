package org.slackerdb.dbcli.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 作业协调状态的**唯一真相**：一个 SQLite 库同时保存注册表、状态机、停止意图、超时、
 * 认领与定时点（屏障）。
 *
 * <h2>为什么只剩这一个存储</h2>
 * <p>线程作业与进程作业以前各有一套协调机制（内存闸门 / SQLite 同步库），靠运行期切换器粘起来。
 * 现在两种执行层都写同一个库，编排代码因此只有一条路径：读库判断状态、写库表达意图。
 * 执行层（线程 / 子进程）的差异只在 {@link JobExecutor} 后面。</p>
 *
 * <h2>两个作用域</h2>
 * <ul>
 *   <li><b>作业表按 scope 隔离</b>：根进程是 {@code root}，进程作业的子进程是 {@code root/<作业名>}。
 *       保持既有可见性（父看不到孙进程的作业），但线程作业与同进程的进程作业共享同一个 scope；</li>
 *   <li><b>定时点不分 scope</b>：{@code _JOB TIMER}/{@code _JOB WAIT TIMER:} 必须跨进程命中。</li>
 * </ul>
 *
 * <h2>库的生命周期</h2>
 * <p>运行根固定使用 {@code WORK/dbcli_log.db}：启动阶段无论是否出现 {@code _JOB} 都创建，
 * 正常关闭只释放连接、不删除；root 下一次清空 WORK 时回收。作业子进程只关连接。</p>
 *
 * <h2>并发纪律</h2>
 * <p>SQLite 连接不是线程安全的：所有访问都在 {@link #lock} 下串行。等待（作业终态 / 定时点）是
 * 轮询（{@value #WAIT_POLL_MILLIS} 毫秒），超时是正常结果而不是异常。所有状态迁移都是
 * {@code where state='RUNNING'} 之类的 CAS，终态只由监督者写一次。</p>
 */
public final class SqliteJobStore implements AutoCloseable {

    /** 等待没有时限（脚本里不写 {@code timeout=}）。 */
    public static final long NO_TIMEOUT = -1L;

    /** 等待轮询间隔（毫秒）：既是作业 WAIT 的粒度，也是定时点 WAIT 的粒度。 */
    public static final long WAIT_POLL_MILLIS = 10L;

    /** 子进程用来加入本次运行的库路径（绝对路径）。 */
    public static final String ENV_JOB_DB = "_DBCLI_JOB_DB";

    /** 子进程的作业命名空间（{@code root/<作业名>[/...]}）。 */
    public static final String ENV_JOB_SCOPE = "_DBCLI_JOB_SCOPE";

    /** 表结构版本：{@code --job-db} 指到旧文件时靠它明确报错。 */
    public static final int SCHEMA_VERSION = 1;

    /** SQLite 忙等上限：另一个进程/线程正在写时最多等它这么久。 */
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path database;
    private final String scope;
    /** 库访问串行化（SQLite 连接不是线程安全的）。 */
    private final Object lock = new Object();

    private final Connection connection;

    private volatile boolean closed;

    /**
     * 打开一个已有的库（作业子进程用）。
     *
     * @param database 父进程通过 {@link #ENV_JOB_DB} 传下来的绝对路径
     * @param scope    子进程的命名空间
     */
    public SqliteJobStore(Path database, String scope) {
        this(database, scope, false);
    }

    /**
     * @param owner 历史兼容参数；关闭时不再删除库文件
     */
    public SqliteJobStore(Path database, String scope, boolean owner) {
        if (database == null) {
            throw new IllegalArgumentException("The job database path is missing.");
        }
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("The job scope is missing.");
        }
        this.database = database.toAbsolutePath().normalize();
        this.scope = scope;
        Connection opened = connect();
        try {
            this.connection = opened;
            ensureSchema();
            verifySchema();
        } catch (RuntimeException | Error initializationFailed) {
            closeQuietly(opened);
            throw initializationFailed;
        }
    }

    // ------------------------------------------------------------------ 元信息

    public Path database() {
        return database;
    }

    public String scope() {
        return scope;
    }

    public boolean isClosed() {
        return closed;
    }

    /** 记录一句可读日志用的库标识（不参与任何判定）。 */
    public String describe() {
        return database + " [scope " + scope + "]";
    }

    // ------------------------------------------------------------------ 作业：定义

    /**
     * 定义一个新作业（{@code _JOB CREATE}）。唯一约束冲突转成既有文案
     * {@code Job [x] already exists.}。
     */
    public JobRecord create(String name, JobMode mode, Long timeoutMillis) {
        synchronized (lock) {
            requireOpen();
            long now = System.currentTimeMillis();
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into job(scope, name, mode, timeout_ms, state, created_at_ms, definition_version) "
                            + "values(?, ?, ?, ?, 'CREATED', ?, 1)")) {
                insert.setString(1, scope);
                insert.setString(2, name);
                insert.setString(3, mode.name().toLowerCase(Locale.ROOT));
                setNullableLong(insert, 4, timeoutMillis);
                insert.setLong(5, now);
                insert.executeUpdate();
            } catch (SQLException failed) {
                if (isUniqueViolation(failed)) {
                    throw new JobException("Job [" + name + "] already exists.");
                }
                throw writeFailure("create job [" + name + "]", failed);
            }
            JobRecord created;
            try {
                created = findByNameLocked(name);
            } catch (SQLException failed) {
                throw readFailure("read back job [" + name + "]", failed);
            }
            if (created == null) {
                throw new JobException("Job [" + name + "] was not created.");
            }
            return created;
        }
    }

    /**
     * 重新定义（{@code _JOB SET}）：只在没有运行时允许；重置运行期字段与认领，
     * 并递增 {@code definition_version} 让并发的 START 失败。
     *
     * @return 更新后的定义；{@code null} 表示作业正在运行（或不存在）
     */
    public JobRecord redefine(String name, JobMode mode, Long timeoutMillis) {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set mode=?, timeout_ms=?, definition_version=definition_version+1, "
                            + "state='CREATED', exit_code=null, failure=null, created_at_ms=?, "
                            + "started_at_ms=null, finished_at_ms=null, runtime_id=null, "
                            + "supervisor_pid=null, supervisor_start_ms=null, "
                            + "stop_terminal=null, stop_forced=0, timeout_hit=0, ack=0 "
                            + "where scope=? and name=? and state<>'RUNNING'")) {
                update.setString(1, mode.name().toLowerCase(Locale.ROOT));
                setNullableLong(update, 2, timeoutMillis);
                update.setLong(3, System.currentTimeMillis());
                update.setString(4, scope);
                update.setString(5, name);
                if (update.executeUpdate() == 0) {
                    return null;
                }
            } catch (SQLException failed) {
                throw writeFailure("redefine job [" + name + "]", failed);
            }
            try {
                return findByNameLocked(name);
            } catch (SQLException failed) {
                throw readFailure("read back job [" + name + "]", failed);
            }
        }
    }

    /** 按名字查（本 scope）；不存在返回 null。 */
    public JobRecord find(String name) {
        synchronized (lock) {
            requireOpen();
            try {
                return findByNameLocked(name);
            } catch (SQLException failed) {
                throw readFailure("find job [" + name + "]", failed);
            }
        }
    }

    public JobRecord findById(long jobId) {
        synchronized (lock) {
            requireOpen();
            try {
                return findByIdLocked(jobId);
            } catch (SQLException failed) {
                throw readFailure("find job #" + jobId, failed);
            }
        }
    }

    /** 本 scope 的全部作业（含已结束），按名字排序。 */
    public List<JobRecord> list() {
        synchronized (lock) {
            requireOpen();
            try {
                return queryLocked("select " + SELECT_COLUMNS + " from job where scope=? order by name",
                        statement -> statement.setString(1, scope));
            } catch (SQLException failed) {
                throw readFailure("list jobs", failed);
            }
        }
    }

    /** 本 scope 仍在运行的行（收尾/等待用）。 */
    public List<JobRecord> listRunning() {
        synchronized (lock) {
            requireOpen();
            try {
                return queryLocked("select " + SELECT_COLUMNS
                                + " from job where scope=? and state='RUNNING' order by name",
                        statement -> statement.setString(1, scope));
            } catch (SQLException failed) {
                throw readFailure("list running jobs", failed);
            }
        }
    }

    /** 全部作业执行审计（按完成时间/自增顺序；跨 scope，进程子作业也在同一库）。 */
    public List<JobAuditRecord> listAudit() {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement query = connection.prepareStatement(
                    "select audit_id, job_id, scope, job_name, script_name, mode, pid, "
                            + "started_at_ms, finished_at_ms, exit_code, state, failure "
                            + "from job_audit order by audit_id");
                 ResultSet rows = query.executeQuery()) {
                List<JobAuditRecord> audit = new ArrayList<>();
                while (rows.next()) {
                    audit.add(readAudit(rows));
                }
                return audit;
            } catch (SQLException failed) {
                throw readFailure("list job audit", failed);
            }
        }
    }

    // ------------------------------------------------------------------ 作业：状态机

    /**
     * CREATED → RUNNING（CAS：定义版本必须没变过）。
     *
     * @return {@code true} 表示抢到了启动权；{@code false} 表示被并发的 SET/START 抢先
     */
    public boolean markRunning(long jobId, long definitionVersion, String runtimeId,
                               long supervisorPid, long supervisorStartMillis) {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set state='RUNNING', started_at_ms=?, runtime_id=?, "
                            + "supervisor_pid=?, supervisor_start_ms=?, exit_code=null, failure=null "
                            + "where job_id=? and state='CREATED' and definition_version=?")) {
                update.setLong(1, System.currentTimeMillis());
                update.setString(2, runtimeId == null ? "" : runtimeId);
                update.setLong(3, supervisorPid);
                update.setLong(4, supervisorStartMillis);
                update.setLong(5, jobId);
                update.setLong(6, definitionVersion);
                return update.executeUpdate() > 0;
            } catch (SQLException failed) {
                throw writeFailure("start job #" + jobId, failed);
            }
        }
    }

    /** 进程作业登记子进程 pid（{@code _JOB SHOW} 的 thread 列据此显示 {@code pid <n>}）。 */
    public void setRuntimeId(long jobId, String runtimeId) {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set runtime_id=? where job_id=? and state='RUNNING'")) {
                update.setString(1, runtimeId == null ? "" : runtimeId);
                update.setLong(2, jobId);
                update.executeUpdate();
            } catch (SQLException failed) {
                throw writeFailure("record runtime of job #" + jobId, failed);
            }
        }
    }

    /**
     * 写停止意图（只作用于 RUNNING 行）：**非强制请求不覆盖已有意图**（shutdown 不会把 abort
     * 降级），**强制请求可以升级**（abort 把 shutdown/超时改写成 ABORTED），
     * {@code stop_forced} 单调（只升不降）。
     *
     * <p>超时标记（{@link #markTimeout(long)}）反过来总是用 coalesce：一次已经到点的超时不应该
     * 把用户明确的 abort 改成 TIMEOUT。</p>
     *
     * @return 写入后的行（作业已终态时原样返回）
     */
    public JobRecord requestStop(long jobId, JobState terminal, boolean forced) {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set stop_terminal=case when ? then ? else coalesce(stop_terminal, ?) end, "
                            + "stop_forced=max(stop_forced, ?) "
                            + "where job_id=? and state='RUNNING'")) {
                update.setInt(1, forced ? 1 : 0);
                update.setString(2, terminal.name());
                update.setString(3, terminal.name());
                update.setInt(4, forced ? 1 : 0);
                update.setLong(5, jobId);
                update.executeUpdate();
            } catch (SQLException failed) {
                throw writeFailure("request stop of job #" + jobId, failed);
            }
            try {
                JobRecord current = findByIdLocked(jobId);
                if (current == null) {
                    throw new JobException("Job #" + jobId + " not found.");
                }
                return current;
            } catch (SQLException failed) {
                throw readFailure("read after stop request of job #" + jobId, failed);
            }
        }
    }

    /**
     * 升级为强制停止（{@code _JOB SHUTDOWN} 的宽限期之后）：只改 {@code stop_forced}，
     * **不改** {@code stop_terminal}——并发发生的 ABORT 不会被这次升级覆盖。
     */
    public void escalateStop(long jobId) {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set stop_forced=1 where job_id=? and state='RUNNING'")) {
                update.setLong(1, jobId);
                update.executeUpdate();
            } catch (SQLException failed) {
                throw writeFailure("escalate stop of job #" + jobId, failed);
            }
        }
    }

    /**
     * 到点：记录超时意图。**不覆盖**已经存在的停止意图（ABORTED/SHUTDOWN 优先）。
     *
     * @return {@code true} 表示这次确实把一行 RUNNING 标成了超时
     */
    public boolean markTimeout(long jobId) {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set timeout_hit=1, stop_forced=1, "
                            + "stop_terminal=coalesce(stop_terminal, 'TIMEOUT') "
                            + "where job_id=? and state='RUNNING' and timeout_ms is not null")) {
                update.setLong(1, jobId);
                return update.executeUpdate() > 0;
            } catch (SQLException failed) {
                throw writeFailure("mark timeout of job #" + jobId, failed);
            }
        }
    }

    /**
     * 监督者收尾：**唯一的终态写者**。
     *
     * <p>终态优先级与既有实现一致：{@code stop_terminal}（ABORTED/SHUTDOWN/TIMEOUT）
     * &gt; {@code timeout_hit} &gt; 退出码。CAS 在 RUNNING 上，重复调用不会改写终态。</p>
     *
     * <p>这次真正从 RUNNING 迁移到终态时，同时写一行 {@code job_audit}；CAS 失败（已经收尾）
     * 不会再写审计，因此一次执行恰好一行。</p>
     *
     * @param exitCode 作业退出码；异常路径传 {@link Integer#MIN_VALUE}（表示"没有退出码"）
     * @param failure  异常/失败原因；正常结束为 null
     */
    public JobRecord finish(long jobId, int exitCode, String failure) {
        return finish(jobId, exitCode, failure, null);
    }

    /**
     * @param scriptName 作业定义里的脚本名（审计用；未知时传 null）
     */
    public JobRecord finish(long jobId, int exitCode, String failure, String scriptName) {
        synchronized (lock) {
            requireOpen();
            try {
                JobRecord current = findByIdLocked(jobId);
                if (current == null) {
                    throw new JobException("Job #" + jobId + " not found.");
                }
                if (!current.isRunning()) {
                    return current;
                }
                JobState terminal = current.stopTerminal() != null
                        ? current.stopTerminal()
                        : current.timeoutHit() ? JobState.TIMEOUT
                        : (failure != null || exitCode != 0) ? JobState.FAILED : JobState.DONE;
                int changed;
                try (PreparedStatement update = connection.prepareStatement(
                        "update job set state=?, exit_code=?, failure=?, finished_at_ms=? "
                                + "where job_id=? and state='RUNNING'")) {
                    update.setString(1, terminal.name());
                    if (exitCode == Integer.MIN_VALUE) {
                        update.setNull(2, java.sql.Types.INTEGER);
                    } else {
                        update.setInt(2, exitCode);
                    }
                    update.setString(3, failure);
                    update.setLong(4, System.currentTimeMillis());
                    update.setLong(5, jobId);
                    changed = update.executeUpdate();
                }
                JobRecord ended = findByIdLocked(jobId);
                if (ended == null) {
                    throw new JobException("Job #" + jobId + " disappeared after finishing.");
                }
                if (changed > 0) {
                    insertAuditLocked(ended, scriptName);
                }
                return ended;
            } catch (SQLException failed) {
                throw writeFailure("finish job #" + jobId, failed);
            }
        }
    }

    /** 启动失败（输出文件都打不开）：直接 FAILED，并且**已经报过**（ack=1，收尾不重复算）。 */
    public void markFailedToStart(long jobId, String message) {
        markFailedToStart(jobId, message, null);
    }

    /**
     * @param scriptName 作业定义里的脚本名（审计用；未知时传 null）
     */
    public void markFailedToStart(long jobId, String message, String scriptName) {
        synchronized (lock) {
            requireOpen();
            long now = System.currentTimeMillis();
            try {
                int changed;
                try (PreparedStatement update = connection.prepareStatement(
                        "update job set state='FAILED', failure=?, ack=1, started_at_ms=?, finished_at_ms=? "
                                + "where job_id=? and state='CREATED'")) {
                    update.setString(1, message);
                    update.setLong(2, now);
                    update.setLong(3, now);
                    update.setLong(4, jobId);
                    changed = update.executeUpdate();
                }
                if (changed > 0) {
                    JobRecord ended = findByIdLocked(jobId);
                    if (ended != null) {
                        insertAuditLocked(ended, scriptName);
                    }
                }
            } catch (SQLException failed) {
                throw writeFailure("fail to start job #" + jobId, failed);
            }
        }
    }

    /**
     * 还没 START 的作业被 abort/shutdown：没有监督者可以写终态，由调用方 CAS。
     *
     * @return 写入后的行
     */
    public JobRecord finishCreated(long jobId, JobState terminal) {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set state=?, finished_at_ms=? where job_id=? and state='CREATED'")) {
                update.setString(1, terminal.name());
                update.setLong(2, System.currentTimeMillis());
                update.setLong(3, jobId);
                update.executeUpdate();
                JobRecord current = findByIdLocked(jobId);
                if (current == null) {
                    throw new JobException("Job #" + jobId + " not found.");
                }
                return current;
            } catch (SQLException failed) {
                throw writeFailure("finish created job #" + jobId, failed);
            }
        }
    }

    // ------------------------------------------------------------------ 认领与失败

    /** 认领一个具体定义版本（{@code _JOB SET} 之后的新定义不会被旧认领带走）。 */
    public void acknowledge(long jobId, long definitionVersion) {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set ack=1 where job_id=? and definition_version=? and ack=0")) {
                update.setLong(1, jobId);
                update.setLong(2, definitionVersion);
                update.executeUpdate();
            } catch (SQLException failed) {
                throw writeFailure("acknowledge job #" + jobId, failed);
            }
        }
    }

    /** 本 scope 里"失败/超时且没被认领"的行，按名字排序。 */
    public List<JobRecord> unacknowledgedFailures() {
        synchronized (lock) {
            requireOpen();
            try {
                return queryLocked("select " + SELECT_COLUMNS
                                + " from job where scope=? and ack=0 and state in ('FAILED','TIMEOUT') "
                                + "order by name",
                        statement -> statement.setString(1, scope));
            } catch (SQLException failed) {
                throw readFailure("list unacknowledged failures", failed);
            }
        }
    }

    /** 收尾补报之后一次认领全部失败作业。 */
    public void acknowledgeFailures() {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set ack=1 where scope=? and ack=0 and state in ('FAILED','TIMEOUT')")) {
                update.setString(1, scope);
                update.executeUpdate();
            } catch (SQLException failed) {
                throw writeFailure("acknowledge failures", failed);
            }
        }
    }

    // ------------------------------------------------------------------ 收尾

    /** 对所有 RUNNING 行写 SHUTDOWN 意图（不动既有的 forced 标记）。 */
    public void requestStopAll() {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set stop_terminal=coalesce(stop_terminal, 'SHUTDOWN') "
                            + "where scope=? and state='RUNNING'")) {
                update.setString(1, scope);
                update.executeUpdate();
            } catch (SQLException failed) {
                throw writeFailure("request stop of all jobs", failed);
            }
        }
    }

    /** 收尾升级：对所有 RUNNING 行要求强制停止。 */
    public void escalateAll() {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement update = connection.prepareStatement(
                    "update job set stop_forced=1 where scope=? and state='RUNNING'")) {
                update.setString(1, scope);
                update.executeUpdate();
            } catch (SQLException failed) {
                throw writeFailure("escalate stop of all jobs", failed);
            }
        }
    }

    // ------------------------------------------------------------------ 等待

    /**
     * 等作业变成终态（轮询）。
     *
     * @param timeoutMillis {@link #NO_TIMEOUT} 一直等；{@code 0} 只看一眼；正数为上限
     * @return 最后一次读到的行（超时返回非终态行，由调用方判断 {@code timedOut}）
     */
    public JobRecord awaitTerminal(long jobId, long timeoutMillis) throws InterruptedException {
        boolean unlimited = timeoutMillis < 0L;
        long deadline = System.nanoTime() + nanosForMillis(timeoutMillis);
        while (true) {
            JobRecord current = findById(jobId);
            if (current.state().isTerminal() || timeoutMillis == 0L || isClosed()) {
                return current;
            }
            if (!unlimited) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    // 到点后再读一次：可能刚好在这一瞬间结束（与"超时是正常结果"一致）
                    return findById(jobId);
                }
                Thread.sleep(Math.min(WAIT_POLL_MILLIS, Math.max(remainingNanos / 1_000_000L, 1L)));
            } else {
                Thread.sleep(WAIT_POLL_MILLIS);
            }
        }
    }

    // ------------------------------------------------------------------ 定时点（屏障）

    /** 记下"某个参与者到达了某个点名"（只用于观察/排错）。 */
    public void arrive(String point, String participant) {
        if (point == null || closed) {
            return;
        }
        String normalized = normalizePoint(point);
        String who = participant == null || participant.isBlank() ? "MAIN" : participant;
        synchronized (lock) {
            if (closed) {
                return;
            }
            try (PreparedStatement createPoint = connection.prepareStatement(
                    "insert or ignore into job_point(point, released) values(?, 0)");
                 PreparedStatement record = connection.prepareStatement(
                         "insert into job_arrive(point, scope, participant, arrived_at_ms) values(?, ?, ?, ?)")) {
                createPoint.setString(1, normalized);
                createPoint.executeUpdate();
                record.setString(1, normalized);
                record.setString(2, scope);
                record.setString(3, who);
                record.setLong(4, System.currentTimeMillis());
                record.executeUpdate();
            } catch (SQLException ignored) {
                // 到达记录写不进去不影响同步语义（放行仍由 release 落地）
            }
        }
    }

    /** 放行某个点名（原子、幂等）。 */
    public void release(String point) {
        if (point == null || closed) {
            return;
        }
        String normalized = normalizePoint(point);
        synchronized (lock) {
            if (closed) {
                return;
            }
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement release = connection.prepareStatement(
                        "insert or replace into job_point(point, released) values(?, 1)")) {
                    release.setString(1, normalized);
                    release.executeUpdate();
                    connection.commit();
                } catch (SQLException failed) {
                    rollbackQuietly();
                    throw failed;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException failed) {
                throw new IllegalStateException("Cannot release the sync point [" + point + "] in ["
                        + database + "]: " + failed.getMessage(), failed);
            }
        }
    }

    /** 这个点名是否已经出现过（决定 {@code _JOB WAIT <名字>} 的第二种解释）。 */
    public boolean knows(String point) {
        if (point == null || closed) {
            return false;
        }
        String normalized = normalizePoint(point);
        synchronized (lock) {
            if (closed) {
                return false;
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "select 1 from job_point where point = ?")) {
                query.setString(1, normalized);
                try (ResultSet row = query.executeQuery()) {
                    return row.next();
                }
            } catch (SQLException readFailed) {
                return false;
            }
        }
    }

    /** 在某个定时点上等待放行；超时是正常结果（返回 false）。 */
    public boolean await(String point, long timeoutMillis) throws InterruptedException {
        boolean unlimited = timeoutMillis < 0L;
        long deadline = System.nanoTime() + nanosForMillis(timeoutMillis);
        while (true) {
            if (isReleased(point)) {
                return true;
            }
            if (closed) {
                return false;
            }
            if (!unlimited) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return isReleased(point);
                }
                Thread.sleep(Math.min(WAIT_POLL_MILLIS, Math.max(remainingNanos / 1_000_000L, 1L)));
            } else {
                Thread.sleep(WAIT_POLL_MILLIS);
            }
        }
    }

    private boolean isReleased(String point) {
        if (point == null || closed) {
            return false;
        }
        String normalized = normalizePoint(point);
        synchronized (lock) {
            if (closed) {
                return false;
            }
            // 读失败只是"还没放行"，不是异常（库被删/被锁时继续等或超时）
            try (PreparedStatement query = connection.prepareStatement(
                    "select released from job_point where point = ?")) {
                query.setString(1, normalized);
                try (ResultSet row = query.executeQuery()) {
                    return row.next() && row.getInt(1) != 0;
                }
            } catch (SQLException readFailed) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ 小工具（静态）

    /** 定时点名规范形：**不区分大小写**（作业名不走这里，仍区分大小写）。 */
    public static String normalizePoint(String point) {
        return point == null ? null : point.toUpperCase(Locale.ROOT);
    }

    /** 毫秒 → 纳秒，**夹紧**而不是回绕（超大 timeout 等价于"没有时限"）。 */
    public static long nanosForMillis(long millis) {
        if (millis <= 0L) {
            return 0L;
        }
        long cap = Long.MAX_VALUE / 4L;
        if (millis > cap / 1_000_000L) {
            return cap;
        }
        return millis * 1_000_000L;
    }

    // ------------------------------------------------------------------ 关闭

    /**
     * 收尾：只关闭连接；库文件保留，由下一次 root 清空 WORK 回收。
     * 幂等；关闭后所有等待立即返回、状态写入只记日志（不抛给调用方）。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        synchronized (lock) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关不掉也无法补救：进程即将退出，文件句柄随之释放
            }
        }
    }

    // ------------------------------------------------------------------ 内部：连接与 schema

    private Connection connect() {
        try {
            // 显式加载：standalone jar 是"解包合并"的产物，META-INF/services/java.sql.Driver
            // 可能被自带驱动占住，不能指望 DriverManager 自动发现
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException missingDriver) {
            throw new IllegalStateException("The SQLite driver is not on the classpath; "
                    + "jobs require the job database.", missingDriver);
        }
        try {
            Path parent = database.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection opened = DriverManager.getConnection("jdbc:sqlite:" + database);
            try (Statement pragma = opened.createStatement()) {
                pragma.execute("pragma busy_timeout=" + BUSY_TIMEOUT_MILLIS);
                pragma.execute("pragma journal_mode=WAL");
                pragma.execute("pragma synchronous=NORMAL");
            }
            return opened;
        } catch (IOException | SQLException openFailed) {
            throw new IllegalStateException("Cannot open the job database [" + database + "]: "
                    + openFailed.getMessage(), openFailed);
        }
    }

    private void ensureSchema() {
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("create table if not exists job_meta ("
                    + "id integer primary key check (id = 1), "
                    + "schema_version integer not null, "
                    + "run_id text not null, "
                    + "root_pid integer not null, "
                    + "root_start_ms integer not null, "
                    + "workdir text not null, "
                    + "created_at_ms integer not null)");
            ddl.execute("create table if not exists job ("
                    + "job_id integer primary key autoincrement, "
                    + "scope text not null, "
                    + "name text not null, "
                    + "mode text not null, "
                    + "timeout_ms integer, "
                    + "state text not null, "
                    + "exit_code integer, "
                    + "failure text, "
                    + "created_at_ms integer not null, "
                    + "started_at_ms integer, "
                    + "finished_at_ms integer, "
                    + "runtime_id text, "
                    + "supervisor_pid integer, "
                    + "supervisor_start_ms integer, "
                    + "definition_version integer not null default 1, "
                    + "stop_terminal text, "
                    + "stop_forced integer not null default 0, "
                    + "timeout_hit integer not null default 0, "
                    + "ack integer not null default 0, "
                    + "unique (scope, name))");
            ddl.execute("create index if not exists job_scope_state on job(scope, state)");
            ddl.execute("create index if not exists job_supervisor on job(supervisor_pid, state)");
            ddl.execute("create table if not exists job_point ("
                    + "point text primary key, released integer not null default 0)");
            ddl.execute("create table if not exists job_arrive ("
                    + "point text not null, scope text not null, participant text not null, "
                    + "arrived_at_ms integer not null)");
            ddl.execute("create table if not exists job_audit ("
                    + "audit_id integer primary key autoincrement, "
                    + "job_id integer not null, "
                    + "scope text not null, "
                    + "job_name text not null, "
                    + "script_name text not null, "
                    + "mode text not null, "
                    + "pid integer not null, "
                    + "started_at_ms integer not null, "
                    + "finished_at_ms integer not null, "
                    + "exit_code integer, "
                    + "state text not null, "
                    + "failure text)");
            ddl.execute("create index if not exists job_audit_finished "
                    + "on job_audit(finished_at_ms)");
        } catch (SQLException schemaFailed) {
            throw new IllegalStateException("Cannot create the job tables in [" + database + "]: "
                    + schemaFailed.getMessage(), schemaFailed);
        }
    }

    private void verifySchema() {
        synchronized (lock) {
            try (Statement query = connection.createStatement();
                 ResultSet row = query.executeQuery("select schema_version from job_meta where id = 1")) {
                if (!row.next()) {
                    long now = System.currentTimeMillis();
                    try (PreparedStatement insert = connection.prepareStatement(
                            "insert into job_meta(id, schema_version, run_id, root_pid, root_start_ms, "
                                    + "workdir, created_at_ms) values(1, ?, ?, ?, ?, ?, ?)")) {
                        insert.setInt(1, SCHEMA_VERSION);
                        insert.setString(2, UUID.randomUUID().toString());
                        insert.setLong(3, ProcessHandle.current().pid());
                        insert.setLong(4, ProcessHandle.current().info().startInstant()
                                .map(java.time.Instant::toEpochMilli).orElse(0L));
                        insert.setString(5, Path.of("").toAbsolutePath().toString());
                        insert.setLong(6, now);
                        insert.executeUpdate();
                    }
                    return;
                }
                int version = row.getInt(1);
                if (version != SCHEMA_VERSION) {
                    throw new IllegalStateException("The job database [" + database
                            + "] has schema version " + version + "; this build needs "
                            + SCHEMA_VERSION + ". Remove the file or choose another --job-db.");
                }
            } catch (SQLException metaFailed) {
                throw new IllegalStateException("Cannot read the job database metadata in ["
                        + database + "]: " + metaFailed.getMessage(), metaFailed);
            }
        }
    }

    // ------------------------------------------------------------------ 内部：查询

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static final String SELECT_COLUMNS =
            "job_id, scope, name, mode, timeout_ms, state, exit_code, failure, created_at_ms, "
                    + "started_at_ms, finished_at_ms, runtime_id, supervisor_pid, definition_version, "
                    + "stop_terminal, stop_forced, timeout_hit, ack";

    private List<JobRecord> queryLocked(String sql, Binder binder) throws SQLException {
        List<JobRecord> records = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            binder.bind(query);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    records.add(readRecord(rows));
                }
            }
        }
        return records;
    }

    private JobRecord findByNameLocked(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select " + SELECT_COLUMNS + " from job where scope=? and name=?")) {
            query.setString(1, scope);
            query.setString(2, name);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? readRecord(row) : null;
            }
        }
    }

    private JobRecord findByIdLocked(long jobId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select " + SELECT_COLUMNS + " from job where job_id=?")) {
            query.setLong(1, jobId);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? readRecord(row) : null;
            }
        }
    }

    private static JobRecord readRecord(ResultSet row) throws SQLException {
        String stopTerminal = row.getString("stop_terminal");
        return new JobRecord(
                row.getLong("job_id"),
                row.getString("scope"),
                row.getString("name"),
                JobMode.fromOption(row.getString("mode")),
                nullableLong(row, "timeout_ms"),
                JobState.valueOf(row.getString("state")),
                nullableInteger(row, "exit_code"),
                row.getString("failure"),
                row.getLong("created_at_ms"),
                nullableLong(row, "started_at_ms"),
                nullableLong(row, "finished_at_ms"),
                row.getString("runtime_id"),
                nullableLong(row, "supervisor_pid"),
                row.getLong("definition_version"),
                stopTerminal == null ? null : JobState.valueOf(stopTerminal),
                row.getInt("stop_forced") != 0,
                row.getInt("timeout_hit") != 0,
                row.getInt("ack") != 0);
    }

    /** 一次真正的 RUNNING → 终态迁移写一行审计；调用方已持有 {@link #lock}。 */
    private void insertAuditLocked(JobRecord record, String scriptName) throws SQLException {
        long now = System.currentTimeMillis();
        long started = record.startedAtMillis() == null ? now : record.startedAtMillis();
        long finished = record.finishedAtMillis() == null ? now : record.finishedAtMillis();
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into job_audit(job_id, scope, job_name, script_name, mode, pid, "
                        + "started_at_ms, finished_at_ms, exit_code, state, failure) "
                        + "values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setLong(1, record.jobId());
            insert.setString(2, record.scope());
            insert.setString(3, record.name());
            insert.setString(4, scriptName == null ? "" : scriptName);
            insert.setString(5, record.mode().name().toLowerCase(Locale.ROOT));
            insert.setLong(6, auditPid(record));
            insert.setLong(7, started);
            insert.setLong(8, finished);
            if (record.exitCode() == null) {
                insert.setNull(9, java.sql.Types.INTEGER);
            } else {
                insert.setInt(9, record.exitCode());
            }
            insert.setString(10, record.state().name());
            insert.setString(11, record.failure());
            insert.executeUpdate();
        }
    }

    /** 进程作业取真正的子进程 PID；线程作业取当前 dbcli 进程 PID。 */
    private static long auditPid(JobRecord record) {
        String runtime = record.runtimeId();
        if (runtime != null && runtime.startsWith("pid ")) {
            try {
                return Long.parseLong(runtime.substring(4).strip());
            } catch (NumberFormatException ignored) {
                // 落回 supervisor pid（诊断字段损坏时仍保留一条可读审计）
            }
        }
        Long supervisor = record.supervisorPid();
        return supervisor == null ? ProcessHandle.current().pid() : supervisor;
    }

    private static JobAuditRecord readAudit(ResultSet row) throws SQLException {
        return new JobAuditRecord(
                row.getLong("audit_id"),
                row.getLong("job_id"),
                row.getString("scope"),
                row.getString("job_name"),
                row.getString("script_name"),
                row.getString("mode"),
                row.getLong("pid"),
                row.getLong("started_at_ms"),
                row.getLong("finished_at_ms"),
                nullableInteger(row, "exit_code"),
                JobState.valueOf(row.getString("state")),
                row.getString("failure"));
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    private static boolean isUniqueViolation(SQLException failed) {
        // SQLite: SQLITE_CONSTRAINT = 19；驱动也会在 message 里写 "UNIQUE constraint failed"
        return failed.getErrorCode() == 19
                || (failed.getMessage() != null
                && failed.getMessage().toLowerCase(Locale.ROOT).contains("unique constraint"));
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The job database [" + database + "] is closed.");
        }
    }

    private IllegalStateException readFailure(String action, SQLException failed) {
        return new IllegalStateException("Cannot " + action + " in the job database ["
                + database + "]: " + failed.getMessage(), failed);
    }

    private IllegalStateException writeFailure(String action, SQLException failed) {
        return new IllegalStateException("Cannot " + action + " in the job database ["
                + database + "]: " + failed.getMessage(), failed);
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 回滚失败无法补救：调用方已经在处理真正的失败
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 关不掉无法补救：进程即将退出，句柄随之释放
        }
    }
}
