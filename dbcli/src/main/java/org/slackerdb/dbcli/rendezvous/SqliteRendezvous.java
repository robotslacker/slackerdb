package org.slackerdb.dbcli.rendezvous;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * 跨进程会合点：一个 SQLite 同步库，父进程与所有 {@code mode=process} 的作业进程共用它。
 *
 * <h2>它解决什么</h2>
 * <p>线程模式的定时点是进程内闸门（{@link LatchRendezvous}），跨不过进程边界。作业跑成独立进程之后，
 * "父脚本 {@code _JOB TIMER} 放行、子进程里的 {@code _JOB WAIT} 通过"必须落到一个**两个进程都能看见、
 * 且写入是原子的**地方；SQLite 就够（单文件、无服务端、无端口、事务保证原子性与幂等）。</p>
 *
 * <h2>同步库从哪儿来</h2>
 * <ul>
 *   <li>父进程第一次创建 {@code mode=process} 作业时，在**脚本工作目录**下建
 *       {@value #SYNC_DB_FILE_NAME}，并生成一个**运行代号**（GUID）；</li>
 *   <li>同步库路径与运行代号通过环境变量 {@value #ENV_SYNC_DB} / {@value #ENV_SYNC_GENERATION}
 *       传给作业进程；作业进程（以及它再起的孙作业）读环境变量，打开同一个库、用同一个代号，
 *       于是<b>任意深度</b>的会合都成立；</li>
 *   <li>父进程正常结束时**尽力清理**（自己建的才删：作业进程删掉的话会把父进程正在用的库抽走）。</li>
 * </ul>
 *
 * <h2>表结构</h2>
 * <pre>
 *   sync_point (point text primary key, released integer not null default 0)
 *   sync_arrive(point text, participant text, generation text, arrived_at text)
 *   sync_run   (id integer primary key check (id = 1), generation text not null)   -- 本库归属哪一代运行
 * </pre>
 *
 * <h2>点名规范化</h2>
 * <p>{@code sync_point.point} 与 {@code sync_arrive.point} 里存的一律是
 * {@link RendezvousBackend#normalizePoint(String)} 的结果，查询也用同一个值：
 * <b>定时点名不区分大小写</b>（父进程 {@code _JOB TIMER GO} 与子进程 {@code _JOB WAIT timer:go}
 * 命中同一行）。父子进程跑的是同一份代码，所以规范化口径不可能错配；作业名不走这里，仍区分大小写。</p>
 *
 * <h2>四条正确性规则</h2>
 * <ol>
 *   <li><b>放行是事务内的原子写入且幂等</b>：{@code insert or replace ... released=1} 放在一个事务里提交，
 *       重复放行、并发放行都得到同一个结果；</li>
 *   <li><b>等待是轮询</b>：默认每 {@value #DEFAULT_POLL_MILLIS} 毫秒查一次 {@code released}；
 *       <b>超时是正常结果</b>（返回 false），不是异常；脚本不写 {@code timeout=} 时
 *       （{@link RendezvousBackend#NO_TIMEOUT}）一直轮询到放行或本库关闭；</li>
 *   <li><b>陈旧行按运行代号清理</b>：打开库时先看 {@code sync_run} 里的代号——<b>不是自己这一代</b>
 *       （上一次被强杀留下的）就把 {@code sync_point}/{@code sync_arrive} 清空再登记自己这一代，
 *       避免上一次的"放行"冒充本次放行、上一次的"到达"冒充本次到达；代号相同则只清掉非本代的到达行；</li>
 *   <li><b>读取失败不当异常</b>：等待期间库被删/被锁（父进程已经收尾）都只是"还没放行"，
 *       继续等或超时返回，绝不让作业因为协调设施的问题而失败。</li>
 * </ol>
 */
public final class SqliteRendezvous implements RendezvousBackend {

    /** 同步库路径的环境变量名（父进程 → 作业进程）。 */
    public static final String ENV_SYNC_DB = "DBCLI_SYNC_DB";

    /** 运行代号的环境变量名（父进程 → 作业进程；用于识别"这不是上一次运行留下的库"）。 */
    public static final String ENV_SYNC_GENERATION = "DBCLI_SYNC_GENERATION";

    /** 同步库的固定文件名（放在脚本工作目录下）。 */
    public static final String SYNC_DB_FILE_NAME = ".dbcli-sync.db";

    /** 等待时的轮询间隔（毫秒）。 */
    public static final long DEFAULT_POLL_MILLIS = 20L;

    /** SQLite 的忙等上限：另一个进程正在写时最多等它这么久，而不是立刻报 "database is locked"。 */
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path database;
    private final String generation;
    private final boolean owner;
    private final long pollMillis;

    /** SQLite 连接不是线程安全的，而作业管理器会被多个线程并发调用：所有库访问都串行化。 */
    private final Object lock = new Object();

    private final Connection connection;

    private volatile boolean closed;

    /** 建库（父进程）：{@code owner=true}，结束时会尽力删掉同步库。 */
    public static SqliteRendezvous create(Path database, String generation) {
        return new SqliteRendezvous(database, generation, true);
    }

    /** 打开已有的库（作业进程）：{@code owner=false}，不删库。 */
    public static SqliteRendezvous open(Path database, String generation) {
        return new SqliteRendezvous(database, generation, false);
    }

    public SqliteRendezvous(Path database, String generation, boolean owner) {
        this(database, generation, owner, DEFAULT_POLL_MILLIS);
    }

    public SqliteRendezvous(Path database, String generation, boolean owner, long pollMillis) {
        if (database == null) {
            throw new IllegalArgumentException("同步库路径不能为空");
        }
        if (generation == null || generation.isBlank()) {
            throw new IllegalArgumentException("运行代号不能为空");
        }
        this.database = database.toAbsolutePath().normalize();
        this.generation = generation;
        this.owner = owner;
        this.pollMillis = Math.max(pollMillis, 1L);
        Connection opened = connect();
        try {
            this.connection = opened;
            ensureSchema();
            claimRun();
        } catch (RuntimeException | Error initializationFailed) {
            // 构造没走完时不会有人调 close()：这里必须自己收掉连接，
            // 否则 Windows 上库文件被锁住删不掉，下一次运行又要面对脏库
            closeQuietly(opened);
            throw initializationFailed;
        }
    }

    @Override
    public Path database() {
        return database;
    }

    @Override
    public String generation() {
        return generation;
    }

    public boolean owner() {
        return owner;
    }

    // ------------------------------------------------------------------ 后端接口

    @Override
    public void arrive(String point, String participant) {
        if (point == null || closed) {
            return;
        }
        String normalized = RendezvousBackend.normalizePoint(point);
        String who = participant == null || participant.isBlank() ? "MAIN" : participant;
        synchronized (lock) {
            // released=0 只在"这个点还没出现过"时写入：insert or ignore 不会把已放行的点打回未放行
            try (PreparedStatement createPoint = connection.prepareStatement(
                    "insert or ignore into sync_point(point, released) values(?, 0)");
                 PreparedStatement record = connection.prepareStatement(
                         "insert into sync_arrive(point, participant, generation, arrived_at) values(?, ?, ?, ?)")) {
                createPoint.setString(1, normalized);
                createPoint.executeUpdate();
                record.setString(1, normalized);
                record.setString(2, who);
                record.setString(3, generation);
                record.setString(4, Instant.now().toString());
                record.executeUpdate();
            } catch (SQLException writeFailed) {
                // 到达记录写不进去不影响同步语义（放行仍由 release 落地），如实忽略
            }
        }
    }

    @Override
    public void release(String point) {
        if (point == null || closed) {
            return;
        }
        String normalized = RendezvousBackend.normalizePoint(point);
        synchronized (lock) {
            try {
                // 规则 1：事务内的 insert or replace + commit —— 原子且幂等
                connection.setAutoCommit(false);
                try (PreparedStatement release = connection.prepareStatement(
                        "insert or replace into sync_point(point, released) values(?, 1)")) {
                    release.setString(1, normalized);
                    release.executeUpdate();
                    connection.commit();
                } catch (SQLException failed) {
                    rollbackQuietly();
                    throw failed;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException writeFailed) {
                throw new IllegalStateException("Cannot release the sync point [" + point + "] in ["
                        + database + "]: " + writeFailed.getMessage(), writeFailed);
            }
        }
    }

    @Override
    public boolean knows(String point) {
        if (point == null || closed) {
            return false;
        }
        String normalized = RendezvousBackend.normalizePoint(point);
        synchronized (lock) {
            // 与 isReleased 同一条"读失败只是没出现"的规则：库被删/被锁时不冒充"点存在"
            try (PreparedStatement query = connection.prepareStatement(
                    "select 1 from sync_point where point = ?")) {
                query.setString(1, normalized);
                try (ResultSet row = query.executeQuery()) {
                    return row.next();
                }
            } catch (SQLException readFailed) {
                return false;
            }
        }
    }

    @Override
    public boolean await(String point, long timeoutMillis) throws InterruptedException {
        // 没有时限（脚本没写 timeout=）：一直轮询到放行或本库关闭
        boolean unlimited = timeoutMillis < 0L;
        long deadline = System.nanoTime() + RendezvousBackend.nanosForMillis(timeoutMillis);
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
                    // 规则 2：超时是正常结果
                    return isReleased(point);
                }
                Thread.sleep(Math.min(pollMillis, Math.max(remainingNanos / 1_000_000L, 1L)));
            } else {
                Thread.sleep(pollMillis);
            }
        }
    }

    /** 收尾：关连接；**自己建的库**（父进程）再尽力删掉库文件与 WAL 边车文件。 */
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
        if (!owner) {
            return;
        }
        deleteQuietly(database);
        // WAL 模式的边车文件：不同步删掉的话，下一次运行会看到上一次的残留
        deleteQuietly(Path.of(database + "-wal"));
        deleteQuietly(Path.of(database + "-shm"));
    }

    // ------------------------------------------------------------------ 内部

    private boolean isReleased(String point) {
        String normalized = RendezvousBackend.normalizePoint(point);
        synchronized (lock) {
            if (closed) {
                return false;
            }
            // 规则 4：读失败（库被删/被锁）只是"还没放行"，不是异常
            try (PreparedStatement query = connection.prepareStatement(
                    "select released from sync_point where point = ?")) {
                query.setString(1, normalized);
                try (ResultSet row = query.executeQuery()) {
                    return row.next() && row.getInt(1) != 0;
                }
            } catch (SQLException readFailed) {
                return false;
            }
        }
    }

    private Connection connect() {
        try {
            // 显式加载：standalone jar 是"解包后合并"的产物，META-INF/services/java.sql.Driver
            // 可能被自带驱动占住，不能指望 DriverManager 自动发现（§5 第 21 条）
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException missingDriver) {
            throw new IllegalStateException("The SQLite driver is not on the classpath; "
                    + "process-mode rendezvous is unavailable.", missingDriver);
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
            throw new IllegalStateException("Cannot open the sync database [" + database + "]: "
                    + openFailed.getMessage(), openFailed);
        }
    }

    private void ensureSchema() {
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("create table if not exists sync_point ("
                    + "point text primary key, released integer not null default 0)");
            ddl.execute("create table if not exists sync_arrive ("
                    + "point text not null, participant text not null, "
                    + "generation text not null, arrived_at text not null)");
            ddl.execute("create table if not exists sync_run ("
                    + "id integer primary key check (id = 1), generation text not null)");
        } catch (SQLException schemaFailed) {
            throw new IllegalStateException("Cannot create the sync tables in [" + database + "]: "
                    + schemaFailed.getMessage(), schemaFailed);
        }
    }

    /**
     * 规则 3：认领/清理本代运行。
     *
     * <p>{@code sync_run} 里记着"这个库上一次属于哪一代"：</p>
     * <ul>
     *   <li>没有记录，或记录的代号**不是**自己 → 这是一次新运行（也可能是上一次被强杀留下的库）：
     *       清空 {@code sync_point} 与 {@code sync_arrive} 再登记自己这一代；</li>
     *   <li>代号相同 → 同一次运行里的第二个进程（父进程已经建好了库）：只做一次防御性清理，
     *       把**别的代号**留下的到达行删掉，绝不碰本次的放行状态。</li>
     * </ul>
     */
    private void claimRun() {
        synchronized (lock) {
            try {
                String existing = null;
                try (Statement query = connection.createStatement();
                     ResultSet row = query.executeQuery("select generation from sync_run where id = 1")) {
                    if (row.next()) {
                        existing = row.getString(1);
                    }
                }
                if (generation.equals(existing)) {
                    try (PreparedStatement stale = connection.prepareStatement(
                            "delete from sync_arrive where generation <> ?")) {
                        stale.setString(1, generation);
                        stale.executeUpdate();
                    }
                    return;
                }
                connection.setAutoCommit(false);
                try (Statement clear = connection.createStatement()) {
                    clear.executeUpdate("delete from sync_point");
                    clear.executeUpdate("delete from sync_arrive");
                }
                try (PreparedStatement claim = connection.prepareStatement(
                        "insert or replace into sync_run(id, generation) values(1, ?)")) {
                    claim.setString(1, generation);
                    claim.executeUpdate();
                }
                connection.commit();
                connection.setAutoCommit(true);
            } catch (SQLException claimFailed) {
                rollbackQuietly();
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // 已经报错，恢复 autoCommit 失败不再叠加
                }
                throw new IllegalStateException("Cannot claim the sync generation [" + generation
                        + "] in [" + database + "]: " + claimFailed.getMessage(), claimFailed);
            }
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 回滚失败无法补救：调用方已经在处理真正的失败
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            // 尽力清理：删不掉（杀软/句柄）不影响任何结论
        }
    }

    /** 关闭连接但不抛（用在构造失败/收尾这两条"已经在处理别的问题"的路径上）。 */
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
