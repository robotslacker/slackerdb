package org.slackerdb.dbsyncer.engine;

import org.slackerdb.dbsyncer.config.ConfigFingerprint;
import org.slackerdb.dbsyncer.config.SyncConfig;
import org.slackerdb.dbsyncer.state.SyncStateStore;
import org.slackerdb.dbsyncer.target.DuckDbTarget;
import org.slackerdb.dbsyncer.target.DuckDbWriter;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作业注册表与启停编排。
 *
 * <p>所有对 {@code runtimes} 的读写都在本对象的监视器内完成：{@code reload} 会整体替换注册表，
 * 而 {@code /jobs}、{@code /jobs/{name}}、{@code start} 可能并发到达。会阻塞或做网络 I/O 的动作
 * （清理复制对象、停止作业）一律放到锁外，避免把状态查询拖住。</p>
 */
public final class JobManager {

    private SyncConfig config;
    private final DuckDbTarget target;
    private final SyncStateStore stateStore;
    private final DuckDbWriter writer;
    private final Logger logger;
    private final int maxJobs;
    private final Map<String, JobRuntime> runtimes = new LinkedHashMap<>();

    public JobManager(SyncConfig config, DuckDbTarget target, SyncStateStore stateStore,
                      DuckDbWriter writer, Logger logger, int maxJobs) {
        this.config = config;
        this.target = target;
        this.stateStore = stateStore;
        this.writer = writer;
        this.logger = logger;
        this.maxJobs = maxJobs;
        rebuild(config);
    }

    private synchronized void rebuild(SyncConfig config) {
        runtimes.clear();
        for (SyncConfig.JobConfig job : config.jobs) {
            runtimes.put(job.name, new JobRuntime(config, job, ConfigFingerprint.of(config, job),
                    target, stateStore, writer, logger));
        }
    }

    public synchronized List<JobRuntime> jobs() {
        return new ArrayList<>(runtimes.values());
    }

    public synchronized JobRuntime job(String name) {
        return runtimes.get(name);
    }

    public synchronized JobRuntime require(String name) {
        JobRuntime runtime = runtimes.get(name);
        if (runtime == null) {
            throw new IllegalArgumentException("job [" + name + "] not found");
        }
        return runtime;
    }

    public synchronized JobState start(String name, boolean resync) {
        JobRuntime runtime = runtimes.get(name);
        if (runtime == null) {
            throw new IllegalArgumentException("job [" + name + "] not found");
        }
        if (runtime.orphaned()) {
            throw new IllegalStateException("job [" + name + "] is orphaned; reload its definition first");
        }
        if (runtime.state() == JobState.STOPPING) {
            throw new IllegalStateException("job [" + name + "] is STOPPING");
        }
        if (!runtime.state().isRunning() && runningCount() >= maxJobs) {
            throw new IllegalStateException("too many running jobs (maxJobs=" + maxJobs + ")");
        }
        return runtime.start(resync);
    }

    /** 停止可能阻塞到 timeout，不能在持有注册表锁时调用。 */
    public JobState stop(String name, boolean wait, long timeoutMs) {
        return require(name).stop(wait, timeoutMs);
    }

    public void startAutoStart() {
        List<JobRuntime> snapshot = jobs();
        for (JobRuntime runtime : snapshot) {
            if (!runtime.job().autoStart || runtime.orphaned()) {
                continue;
            }
            try {
                start(runtime.name(), false);
            } catch (Exception e) {
                logger.error("[DBSYNCER][{}] autoStart failed: {}", runtime.name(), e.getMessage(), e);
            }
        }
    }

    public void stopAll(long timeoutMs) {
        List<JobRuntime> snapshot = jobs();
        // 所有作业共用一个截止时间：单个作业最多等 timeoutMs，整体停机不会变成 N×timeoutMs。
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        for (JobRuntime runtime : snapshot) {
            long remaining = Math.max(1000, deadline - System.currentTimeMillis());
            try {
                runtime.stop(true, remaining);
            } catch (Exception e) {
                logger.warn("[DBSYNCER][{}] stop failed: {}", runtime.name(), e.getMessage());
            }
        }
    }

    private long runningCount() {
        long count = 0;
        for (JobRuntime runtime : runtimes.values()) {
            if (runtime.state().isRunning()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 热加载：运行中的作业继续旧定义并记录提示；停止的作业直接换新定义；
     * 被删除但仍在运行的作业标记 orphaned；被删掉又重新加回来的作业解除 orphaned。
     */
    public ReloadResult reload(SyncConfig newConfig) {
        Map<String, JobRuntime> next = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> staleRunning = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<JobRuntime> cleanup = new ArrayList<>();
        ReloadResult result;
        synchronized (this) {
            for (SyncConfig.JobConfig job : newConfig.jobs) {
                String hash = ConfigFingerprint.of(newConfig, job);
                JobRuntime existing = runtimes.get(job.name);
                if (existing == null) {
                    next.put(job.name, new JobRuntime(newConfig, job, hash, target, stateStore, writer, logger));
                    added.add(job.name);
                    continue;
                }
                boolean running = existing.state().isRunning();
                if (existing.orphaned()) {
                    // 作业之前被删掉、现在又回到配置里：必须解除 orphan，否则它永远不能 start。
                    if (running) {
                        existing.clearOrphaned();
                        next.put(job.name, existing);
                        if (!existing.configHash().equals(hash)) {
                            staleRunning.add(job.name);
                            warnings.add("job [" + job.name + "] was re-added; it is running with"
                                    + " the old definition, definition unchanged until restart");
                        }
                        continue;
                    }
                    existing.replaceDefinition(newConfig, job, hash);
                    next.put(job.name, existing);
                    updated.add(job.name);
                    continue;
                }
                if (existing.configHash().equals(hash)) {
                    next.put(job.name, existing);
                    continue;
                }
                if (running) {
                    next.put(job.name, existing);
                    staleRunning.add(job.name);
                    warnings.add("job [" + job.name + "] is running; reload skipped, definition unchanged");
                } else {
                    existing.replaceDefinition(newConfig, job, hash);
                    next.put(job.name, existing);
                    updated.add(job.name);
                }
            }

            for (Map.Entry<String, JobRuntime> entry : runtimes.entrySet()) {
                if (next.containsKey(entry.getKey())) {
                    continue;
                }
                JobRuntime old = entry.getValue();
                if (old.state().isRunning()) {
                    old.markOrphaned();
                    next.put(entry.getKey(), old);
                    warnings.add("job [" + entry.getKey() + "] was removed; it keeps running until stopped");
                } else {
                    cleanup.add(old);
                    removed.add(entry.getKey());
                }
            }

            this.config = newConfig;
            runtimes.clear();
            runtimes.putAll(next);
            result = new ReloadResult(added, updated, removed, staleRunning, warnings);
        }
        // 清理复制对象要连源库，放到锁外，避免拖住 /jobs 等状态请求。
        for (JobRuntime old : cleanup) {
            old.cleanupReplication();
        }
        return result;
    }

    public record ReloadResult(List<String> added, List<String> updated, List<String> removed,
                               List<String> staleRunning, List<String> warnings) {
    }
}
