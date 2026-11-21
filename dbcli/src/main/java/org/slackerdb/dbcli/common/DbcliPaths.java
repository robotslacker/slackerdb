package org.slackerdb.dbcli.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 一次运行使用的三个目录：代码目录（HOME，只读）、工作目录（WORK）、输出目录（OUTPUT）。
 *
 * <p>它是**自动写点**的唯一路径入口：默认 log、`--ref` 产物、`_COMPARE` 产物、作业 `.out`、
 * 作业库、临时文件、lock 都从这里取。用户显式给的路径只在“相对时选基座”，绝对路径原样使用
 * （按项目决定，不做越界/安全校验）。</p>
 *
 * <h2>默认值</h2>
 * <ul>
 *   <li>HOME = 进程当前目录；</li>
 *   <li>WORK = {@code <java.io.tmpdir>/dbcli-<pid>}；</li>
 *   <li>OUTPUT = {@code <HOME>/output}。</li>
 * </ul>
 * CLI 选项优先于环境变量（{@code DBCLI_HOME/WORK/OUTPUT}），环境变量优先于默认值。
 *
 * <h2>生命周期</h2>
 * <p>{@link #resolve} 只做“解析 + 校验 + 创建 + 写探针”，不产生清空副作用；
 * root 随后调用 {@link #prepareRun(boolean)} 抢 lock 并清空；子进程（作业/daemon）用
 * {@code DBCLI_RUN_CHILD}/{@code --run-child} 走“只校验 lock”的路径，绝不清空。</p>
 *
 * <h2>OUTPUT 按需创建</h2>
 * <p><b>普通运行不创建 OUTPUT</b>：没有 {@code --logfile}/{@code --ref}/{@code _COMPARE}/
 * {@code _JOB} 这些"要往 OUTPUT 写东西"的动作时，磁盘上不会多出 {@code output/}、{@code output/ref/}
 * 这类空目录（以前每次运行都建，CI/调度器的工作区因此被垃圾目录填满）。</p>
 * <p>第一次真正要写时由 {@link #ensureOutput()} 创建并抢 OUTPUT 的 lock（子进程沿用 root 的 lock）；
 * 如果 OUTPUT 在启动时就已存在，则保持原语义：root 抢 lock 并清空。</p>
 */
public final class DbcliPaths implements AutoCloseable {

    public static final String ENV_HOME = "DBCLI_HOME";
    public static final String ENV_WORK = "DBCLI_WORK";
    public static final String ENV_OUTPUT = "DBCLI_OUTPUT";
    public static final String ENV_RUN_CHILD = "_DBCLI_RUN_CHILD";
    public static final String ENV_RUN_ID = "_DBCLI_RUN_ID";
    /** 嵌入/调度器宿主：不持有 root lock，进程作业子进程也不要校验 lock。 */
    public static final String ENV_NO_LOCK = "_DBCLI_NO_LOCK";
    /** 仅测试/旧构造器使用的兼容标记（生产线不会设置）。 */
    public static final String ENV_TEST_LEGACY = "_DBCLI_TEST_LEGACY_PATHS";

    public static final String CONTROL_DIR = ".dbcli";
    public static final String LOCK_FILE = "lock";
    public static final String LOCK_TEMP = "lock.tmp";

    /** 本次运行共享的 SQLite 文件（JobManager 以及后续其它用途）。 */
    public static final String RUN_DATABASE_NAME = "dbcli_log.db";

    /** 目录/路径策略错误：调用方转成退出码 2（目录问题）或 DbcliException。 */
    public static final class DirectoryException extends Exception {
        private static final long serialVersionUID = 1L;

        public DirectoryException(String message) {
            super(message);
        }

        public DirectoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Path home;
    private final Path work;
    /** 按需创建：{@link #resolve} 只在它已存在时解析成真实路径，{@link #ensureOutput()} 建好后会更新。 */
    private volatile Path output;
    /** OUTPUT 是否已经准备好（created + locked）；并发作业会一起调用 {@link #ensureOutput()}。 */
    private volatile boolean outputPrepared;
    private final String runId;
    private final boolean runChild;
    private final boolean daemonChild;
    private final boolean defaultWork;
    /** 旧构造器/测试用的“三目录同根”兼容模式（产物仍放旧位置）。 */
    private final boolean legacy;
    /** 嵌入/调度器宿主：不持有 root lock。 */
    private final boolean noLock;
    private volatile boolean locked;
    /** 同一 WORK 的 GLOBAL 变量库（懒创建）。 */
    private GlobalVariableStore globalVariables;

    /** lock 句柄（按真实路径排序后获取；失败时释放已拿到的）。 */
    private LockHandle workLock;
    private LockHandle outputLock;

    private DbcliPaths(Path home, Path work, Path output, String runId, boolean runChild,
                       boolean daemonChild, boolean defaultWork, boolean legacy, boolean noLock) {
        this.home = home;
        this.work = work;
        this.output = output;
        this.runId = runId;
        this.runChild = runChild;
        this.daemonChild = daemonChild;
        this.defaultWork = defaultWork;
        this.legacy = legacy;
        this.noLock = noLock;
    }

    // ------------------------------------------------------------------ 解析/校验

    /**
     * 解析本次运行的三个目录。
     *
     * @param homeOpt    {@code --home_dir}（null/blank 表示未给）
     * @param workOpt    {@code --work_dir}
     * @param outputOpt  {@code --output_dir}
     * @param runChildArg 命令行上的隐藏 {@code --run-child}
     * @throws DirectoryException HOME 不可读、WORK/OUTPUT 不可创建/不可写、目录关系非法等
     */
    public static DbcliPaths resolve(String homeOpt, String workOpt, String outputOpt,
                                     boolean runChildArg) throws DirectoryException {
        Path processDirectory = Path.of("").toAbsolutePath();
        boolean runChild = runChildArg || truthy(System.getenv(ENV_RUN_CHILD));

        Path home = firstNonBlank(homeOpt, System.getenv(ENV_HOME)) == null
                ? processDirectory
                : resolveAgainst(processDirectory, firstNonBlank(homeOpt, System.getenv(ENV_HOME)), "home");
        Path work;
        String workValue = firstNonBlank(workOpt, System.getenv(ENV_WORK));
        boolean defaultWork = workValue == null;
        if (defaultWork) {
            work = Path.of(System.getProperty("java.io.tmpdir", "."), "dbcli-" + currentPid());
        } else {
            work = resolveAgainst(processDirectory, workValue, "work");
        }
        String outputValue = firstNonBlank(outputOpt, System.getenv(ENV_OUTPUT));
        Path output = outputValue == null
                ? home.resolve("output")
                : resolveAgainst(processDirectory, outputValue, "output");

        home = realDirectory(home, "home", true);
        boolean legacy = truthy(System.getenv(ENV_TEST_LEGACY));
        if (!legacy) {
            checkRelations(home, work, output);
        }
        work = prepareRoot(work, "work", runChild);
        output = prepareOutputRoot(output, runChild);
        if (!legacy) {
            checkRelations(home, work, output);
        }

        if (!runChild) {
            probeWritable(work, "work");
            // OUTPUT 不存在是正常状态（按需创建）；存在时仍做写探针，把"不可写"挡在启动阶段
            if (Files.isDirectory(output)) {
                probeWritable(output, "output");
            }
        }

        String envRunId = firstNonBlank(null, System.getenv(ENV_RUN_ID));
        boolean daemonChild = runChild && envRunId == null;
        String runId = envRunId;
        if (runId == null) {
            if (runChild) {
                runId = readRunId(work.resolve(CONTROL_DIR).resolve(LOCK_FILE)).orElse(null);
            }
            if (runId == null) {
                runId = currentPid() + "-" + UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
            }
        }
        boolean noLock = truthy(System.getenv(ENV_NO_LOCK));
        return new DbcliPaths(home, work, output, runId, runChild, daemonChild, defaultWork, legacy, noLock);
    }

    public Path home() {
        return home;
    }

    /**
     * 兼容/测试用：把同一个目录当作 HOME/WORK/OUTPUT（不校验、不清空）。
     * 生产路径必须走 {@link #resolve}；这个入口只给旧构造器与 hermetic 测试用。
     */
    public static DbcliPaths forDirectory(Path directory) {
        Path real = directory.toAbsolutePath().normalize();
        return new DbcliPaths(real, real, real,
                currentPid() + "-fixture", false, false, false, true, true);
    }

    public boolean legacy() {
        return legacy;
    }

    /** 本对象是否已经持有/验证过 root lock（决定子进程要不要校验）。 */
    public boolean isLocked() {
        return locked;
    }

    /**
     * 同一 WORK 的 GLOBAL 变量存储；线程作业共享本对象，进程作业各自打开同一个 SQLite 文件。
     */
    public synchronized GlobalVariableStore globalVariables() {
        if (globalVariables == null) {
            globalVariables = legacy
                    ? GlobalVariableStore.inMemory()
                    : new GlobalVariableStore(
                            work.resolve(CONTROL_DIR).resolve(GlobalVariableStore.DATABASE_NAME));
        }
        return globalVariables;
    }

    public Path work() {
        return work;
    }

    public Path output() {
        return output;
    }

    public String runId() {
        return runId;
    }

    public boolean runChild() {
        return runChild;
    }

    /** 默认 WORK（`<tmp>/dbcli-<pid>`）才做陈旧目录清理；显式 WORK 不扫别人的目录。 */
    public boolean defaultWork() {
        return defaultWork;
    }

    // ------------------------------------------------------------------ 运行准备（root 清空 / 子进程校验）

    /**
     * root：抢 WORK/OUTPUT 的 lock → 清空两目录（保留 lock/lock.tmp）。
     * 子进程：只校验两个 lock 存在且 runId 一致；不清空、不抢锁、不扫陈旧 WORK。
     *
     * <p>OUTPUT 按需创建（见类注释）：启动时它不存在就<b>不建、不锁、不清</b>，等第一次真正
     * 要写时由 {@link #ensureOutput()} 建好并抢 lock；启动时已存在则照旧抢 lock + 清空。</p>
     *
     * @param clean {@code false} 时完全跳过（嵌入式调用方自己管理目录）；root 正常为 true
     */
    public void prepareRun(boolean clean) throws DirectoryException {
        if (noLock) {
            return;
        }
        if (runChild) {
            if (legacy) {
                return;
            }
            validateChildLocks();
            if (daemonChild) {
                rewriteLockOwner(workLockPath());
                // OUTPUT 可能是按需创建的：父进程没建过它时这里没有可接管的 lock
                if (Files.isRegularFile(outputLockPath())) {
                    rewriteLockOwner(outputLockPath());
                }
            }
            locked = true;
            return;
        }
        if (!clean) {
            return;
        }
        if (defaultWork) {
            cleanStaleDefaultWorks();
        }
        boolean outputExists = Files.isDirectory(output);
        List<Path> roots = new ArrayList<>();
        roots.add(work);
        if (outputExists) {
            roots.add(output);
        }
        roots.sort(Comparator.comparing(Path::toString));
        try {
            for (Path root : roots) {
                LockHandle handle = acquireLock(root);
                if (root.equals(work)) {
                    workLock = handle;
                } else {
                    outputLock = handle;
                }
            }
            for (Path root : roots) {
                clearRoot(root);
            }
            locked = true;
        } catch (DirectoryException failure) {
            releaseLocks();
            throw failure;
        }
    }

    private void validateChildLocks() throws DirectoryException {
        LockInfo workInfo = readLock(work.resolve(CONTROL_DIR).resolve(LOCK_FILE));
        if (workInfo == null) {
            throw new DirectoryException("The job run lock is missing under [" + work + "]; "
                    + "refusing to run without a root run.");
        }
        if (!runId.equals(workInfo.runId())) {
            throw new DirectoryException("The run id [" + runId + "] does not match the job run lock ["
                    + workInfo.runId() + "]; refusing to clear/join another run.");
        }
        // OUTPUT 在启动时不存在就意味着 root 没有锁它（按需创建）；只要它存在就必须是本次运行的
        LockInfo outputInfo = readLock(output.resolve(CONTROL_DIR).resolve(LOCK_FILE));
        if (Files.isDirectory(output)) {
            if (outputInfo == null) {
                throw new DirectoryException("The job run lock is missing under [" + output + "]; "
                        + "refusing to run without a root run.");
            }
            if (!runId.equals(outputInfo.runId())) {
                throw new DirectoryException("The run id [" + runId + "] does not match the job run lock ["
                        + outputInfo.runId() + "]; refusing to clear/join another run.");
            }
        }
    }

    // ------------------------------------------------------------------ 路径解析

    /** 相对脚本/输入：WORK → 调用脚本目录 → HOME；绝对路径原样（读取时负责存在性）。 */
    public Path findScript(String raw, Path callingScriptDirectory) {
        Path value = parse(raw);
        if (value.isAbsolute()) {
            return Files.isRegularFile(value) ? value.normalize() : null;
        }
        Path found = firstExisting(value, callingScriptDirectory);
        return found != null && Files.isRegularFile(found) ? found : null;
    }

    public Path findInput(String raw, Path callingScriptDirectory) {
        Path value = parse(raw);
        if (value.isAbsolute()) {
            return value.normalize();
        }
        Path found = firstExisting(value, callingScriptDirectory);
        return found == null ? work.resolve(value).normalize() : found;
    }

    private Path firstExisting(Path value, Path callingScriptDirectory) {
        Path inWork = work.resolve(value).normalize();
        if (Files.isRegularFile(inWork)) {
            return inWork;
        }
        if (callingScriptDirectory != null) {
            Path beside = callingScriptDirectory.resolve(value).normalize();
            if (Files.isRegularFile(beside)) {
                return beside;
            }
        }
        Path inHome = home.resolve(value).normalize();
        return Files.isRegularFile(inHome) ? inHome : null;
    }

    /** 用户写路径：相对 WORK（自动建父目录）；绝对原样（不校验/不建父目录）。 */
    public Path workFile(String raw) {
        Path value = parse(raw);
        if (value.isAbsolute()) {
            return value.normalize();
        }
        Path target = work.resolve(value).normalize();
        createParent(target);
        return target;
    }

    /**
     * 确保 OUTPUT 已存在并且被本次运行锁住。
     *
     * <p>只在"真的要往 OUTPUT 写东西"时调用：{@code --logfile}/{@code --ref}/{@code _COMPARE}/
     * {@code _JOB} 的 {@code .out}。普通脚本运行因此不会在磁盘上留下空的 {@code output/}。</p>
     *
     * <ul>
     *   <li>root：建目录（如果还没有）+ 抢 OUTPUT 的 lock；启动时已存在的 OUTPUT 已经锁过，幂等。</li>
     *   <li>daemon 副本：它接管了这次运行，和 root 一样建目录 + 抢 lock。</li>
     *   <li>作业子进程：<b>不建、不锁</b>——父进程在拉起子进程之前已经建好并锁好了 OUTPUT
     *       （有作业就一定有 {@code OUTPUT/jobs}）；目录不在说明调用方式错了，直接报可读错误。</li>
     *   <li>legacy（{@link #forDirectory}，OUTPUT 就是 WORK）/ noLock（宿主自管）/ 宿主没调用
     *       {@link #prepareRun}（嵌入式，自己管并发）：只保证目录存在，不抢 lock。</li>
     * </ul>
     *
     * @throws IllegalStateException 目录建不出来或抢不到 lock（调用方转成一行可读错误）
     */
    public synchronized void ensureOutput() {
        if (legacy || outputPrepared) {
            return;
        }
        boolean child = runChild && !daemonChild;
        if (child && !noLock && !Files.isDirectory(output)) {
            throw new IllegalStateException("The output directory [" + output
                    + "] does not exist; the parent run must create it.");
        }
        try {
            if (!Files.isDirectory(output)) {
                Files.createDirectories(output);
                output = output.toRealPath();
            }
            // 只有为这次运行抢过 lock 的进程才锁 OUTPUT：宿主（没走 prepareRun）自己管并发
            if (!noLock && locked && !child && outputLock == null) {
                outputLock = acquireLock(output);
            }
            outputPrepared = true;
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot create the output directory [" + output + "]: "
                    + failed.getMessage(), failed);
        } catch (DirectoryException failed) {
            throw new IllegalStateException(failed.getMessage(), failed);
        }
    }

    /** 用户写路径：相对 OUTPUT（自动建父目录）；绝对原样（不校验/不建父目录）。 */
    public Path outputFile(String raw) {
        Path value = parse(raw);
        if (value.isAbsolute()) {
            return value.normalize();
        }
        ensureOutput();
        Path target = output.resolve(value).normalize();
        createParent(target);
        return target;
    }

    public Path outputDirectory(String raw) {
        Path target = outputFile(raw);
        createDirectories(target);
        return target;
    }

    /** 本次运行的文件名前缀（作业库/临时文件用）。 */
    public Path workScratch(String name) {
        Path directory = work.resolve(CONTROL_DIR).resolve("tmp");
        createDirectories(directory);
        return directory.resolve(name);
    }

    public Path jobDatabase() {
        return runDatabase();
    }

    /** 本次运行共享的 SQLite 文件：{@code WORK/dbcli_log.db}（固定名，不随 runId 变）。 */
    public Path runDatabase() {
        return work.resolve(RUN_DATABASE_NAME);
    }

    /** 交互控制台历史：`~/.dbcli/history`（只在 consoleMode 使用）。 */
    public Path consoleHistoryFile() {
        return Path.of(System.getProperty("user.home", "."), ".dbcli", "history");
    }

    public Path workLock() {
        return work.resolve(CONTROL_DIR).resolve(LOCK_FILE);
    }

    public Path outputLock() {
        return output.resolve(CONTROL_DIR).resolve(LOCK_FILE);
    }

    private Path workLockPath() {
        return workLock();
    }

    private Path outputLockPath() {
        return outputLock();
    }

    private void rewriteLockOwner(Path lock) throws DirectoryException {
        try {
            writeLock(lock, lock.getParent().getParent());
        } catch (IOException failed) {
            throw new DirectoryException("Cannot take over the run lock [" + lock + "]: "
                    + failed.getMessage(), failed);
        }
    }

    public Path jobOutputPath(String jobName) {
        ensureOutput();
        Path directory = output.resolve("jobs");
        createDirectories(directory);
        return directory.resolve(jobName + ".out");
    }

    /** `--ref` 默认 log：OUTPUT/logs/<脚本基名>.log。 */
    public Path refLog(Path script) {
        ensureOutput();
        Path directory = output.resolve("logs");
        createDirectories(directory);
        return directory.resolve(baseName(script) + ".log");
    }

    public Path refReportDir() {
        ensureOutput();
        Path directory = output.resolve("ref");
        createDirectories(directory);
        return directory;
    }

    /** `_COMPARE` 产物：`OUTPUT/<目标基名>.<扩展名>`（不建 compare 子目录）。 */
    public Path compareReportPath(Path target, String extension) {
        return output.resolve(baseName(target) + extension);
    }

    public Path referenceTempFile() {
        return referenceTempFile(runId);
    }

    /** 参考比对的临时输出（同一 run 内可带作业名，避免并发作业共用一个文件）。 */
    public Path referenceTempFile(String name) {
        ensureOutput();
        Path directory = output.resolve("tmp");
        createDirectories(directory);
        String safe = name == null ? runId : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return directory.resolve(runId + "-" + safe + ".out");
    }

    // ------------------------------------------------------------------ 关闭

    /** 释放 root 自己写的锁；子进程没有锁。幂等。 */
    @Override
    public void close() {
        if (daemonChild) {
            // 副本接管了父进程的锁：它退出时就是这次运行的结束，删锁；作业子进程不能删。
            releaseLockFile(workLockPath());
            releaseLockFile(outputLockPath());
        }
        releaseLocks();
        if (globalVariables != null) {
            globalVariables.close();
            globalVariables = null;
        }
    }

    private void releaseLockFile(Path path) {
        LockInfo info = readLock(path);
        if (info != null && runId.equals(info.runId()) && info.pid() == currentPid()) {
            deleteQuietly(path);
        }
    }

    /**
     * daemon 父进程把锁“移交”给副本：清掉自己的句柄但**不删锁文件**。
     * 副本会在启动后把 lock 的 pid/processStart 改写成自己。
     */
    public void transferLocks() {
        workLock = null;
        outputLock = null;
    }

    /** 目标是否落在 WORK/OUTPUT 内（root 的预置输入检查用；绝对/相对都先规范化）。 */
    public boolean isInsideWorkOrOutput(Path path) {
        if (path == null) {
            return false;
        }
        // 两边都解析到真实路径再比较：Windows 8.3 短名（如 ADMINI~1 vs Administrator）会让
        // 纯词法 startsWith 判断漏判，预置在工作/输出目录里的输入就能绕过清空前的拒绝检查。
        Path target = canonicalize(path);
        return target.startsWith(canonicalize(work)) || target.startsWith(canonicalize(output));
    }

    /**
     * 解析真实路径；文件/目录不存在时，把存在的最近父目录解析成真实路径后再拼回文件名，
     * 保证与 {@code resolve()} 里用 {@code toRealPath()} 规范化过的 work/output 可比。
     */
    private static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException notResolvable) {
            Path absolute = path.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent == null) {
                return absolute;
            }
            try {
                return parent.toRealPath().resolve(absolute.getFileName());
            } catch (IOException | RuntimeException parentNotResolvable) {
                return absolute;
            }
        }
    }

    // ------------------------------------------------------------------ 内部：目录/关系

    private static Path prepareRoot(Path path, String role, boolean runChild)
            throws DirectoryException {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(path)) {
                    throw new DirectoryException(role + " directory [" + path
                            + "] is a symbolic link; choose a real directory.");
                }
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new DirectoryException(role + " directory [" + path + "] is not a directory.");
                }
            } else {
                if (runChild) {
                    throw new DirectoryException(role + " directory [" + path
                            + "] does not exist; the parent run must create it.");
                }
                Files.createDirectories(path);
            }
            return path.toRealPath();
        } catch (IOException failed) {
            throw new DirectoryException(role + " directory [" + path + "] cannot be prepared: "
                    + failed.getMessage(), failed);
        }
    }

    /**
     * OUTPUT 的 root 解析：存在就照旧校验（符号链接/非目录/真实路径），
     * <b>不存在也不创建</b>——它是按需创建的（见类注释），这里只把它规范成可与 WORK 比较的绝对路径。
     */
    private static Path prepareOutputRoot(Path path, boolean runChild) throws DirectoryException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return prepareRoot(path, "output", runChild);
        }
        return canonicalize(path);
    }

    private static Path realDirectory(Path path, String role, boolean mustExist)
            throws DirectoryException {
        try {
            if (!Files.isDirectory(path)) {
                throw new DirectoryException(role + " directory [" + path
                        + "] does not exist or is not a directory.");
            }
            if (!Files.isReadable(path)) {
                throw new DirectoryException(role + " directory [" + path + "] is not readable.");
            }
            return path.toRealPath();
        } catch (IOException failed) {
            throw new DirectoryException(role + " directory [" + path + "] is not readable: "
                    + failed.getMessage(), failed);
        }
    }

    private static void checkRelations(Path home, Path work, Path output) throws DirectoryException {
        if (home.equals(work) || home.equals(output)) {
            throw new DirectoryException("home directory [" + home
                    + "] must be different from work/output directories.");
        }
        if (home.startsWith(work) || home.startsWith(output)) {
            throw new DirectoryException("home directory [" + home
                    + "] cannot be inside work/output; clearing would delete the code directory.");
        }
        if (work.equals(output)) {
            throw new DirectoryException("work and output directories must be different: [" + work + "].");
        }
        if (work.startsWith(output) || output.startsWith(work)) {
            throw new DirectoryException("work [" + work + "] and output [" + output
                    + "] cannot contain each other; clearing one would delete the other.");
        }
        if (isFilesystemRoot(work) || isFilesystemRoot(output)) {
            throw new DirectoryException("work/output must not be a filesystem root: [" + work
                    + "] / [" + output + "].");
        }
    }

    private static boolean isFilesystemRoot(Path path) {
        Path root = path.getRoot();
        return root != null && path.equals(root);
    }

    private static Path resolveAgainst(Path base, String value, String role)
            throws DirectoryException {
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? path.normalize() : base.resolve(path).normalize();
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new DirectoryException("Invalid " + role + " directory [" + value + "]: "
                    + invalid.getReason());
        }
    }

    private static void probeWritable(Path root, String role) throws DirectoryException {
        Path probe = root.resolve(".dbcli-probe-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createFile(probe);
            Files.writeString(probe, "probe", StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new DirectoryException(role + " directory [" + root + "] is not writable: "
                    + failed.getMessage(), failed);
        } finally {
            deleteQuietly(probe);
        }
    }

    // ------------------------------------------------------------------ 内部：lock

    private LockHandle acquireLock(Path root) throws DirectoryException {
        Path control = root.resolve(CONTROL_DIR);
        prepareControlDirectory(control);
        Path lock = control.resolve(LOCK_FILE);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Files.createFile(lock);
                writeLock(lock, root);
                return new LockHandle(lock);
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                LockInfo info = readLock(lock);
                if (info == null) {
                    throw new DirectoryException("Cannot use [" + root
                            + "]: its lock file is unreadable; remove [" + lock + "] or choose another directory.");
                }
                if (runId.equals(info.runId())) {
                    // 同一次运行重复准备（例如 --logfile 先按需建了 OUTPUT，prepareRun 随后又要锁它）
                    return new LockHandle(lock);
                }
                if (info.alive()) {
                    throw new DirectoryException("Directory [" + root
                            + "] is in use by another dbcli run (pid " + info.pid() + ").");
                }
                deleteQuietly(lock);
            } catch (IOException failed) {
                throw new DirectoryException("Cannot lock [" + root + "]: " + failed.getMessage(), failed);
            }
        }
        throw new DirectoryException("Cannot acquire the run lock in [" + root + "].");
    }

    private void writeLock(Path lock, Path root) throws IOException {
        String content = runId + "\n" + currentPid() + "\n" + processStartMillis() + "\n"
                + System.currentTimeMillis() + "\n" + root + "\n";
        Files.writeString(lock, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static void prepareControlDirectory(Path control) throws DirectoryException {
        try {
            if (Files.exists(control, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(control)) {
                    throw new DirectoryException("Control path [" + control + "] is a symbolic link.");
                }
                if (!Files.isDirectory(control, LinkOption.NOFOLLOW_LINKS)) {
                    throw new DirectoryException("Control path [" + control + "] is not a directory.");
                }
            } else {
                Files.createDirectories(control);
            }
        } catch (IOException failed) {
            throw new DirectoryException("Cannot prepare [" + control + "]: " + failed.getMessage(), failed);
        }
    }

    private static LockInfo readLock(Path lock) {
        try {
            List<String> lines = Files.readAllLines(lock, StandardCharsets.UTF_8);
            if (lines.size() < 3) {
                return null;
            }
            String id = lines.get(0).strip();
            long pid = Long.parseLong(lines.get(1).strip());
            long start = Long.parseLong(lines.get(2).strip());
            return new LockInfo(id, pid, start);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static Optional<String> readRunId(Path lock) {
        LockInfo info = readLock(lock);
        return info == null || info.runId().isBlank() ? Optional.empty() : Optional.of(info.runId());
    }

    private record LockInfo(String runId, long pid, long processStart) {
        boolean alive() {
            return ProcessHandle.of(pid)
                    .map(handle -> handle.isAlive()
                            && handle.info().startInstant().map(Instant::toEpochMilli).orElse(processStart)
                            == processStart)
                    .orElse(false);
        }
    }

    private final class LockHandle {
        private final Path path;

        private LockHandle(Path path) {
            this.path = path;
        }

        private void release() {
            LockInfo info = readLock(path);
            if (info != null && runId.equals(info.runId()) && info.pid() == currentPid()) {
                deleteQuietly(path);
            }
        }
    }

    private void releaseLocks() {
        if (outputLock != null) {
            outputLock.release();
            outputLock = null;
        }
        if (workLock != null) {
            workLock.release();
            workLock = null;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.setAttribute(file, "dos:readonly", false);
                } catch (IOException | RuntimeException ignored) {
                    // 非 Windows/无权限：继续尝试删除
                }
                Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException ignored) {
            // 清理失败不改变调用方的结论（锁/临时文件的最终回收由 OS 负责）
        }
    }

    // ------------------------------------------------------------------ 内部：清空

    private static void clearRoot(Path root) throws DirectoryException {
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                String name = child.getFileName() == null ? "" : child.getFileName().toString();
                if (CONTROL_DIR.equals(name)) {
                    clearControlDirectory(child);
                } else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    // 清内容、保留顶层目录本身：log/ref 等路径可能在清空前已被解析并建好目录。
                    clearDirectoryContents(child);
                } else {
                    deleteTree(child);
                }
            }
        } catch (IOException failed) {
            throw new DirectoryException("Cannot clear [" + root + "]: " + failed.getMessage(), failed);
        }
    }

    private static void clearDirectoryContents(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                deleteTree(child);
            }
        }
    }

    private static void clearControlDirectory(Path control) throws IOException {
        try (Stream<Path> children = Files.list(control)) {
            for (Path child : children.toList()) {
                String name = child.getFileName() == null ? "" : child.getFileName().toString();
                if (LOCK_FILE.equals(name) || LOCK_TEMP.equals(name)) {
                    continue;
                }
                deleteTree(child);
            }
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            deleteFile(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            if (exc != null) {
                                throw exc;
                            }
                            deleteFile(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
            return;
        }
        deleteFile(path);
    }

    private static void deleteFile(Path file) throws IOException {
        try {
            Files.setAttribute(file, "dos:readonly", false);
        } catch (IOException | RuntimeException ignored) {
            // 非 Windows 或没有权限：直接尝试删除
        }
        Files.deleteIfExists(file);
    }

    private void cleanStaleDefaultWorks() {
        Path parent = work.getParent();
        if (parent == null) {
            return;
        }
        String prefix = "dbcli-";
        try (Stream<Path> entries = Files.list(parent)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName() == null ? "" : entry.getFileName().toString();
                if (!name.startsWith(prefix) || entry.equals(work)) {
                    continue;
                }
                String pidText = name.substring(prefix.length());
                long pid;
                try {
                    pid = Long.parseLong(pidText);
                } catch (NumberFormatException notPid) {
                    continue;
                }
                boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                if (alive) {
                    continue;
                }
                try {
                    long age = System.currentTimeMillis() - Files.getLastModifiedTime(entry).toMillis();
                    if (age > 3_600_000L) {
                        deleteTree(entry);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // 陈旧目录清理失败不影响本次运行
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 扫描失败不影响本次运行
        }
    }

    // ------------------------------------------------------------------ 内部：小工具

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        return second != null && !second.isBlank() ? second.strip() : null;
    }

    private static boolean truthy(String value) {
        return value != null && !value.isBlank()
                && !"0".equals(value.strip()) && !"false".equalsIgnoreCase(value.strip());
    }

    private static Path parse(String raw) {
        try {
            return Path.of(raw == null ? "" : raw.strip());
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new IllegalArgumentException("Invalid path [" + raw + "]: " + invalid.getReason(), invalid);
        }
    }

    private static String baseName(Path path) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot create directory [" + directory + "]: "
                    + failed.getMessage(), failed);
        }
    }

    private static void createParent(Path file) {
        Path parent = file.getParent();
        if (parent != null) {
            createDirectories(parent);
        }
    }

    private static long currentPid() {
        return ProcessHandle.current().pid();
    }

    private static long processStartMillis() {
        return ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }
}
