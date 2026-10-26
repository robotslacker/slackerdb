package org.slackerdb.dbcli.job;

import org.slackerdb.dbcli.common.CommandOutcome;
import org.slackerdb.dbcli.common.UserNumber;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code _JOB} 的动作分派器：把 IR 变成"要打印的结果"（与 {@code _SSH}/{@code _COMPARE} 同构）。
 *
 * <h2>动作与文案</h2>
 * <pre>
 *   JOBMANAGER ON/OFF      → "Job manager started." / "Job manager stopped."
 *   CREATE &lt;名&gt; k=v…      → "Job [名] created."
 *   SET &lt;名&gt; k=v…         → "Job [名] parameters updated."
 *   START &lt;名&gt;            → "Job [名] started."
 *   WAIT &lt;名&gt; [timeout=秒] → 作业类："Job [名] finished with exit code N." / "… wait timed out."
 *                             （终态不是 DONE/FAILED 时按状态词："Job [名] aborted."/"… shutdown."/
 *                              "… timeout."）
 *                             定时点："Timer point [名] released." / "… wait timed out."
 *   SHOW &lt;名&gt;             → 结果集（表头 name/state/thread/elapsed/exitCode；thread 列在两种执行层下的
 *                              取值见类注释末尾）
 *   ABORT &lt;名&gt;            → "Job [名] aborted."
 *   SHUTDOWN &lt;名&gt;         → "Job [名] shutdown."
 *   TIMER &lt;点名&gt;          → "Timer point [点名] released."
 * </pre>
 *
 * <p><b>WAIT 的名字解析</b>：先按作业名（区分大小写），再按定时点名（**不区分大小写**）；
 * 可用 {@code JOB:} / {@code TIMER:} 前缀强制指定（前缀也不区分大小写）；两者都不是时报错（不静默挂住）。
 * {@code timeout} 的单位是**秒**，参数名不区分大小写（{@code timeout=} / {@code TIMEOUT=} 都认）；
 * <b>不写 {@code timeout=} 就是无限等待</b>（等到作业结束或定时点被放行）；
 * 文案里回显的是**调用方写的原样名字**。</p>
 *
 * <p><b>WAIT 的结论是失败还是状态</b>：等到的作业 {@code FAILED}/{@code TIMEOUT}、或者这次等待本身超时，
 * 一律产出 {@link CommandOutcome.Failure}——状态行照旧在 stdout 上（输出契约不变），
 * 但引擎同时计一次错误：{@code _WHENEVER ERROR EXIT n} 会因此生效，进程退出码则仍按 README §9.3
 * （脚本内部错误默认不改变退出码；要门禁就写 {@code _WHENEVER ERROR EXIT 1}）。
 * **没有"忽略"开关**（曾经的 {@code onfail=ignore} 已删除）：失败就是失败。</p>
 *
 * <p>无论哪种结论，WAIT 都会**认领**该作业：它的终态已经被检查过，收尾时不该再算一次
 * （{@code JobManager.acknowledgedJobs}）。没有被任何 WAIT/ABORT/SHUTDOWN 检查过的失败作业
 * 由引擎收尾时补报一条错误行并计入错误数（见 {@code JobManager.hasUnacknowledgedFailures()}）。</p>
 *
 * <p><b>参数名大小写不敏感</b>（{@code create}/{@code set} 的参数映射见 {@code DbcliJobSpecFactory}）：
 * 代价是"参数名不再能当同名变量用"（{@code MODE=process} 被编排参数吃掉），
 * 而**其余** {@code k=v} 变成的脚本变量仍然区分大小写。</p>
 *
 * <p><b>作业的执行层</b>由 {@code _JOB CREATE … mode=<thread|process>} 决定（缺省 {@code thread}）：
 * 线程模式与任意深度的作业共享同一个 {@link JobManager}，{@code _JOB TIMER} 因此能唤醒作业脚本里的
 * {@code _JOB WAIT TIMER:…}（零 IPC）；进程模式下同一件事由 SQLite 同步库完成（跨进程）。
 * 两种模式下 {@code SHOW} 的 {@code thread} 列都表示"作业的运行身份"：线程模式是线程名
 * （{@code dbcli-job-<作业名>}），进程模式是 {@code pid <n>}，未启动时为空。</p>
 */
public final class JobCommandDispatcher {

    private final JobManager manager;

    public JobCommandDispatcher(JobManager manager) {
        this.manager = manager;
    }

    public JobManager manager() {
        return manager;
    }

    /** 执行一条 {@code _JOB} 命令。 */
    public List<CommandOutcome> execute(Map<String, Object> ir) {
        List<CommandOutcome> outcomes = new ArrayList<>();
        String action = String.valueOf(ir.get("action"));
        try {
            switch (action) {
                case "startJobmanager" -> {
                    manager.start();
                    outcomes.add(status("Job manager started."));
                }
                case "stopJobmanager" -> {
                    manager.stop();
                    outcomes.add(status("Job manager stopped."));
                }
                case "create" -> {
                    String name = requireText(ir, "jobName");
                    manager.create(name, parameters(ir));
                    outcomes.add(status("Job [" + name + "] created."));
                }
                case "set" -> {
                    String name = requireText(ir, "jobName");
                    manager.setParameters(name, parameters(ir));
                    outcomes.add(status("Job [" + name + "] parameters updated."));
                }
                case "start" -> {
                    String name = requireText(ir, "jobName");
                    manager.start(name);
                    outcomes.add(status("Job [" + name + "] started."));
                }
                case "wait" -> wait(ir, outcomes);
                case "show" -> {
                    String name = requireText(ir, "jobName");
                    Job job = manager.show(name);
                    List<List<Object>> rows = new ArrayList<>();
                    rows.add(new ArrayList<>(job.describe()));
                    outcomes.add(new CommandOutcome.Table(
                            List.of("name", "state", "thread", "elapsed", "exitCode"), rows));
                }
                case "abort" -> {
                    String name = requireText(ir, "jobName");
                    Job job = manager.abort(name);
                    outcomes.add(status(job.state() == JobState.ABORTED
                            ? "Job [" + name + "] aborted."
                            : "Job [" + name + "] abort requested; it is still running."));
                }
                case "shutdown" -> {
                    String name = requireText(ir, "jobName");
                    Job job = manager.shutdown(name);
                    outcomes.add(status(job.state() == JobState.SHUTDOWN
                            ? "Job [" + name + "] shutdown."
                            : "Job [" + name + "] shutdown requested; it is still running."));
                }
                case "timer" -> {
                    String point = requireText(ir, "timerPoint");
                    manager.releaseTimer(point);
                    outcomes.add(status("Timer point [" + point + "] released."));
                }
                default -> outcomes.add(new CommandOutcome.Error("Unknown job action [" + action + "]."));
            }
        } catch (JobException jobError) {
            outcomes.add(new CommandOutcome.Error(jobError.getMessage()));
        }
        return outcomes;
    }

    // ------------------------------------------------------------------ 内部

    private void wait(Map<String, Object> ir, List<CommandOutcome> outcomes) {
        String name = requireText(ir, "jobName");
        Map<String, String> parameters = parameters(ir);
        requireOnlyTimeout(parameters);
        Long timeoutMillis = timeoutMillis(parameters);
        JobManager.WaitResult result = manager.waitFor(name, timeoutMillis);
        String resolved = result.resolvedName();
        if (result.job() == null) {
            // 定时点：放行永远是正常结果；**等待超时**才是失败（屏障没来 = 编排错了，不能静默通过）
            outcomes.add(result.timerReleased()
                    ? status("Timer point [" + resolved + "] released.")
                    : new CommandOutcome.Failure("Timer point [" + resolved + "] wait timed out."));
            return;
        }
        Job job = result.job();
        if (result.timedOut()) {
            // 超时是"这次等待没等到"，不是作业状态：作业可能仍在运行。
            // 但对编排者来说它就是失败（该来的屏障没来），因此默认产出 Failure。
            // 这里**不能认领**：作业还在跑，它随后失败时收尾必须照样报（并发作业不允许静默失败）。
            outcomes.add(new CommandOutcome.Failure("Job [" + resolved + "] wait timed out."));
            return;
        }
        // 等到了：这个作业的终态已经在这里检查过，脚本收尾时不该再算一次
        // （见 JobManager.acknowledgedJobs）。仍然只在**终态**上认领：RUNNING（并发窗口里可能出现）
        // 之后还会失败，认领早了就会把那次失败咽掉。
        if (job.isTerminal()) {
            manager.acknowledge(resolved);
        }
        String text;
        boolean failure;
        switch (job.state()) {
            case DONE -> {
                text = finishedText(resolved, job);
                failure = false;
            }
            case FAILED -> {
                text = finishedText(resolved, job);
                failure = true;
            }
            default -> {
                // TIMEOUT 是失败；ABORTED/SHUTDOWN/CREATED/RUNNING 是显式操作或未开始，不算失败
                text = "Job [" + resolved + "] " + stateWord(job) + ".";
                failure = job.state() == JobState.TIMEOUT;
            }
        }
        outcomes.add(failure ? new CommandOutcome.Failure(text) : status(text));
    }

    /**
     * WAIT 只接受 {@code timeout=<秒>}。别的参数（尤其是已删除的 {@code onfail=}）当场报错，
     * 不静默忽略——静默会让"我以为它被忽略了"变成又一个假绿。
     */
    private static void requireOnlyTimeout(Map<String, String> parameters) {
        for (String key : parameters.keySet()) {
            if (!JobSpec.OPTION_TIMEOUT.equalsIgnoreCase(key.strip())) {
                throw new JobException("Unknown job wait option [" + key.strip()
                        + "]; supported: timeout=<seconds>.");
            }
        }
    }

    /** {@code Job [x] finished with exit code N.}；退出码没落地（启动就失败）时退回状态词。 */
    private static String finishedText(String resolved, Job job) {
        if (job.exitCode() == Integer.MIN_VALUE) {
            return "Job [" + resolved + "] " + stateWord(job) + ".";
        }
        return "Job [" + resolved + "] finished with exit code " + job.exitCode() + ".";
    }

    private static String stateWord(Job job) {
        return job.state().name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * IR 的 {@code param} 里取 {@code timeout}（**秒**，允许小数），转成毫秒；
     * 不写 {@code timeout=} 返回 null（= 无限等待，见 {@link JobManager#waitFor}）。
     *
     * <p>参数名大小写不敏感（与 {@code _JOB CREATE/SET} 的参数名同一条纪律）：
     * {@code timeout=30} / {@code TIMEOUT=30} / {@code Timeout=30} 都是"这次等待的时限"。</p>
     */
    private static Long timeoutMillis(Map<String, String> parameters) {
        String value = null;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (JobSpec.OPTION_TIMEOUT.equalsIgnoreCase(parameter.getKey().strip())) {
                value = parameter.getValue();
                break;
            }
        }
        if (value == null) {
            return null;
        }
        double seconds;
        try {
            // NaN / Infinity / 负数 / 0 一律拒绝（它们在管理器里各有"意外含义"：
            // 负数 = 不限，NaN = (long) 0 = 只看一眼，Infinity = 立刻超时）。
            seconds = UserNumber.positiveSeconds("job wait timeout", value);
        } catch (UserNumber.InvalidNumberException invalidTimeout) {
            throw new JobException(invalidTimeout.getMessage());
        }
        return (long) (seconds * 1000.0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parameters(Map<String, Object> ir) {
        Object value = ir.get("param");
        Map<String, String> parameters = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                parameters.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return parameters;
    }

    private static String requireText(Map<String, Object> ir, String key) {
        Object value = ir.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new JobException("Job command needs a '" + key + "' value.");
        }
        return String.valueOf(value);
    }

    private static CommandOutcome status(String text) {
        return new CommandOutcome.Status(text);
    }
}
