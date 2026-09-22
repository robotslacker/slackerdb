package org.slackerdb.dbproxy.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后端会话号 → 上游 的取消路由表。
 *
 * <p><b>为什么需要它</b>：CancelRequest 是"新连接上的首个报文"，既没有 database 也没有别名，
 * 代理无法像普通查询那样按别名选路。但取消报文里的 pid 是后端自己的会话号，
 * 而代理能看到后端回给客户端的 BackendKeyData（'K' 报文），于是可以在握手期建立
 * {@code pid → 上游} 的对照关系，取消到达时按 pid 精确回送。</p>
 *
 * <p><b>并发约定</b>：登记发生在"后端回包"的线程上，查找发生在"取消连接"的线程上，
 * 两者都不在同一个线程，因此容器必须是并发的；摘除必须带上 clientSessionId 做条件判断。</p>
 */
public class CancelRouter {
    // pid → 路由信息。表项数量约等于当前活跃会话数，天然有界。
    private final Map<Integer, CancelRoute> routes = new ConcurrentHashMap<>();

    // 累计登记次数（用于 STATUS 观测与测试）
    private final AtomicLong registeredTotal = new AtomicLong();

    // 累计"取消找不到目标"的次数（正常现象：会话可能刚好已结束）
    private final AtomicLong missTotal = new AtomicLong();

    /**
     * 登记一条路由。
     *
     * <p>刻意使用覆盖语义而不是 putIfAbsent：后端重启后会话号可能被复用，
     * 新会话必须覆盖旧映射，否则取消会被送到已经消失的会话号上。</p>
     */
    public void register(CancelRoute route) {
        if (route == null || route.backendPid <= 0) {
            return;
        }
        routes.put(route.backendPid, route);
        registeredTotal.incrementAndGet();
    }

    /**
     * 按后端会话号查找路由。未命中属正常时序（会话已结束），调用方应静默处理。
     */
    public CancelRoute find(int backendPid) {
        CancelRoute route = routes.get(backendPid);
        if (route == null) {
            missTotal.incrementAndGet();
        }
        return route;
    }

    /**
     * 摘除路由。
     *
     * <p>必须条件化：只有当前表项仍然是**这一次会话**登记的那条时才摘除。
     * 否则"旧会话关闭"会把"pid 被复用后新会话"的映射误删，导致后续取消全部失效。</p>
     *
     * @param clientSessionId 登记该路由时的代理侧会话号；&lt;=0 表示不做条件判断
     */
    public void unregister(int backendPid, long clientSessionId) {
        if (backendPid <= 0) {
            return;
        }
        routes.computeIfPresent(backendPid, (pid, existed) -> {
            if (clientSessionId > 0 && existed.clientSessionId != clientSessionId) {
                // 表项已经被更新的会话覆盖，不能摘除
                return existed;
            }
            return null;
        });
    }

    /** 当前路由表条目数（活跃的后端会话数）。 */
    public int size() {
        return routes.size();
    }

    public long getRegisteredTotal() {
        return registeredTotal.get();
    }

    public long getMissTotal() {
        return missTotal.get();
    }

    /** 清空（用于实例停止）。 */
    public void clear() {
        routes.clear();
    }
}
