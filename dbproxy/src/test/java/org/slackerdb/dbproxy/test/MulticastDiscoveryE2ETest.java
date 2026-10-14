package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.discovery.Announcement;
import org.slackerdb.common.discovery.MulticastAddress;
import org.slackerdb.common.discovery.MulticastAdvertiser;
import org.slackerdb.common.discovery.MulticastListener;
import org.slackerdb.common.discovery.NetworkAddressUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * 真实 socket 的端到端验收：dbserver 的发送端 → 组播 → 代理的接收端。
 *
 * <h3>为什么这条用例会被"跳过"而不是"失败"</h3>
 * <p>
 * 组播能不能在<b>同一台机器的同一条网卡</b>上绕回来，取决于操作系统、网卡驱动与防火墙：
 * Windows 的回环网卡通常不支持组播，部分容器/虚拟网卡上组播也发不出去。这不是代码的问题，
 * 因此收不到报文时用 {@code assumeTrue}/{@code abort} 跳过，并打印具体原因——
 * 把它写成断言失败只会让这个用例在别人的机器上常年飘红，最后被整个禁用。
 * </p>
 * <p>
 * 真正的确定性覆盖在 {@code MulticastListenerTest}（投喂字节）与
 * {@code ProxyInstanceDiscoveryTest}（落库与租约）里；这一条只负责证明"线上真的能跑通"。
 * </p>
 */
class MulticastDiscoveryE2ETest {

    /**
     * 专用测试组与端口：刻意避开文档示例里的 239.255.66.66:46666，
     * 免得开发机恰好跑着一个真实实例而被它的通告干扰断言。
     */
    private static final String TEST_GROUP = "239.255.99.99:47666";

    /** 等待报文的时限。两个通告周期足够，超时即认为本机环境不支持组播回环。 */
    private static final long RECEIVE_TIMEOUT_MILLIS = 15_000L;

    /** 记录收到的报文。 */
    private static final class Collector implements MulticastListener.Handler {
        final List<Announcement> hellos = new CopyOnWriteArrayList<>();
        final List<Announcement> byes = new CopyOnWriteArrayList<>();

        @Override
        public void onHello(Announcement announcement, java.net.InetAddress sourceAddress) {
            hellos.add(announcement);
        }

        @Override
        public void onBye(Announcement announcement, java.net.InetAddress sourceAddress) {
            byes.add(announcement);
        }
    }

    private static Announcement awaitHello(Collector collector) throws InterruptedException {
        long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!collector.hellos.isEmpty()) {
                return collector.hellos.get(0);
            }
            Thread.sleep(100);
        }
        return null;
    }

    @Test
    @DisplayName("发送端 → 组播 → 接收端：HELLO 内容逐字段到达")
    void announcementTravelsOverRealMulticast() throws Exception {
        Logger logger = LoggerFactory.getLogger("test");

        List<NetworkAddressUtil.Candidate> candidates;
        try {
            candidates = NetworkAddressUtil.enumerate();
        } catch (Exception e) {
            abort("Cannot enumerate network interfaces on this host: " + e.getMessage());
            return;
        }
        if (candidates.isEmpty()) {
            abort("This host exposes no multicast-capable interface, cannot exercise real multicast.");
            return;
        }

        MulticastAddress group;
        try {
            group = MulticastAddress.parse(TEST_GROUP);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("test group constant is invalid: " + e.getMessage(), e);
        }

        // 接收端：加入所有支持组播的网卡，与生产路径一致
        Collector collector = new Collector();
        MulticastListener listener = new MulticastListener(logger, group,
                NetworkAddressUtil.multicastReceiveInterfaces(), collector);
        try {
            listener.start();
        } catch (Exception e) {
            abort("Cannot join " + group + " on this host (" + e.getMessage() + ").");
            return;
        }

        // 发送端：走自动选网卡（与 dbserver 的默认行为一致），用一个短周期加快收敛
        NetworkAddressUtil.Candidate nic = candidates.get(0);
        MulticastAdvertiser advertiser = new MulticastAdvertiser(logger,
                new MulticastAdvertiser.Options(group, nic.getNic(), nic.getAddress(), "e2e-db", 5432, 8000)
                        .alias("e2e-db")
                        .version("test")
                        .interval(500L));

        try {
            advertiser.start();
        } catch (Exception e) {
            listener.close();
            abort("Cannot send to " + group + " via NIC [" + nic.getNicName() + "] ("
                    + e.getMessage() + ").");
            return;
        }

        try {
            Announcement received = awaitHello(collector);
            if (received == null) {
                abort("No announcement received within " + RECEIVE_TIMEOUT_MILLIS + " ms on " + group
                        + ". Multicast loopback is unavailable on this host (common on Windows loopback"
                        + " and inside containers).");
                return;
            }

            assertEquals("e2e-db", received.getName());
            assertEquals("e2e-db", received.getAlias());
            assertEquals(5432, received.getPort());
            assertEquals(8000, received.getPortX(), "portX must survive the wire");
            assertEquals("test", received.getVersion());
            assertEquals(nic.getAddress().getHostAddress(), received.getHost(),
                    "the advertised host must be the address of the sending interface");
            assertTrue(received.getSeq() > 0, "sequence must be stamped by the sender");

            // BYE：停机时必须让代理立刻摘除条目，而不是等 15 秒租约
            advertiser.close();
            long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MILLIS;
            while (collector.byes.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(!collector.byes.isEmpty(),
                    "a graceful stop must send BYE so the proxy drops the alias immediately");
            assertEquals("e2e-db", collector.byes.get(0).getName());
        } finally {
            advertiser.close();
            listener.close();
        }
    }

    @Test
    @DisplayName("一块网卡都加入不了时必须明确失败，而不是留一个'看起来在跑'的接收器")
    void startFailsWhenNoInterfaceCanJoin() {
        MulticastListener listener = new MulticastListener(
                LoggerFactory.getLogger("test"),
                MulticastAddress.parse("239.255.99.98:47667"),
                Collections.emptyList(),
                new Collector());

        assertThrows(java.io.IOException.class, listener::start,
                "an empty interface list must be reported, not silently accepted");
        assertFalse(listener.isRunning());
        listener.close();
    }
}
