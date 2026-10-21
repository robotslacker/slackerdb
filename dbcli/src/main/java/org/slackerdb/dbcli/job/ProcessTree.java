package org.slackerdb.dbcli.job;

import java.util.concurrent.TimeUnit;

/**
 * 进程树销毁：**杀一个子进程时必须把它的整棵后代一起杀**。
 *
 * <h2>为什么不能只 {@code Process.destroy()}</h2>
 * <p>{@code _HOST} 在 Windows 上是 {@code cmd.exe /c <命令>}，{@code mode=process} 的作业进程也是
 * "java.exe 再拉子进程"这一类形态。{@code Process.destroy()} 只作用于**直接子进程**（shell/java），
 * 命令真正的进程会变成**孤儿继续运行**：白占 CPU，而且它"以脚本工作目录为当前目录"，
 * 于是那个目录被孤儿当句柄**攥住**——删不掉、也锁住后续写入（实测出来的现象，见 §5 第 18 条）。</p>
 *
 * <p>因此这里的顺序是：<b>先杀后代，再杀直接子进程，最后再兜一次</b>（杀 shell 与杀真正进程之间有竞态，
 * 刚开始拉起的后代可能在第一次扫描之后才出现）。</p>
 */
public final class ProcessTree {

    /** {@code _HOST} 一类本地命令的默认宽限：与 {@code ScriptRunner} 的 {@code STOP_POLL_MILLIS} 一致。 */
    public static final long DEFAULT_GRACE_MILLIS = 50L;

    /** {@code mode=process} 作业"优雅停止"的宽限：给子进程一点时间自己收尾（POSIX 上是 SIGTERM）。 */
    public static final long SHUTDOWN_GRACE_MILLIS = 1_000L;

    private ProcessTree() {
    }

    /** 先优雅、再强杀（宽限 {@value #DEFAULT_GRACE_MILLIS} 毫秒），含整棵进程树。 */
    public static void destroy(Process process) {
        destroy(process, DEFAULT_GRACE_MILLIS);
    }

    /**
     * 先优雅、再强杀（含整棵进程树）。
     *
     * @param graceMillis {@code 0} 表示不宽限：{@code destroy()} 之后立刻 {@code destroyForcibly()}
     */
    public static void destroy(Process process, long graceMillis) {
        if (process == null) {
            return;
        }
        killDescendants(process);
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(Math.max(graceMillis, 0L), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(Math.max(graceMillis, 0L), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        // 兜底：直接子进程走掉之后可能还有刚被它拉起来的后代
        killDescendants(process);
    }

    /** 强杀（{@code _JOB ABORT} 与进程模式硬超时用）：立刻 {@code destroyForcibly()}，含整棵进程树。 */
    public static void kill(Process process) {
        if (process == null) {
            return;
        }
        killDescendants(process);
        if (process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(DEFAULT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        killDescendants(process);
    }

    /** 强杀该进程的所有后代（拿不到进程树时静默跳过）。 */
    public static void killDescendants(Process process) {
        if (process == null) {
            return;
        }
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // 进程已经退出或平台不支持查询进程树：不影响"杀直接子进程"这条主路径
        }
    }
}
