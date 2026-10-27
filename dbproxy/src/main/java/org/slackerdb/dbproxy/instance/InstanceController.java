package org.slackerdb.dbproxy.instance;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实例生命周期操作：create / start / shutdown / restart / destroy / logs。
 *
 * <p>全部操作都是"无状态"的：不依赖内存里的进程句柄，只依赖目录、conf、pid 文件和
 * {@code /health}。因此代理重启、实例被用户手工启停之后，这些命令依然有效。</p>
 */
public final class InstanceController {

    public static final long START_TIMEOUT_MS = 30_000L;
    public static final long STOP_TIMEOUT_MS = 20_000L;
    public static final long SIGTERM_TIMEOUT_MS = 5_000L;
    private static final long POLL_INTERVAL_MS = 200L;

    private final Path root;
    private final InstanceRegistry registry;
    private final InstanceProbe probe;
    private final InstanceLauncher launcher;
    private final Logger logger;

    /** 正在执行的操作（仅内存，用于把 STARTING/STOPPING 显示出来）。 */
    private final Map<String, InstanceState> activeOperations = new ConcurrentHashMap<>();

    public InstanceController(Path root, InstanceRegistry registry, InstanceProbe probe,
                              InstanceLauncher launcher, Logger logger) {
        this.root = root.toAbsolutePath().normalize();
        this.registry = registry;
        this.probe = probe;
        this.launcher = launcher;
        this.logger = logger;
    }

    public Path root() {
        return root;
    }

    // ------------------------------------------------------------------ 查询

    public List<ObservedInstance> list() {
        List<ObservedInstance> result = new ArrayList<>();
        for (ObservedInstance observed : registry.list()) {
            result.add(withActiveOperation(observed));
        }
        return result;
    }

    /**
     * 强制重新扫描后再返回列表。
     *
     * <p>给人看的命令（CLI {@code list}/{@code status}、管理口默认请求）用这个：
     * 用户刚手工杀掉/启动过实例时，"5 秒缓存"会让人以为代理看错了。</p>
     */
    public List<ObservedInstance> listFresh() {
        registry.refresh();
        return list();
    }

    public ObservedInstance status(String alias) {
        String normalized = requireAlias(alias);
        ObservedInstance observed = registry.get(normalized);
        if (observed == null) {
            throw new InstanceException(InstanceException.NOT_FOUND,
                    "Database [" + normalized + "] does not exist under " + root + ".");
        }
        return withActiveOperation(observed);
    }

    private ObservedInstance withActiveOperation(ObservedInstance observed) {
        InstanceState operation = activeOperations.get(observed.alias());
        if (operation == null || observed.state() == operation) {
            return observed;
        }
        return new ObservedInstance(observed.alias(), observed.dir(), operation, observed.dataName(),
                observed.pid(), observed.pgPort(), observed.mgmtPort(), observed.health(),
                observed.uptimeSeconds(), observed.error(), observed.conf());
    }

    // ------------------------------------------------------------------ 创建

    public ObservedInstance create(InstanceSpec spec) {
        String alias = requireAlias(spec.alias());
        if (InstancePaths.RESERVED_ALIASES.contains(alias)) {
            throw new InstanceException(InstanceException.CONFLICT,
                    "Alias [" + alias + "] is reserved by the proxy management endpoint.");
        }
        if (!spec.name().matches("[A-Za-z][A-Za-z0-9_-]{0,62}")) {
            throw new InstanceException(InstanceException.BAD_REQUEST,
                    "Invalid database name [" + spec.name() + "]. Expected [A-Za-z][A-Za-z0-9_-]{0,62}.");
        }
        Path finalDir = root.resolve(alias);
        if (Files.exists(finalDir) || existingAliasIgnoringCase(alias)) {
            throw new InstanceException(InstanceException.CONFLICT,
                    "Alias [" + alias + "] already exists. Choose another alias or destroy the existing instance.");
        }

        try {
            Files.createDirectories(root);
        } catch (IOException ioe) {
            throw new InstanceException(InstanceException.INTERNAL,
                    "Cannot create data_dir [" + root + "]: " + ioe.getMessage(), ioe);
        }

        Path tempDir = root.resolve(InstancePaths.TEMP_PREFIX + alias + "-"
                + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            // 先确认运行时可用：宁可什么都不建，也不要留下一个起不来的目录
            LaunchSpec specRuntime = launcher.resolve();

            InstancePaths tempPaths = new InstancePaths(alias, tempDir);
            Files.createDirectories(tempPaths.dataDir());
            Files.createDirectories(tempPaths.logsDir());

            int[] ports = PortAllocator.allocate(root, 2, Set.of());
            Map<String, String> confValues = buildConf(spec, ports[0], ports[1]);
            Files.writeString(tempPaths.conf(), InstanceConf.render(confValues), StandardCharsets.UTF_8);
            BinScripts.write(tempPaths, specRuntime);

            move(tempDir, finalDir);
            logger.info("[PROXY][INSTANCE   ] Created instance [{}] at {} (pg={}, mgmt={}, data={}).",
                    alias, finalDir, ports[0], ports[1], spec.name());
        } catch (IOException ioe) {
            cleanupQuietly(tempDir);
            throw new InstanceException(InstanceException.INTERNAL,
                    "Failed to create instance [" + alias + "]: " + ioe.getMessage(), ioe);
        } catch (RuntimeException runtimeException) {
            cleanupQuietly(tempDir);
            throw runtimeException;
        }

        registry.refresh();

        if (!spec.start()) {
            ObservedInstance observed = probe.probe(alias, finalDir);
            registry.refreshAlias(alias);
            return observed;
        }

        try {
            return start(alias);
        } catch (InstanceException startFailure) {
            throw new InstanceException(startFailure.status(),
                    "Instance [" + alias + "] was created at " + finalDir
                            + " but failed to start: " + startFailure.getMessage(), startFailure);
        }
    }

    private Map<String, String> buildConf(InstanceSpec spec, int pgPort, int mgmtPort) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("data", spec.name());
        values.put("data_dir", "data");
        values.put("port", String.valueOf(pgPort));
        values.put("port_x", String.valueOf(mgmtPort));
        values.put("pid", "instance.pid");
        values.put("log", "logs/server.log");
        values.put("bind", "127.0.0.1");
        values.put("daemon", "false");
        // 托管实例绝不参与组播：否则它会以自己的 data 名再次注册，与别名的路由打架
        values.put("advertise_address", "");
        values.put("access_mode", "READ_WRITE");

        for (Map.Entry<String, String> entry : spec.overrides().entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z][a-z0-9_]*")) {
                throw new InstanceException(InstanceException.BAD_REQUEST,
                        "Invalid configuration key [" + entry.getKey() + "]. Expected [a-z][a-z0-9_]*.");
            }
            if (InstanceConf.MANAGED_KEYS.contains(key)) {
                throw new InstanceException(InstanceException.BAD_REQUEST,
                        "Configuration key [" + key + "] is managed by the proxy and cannot be overridden.");
            }
            values.put(key, entry.getValue() == null ? "" : entry.getValue().trim());
        }
        return values;
    }

    // ------------------------------------------------------------------ 启动

    public ObservedInstance start(String alias) {
        String normalized = requireAlias(alias);
        InstancePaths paths = requireInstance(normalized);

        ObservedInstance before = probe.probe(normalized, paths.dir());
        if (before.state() == InstanceState.RUNNING || before.state() == InstanceState.DEGRADED) {
            throw new InstanceException(InstanceException.CONFLICT,
                    "Instance [" + normalized + "] is already running (state=" + before.state() + ").");
        }
        if (before.conf() == null) {
            throw new InstanceException(InstanceException.CONFLICT,
                    "Cannot start [" + normalized + "]: " + before.error());
        }

        activeOperations.put(normalized, InstanceState.STARTING);
        try {
            try {
                // 陈旧 pid 文件会让"进程存活"误判；真正启动后 dbserver 会重新写入
                Files.deleteIfExists(paths.pid());
            } catch (IOException ignored) {
                // 删不掉也不影响启动，探测仍以 /health 为准
            }

            Process process;
            try {
                process = launcher.start(paths, before.conf());
            } catch (IOException ioe) {
                throw new InstanceException(InstanceException.INTERNAL,
                        "Failed to launch instance [" + normalized + "]: " + ioe.getMessage()
                                + ". " + tail(paths, 20), ioe);
            }

            long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
            ObservedInstance observed = before;
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) {
                    throw new InstanceException(InstanceException.INTERNAL,
                            "Instance [" + normalized + "] exited during startup (exit code "
                                    + process.exitValue() + "). " + tail(paths, 30));
                }
                observed = probe.probe(normalized, paths.dir());
                if (observed.state() == InstanceState.RUNNING) {
                    registry.refreshAlias(normalized);
                    logger.info("[PROXY][INSTANCE   ] Instance [{}] is running (pid={}, pg={}, mgmt={}).",
                            normalized, observed.pidText(), observed.pgPort(), observed.mgmtPort());
                    // 注意：这里返回原始观察态。STARTING 只是"操作进行中"的展示态，
                    // 由 list()/status() 叠加；操作结果本身必须是 RUNNING。
                    return observed;
                }
                sleepQuietly(POLL_INTERVAL_MS);
            }

            throw new InstanceException(InstanceException.GATEWAY_TIMEOUT,
                    "Instance [" + normalized + "] did not become ready within "
                            + (START_TIMEOUT_MS / 1000) + "s. It may still be starting; check "
                            + "'list' and " + paths.consoleLog() + ". " + tail(paths, 30));
        } finally {
            activeOperations.remove(normalized);
        }
    }

    // ------------------------------------------------------------------ 停止

    public ObservedInstance shutdown(String alias) {
        String normalized = requireAlias(alias);
        InstancePaths paths = requireInstance(normalized);

        ObservedInstance observed = probe.probe(normalized, paths.dir());
        if (observed.state() == InstanceState.STOPPED) {
            registry.refreshAlias(normalized);
            return observed;
        }

        activeOperations.put(normalized, InstanceState.STOPPING);
        try {
            if (observed.pgPort() <= 0) {
                throw new InstanceException(InstanceException.CONFLICT,
                        "Cannot stop [" + normalized + "]: the instance has no usable PG port in "
                                + paths.conf() + ".");
            }

            try {
                InstanceAdmin.sendStop(observed.pgPort(), 3_000);
            } catch (IOException ioe) {
                logger.debug("[PROXY][INSTANCE   ] STOP frame for [{}] failed: {}", normalized, ioe.getMessage());
            }

            boolean stopped = waitUntilStopped(normalized, paths, STOP_TIMEOUT_MS);
            if (!stopped) {
                Integer pid = observed.pid();
                if (pid != null && InstanceProbe.isOwnProcess(paths, ProcessHandle.of(pid).orElse(null))) {
                    logger.warn("[PROXY][INSTANCE   ] Instance [{}] ignored STOP; sending SIGTERM to pid {}.",
                            normalized, pid);
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
                    stopped = waitUntilStopped(normalized, paths, SIGTERM_TIMEOUT_MS);
                }
            }

            if (!stopped) {
                throw new InstanceException(InstanceException.INTERNAL,
                        "Instance [" + normalized + "] is still running after STOP and SIGTERM. "
                                + "Stop it manually if it keeps responding, then retry.");
            }

            logger.info("[PROXY][INSTANCE   ] Instance [{}] stopped.", normalized);
            registry.refreshAlias(normalized);
            return probe.probe(normalized, paths.dir());
        } finally {
            activeOperations.remove(normalized);
        }
    }

    private boolean waitUntilStopped(String alias, InstancePaths paths, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ObservedInstance current = probe.probe(alias, paths.dir());
            if (current.state() == InstanceState.STOPPED) {
                return true;
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
        return probe.probe(alias, paths.dir()).state() == InstanceState.STOPPED;
    }

    // ------------------------------------------------------------------ 重启 / 销毁

    public ObservedInstance restart(String alias) {
        shutdown(alias);
        return start(alias);
    }

    public void destroy(String alias) {
        String normalized = requireAlias(alias);
        InstancePaths paths = requireInstance(normalized);

        ObservedInstance observed = probe.probe(normalized, paths.dir());

        // 只在"确实可能在跑"的时候才去停止：
        //   - 可路由（RUNNING/DEGRADED）→ 走 shutdown；
        //   - pid 活着但命令行校验不通过 → 拒绝删除（可能杀错进程，也说明状态不明）。
        // conf 损坏（UNKNOWN、端口非法）且没有存活进程时，直接允许删除——
        // 否则用户永远清不掉一个"配置写坏了"的目录。
        boolean pidAlive = observed.pid() != null && InstanceProbe.isPidAlive(observed.pid());
        if (observed.routable()) {
            shutdown(normalized);
            observed = probe.probe(normalized, paths.dir());
        } else if (pidAlive) {
            throw new InstanceException(InstanceException.CONFLICT,
                    "Refusing to delete [" + normalized + "]: pid " + observed.pid()
                            + " is alive but cannot be verified as this instance. Stop it manually first.");
        }

        if (observed.routable() || (observed.pid() != null && InstanceProbe.isPidAlive(observed.pid()))) {
            throw new InstanceException(InstanceException.CONFLICT,
                    "Refusing to delete [" + normalized + "]: the instance is still running.");
        }

        Path trash = root.resolve(InstancePaths.TRASH_PREFIX + normalized + "-" + System.currentTimeMillis());
        try {
            move(paths.dir(), trash);
        } catch (IOException ioe) {
            throw new InstanceException(InstanceException.INTERNAL,
                    "Failed to remove [" + normalized + "]: " + ioe.getMessage(), ioe);
        }

        boolean deleted = false;
        IOException lastError = null;
        for (int attempt = 0; attempt < 3 && !deleted; attempt++) {
            try {
                InstanceScanner.deleteRecursively(trash);
                deleted = true;
            } catch (IOException ioe) {
                lastError = ioe;
                sleepQuietly(300);
            }
        }
        if (!deleted) {
            logger.warn("[PROXY][INSTANCE   ] Instance [{}] was detached but its directory could not be fully "
                    + "deleted ({}). It will be garbage-collected on the next proxy start.", normalized, lastError);
        }

        logger.info("[PROXY][INSTANCE   ] Instance [{}] destroyed (directory {}).", normalized, paths.dir());
        registry.refresh();
    }

    // ------------------------------------------------------------------ 日志

    public String logs(String alias, int lines) {
        String normalized = requireAlias(alias);
        InstancePaths paths = requireInstance(normalized);
        int count = lines <= 0 ? 200 : Math.min(lines, 5_000);
        return tail(paths, count);
    }

    private String tail(InstancePaths paths, int lines) {
        // 三个文件各有分工：dbserver 的报错在 server.log，JVM 级失败在 console.log，
        // 我们拉起的命令行在 launch.log。错误信息里都要看到。
        StringBuilder sb = new StringBuilder();
        sb.append(tailOf(paths.serverLog(), lines));
        sb.append(tailOf(paths.consoleLog(), Math.min(lines, 50)));
        sb.append(tailOf(paths.launchLog(), 4));
        if (sb.length() == 0) {
            return "(no log file at " + paths.logsDir() + ")";
        }
        return sb.toString();
    }

    private String tailOf(Path file, int lines) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (all.isEmpty()) {
                return "";
            }
            int from = Math.max(0, all.size() - lines);
            StringBuilder sb = new StringBuilder();
            sb.append("---- ").append(file).append(" (last ").append(all.size() - from).append(" lines) ----\n");
            for (int index = from; index < all.size(); index++) {
                // 超长行（典型是完整的 -cp）在错误信息里没有价值，截断避免刷屏
                String line = all.get(index);
                if (line.length() > 300) {
                    line = line.substring(0, 300) + "...";
                }
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException ioe) {
            return "(cannot read " + file + ": " + ioe.getMessage() + ")\n";
        }
    }

    // ------------------------------------------------------------------ 工具

    private String requireAlias(String alias) {
        String normalized = InstancePaths.normalizeAlias(alias);
        if (normalized.isEmpty()) {
            throw new InstanceException(InstanceException.BAD_REQUEST, "alias is required.");
        }
        if (!InstancePaths.isValidAlias(normalized)) {
            throw new InstanceException(InstanceException.BAD_REQUEST,
                    "Invalid alias [" + alias + "]. Expected [a-z][a-z0-9_-]{0,62}.");
        }
        return normalized;
    }

    private InstancePaths requireInstance(String alias) {
        InstancePaths paths = InstancePaths.of(root, alias);
        if (!Files.isDirectory(paths.dir())) {
            throw new InstanceException(InstanceException.NOT_FOUND,
                    "Database [" + alias + "] does not exist under " + root + ".");
        }
        if (!Files.isRegularFile(paths.conf())) {
            throw new InstanceException(InstanceException.NOT_FOUND,
                    "Directory " + paths.dir() + " exists but has no " + InstancePaths.CONF_FILE
                            + ", so it is not an instance.");
        }
        return paths;
    }

    private boolean existingAliasIgnoringCase(String alias) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)
                        && child.getFileName().toString().equalsIgnoreCase(alias)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(from, to);
        }
    }

    private static void cleanupQuietly(Path path) {
        try {
            InstanceScanner.deleteRecursively(path);
        } catch (IOException ignored) {
            // 残留的 .tmp-* 会在下次启动时被 GC
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
