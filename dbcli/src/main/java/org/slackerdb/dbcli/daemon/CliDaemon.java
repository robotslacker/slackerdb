package org.slackerdb.dbcli.daemon;

import org.slackerdb.common.utils.AppDaemon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * dbcli 的守护形态支撑：把"拉起后台副本"这件事收在一处，命令行层
 * （{@link org.slackerdb.dbcli.Cli}）只负责选词与输出。
 *
 * <h2>形态</h2>
 * <pre>
 * dbcli --execute soak.sql [其它选项] --daemon true --pidfile &lt;p&gt;   # 后台跑脚本，立即返回并打印 pid
 * dbcli --execute soak.sql [其它选项]                                # 不带 --daemon 就是前台
 * </pre>
 *
 * <p><b>没有子命令、也没有 stop/status</b>：{@code --daemon} 本身就说清了"要后台"，
 * 再要求一个 {@code start} 子命令只是重复。{@code --pidfile} 保留 —— 后台副本仍把自己的 pid
 * 写进去，怎么处置（{@code taskkill} / {@code kill}）由使用者自己决定。</p>
 *
 * <h2>为什么复用 {@link AppDaemon}</h2>
 * <p>{@code --daemon} 属于"出错也不报错、只是行为不对"的选项（取值拼错会静默前台、子进程参数复制不全
 * 会少跑东西、忘了 {@code --daemon false} 会无限 fork），这些判定都收敛在 {@code AppDaemon} 里。
 * 这里只做一件它不做的事：<b>报告真正的守护进程 pid</b> —— 父进程拿不到后台 java 进程的 pid
 * （Windows 经 {@code cmd start} 拉起，拿到的是中间 shell 的 pid），因此这里等**副本自己写的 pidfile**
 * 出现，再把它作为 {@code Daemon started (pid n).} 报出去。</p>
 */
public final class CliDaemon {

    /** 参数/用法错误的退出码（与 picocli 自带的参数错误一致，见 §3.7）。 */
    public static final int EXIT_USAGE = 2;

    /** 运行时失败（pidfile 写不出来…）的退出码。 */
    public static final int EXIT_ERROR = 1;

    /**
     * 等后台副本写 pidfile 的上限（毫秒）。
     *
     * <p>要覆盖的是"副本那个<b>全新 JVM</b> 起来、并把 pid 写进文件"这段时间。空闲机器上实测
     * 不到 1 秒，但这条路径会在负载很重的机器上跑（整盘构建、CI 上并发任务、机器上还有别的
     * 进程在抢内存）：那时冷启动一个 JVM 慢一个数量级是正常的。</p>
     *
     * <p>10 秒的上限在 Linux 全量构建里<b>偶发</b>失败过一次（同一份代码前后各跑一遍都是绿的），
     * 现场只有一句 "PID file ... was not written within 10 seconds"——因为副本的启动输出在
     * POSIX 上是 {@code /dev/null}，父进程看不到原因。给到 30 秒：既覆盖慢启动，又与测试侧
     * 等 pidfile 的 30 秒同一把尺子；正常情况仍然是几十毫秒就返回，不会让"起不来"这件事等更久。</p>
     */
    public static final long PID_WAIT_MILLIS = 30_000L;

    /** {@code --logfile} 的选项名（拉起后台副本时只用于提示信息）。 */
    private static final String LOG_OPTION_NAME = "logfile";

    // ------------------------------------------------------------------ 文案

    /** {@code --daemon} 只允许配合脚本执行（控制台模式不行）。 */
    public static final String REQUIRES_SCRIPT =
            "--daemon requires a script to run (use --execute <file> or pipe the script in).";

    public static String started(long pid) {
        return "Daemon started (pid " + pid + ").";
    }

    /**
     * 副本没在时限内写出 pidfile。
     *
     * <p>这条消息只有一半是坏消息：父进程确实把副本拉起来了，只是拿不到它的 pid。副本此刻
     * 可能只是**还在启动**（负载重的机器上冷启动 JVM 会慢很多），也可能启动即失败——而副本的
     * 启动输出在 POSIX 上进了 {@code /dev/null}、在 Windows 上进了一个新控制台窗口，父进程
     * 看不到。所以把"怎么看到原因"一并写进消息里，否则使用者只知道"没写出 pid"。</p>
     *
     * @param logTarget {@code --logfile} 的取值；null 表示没给（那就没地方看，只能前台复现）
     */
    public static String pidFileNotWritten(String pidFileOption, String logTarget) {
        String hint = logTarget == null || logTarget.isBlank()
                ? "re-run with --daemon false to see why the copy did not start"
                : "check its log [" + logTarget + "]";
        return "Daemon started, but the PID file [" + pidFileOption + "] was not written within "
                + (PID_WAIT_MILLIS / 1000) + " seconds. The copy may still be starting; " + hint + ".";
    }

    public static String cannotWritePidFile(String pidFileOption, String reason) {
        return "Cannot write the PID file [" + pidFileOption + "]: " + reason;
    }

    public static String cannotStart(String reason) {
        return "Cannot start the daemon: " + reason;
    }

    private CliDaemon() {
    }

    // -------------------------------------------------------------- 参数意图

    /**
     * 把命令行上的 {@code --daemon} 取值收敛成 {@link AppDaemon.DaemonIntent}。
     *
     * <p>两步都走 {@code AppDaemon}：{@code parseDaemonFlag} 认 {@code true}/{@code false}/
     * 裸写（写别的**直接报错**，不静默前台），{@code resolve} 表达"命令行显式给出时以命令行为准"
     * —— dbcli 没有配置文件，因此配置文件那一侧的取值恒为 false；走这条路径只是为了让
     * 取值规则只有一份实现。</p>
     *
     * @param daemonOption {@code --daemon} 的取值；null 表示命令行上没给
     * @throws IllegalArgumentException 取值既不是 true 也不是 false
     */
    public static AppDaemon.DaemonIntent intent(String daemonOption) {
        if (daemonOption == null) {
            return AppDaemon.resolve(new AppDaemon.DaemonIntent(false, false), false);
        }
        return AppDaemon.resolve(
                new AppDaemon.DaemonIntent(AppDaemon.parseDaemonFlag(daemonOption), true), false);
    }

    // ------------------------------------------------------- 拉起后台副本

    /**
     * 拉起后台副本的结果。
     *
     * @param background true = 已经拉起（父进程应当立即退出）；false = 开发模式（不是从 jar 启动），
     *                   无法脱离当前进程，调用方应当继续**前台**运行
     * @param pid        后台副本的 pid（来自副本自己写的 pidfile）；{@code background=false} 时为 null
     */
    public record StartResult(boolean background, Long pid) {
    }

    /**
     * 拉起后台副本；给了 pidfile 就等它把 pid 写出来。
     *
     * @param anchor       用于推断当前 jar 的类（{@code Cli.class}）
     * @param options      有序选项（{@code AppDaemon.parse} 的结果，已剥掉 {@code --daemon}）
     * @param logTarget    {@code --logfile} 的取值，仅用于提示信息；可为 null
     * @param pidFileOption {@code --pidfile} 的原始取值（只用于文案）；可为 null
     * @param pidFile      解析后的 pidfile 路径；**null 表示没给 {@code --pidfile}**——
     *                     那就无可等，直接返回 "已拉起、pid 未知"
     * @throws Exception 拉不起来（找不到 shell / 子进程启动失败）
     */
    public static StartResult startInBackground(Class<?> anchor, List<AppDaemon.Option> options,
                                                String logTarget, String pidFileOption, Path pidFile)
            throws Exception {
        PidFile target = pidFile == null ? null : new PidFile(pidFile);
        // 上一次运行留下的 pidfile 必须**先删掉**：这里等的是"这一次副本刚写的 pid"，
        // 而旧文件会被 waitForPidFile 当成新 pid 读走（父进程于是报出一个早就死掉的 pid）。
        // 删不掉就**不能**继续等：旧内容还在，第一次轮询就会把它当成本次的 pid 报出去。
        if (target != null && !target.delete()) {
            throw new IOException("Cannot remove the stale PID file [" + pidFileOption
                    + "]; remove it or choose another --pidfile.");
        }
        // 没有子命令：命令行由 AppDaemon 按**原始顺序**拼装，并显式带 --daemon false（否则无限 fork）
        if (!AppDaemon.startInBackground(anchor, null, options, logTarget, LOG_OPTION_NAME, null)) {
            return new StartResult(false, null);
        }
        if (target == null) {
            return new StartResult(true, null);
        }
        return new StartResult(true, waitForPidFile(target));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 轮询等副本写 pidfile（写不出来就是超时，如实报告而不是假装成功）。
     *
     * <p>调用之前旧文件已经删掉了，因此这里读到的任何内容都只可能是本次副本写的。</p>
     */
    private static Long waitForPidFile(PidFile pidFile) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PID_WAIT_MILLIS);
        while (true) {
            Long pid = pidFile.read();
            if (pid != null) {
                return pid;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            if (!sleepQuietly(50L)) {
                return null;
            }
        }
    }

    /** 睡一小会儿；被中断时恢复标志并返回 false（调用方据此提前收尾）。 */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
