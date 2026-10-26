package org.slackerdb.dbcli.rendezvous;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 进程内会合点：**线程模式（默认）的现状逻辑**，一个点名一个 {@link CountDownLatch}。
 *
 * <p>父子脚本与任意深度的作业共享同一个 {@code JobManager}，因此共享这一个后端实例，
 * "作业等、父放行""父等、作业放行""多个作业对齐后一起继续"都不需要任何 IPC。</p>
 *
 * <p>并发要点（作业线程会同时到达/放行不同的点，也可能同时到达同一个点）：
 * 闸门用 {@link ConcurrentHashMap#computeIfAbsent} <b>原子地</b>"没有就建、有就拿"——
 * 否则两个并发等待会各建一个闸门、谁也等不到谁（见 §5 第 20 条）。</p>
 *
 * <p><b>点名规范化</b>：所有 map 的 key 都先过 {@link RendezvousBackend#normalizePoint(String)}
 * （大小写折叠），因此 {@code _JOB TIMER GO} 与 {@code _JOB WAIT timer:go} 落在同一个闸门上
 * ——与跨进程后端 {@link SqliteRendezvous} 用的是同一份规范化，两种模式语义一致。
 * 回显给使用者的名字由调用方（作业管理器）自己保留原样，这里只负责"是不是同一个点"。</p>
 */
public final class LatchRendezvous implements RendezvousBackend {

    /** 点名（规范形）→ 闸门（每个点只放行一次；放行状态由 latch 自身记住）。 */
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();

    /** 点名（规范形）→ 到达过的参与者（只用于观察与排错，不参与同步语义）。 */
    private final Map<String, Set<String>> arrivals = new ConcurrentHashMap<>();

    /** 后端已关闭：等待者必须被唤醒并如实返回 {@code false}（与 {@link SqliteRendezvous} 一致）。 */
    private volatile boolean closed;

    @Override
    public void arrive(String point, String participant) {
        if (point == null) {
            return;
        }
        String normalized = RendezvousBackend.normalizePoint(point);
        arrivals.computeIfAbsent(normalized, key -> ConcurrentHashMap.newKeySet())
                .add(participant == null || participant.isBlank() ? "MAIN" : participant);
        // 到达即"点名存在"：父脚本随后 TIMER 放行时拿到的是同一个闸门
        gates.computeIfAbsent(normalized, key -> new CountDownLatch(1));
    }

    @Override
    public void release(String point) {
        if (point == null) {
            return;
        }
        // 首次调用同时创建（幂等）：放行一个从没人等过的点也必须记住，后到的等待要立即通过
        gates.computeIfAbsent(RendezvousBackend.normalizePoint(point), key -> new CountDownLatch(1)).countDown();
    }

    @Override
    public boolean await(String point, long timeoutMillis) throws InterruptedException {
        if (closed) {
            return false;
        }
        CountDownLatch gate = gates.computeIfAbsent(RendezvousBackend.normalizePoint(point),
                key -> new CountDownLatch(1));
        // 分片等待 + 每片检查 closed：后端被换掉时（JobManager 从进程内闸门切到 SQLite），
        // 已经阻塞的等待者必须被唤醒并得到"没等到"，否则它会永远挂住（见 RendezvousBackend.await）。
        long deadline = timeoutMillis < 0L
                ? Long.MAX_VALUE : System.nanoTime() + RendezvousBackend.nanosForMillis(timeoutMillis);
        while (true) {
            long remainingMillis = deadline == Long.MAX_VALUE
                    ? CLOSE_POLL_MILLIS
                    : Math.max((deadline - System.nanoTime()) / 1_000_000L, 0L);
            if (remainingMillis <= 0L) {
                // 时限已到：最后一次判"是否刚好放行"（与超时语义一致：超时是正常结果）
                return gate.getCount() == 0L;
            }
            if (gate.await(Math.min(remainingMillis, CLOSE_POLL_MILLIS), TimeUnit.MILLISECONDS)) {
                // close() 也会 countDown 所有闸门来唤醒等待者：那不是"放行"，必须返回 false
                return !closed;
            }
            if (closed) {
                return false;
            }
        }
    }

    /** 唤醒分片的长度（毫秒）：决定"后端被关掉"最多多久被等待者看见。 */
    private static final long CLOSE_POLL_MILLIS = 50L;

    /** 某个点名是否已经出现过（作业管理器用它做"先作业名、再定时点名"的名字解析）。 */
    @Override
    public boolean knows(String point) {
        return point != null && gates.containsKey(RendezvousBackend.normalizePoint(point));
    }

    /** 到达过某个点名的参与者（排错用）。 */
    public Set<String> arrivals(String point) {
        Set<String> seen = arrivals.get(RendezvousBackend.normalizePoint(point));
        return seen == null ? Set.of() : Set.copyOf(seen);
    }

    /**
     * 收尾：置 closed 并唤醒所有等待者（它们会看到 closed 并返回 {@code false}）。
     *
     * <p>为什么不能像以前那样留空：{@code JobManager} 在出现 {@code mode=process} 作业时会把后端换成
     * SQLite，并 {@code close()} 旧闸门。正在 {@code _JOB WAIT timer:x}（不写 {@code timeout=}）的线程
     * 若不被唤醒，就永远等不到那个点——一个静默的挂死。</p>
     */
    @Override
    public void close() {
        closed = true;
        for (CountDownLatch gate : gates.values()) {
            gate.countDown();
        }
    }
}
