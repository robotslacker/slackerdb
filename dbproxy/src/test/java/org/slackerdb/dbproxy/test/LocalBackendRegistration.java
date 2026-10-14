package org.slackerdb.dbproxy.test;

import org.slackerdb.common.discovery.Announcement;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.dbserver.server.DBInstance;

import java.net.InetAddress;

/**
 * 测试辅助：把在同一个 JVM 里启动的 dbserver 登记到代理上。
 *
 * <h3>为什么不再让 dbserver "自注册"</h3>
 * <p>
 * 原来的做法是给 dbserver 配 {@code remote_listener}，让它周期性向代理发 TCP REGISTER。
 * 该参数已被组播服务发现取代并从 dbserver 删除，遗留的 REGISTER 通道也已从代理删除。
 * 而这些用例真正要验证的是<b>代理的转发与选路</b>，不是发现通道本身。
 * </p>
 * <p>
 * 真实的组播收发由 {@code MulticastDiscoveryE2ETest} 用真实 socket 覆盖——那条路径在
 * Windows 回环上并不可靠，所以单独隔离并带前置条件跳过，不能让它拖累这些用例的确定性。
 * 这里直接投喂一条 {@code HELLO} 到代理的公开入口，走的是与线上<b>完全相同</b>的落库逻辑。
 * </p>
 * <p>
 * 顺带一提租约：这些用例里代理没有配置 {@code listener_address}，因此不会启动接收线程，
 * 也就没有人调用过期扫描——即便用例跑得比 15 秒的租约还久，条目也不会被摘掉。
 * </p>
 */
final class LocalBackendRegistration {

    private LocalBackendRegistration() {
    }

    /**
     * 把 {@code dbInstance} 以其库名同时作为别名登记到代理，地址固定为回环。
     *
     * <p>库名即别名，与客户连接串里的 database 保持一致
     * （{@code jdbc:postgresql://proxy:port/<库名>}）。</p>
     */
    static void register(ProxyInstance proxyInstance, DBInstance dbInstance) {
        String database = dbInstance.serverConfiguration.getData();
        try {
            // 源地址必须是明确的 127.0.0.1：用 getLoopbackAddress() 在部分机器上会拿到 ::1，
            // 代理随后会去连后端的 IPv6 地址，而这个后端只监听 IPv4。
            // 另外，源地址而非报文里的 host 才是代理认定的可达地址，所以这里两处都写回环。
            proxyInstance.applyAnnouncement(
                    Announcement.hello(database, database, "127.0.0.1",
                            dbInstance.serverConfiguration.getPort(),
                            dbInstance.serverConfiguration.getPortX(), "", 1),
                    InetAddress.getByName("127.0.0.1"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to register [" + database + "] with the proxy", e);
        }
    }
}
