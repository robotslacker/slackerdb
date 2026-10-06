package org.slackerdb.plugins.scheduler.quartz;

import org.slackerdb.plugins.scheduler.entity.SchedulerTask;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * run 启动编排器：把「模板化任务清单」按组与顺序真正跑起来。
 *
 * <p><b>编排语义</b>（组/顺序/必选项三个字段终于生效）：</p>
 * <ol>
 *   <li>任务按 {@code taskStartupOrder} 升序处理；同组连续任务构成一组（顺序在载入模板时
 *       已归一化为 {@code 组序号*1000 + 组内序号}）。</li>
 *   <li>组内 {@code RUN_ONCE} 任务<b>串行</b>执行：上一个结束才放下一个。</li>
 *   <li>{@code taskMandatory=true} 的任务失败 → 整个 run 置 {@code FAILED} 并停止推进；
 *       非必选任务失败只记日志、继续。</li>
 *   <li>组内 {@code CRONTAB}/{@code INTERVAL} 任务在该组的 RUN_ONCE 全部完成后被激活。</li>
 * </ol>
 *
 * <p>编排在独立守护线程中进行，不占用 HTTP 线程，也不占用 Quartz 工作线程。
 * run 状态迁移：{@code CREATED → STARTING → STARTED}（或 {@code FAILED}）。</p>
 */
public final class RunOrchestrator {

    private final DynamicQuartzScheduler quartz;
    private final SchedulerMeta meta;
    private final Logger logger;

    /** runId → 编排线程（用于判断是否在编排中 / 取消）。 */
    private final Map<String, Thread> running = new ConcurrentHashMap<>();

    public RunOrchestrator(DynamicQuartzScheduler quartz, SchedulerMeta meta, Logger logger) {
        this.quartz = quartz;
        this.meta = meta;
        this.logger = logger;
    }

    /** 异步启动一个 run。重复调用会被忽略（正在编排中）。 */
    public void start(String runId, List<SchedulerTask> tasks) {
        if (running.containsKey(runId)) {
            logger.warn("[SCHEDULER] run[{}] 正在编排中，忽略重复启动", runId);
            return;
        }
        List<SchedulerTask> enabled = new ArrayList<>();
        for (SchedulerTask task : tasks) {
            if (task.isTaskEnabled()) {
                enabled.add(task);
            }
        }
        enabled.sort((a, b) -> {
            int byOrder = Integer.compare(a.getTaskStartupOrder(), b.getTaskStartupOrder());
            return byOrder != 0 ? byOrder : a.getTaskName().compareTo(b.getTaskName());
        });

        Thread thread = new Thread(() -> {
            try {
                orchestrate(runId, enabled);
            } catch (Exception e) {
                logger.error("[SCHEDULER] run[{}] 编排失败", runId, e);
                safeUpdateStatus(runId, "FAILED");
            } finally {
                running.remove(runId);
            }
        }, "scheduler-run-" + runId);
        thread.setDaemon(true);
        running.put(runId, thread);
        thread.start();
    }

    public boolean isRunning(String runId) {
        return running.containsKey(runId);
    }

    /** 取消正在进行的编排（已激活的周期任务由调用方负责暂停）。 */
    public void cancel(String runId) {
        Thread thread = running.get(runId);
        if (thread != null) {
            thread.interrupt();
            logger.info("[SCHEDULER] run[{}] 编排被取消", runId);
        }
    }

    private void orchestrate(String runId, List<SchedulerTask> tasks) throws Exception {
        safeUpdateStatus(runId, "STARTING");
        if (tasks.isEmpty()) {
            logger.info("[SCHEDULER] run[{}] 没有启用的任务，直接置为 STARTED", runId);
            safeUpdateStatus(runId, "STARTED");
            return;
        }

        for (List<SchedulerTask> group : groupsInOrder(tasks)) {
            String groupName = group.isEmpty() ? "" : String.valueOf(group.get(0).getTaskGroup());
            logger.info("[SCHEDULER] run[{}] 处理组[{}]，共 {} 个任务", runId, groupName, group.size());

            for (SchedulerTask task : group) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.info("[SCHEDULER] run[{}] 编排被中断，停止推进", runId);
                    return;
                }
                if (!"RUN_ONCE".equalsIgnoreCase(policy(task))) {
                    continue;
                }
                logger.info("[SCHEDULER] run[{}] 串行执行一次性任务[{}]", runId, task.getTaskName());
                boolean completed = quartz.triggerAndWait(runId, task.getTaskName(), waitMillis(task));
                int exitCode = QuartzJobStatusManager.getPreviousExitCode(jobKey(runId, task.getTaskName()));
                if (!completed || exitCode != 0) {
                    if (task.isTaskMandatory()) {
                        logger.error("[SCHEDULER] run[{}] 必选任务[{}] 失败(exitCode={})，run 置为 FAILED",
                                runId, task.getTaskName(), exitCode);
                        safeUpdateStatus(runId, "FAILED");
                        return;
                    }
                    logger.warn("[SCHEDULER] run[{}] 非必选任务[{}] 失败(exitCode={})，继续推进",
                            runId, task.getTaskName(), exitCode);
                }
            }

            for (SchedulerTask task : group) {
                String taskPolicy = policy(task);
                if ("CRONTAB".equals(taskPolicy) || "INTERVAL".equals(taskPolicy)) {
                    logger.info("[SCHEDULER] run[{}] 激活周期任务[{}] (policy={})",
                            runId, task.getTaskName(), taskPolicy);
                    quartz.startJob(runId, task.getTaskName(), taskPolicy);
                }
            }
        }
        safeUpdateStatus(runId, "STARTED");
        logger.info("[SCHEDULER] run[{}] 启动完成", runId);
    }

    /** 按已归一化的顺序切成"同组连续任务"块。 */
    public static List<List<SchedulerTask>> groupsInOrder(List<SchedulerTask> sortedTasks) {
        List<List<SchedulerTask>> groups = new ArrayList<>();
        List<SchedulerTask> current = null;
        String currentGroup = null;
        for (SchedulerTask task : sortedTasks) {
            String group = task.getTaskGroup() == null ? "" : task.getTaskGroup();
            if (current == null || !group.equals(currentGroup)) {
                current = new ArrayList<>();
                groups.add(current);
                currentGroup = group;
            }
            current.add(task);
        }
        return groups;
    }

    /** 等待上限：有超时则超时 + 60s 余量，否则 24 小时（防止无限等待）。 */
    static long waitMillis(SchedulerTask task) {
        int timeout = task.getTaskTimeout();
        return timeout > 0 ? (timeout + 60L) * 1000L : 24L * 3600L * 1000L;
    }

    private static String policy(SchedulerTask task) {
        String policy = task.getTaskRunPolicy();
        return policy == null ? "" : policy.trim().toUpperCase(Locale.ROOT);
    }

    private static String jobKey(String runId, String taskName) {
        return runId + ":" + taskName;
    }

    private void safeUpdateStatus(String runId, String status) {
        try {
            meta.updateRunStatus(runId, status);
        } catch (Exception e) {
            logger.error("[SCHEDULER] 更新 run[{}] 状态为 [{}] 失败: {}", runId, status, e.getMessage());
        }
    }
}
