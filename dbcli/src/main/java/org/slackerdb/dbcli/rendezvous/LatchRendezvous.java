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
        CountDownLatch gate = gates.computeIfAbsent(RendezvousBackend.normalizePoint(point),
                key -> new CountDownLatch(1));
        if (timeoutMillis < 0L) {
            // 没有时限（脚本没写 timeout=）：一直等，直到有人放行
            gate.await();
            return true;
        }
        return gate.await(Math.max(timeoutMillis, 0L), TimeUnit.MILLISECONDS);
    }

    /** 某个点名是否已经出现过（作业管理器用它做"先作业名、再定时点名"的名字解析）。 */
    public boolean knows(String point) {
        return point != null && gates.containsKey(RendezvousBackend.normalizePoint(point));
    }

    /** 到达过某个点名的参与者（排错用）。 */
    public Set<String> arrivals(String point) {
        Set<String> seen = arrivals.get(RendezvousBackend.normalizePoint(point));
        return seen == null ? Set.of() : Set.copyOf(seen);
    }

    /** 进程内实现没有需要释放的资源。 */
    @Override
    public void close() {
        // 刻意留空：闸门是纯内存对象，随管理器一起被回收
    }
}
