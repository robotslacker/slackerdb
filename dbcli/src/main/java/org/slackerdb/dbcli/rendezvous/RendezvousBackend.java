package org.slackerdb.dbcli.rendezvous;

/**
 * 会合点后端：{@code _JOB TIMER <点名>}（放行）与 {@code _JOB WAIT <点名>}（等待）的落地点。
 *
 * <h2>为什么要有这一层</h2>
 * <p>作业默认跑在**同一 JVM 的线程**里（§3.6），父子共享同一个作业管理器，定时点直接就是一个进程内闸门
 * ——{@link LatchRendezvous}，零 IPC。但作业也可以跑成**独立进程**（{@code mode=process}），
 * 那时"父放行、子等待"必须跨进程传递，于是有了 {@link SqliteRendezvous}（一个 SQLite 同步库当会合点）。
 * 把这两件事收在同一个接口后面，作业管理器与脚本层就不必知道自己在哪种模式下跑。</p>
 *
 * <h2>三条语义纪律（两种实现都必须遵守）</h2>
 * <ol>
 *   <li>{@link #release(String)} <b>原子且幂等</b>：放行一次与放行多次等价，且一旦放行，
 *       之后（包括后来才到达的）等待都立即通过；</li>
 *   <li>{@link #await(String, long)} <b>超时是正常结果</b>（返回 {@code false}），不是异常——
 *       它只表示"这次没等到"，不代表作业出了问题；脚本不写 {@code timeout=} 时用
 *       {@link #NO_TIMEOUT}，表示<b>一直等到放行为止</b>；</li>
 *   <li>{@link #arrive(String, String)} 只记录"谁到了"，<b>不</b>放行、也<b>不</b>清除已放行状态
 *       （父脚本先放行、作业后到达时，作业必须直接通过）。</li>
 * </ol>
 *
 * <h2>第四条纪律：点名先规范化，再落地</h2>
 * <p>两个后端都必须把点名过一遍 {@link #normalizePoint(String)} 再用它当 key（进程内 map 的 key /
 * SQL 里 {@code sync_point.point} 与 {@code sync_arrive.point}）。<b>定时点名因此不区分大小写</b>：
 * 父脚本 {@code _JOB TIMER GO} 与作业里 {@code _JOB WAIT timer:go} 是同一个点。
 * 父子进程用的是同一份实现，所以"两边看到的点名"必然一致——这正是这条纪律要防的错配。</p>
 */
public interface RendezvousBackend extends AutoCloseable {

    /**
     * "没有时限"：{@link #await(String, long)} 传它就一直等到放行（或后端关闭）为止。
     *
     * <p>脚本里的 {@code _JOB WAIT} 不写 {@code timeout=} 就是这种语义（见 {@code job.JobManager}）。</p>
     */
    long NO_TIMEOUT = -1L;

    /**
     * 会合点名字的**唯一规范形**（两个后端、父子进程共用这一份实现）。
     *
     * <p>语义：<b>定时点名不区分大小写</b>（{@code GO} / {@code go} / {@code Go} 是同一个点）。
     * <b>作业名不走这里</b>：它是脚本里的标识符，仍然保持区分大小写的既有语义（见 {@code job.JobManager}）。
     * 调用方写的**原样名字**照旧用于回显文案，规范化只用于"这是个哪个点"的判定。</p>
     *
     * <p>用 {@link java.util.Locale#ROOT} 而不是默认 Locale：土耳其语环境下 {@code "i"} 的
     * {@code toUpperCase} 会给出带点的 {@code "İ"}，同一个脚本在不同机器上会合到不同的点。</p>
     *
     * @param point 调用方写的点名（可为 null：null 原样返回，由各后端自行处理）
     * @return 规范化后的点名
     */
    static String normalizePoint(String point) {
        return point == null ? null : point.toUpperCase(java.util.Locale.ROOT);
    }

    /** 记下"某个参与者到达了某个点名"（用于观察/排错；不改变放行状态）。 */
    void arrive(String point, String participant);

    /** 放行某个点名（原子、幂等）。 */
    void release(String point);

    /**
     * 在某个点名上等待放行。
     *
     * @param timeoutMillis 超时（毫秒）；{@link #NO_TIMEOUT} 表示一直等
     * @return 是否已放行；{@code false} 表示**超时**（正常结果，调用方按"没等到"处理）
     * @throws InterruptedException 等待中被中断（调用方决定怎么解释）
     */
    boolean await(String point, long timeoutMillis) throws InterruptedException;

    /** 收尾（{@link LatchRendezvous} 无事可做；{@link SqliteRendezvous} 关连接并尽力清理同步库）。 */
    @Override
    void close();
}
