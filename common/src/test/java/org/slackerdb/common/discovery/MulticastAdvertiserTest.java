package org.slackerdb.common.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link MulticastAdvertiser} 的验收：参数校验、通告内容、以及"没开盘就能收摊"。
 *
 * <h3>为什么不在单测里真的发组播</h3>
 * <p>
 * 组播在回环网卡上的行为不可靠（Windows 上尤其），把它写进单元测试只会得到一个
 * "在某人机器上偶尔失败"的用例。所以这里只验证<b>将要发出去的那条报文内容</b>和生命周期，
 * 真实的收发在端到端测试里覆盖。
 * </p>
 */
class MulticastAdvertiserTest {

    private static final MulticastAddress GROUP = MulticastAddress.parse("239.255.66.66:46666");

    private static NetworkInterface anyInterface() throws SocketException {
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        if (all == null) {
            return null;
        }
        for (NetworkInterface nic : Collections.list(all)) {
            if (nic.isUp()) {
                return nic;
            }
        }
        return null;
    }

    private static MulticastAdvertiser.Options options(NetworkInterface nic) throws Exception {
        return new MulticastAdvertiser.Options(GROUP, nic,
                (Inet4Address) InetAddress.getByName("192.168.1.10"), "mydb", 5432, 8000);
    }

    @Test
    @DisplayName("参数校验：组地址、网卡、本机地址、库名、端口缺一不可")
    void optionsRejectIncompleteInput() throws Exception {
        NetworkInterface nic = anyInterface();
        assumeTrue(nic != null, "this machine exposes no network interface");
        Inet4Address local = (Inet4Address) InetAddress.getByName("192.168.1.10");

        assertThrows(IllegalArgumentException.class,
                () -> new MulticastAdvertiser.Options(null, nic, local, "mydb", 5432, 8000));
        assertThrows(IllegalArgumentException.class,
                () -> new MulticastAdvertiser.Options(GROUP, null, local, "mydb", 5432, 8000));
        assertThrows(IllegalArgumentException.class,
                () -> new MulticastAdvertiser.Options(GROUP, nic, null, "mydb", 5432, 8000));
        assertThrows(IllegalArgumentException.class,
                () -> new MulticastAdvertiser.Options(GROUP, nic, local, "  ", 5432, 8000));
        assertThrows(IllegalArgumentException.class,
                () -> new MulticastAdvertiser.Options(GROUP, nic, local, "mydb", 0, 8000));
        assertThrows(IllegalArgumentException.class,
                () -> new MulticastAdvertiser.Options(GROUP, nic, local, "mydb", 5432, -2));
    }

    @Test
    @DisplayName("别名缺省等于库名：保证客户端连接串不用改")
    void aliasDefaultsToDatabaseName() throws Exception {
        NetworkInterface nic = anyInterface();
        assumeTrue(nic != null, "this machine exposes no network interface");

        assertEquals("mydb", options(nic).getAlias());
        assertEquals("mydb", options(nic).alias("").getAlias());
        assertEquals("mydb", options(nic).alias(null).getAlias());
        assertEquals("other", options(nic).alias(" other ").getAlias());
    }

    @Test
    @DisplayName("通告周期有下限：周期小于 200ms 会被拒（否则等于对网段做 UDP 洪泛）")
    void intervalHasLowerBound() throws Exception {
        NetworkInterface nic = anyInterface();
        assumeTrue(nic != null, "this machine exposes no network interface");

        assertEquals(MulticastAdvertiser.DEFAULT_INTERVAL_MILLIS, options(nic).getIntervalMillis());
        assertEquals(7_000L, options(nic).interval(7_000L).getIntervalMillis());
        assertThrows(IllegalArgumentException.class, () -> options(nic).interval(10L));
    }

    @Test
    @DisplayName("将要发出的 HELLO 内容正确，且序号单调递增")
    void buildsIncrementingHello() throws Exception {
        NetworkInterface nic = anyInterface();
        assumeTrue(nic != null, "this machine exposes no network interface");

        MulticastAdvertiser advertiser = new MulticastAdvertiser(LoggerFactory.getLogger("test"),
                options(nic).alias("mydb-alias").version("0.2.2"));

        Announcement first = advertiser.nextHello();
        Announcement second = advertiser.nextHello();

        assertTrue(first.isHello());
        assertEquals("mydb", first.getName());
        assertEquals("mydb-alias", first.getAlias());
        assertEquals("192.168.1.10", first.getHost());
        assertEquals(5432, first.getPort());
        assertEquals(8000, first.getPortX());
        assertEquals("0.2.2", first.getVersion());
        assertTrue(second.getSeq() > first.getSeq(), "sequence must increase so receivers can spot reordering");
    }

    @Test
    @DisplayName("portX 未启用时按 -1 通告，与旧 REGISTER 报文的语义一致")
    void advertisesDisabledPortX() throws Exception {
        NetworkInterface nic = anyInterface();
        assumeTrue(nic != null, "this machine exposes no network interface");

        MulticastAdvertiser advertiser = new MulticastAdvertiser(LoggerFactory.getLogger("test"),
                new MulticastAdvertiser.Options(GROUP, nic,
                        (Inet4Address) InetAddress.getByName("192.168.1.10"), "mydb", 5432, -1));

        assertEquals(Announcement.PORT_X_DISABLED, advertiser.nextHello().getPortX());
    }

    @Test
    @DisplayName("没 start 就 close：安全空转，不抛异常（停机路径可能重复调用）")
    void closeBeforeStartIsNoOp() throws Exception {
        NetworkInterface nic = anyInterface();
        assumeTrue(nic != null, "this machine exposes no network interface");

        MulticastAdvertiser advertiser = new MulticastAdvertiser(LoggerFactory.getLogger("test"),
                options(nic));

        assertFalse(advertiser.isRunning());
        advertiser.close();
        advertiser.close();
        assertFalse(advertiser.isRunning());
    }
}
