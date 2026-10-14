package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.common.discovery.MulticastAddress;
import org.slackerdb.common.discovery.NetworkAddressUtil;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;

import java.net.Inet4Address;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * dbserver 的组播通告配置验收：参数解析、错误取值在启动期就报错、以及"不配置就是不启用"。
 *
 * <p>断言消息用英文：surefire 在中文控制台上会把非 ASCII 打成乱码。</p>
 */
class AdvertiseConfigurationTest {

    // ------------------------------------------------------------------ 取值解析

    @Test
    @DisplayName("默认不启用：agvertise_address 缺省为空，且不解析网卡、不产生任何副作用")
    void discoveryIsDisabledByDefault() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();

        assertFalse(configuration.isAdvertiseEnabled());
        assertNull(configuration.getAdvertiseAddress(), "a disabled configuration has no group to parse");
        assertEquals("", configuration.getAdvertiseNic());
        assertNull(configuration.getAdvertiseHost(), "nothing was announced, so there is no effective host");
    }

    @Test
    @DisplayName("合法取值被规范化后保存：两端配置的对比不受空格与大小写写法影响")
    void parsesAndNormalisesValidAddress() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setAdvertiseAddress("  239.255.66.66:46666  ");

        assertTrue(configuration.isAdvertiseEnabled());
        MulticastAddress address = configuration.getAdvertiseAddress();
        assertEquals("239.255.66.66", address.getGroupHost());
        assertEquals(46666, address.getPort());
        assertEquals("239.255.66.66:46666", address.toString());
    }

    @Test
    @DisplayName("非法取值在设置时就报 SLACKERDB-00020，并保留具体原因")
    void rejectsInvalidAddress() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();

        for (String invalid : new String[]{"192.168.1.1:46666", "239.255.66.66", "239.255.66.66:0",
                "224.0.0.1:46666", "example.com:46666", "ff02::1:46666"}) {
            ServerException e = assertThrows(ServerException.class,
                    () -> configuration.setAdvertiseAddress(invalid),
                    "must be rejected: " + invalid);
            assertEquals("SLACKERDB-00020", e.getErrorCode(), invalid);
            assertTrue(e.getMessage().contains("Invalid advertise address"), e.getMessage());
        }
    }

    @Test
    @DisplayName("清空取值 = 关闭发现：不会留下上一次的组地址")
    void clearingAddressDisablesDiscovery() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setAdvertiseAddress("239.255.66.66:46666");
        assertTrue(configuration.isAdvertiseEnabled());

        configuration.setAdvertiseAddress("");
        assertFalse(configuration.isAdvertiseEnabled());
        assertNull(configuration.getAdvertiseAddress());
    }

    @Test
    @DisplayName("网卡取值只做裁剪记录，不在解析配置时就去解析网卡")
    void recordsNicWithoutResolvingIt() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();

        configuration.setAdvertiseNic("  eth0  ");
        assertEquals("eth0", configuration.getAdvertiseNic());

        configuration.setAdvertiseNic(null);
        assertEquals("", configuration.getAdvertiseNic());

        // 关键：哪怕取值指向一块不存在的网卡，这里也不能报错——解析要等到真正启动通告时，
        // 那时才知道本机有哪些网卡；而且通告未启用时这个参数根本不该被使用
        configuration.setAdvertiseNic("no-such-nic-xyz");
        assertEquals("no-such-nic-xyz", configuration.getAdvertiseNic());
    }

    // ------------------------------------------------------------------ 配置文件

    @Test
    @DisplayName("从 conf 读取 advertise_address / advertise_nic")
    void loadsFromConfigurationFile(@TempDir Path tempDir) throws Exception {
        Path conf = tempDir.resolve("dbserver.conf");
        Files.writeString(conf, "advertise_address=239.255.66.66:46666\nadvertise_nic=127.0.0.1\n");

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.loadConfigurationFile(conf.toString());

        assertTrue(configuration.isAdvertiseEnabled());
        assertEquals(46666, configuration.getAdvertiseAddress().getPort());
        assertEquals("127.0.0.1", configuration.getAdvertiseNic());
    }

    // ------------------------------------------------------------------ 本机地址选择

    @Test
    @DisplayName("具体地址判定：排除空、通配与主机名")
    void recognisesConcreteAddresses() {
        assertTrue(NetworkAddressUtil.isConcreteAddress("192.168.1.5"));
        assertTrue(NetworkAddressUtil.isConcreteAddress("127.0.0.1"));

        assertFalse(NetworkAddressUtil.isConcreteAddress(null));
        assertFalse(NetworkAddressUtil.isConcreteAddress(""));
        assertFalse(NetworkAddressUtil.isConcreteAddress("   "));
        assertFalse(NetworkAddressUtil.isConcreteAddress("0.0.0.0"));
        assertFalse(NetworkAddressUtil.isConcreteAddress("::"));
        assertFalse(NetworkAddressUtil.isConcreteAddress("*"));
        assertFalse(NetworkAddressUtil.isConcreteAddress("localhost"));
        assertFalse(NetworkAddressUtil.isConcreteAddress("eth0"));
    }

    @Test
    @DisplayName("advertise_nic 优先级最高：配了就用它，哪怕 bind 也是一个具体地址")
    void explicitNicWinsOverBind() {
        // 取值不存在 → 必须报"网卡找不到"，而不是悄悄退回 bind 对应的地址
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NetworkAddressUtil.resolveForAdvertise("no-such-nic-xyz", "127.0.0.1"));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
    }

    @Test
    @DisplayName("bind 是具体地址时优先用它：否则会出现'绑在 A、通告 B'导致代理连不上")
    void concreteBindWinsOverAutoSelect() {
        // 203.0.113.0/24 是 RFC5737 保留的测试段，任何机器都不会真的持有它
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NetworkAddressUtil.resolveForAdvertise(null, "203.0.113.9"));
        assertTrue(e.getMessage().contains("does not belong to any local network interface"),
                "the bind address must be used as-is, not replaced by an automatic pick: " + e.getMessage());
    }

    @Test
    @DisplayName("bind=127.0.0.1 时通告回环地址：服务本来就只监听本机，通告别的地址没有任何意义")
    void loopbackBindAnnouncesLoopback() throws Exception {
        assumeTrue(hasLoopbackInterface(), "this machine does not enumerate a loopback interface");

        NetworkAddressUtil.Candidate candidate = NetworkAddressUtil.resolveForAdvertise(null, "127.0.0.1");
        assertTrue(candidate.getAddress().isLoopbackAddress());
    }

    @Test
    @DisplayName("通配 bind 回落到自动选择：不能把 0.0.0.0 当成可通告的地址")
    void wildcardBindFallsBackToAutoSelect() throws Exception {
        List<NetworkAddressUtil.Candidate> candidates = NetworkAddressUtil.enumerate();
        assumeTrue(!candidates.isEmpty(), "this machine exposes no usable multicast NIC");

        for (String wildcard : new String[]{"0.0.0.0", "::", "*", ""}) {
            NetworkAddressUtil.Candidate candidate = NetworkAddressUtil.resolveForAdvertise(null, wildcard);
            assertEquals(candidates.get(0).toString(), candidate.toString(),
                    "wildcard bind [" + wildcard + "] must fall back to the automatic pick");
        }
    }

    private static boolean hasLoopbackInterface() throws Exception {
        for (java.net.NetworkInterface nic
                : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            for (java.net.InterfaceAddress interfaceAddress : nic.getInterfaceAddresses()) {
                if (interfaceAddress.getAddress() instanceof Inet4Address address
                        && address.isLoopbackAddress()) {
                    return true;
                }
            }
        }
        return false;
    }
}
