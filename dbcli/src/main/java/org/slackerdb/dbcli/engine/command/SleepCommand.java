package org.slackerdb.dbcli.engine.command;

import org.slackerdb.dbcli.common.UserNumber;

import java.util.Map;
import java.util.Set;

/**
 * {@code _SLEEP <秒>}：可中断、且能看见停止请求的等待。
 *
 * <p>分段睡（{@link CommandHost#STOP_POLL_MILLIS}）有两个好处：{@code shutdown} 的优雅期不必等睡眠结束，
 * 而且 {@code Thread.sleep} 本来就可中断，{@code abort} 的 {@code interrupt()} 一样能立刻唤醒它。
 * 总时长仍然逼近请求值（每段按剩余时间睡，不为轮询额外加班）。</p>
 */
public final class SleepCommand implements ScriptCommand {

    @Override
    public Set<String> names() {
        return Set.of("SLEEP");
    }

    @Override
    public void execute(CommandCall call, Map<String, Object> ir) {
        String text = ir.get("sleepTime") == null ? "" : String.valueOf(ir.get("sleepTime")).strip();
        double seconds;
        try {
            // 非数字、NaN/Infinity（_sleep 1e400 就是 Infinity）、负数都在这里挡住：
            // Infinity 换算成纳秒会溢出，结果是一个几乎永不返回的 sleep（脚本看起来"挂住"）。
            seconds = UserNumber.nonNegativeSeconds("sleep time", text);
        } catch (UserNumber.InvalidNumberException invalidSleep) {
            call.reportError(invalidSleep.getMessage());
            return;
        }
        try {
            sleep(call.host(), seconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            call.reportError("Sleep interrupted.");
            return;
        }
        // 停止请求（优雅 shutdown 不中断线程，只置位）：不必睡满，立刻交给上层收尾
        call.host().checkStopRequested();
    }

    /** 可中断、且能看见停止请求的等待（纳秒换算夹紧，避免溢出成永不过期/立刻返回）。 */
    private static void sleep(CommandHost host, double seconds) throws InterruptedException {
        double nanos = seconds * 1_000_000_000d;
        long durationNanos = nanos >= Long.MAX_VALUE / 2d ? Long.MAX_VALUE / 2 : (long) nanos;
        long deadline = System.nanoTime() + durationNanos;
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0 || host.stopRequested()) {
                return;
            }
            long sliceMillis = Math.min(remainingNanos / 1_000_000L + 1L, CommandHost.STOP_POLL_MILLIS);
            Thread.sleep(sliceMillis);
        }
    }
}
