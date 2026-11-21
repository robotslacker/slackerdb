package org.slackerdb.dbcli.job;

import java.util.Locale;

/**
 * 作业的执行层（{@code _JOB CREATE <名字> … mode=<取值>}）。
 *
 * <p>两种取值不是"哪个更高级"，而是**取舍不同**：</p>
 * <ul>
 *   <li>{@link #THREAD}（**默认**）：作业 = 同一 JVM 里的一个 daemon 线程。编排状态与定时点
 *       都落在同一个 SQLite 作业库里，跨线程与跨进程用同一套语义；代价是"强杀"只能协作式；</li>
 *   <li>{@link #PROCESS}：作业 = 一个独立 JVM 进程（再起一个 dbcli 跑同一份脚本）。隔离硬、
 *       超时能 OS 级强杀；跨进程的会合与状态共享由同一个作业库完成
 *       （见 {@code job.SqliteJobStore}）。</li>
 * </ul>
 */
public enum JobMode {

    /** 同 JVM 线程（默认）。 */
    THREAD,

    /** 独立子进程。 */
    PROCESS;

    /** 参数里的选项名（{@code _JOB CREATE <名字> script=… mode=process}）。 */
    public static final String OPTION = "mode";

    /** 解析 {@code mode=} 的取值（大小写不敏感）；{@code null}/空 表示默认 {@link #THREAD}。 */
    public static JobMode fromOption(String value) {
        if (value == null || value.isBlank()) {
            return THREAD;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "thread" -> THREAD;
            case "process" -> PROCESS;
            default -> throw new JobException("mode must be 'thread' or 'process', but was [" + value + "].");
        };
    }
}
