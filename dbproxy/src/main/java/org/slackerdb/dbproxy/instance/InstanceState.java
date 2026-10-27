package org.slackerdb.dbproxy.instance;

/**
 * 实例的<b>观察态</b>。
 *
 * <p>这些值全部由 {@link InstanceProbe} 每次实时推导得出，代理不持久化、也不"记住"它们。
 * 因此代理重启、被 kill、实例被用户手工启停之后，列表依然是事实。</p>
 */
public enum InstanceState {
    /** 进程在，{@code /health} 返回 UP。 */
    RUNNING,
    /** 进程在，但 {@code /health} 超时或报 DEGRADED。 */
    DEGRADED,
    /** 没有存活的实例进程。 */
    STOPPED,
    /** 无法判断：conf 损坏、pid 被复用、目录名非法、大小写冲突等。 */
    UNKNOWN,
    /** 代理正在对该别名执行启动操作（仅内存瞬态，优先于探测结果）。 */
    STARTING,
    /** 代理正在对该别名执行停止操作（仅内存瞬态）。 */
    STOPPING;

    /** 是否可以把客户端流量转发过去。 */
    public boolean routable() {
        return this == RUNNING || this == DEGRADED;
    }
}
