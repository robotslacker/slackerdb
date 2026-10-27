package org.slackerdb.dbproxy.instance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实例快照缓存。
 *
 * <p>后台每 {@value #REFRESH_INTERVAL_MS} ms 扫描一次（供路由与 {@code /status} 快速读取）；
 * 读取时如果快照过旧、或别名不在快照里，会<b>同步补探</b>该别名——这样"用户手工启动后立刻连接"
 * 不用等下一轮扫描。</p>
 */
public final class InstanceRegistry implements AutoCloseable {

    public static final long REFRESH_INTERVAL_MS = 5_000L;
    public static final long CACHE_TTL_MS = 2_000L;

    private final Path root;
    private final InstanceScanner scanner;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Map<String, ObservedInstance>> snapshot = new AtomicReference<>(Map.of());

    private volatile long lastScanAt = 0L;
    private volatile boolean started = false;
    private final Object scanLock = new Object();

    public InstanceRegistry(Path root, InstanceScanner scanner) {
        this.root = root.toAbsolutePath().normalize();
        this.scanner = scanner;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PROXY-INSTANCE-SCAN");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Path root() {
        return root;
    }

    /** 启动后台扫描（首次扫描会顺手清理 {@code .tmp-*}/{@code .trash-*}）。 */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        refreshWithGc();
        scheduler.scheduleWithFixedDelay(this::refreshQuietly, REFRESH_INTERVAL_MS, REFRESH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    public void refresh() {
        synchronized (scanLock) {
            snapshot.set(scanner.scan(root, false));
            lastScanAt = System.currentTimeMillis();
        }
    }

    private void refreshWithGc() {
        synchronized (scanLock) {
            snapshot.set(scanner.scan(root, true));
            lastScanAt = System.currentTimeMillis();
        }
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException ignored) {
            // 扫描失败不能让调度线程退出；保留上一份快照
        }
    }

    public Map<String, ObservedInstance> snapshot() {
        if (lastScanAt == 0L) {
            refresh();
        }
        return snapshot.get();
    }

    public List<ObservedInstance> list() {
        return List.copyOf(snapshot().values());
    }

    /**
     * 按别名取实例。命中但快照过旧时会同步补探。
     *
     * @return 不存在时返回 {@code null}
     */
    public ObservedInstance get(String alias) {
        if (alias == null || alias.isEmpty()) {
            return null;
        }
        // 客户端连接串里的 database 是外部输入：只接受合法别名字符，避免 ../ 之类的路径逃逸
        String candidate = alias.trim();
        if (!candidate.matches("[A-Za-z0-9_-]{1,63}")) {
            return null;
        }
        Map<String, ObservedInstance> current = snapshot();
        ObservedInstance found = current.get(candidate);
        if (found == null) {
            found = current.get(candidate.toLowerCase(Locale.ROOT));
        }
        long age = System.currentTimeMillis() - lastScanAt;
        if (age > CACHE_TTL_MS || found == null) {
            ObservedInstance refreshed = refreshAlias(candidate);
            if (refreshed != null) {
                return refreshed;
            }
        }
        return found;
    }

    /**
     * 别名是否被本地实例目录占用（只看快照，不触发探测）。
     *
     * <p>用于组播注册的热路径：每 5 秒每个后端都会来一条 HELLO，不能在这里做 1 秒的 /health 探测。</p>
     */
    public boolean contains(String alias) {
        if (alias == null || alias.isEmpty()) {
            return false;
        }
        Map<String, ObservedInstance> current = snapshot();
        return current.containsKey(alias) || current.containsKey(alias.toLowerCase(Locale.ROOT));
    }

    /** 只补探一个别名，并合并进快照。 */
    public ObservedInstance refreshAlias(String alias) {
        if (alias == null || alias.isEmpty() || !alias.matches("[A-Za-z0-9_-]{1,63}")) {
            return null;
        }
        Path dir = root.resolve(alias);
        ObservedInstance probed = null;
        if (Files.isDirectory(dir) && Files.isRegularFile(dir.resolve(InstancePaths.CONF_FILE))) {
            probed = scanner.probe(alias, dir);
        }

        Map<String, ObservedInstance> next = new LinkedHashMap<>(snapshot.get());
        if (probed == null) {
            next.remove(alias);
        } else {
            next.put(alias, probed);
        }
        snapshot.set(Map.copyOf(next));
        return probed;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
