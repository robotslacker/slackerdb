package org.slackerdb.common.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link NetworkAddressUtil} 的验收：自动选择必须<b>可复现</b>，显式点名必须<b>优先</b>。
 *
 * <h3>为什么排序规则要单独测</h3>
 * <p>
 * {@code advertise_nic} 不配置时的语义是"自动选一块网卡"。如果这个"自动"依赖
 * {@code getNetworkInterfaces()} 的枚举顺序，那么同一台机器两次启动可能挑中不同的网卡
 * （这一次是物理网卡、下一次是 Hyper-V 的 vEthernet），表现就是"组播时通时不通"，
 * 而且几乎无法复现。所以排序规则被抽成了一个纯函数式的比较器，用<b>手工构造的候选</b>
 * 来断言优先级——真机枚举结果不可控，没法靠"这台机器应该选 eth0"来验证规则。
 * </p>
 *
 * <p>涉及真实网卡的用例都挂了 {@code assumeTrue} 前置条件，在没有网卡的环境（部分容器）
 * 里会被跳过而不是误报失败。</p>
 */
class NetworkAddressUtilTest {

    private static NetworkAddressUtil.Candidate candidate(String nicName, String address, boolean suspicious)
            throws Exception {
        return new NetworkAddressUtil.Candidate(null, nicName,
                (Inet4Address) InetAddress.getByName(address), suspicious);
    }

    private static NetworkAddressUtil.Candidate candidate(String nicName, String address) throws Exception {
        return candidate(nicName, address, false);
    }

    // ------------------------------------------------------------------ 地址字面量

    @Test
    @DisplayName("IPv4 字面量判定：拒绝 1.2.3 / 1.2.3.256 这类会被 getByName 静默变形的写法")
    void recognisesIpv4Literals() {
        assertTrue(NetworkAddressUtil.isIpv4Literal("192.168.1.1"));
        assertTrue(NetworkAddressUtil.isIpv4Literal("0.0.0.0"));
        assertTrue(NetworkAddressUtil.isIpv4Literal("255.255.255.255"));

        assertFalse(NetworkAddressUtil.isIpv4Literal(null));
        assertFalse(NetworkAddressUtil.isIpv4Literal(""));
        assertFalse(NetworkAddressUtil.isIpv4Literal("1.2.3"));
        assertFalse(NetworkAddressUtil.isIpv4Literal("1.2.3.4.5"));
        assertFalse(NetworkAddressUtil.isIpv4Literal("1.2.3.256"));
        assertFalse(NetworkAddressUtil.isIpv4Literal("1.2.3.-1"));
        assertFalse(NetworkAddressUtil.isIpv4Literal("1.2.3."));
        assertFalse(NetworkAddressUtil.isIpv4Literal(".1.2.3"));
        assertFalse(NetworkAddressUtil.isIpv4Literal("eth0"));
        assertFalse(NetworkAddressUtil.isIpv4Literal("fe80::1"));
    }

    @Test
    @DisplayName("解析字面量：主机名一律返回 null，绝不做 DNS 查询")
    void parsesLiteralsWithoutDns() throws Exception {
        assertEquals(InetAddress.getByName("10.0.0.5"), NetworkAddressUtil.parseLiteral("10.0.0.5"));
        assertEquals(InetAddress.getByName("fe80::1"), NetworkAddressUtil.parseLiteral("fe80::1"));
        assertNull(NetworkAddressUtil.parseLiteral("localhost"));
        assertNull(NetworkAddressUtil.parseLiteral("example.com"));
        assertNull(NetworkAddressUtil.parseLiteral("1.2.3"));
        assertNull(NetworkAddressUtil.parseLiteral(null));
    }

    // ------------------------------------------------------------------ 可用地址判定

    @Test
    @DisplayName("可用地址：排除回环、link-local、0.0.0.0、组播与 IPv6")
    void filtersUnusableAddresses() throws Exception {
        assertTrue(NetworkAddressUtil.isUsable(InetAddress.getByName("192.168.1.10")));
        assertTrue(NetworkAddressUtil.isUsable(InetAddress.getByName("10.0.0.5")));
        assertTrue(NetworkAddressUtil.isUsable(InetAddress.getByName("203.0.113.9")));

        assertFalse(NetworkAddressUtil.isUsable(InetAddress.getByName("127.0.0.1")));
        assertFalse(NetworkAddressUtil.isUsable(InetAddress.getByName("169.254.1.1")));
        assertFalse(NetworkAddressUtil.isUsable(InetAddress.getByName("0.0.0.0")));
        assertFalse(NetworkAddressUtil.isUsable(InetAddress.getByName("224.0.0.1")));
        assertFalse(NetworkAddressUtil.isUsable(InetAddress.getByName("::1")));
        assertFalse(NetworkAddressUtil.isUsable(InetAddress.getByName("fe80::1")));
    }

    @Test
    @DisplayName("疑似虚拟网卡名：docker/veth/vEthernet/隧道被识别，物理网卡名不受影响")
    void recognisesVirtualNicNames() {
        for (String virtual : new String[]{"docker0", "DOCKER0", "veth1a2b3c", "br-9f8e",
                "vEthernet (WSL)", "VMware Network Adapter VMnet1", "vmnet1", "vboxnet0",
                "utun0", "tun0", "tap0", "tailscale0", "wsl0"}) {
            assertTrue(NetworkAddressUtil.isSuspiciousVirtualName(virtual),
                    "should be treated as virtual: " + virtual);
        }

        for (String physical : new String[]{"eth0", "eth0.100", "Ethernet", "en0", "wlan0",
                "ens192", "enp3s0", "Wi-Fi"}) {
            assertFalse(NetworkAddressUtil.isSuspiciousVirtualName(physical),
                    "should not be treated as virtual: " + physical);
        }

        assertFalse(NetworkAddressUtil.isSuspiciousVirtualName(null));
        assertFalse(NetworkAddressUtil.isSuspiciousVirtualName(""));
    }

    // ------------------------------------------------------------------ 排序规则

    @Test
    @DisplayName("排序优先级：先按是否疑似虚拟，再按私网优先，再按网卡名，最后按地址")
    void ordersCandidatesDeterministically() throws Exception {
        List<NetworkAddressUtil.Candidate> candidates = new ArrayList<>(Arrays.asList(
                candidate("docker0", "10.0.0.1", true),      // 虚拟 + 私网
                candidate("veth9", "192.168.9.9", true),     // 虚拟 + 私网
                candidate("eth1", "203.0.113.9"),            // 物理 + 公网
                candidate("eth0", "10.0.0.5"),               // 物理 + 私网
                candidate("eth0", "10.0.0.2"),               // 物理 + 私网，同网卡更小地址
                candidate("ens192", "10.0.0.7")));           // 物理 + 私网

        candidates.sort(NetworkAddressUtil.CANDIDATE_ORDER);

        assertEquals(Arrays.asList(
                        // 物理 + 私网一组：网卡名按字典序（'n' < 't'，所以 ens192 在 eth0 之前），
                        // 同名网卡内部再按地址升序
                        "ens192/10.0.0.7",
                        "eth0/10.0.0.2",
                        "eth0/10.0.0.5",
                        "eth1/203.0.113.9", // 物理但公网：排在所有私网物理网卡之后
                        "docker0/10.0.0.1", // 虚拟网卡整体排在物理网卡之后（即使它是私网）
                        "veth9/192.168.9.9"),
                toStrings(candidates));
    }

    @Test
    @DisplayName("排序结果与输入顺序无关：同一集合任意打乱都得到同一序列")
    void orderIsIndependentOfInputOrder() throws Exception {
        List<NetworkAddressUtil.Candidate> baseline = Arrays.asList(
                candidate("eth0", "10.0.0.5"),
                candidate("docker0", "172.17.0.1", true),
                candidate("eth1", "192.168.1.10"),
                candidate("vEthernet (WSL)", "172.20.0.1", true),
                candidate("eth0", "10.0.0.2"));

        List<String> first = toStrings(sorted(baseline));
        assertEquals(Arrays.asList(
                        "eth0/10.0.0.2", "eth0/10.0.0.5", "eth1/192.168.1.10",
                        "docker0/172.17.0.1", "vEthernet (WSL)/172.20.0.1"),
                first);

        for (int seed = 0; seed < 20; seed++) {
            List<NetworkAddressUtil.Candidate> shuffled = new ArrayList<>(baseline);
            Collections.shuffle(shuffled, new Random(seed));
            assertEquals(first, toStrings(sorted(shuffled)),
                    "order must not depend on enumeration order (seed " + seed + ")");
        }
    }

    private static List<NetworkAddressUtil.Candidate> sorted(List<NetworkAddressUtil.Candidate> candidates) {
        List<NetworkAddressUtil.Candidate> copy = new ArrayList<>(candidates);
        copy.sort(NetworkAddressUtil.CANDIDATE_ORDER);
        return copy;
    }

    @Test
    @DisplayName("同网卡内多地址：私网优先，然后按无符号数值升序")
    void ordersAddressesWithinOneNic() throws Exception {
        List<Inet4Address> addresses = new ArrayList<>(Arrays.asList(
                (Inet4Address) InetAddress.getByName("203.0.113.9"),
                (Inet4Address) InetAddress.getByName("10.0.0.9"),
                (Inet4Address) InetAddress.getByName("10.0.0.2")));

        addresses.sort(NetworkAddressUtil.ADDRESS_ORDER);

        assertEquals("10.0.0.2", addresses.get(0).getHostAddress());
        assertEquals("10.0.0.9", addresses.get(1).getHostAddress());
        assertEquals("203.0.113.9", addresses.get(2).getHostAddress());
    }

    @Test
    @DisplayName("地址比较按无符号：192.x 这类高位地址不会被当成负数排到最前")
    void comparesAddressesAsUnsigned() throws Exception {
        int high = NetworkAddressUtil.addressKey((Inet4Address) InetAddress.getByName("200.1.1.1"));
        int low = NetworkAddressUtil.addressKey((Inet4Address) InetAddress.getByName("10.0.0.1"));

        assertTrue(high < 0, "200.1.1.1 must be negative when read as a signed int (this is the trap)");
        assertTrue(Integer.compareUnsigned(high, low) > 0,
                "unsigned comparison must keep 200.1.1.1 after 10.0.0.1");
    }

    private static List<String> toStrings(List<NetworkAddressUtil.Candidate> candidates) {
        List<String> texts = new ArrayList<>(candidates.size());
        for (NetworkAddressUtil.Candidate candidate : candidates) {
            texts.add(candidate.toString());
        }
        return texts;
    }

    // ------------------------------------------------------------------ 真实网卡上的解析

    @Test
    @DisplayName("显式点名不存在的网卡：报错里说明可以用网卡名或本机 IP")
    void rejectsUnknownNic() {
        for (String unknown : new String[]{"no-such-nic-xyz", "999.1.1.1"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> NetworkAddressUtil.resolve(unknown));
            assertTrue(e.getMessage().contains("not found"), e.getMessage());
        }
    }

    @Test
    @DisplayName("显式点名一个不是本机的 IP：报错说明它不属于任何本机网卡")
    void rejectsForeignAddress() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NetworkAddressUtil.resolve("203.0.113.9"));
        assertTrue(e.getMessage().contains("does not belong to any local network interface"),
                e.getMessage());
    }

    @Test
    @DisplayName("留空 = 自动选择：结果与 autoSelect 一致")
    void blankFallsBackToAutoSelect() throws Exception {
        List<NetworkAddressUtil.Candidate> candidates = NetworkAddressUtil.enumerate();
        assumeTrue(!candidates.isEmpty(), "this machine exposes no usable multicast NIC");

        for (String blank : new String[]{null, "", "   "}) {
            assertEquals(candidates.get(0).toString(), NetworkAddressUtil.resolve(blank).toString());
        }
    }

    @Test
    @DisplayName("显式点名回环地址：本机联调组播时允许")
    void resolvesLoopbackOnDemand() throws Exception {
        assumeTrue(hasLoopbackInterface(), "this machine does not enumerate a loopback interface");

        NetworkAddressUtil.Candidate candidate = NetworkAddressUtil.resolve("127.0.0.1");
        assertNotNull(candidate.getNic());
        assertTrue(candidate.getAddress().isLoopbackAddress());
    }

    @Test
    @DisplayName("按网卡名与按本机 IP 点名同一块网卡，得到同一个地址")
    void resolvesByNameAndByAddress() throws Exception {
        List<NetworkAddressUtil.Candidate> candidates = NetworkAddressUtil.enumerate();
        assumeTrue(!candidates.isEmpty(), "this machine exposes no usable multicast NIC");

        NetworkAddressUtil.Candidate first = candidates.get(0);
        NetworkAddressUtil.Candidate byName = NetworkAddressUtil.resolve(first.getNicName());

        assertEquals(first.getNicName(), byName.getNicName());
        assertTrue(NetworkAddressUtil.isUsable(byName.getAddress()));

        NetworkAddressUtil.Candidate byAddress = NetworkAddressUtil.resolve(first.getAddress().getHostAddress());
        assertEquals(first.getAddress(), byAddress.getAddress());
        assertEquals(first.getNicName(), byAddress.getNicName());
    }

    @Test
    @DisplayName("自动枚举的不变式：地址都可用、都来自 up 且支持组播的非回环网卡、结果有序")
    void enumerateHonoursItsContract() throws Exception {
        List<NetworkAddressUtil.Candidate> candidates = NetworkAddressUtil.enumerate();

        for (NetworkAddressUtil.Candidate candidate : candidates) {
            assertTrue(NetworkAddressUtil.isUsable(candidate.getAddress()),
                    "unusable address leaked into candidates: " + candidate);
            assertNotNull(candidate.getNic(), "real candidates must carry their interface");
            assertTrue(candidate.getNic().isUp(), "candidate must come from an up interface");
            assertFalse(candidate.getNic().isLoopback(), "loopback must not be auto-selected");
        }

        List<NetworkAddressUtil.Candidate> resorted = new ArrayList<>(candidates);
        resorted.sort(NetworkAddressUtil.CANDIDATE_ORDER);
        assertEquals(toStrings(candidates), toStrings(resorted), "enumerate() must return a sorted list");
    }

    private static boolean hasLoopbackInterface() throws SocketException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InterfaceAddress interfaceAddress : nic.getInterfaceAddresses()) {
                if (interfaceAddress.getAddress() instanceof Inet4Address address
                        && address.isLoopbackAddress()) {
                    return true;
                }
            }
        }
        return false;
    }
}
