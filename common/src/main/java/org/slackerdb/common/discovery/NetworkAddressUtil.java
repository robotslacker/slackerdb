package org.slackerdb.common.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 组播网卡的选择工具：把配置里的 {@code advertise_nic} / {@code listener_nic} 解析成
 * "一块具体的网卡 + 一个具体的 IPv4 地址"。
 *
 * <h3>为什么要绕这一圈，而不是直接取"第一个网卡"</h3>
 * <p>
 * {@link NetworkInterface#getNetworkInterfaces()} 的枚举顺序是<b>未定义</b>的：同一台机器
 * 两次启动可能给出不同顺序，Windows 上尤其明显。而 {@code advertise_nic} 不配置时的语义是
 * "自动选一块"——如果这个"自动"每次都挑中不同的网卡（比如这次是物理网卡、下次是 Hyper-V 的
 * vEthernet），表现就是"组播时通时不通"，而且极难复现。
 * </p>
 * <p>
 * 所以这里的"自动"不是"枚举到的第一个"，而是一套<b>确定性排序</b>：
 * </p>
 * <ol>
 *   <li>疑似虚拟网卡（docker/veth/vEthernet/VMware/VirtualBox/隧道…）排在后面；</li>
 *   <li>私网地址（RFC1918）排在公网地址前面——组播发现就是局域网内的事；</li>
 *   <li>网卡名（不区分大小写）字典序；</li>
 *   <li>地址数值（无符号）升序。</li>
 * </ol>
 * <p>
 * 同样的输入永远得到同样的结果。选择结果必须打日志，用户也可以直接用网卡名或本机 IP 显式指定。
 * </p>
 *
 * <h3>过滤规则</h3>
 * <p>
 * 自动候选必须同时满足：网卡 up、非 loopback、支持组播，且有一个非 loopback、非 link-local
 * （169.254.0.0/16）、非 0.0.0.0 的 IPv4 地址。<b>显式点名时不受这些规则限制</b>——用户说要
 * 用哪块就用哪块，包括回环网卡（本机联调组播时用得上）。
 * </p>
 */
public final class NetworkAddressUtil {

    /** 名字以这些前缀开头的网卡被视为"疑似虚拟"，只在自动选择时降权，不参与显式点名。 */
    private static final String[] VIRTUAL_NAME_PREFIXES = {
            "docker", "veth", "br-", "virbr", "vmnet", "vboxnet",
            "utun", "tun", "tap", "vethernet", "virtualbox", "vmware",
            "hyper-v", "loopback", "bluetooth", "wsl", "tailscale", "npcap",
    };

    private NetworkAddressUtil() {
    }

    /**
     * 一个候选地址：某块网卡上的一个可用 IPv4。
     *
     * <p>之所以把它和 {@link NetworkInterface} 解耦，是因为后者没有任何可用的公开构造方式
     * （枚举只有静态方法、依赖真实机器），排序规则若与它绑死就无法单测。生产路径
     * （{@link #enumerate()}）会填好 {@code nic}；单测自行构造时 {@code nic} 为 {@code null}，
     * 只参与排序断言。</p>
     */
    public static final class Candidate {
        private final NetworkInterface nic;
        private final String nicName;
        private final Inet4Address address;
        private final boolean suspiciousVirtual;

        Candidate(NetworkInterface nic, String nicName, Inet4Address address, boolean suspiciousVirtual) {
            this.nic = nic;
            this.nicName = nicName;
            this.address = address;
            this.suspiciousVirtual = suspiciousVirtual;
        }

        /** 真实枚举得到的网卡对象；单测构造的候选为 {@code null}。 */
        public NetworkInterface getNic() {
            return nic;
        }

        public String getNicName() {
            return nicName;
        }

        public Inet4Address getAddress() {
            return address;
        }

        /** 名字像虚拟网卡（或网卡自报 virtual / point-to-point）。 */
        public boolean isSuspiciousVirtual() {
            return suspiciousVirtual;
        }

        @Override
        public String toString() {
            return nicName + "/" + address.getHostAddress();
        }
    }

    /**
     * 自动选择的排序规则。抽成包级常量是为了能用"手工构造的候选"单测——
     * 真实网卡的枚举结果不可控，没法靠断言"这台机器选出来的是 eth0"来验证规则。
     */
    static final Comparator<Candidate> CANDIDATE_ORDER = (a, b) -> {
        int byVirtual = Boolean.compare(a.suspiciousVirtual, b.suspiciousVirtual);
        if (byVirtual != 0) {
            return byVirtual;
        }
        int byScope = Boolean.compare(!a.address.isSiteLocalAddress(), !b.address.isSiteLocalAddress());
        if (byScope != 0) {
            return byScope;
        }
        int byName = a.nicName.compareToIgnoreCase(b.nicName);
        if (byName != 0) {
            return byName;
        }
        return Integer.compareUnsigned(addressKey(a.address), addressKey(b.address));
    };

    /** 同一块网卡内部多个 IPv4 之间的取舍：私网优先，然后按数值升序，保证可复现。 */
    static final Comparator<Inet4Address> ADDRESS_ORDER = (a, b) -> {
        int byScope = Boolean.compare(!a.isSiteLocalAddress(), !b.isSiteLocalAddress());
        if (byScope != 0) {
            return byScope;
        }
        return Integer.compareUnsigned(addressKey(a), addressKey(b));
    };

    /**
     * 枚举本机所有"可用"的候选地址，并按要求排序。取第一个即为自动选择的结果。
     */
    public static List<Candidate> enumerate() throws SocketException {
        List<Candidate> candidates = new ArrayList<>();
        for (NetworkInterface nic : allInterfaces()) {
            if (!nic.isUp() || nic.isLoopback() || !nic.supportsMulticast()) {
                continue;
            }
            boolean suspicious = isSuspiciousVirtualName(nic.getName())
                    || nic.isVirtual() || nic.isPointToPoint();
            for (InterfaceAddress interfaceAddress : nic.getInterfaceAddresses()) {
                if (interfaceAddress.getAddress() instanceof Inet4Address address && isUsable(address)) {
                    candidates.add(new Candidate(nic, nic.getName(), address, suspicious));
                }
            }
        }
        candidates.sort(CANDIDATE_ORDER);
        return candidates;
    }

    /**
     * 自动选择一块网卡（{@code advertise_nic} 留空时的行为）。
     *
     * @throws IllegalArgumentException 本机找不到任何可用网卡（消息里说明需要什么样的网卡）
     * @throws SocketException          操作系统层面查询网卡失败
     */
    public static Candidate autoSelect() throws SocketException {
        List<Candidate> candidates = enumerate();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "No usable network interface found for multicast. An interface must be up, "
                            + "non-loopback, multicast-capable and carry a non-link-local IPv4 address. "
                            + "Set the interface explicitly by name or by local IP address.");
        }
        return candidates.get(0);
    }

    /**
     * 解析 {@code advertise_nic} / {@code listener_nic} 的取值。
     *
     * <p>取值可以是网卡名（{@code eth0}、{@code Ethernet}）、网卡的显示名
     * （{@code Intel(R) Ethernet Connection}），或本机某个 IPv4 地址；留空表示自动选择。</p>
     *
     * @throws IllegalArgumentException 取值非空但匹配不到任何本机网卡
     * @throws SocketException          操作系统层面查询网卡失败
     */
    public static Candidate resolve(String specification) throws SocketException {
        if (specification == null || specification.trim().isEmpty()) {
            return autoSelect();
        }
        String wanted = specification.trim();

        // 1) 按网卡名 / 显示名匹配。显式点名时不套用自动选择的过滤规则：
        //    用户说要用 vEthernet 或 lo，就给他用，否则"配了却说不存在"更让人费解。
        for (NetworkInterface nic : allInterfaces()) {
            if (wanted.equalsIgnoreCase(nic.getName()) || wanted.equalsIgnoreCase(safeDisplayName(nic))) {
                Inet4Address address = pickAddress(nic);
                if (address == null) {
                    throw new IllegalArgumentException("Network interface [" + wanted
                            + "] has no usable IPv4 address.");
                }
                return toCandidate(nic, address);
            }
        }

        // 2) 按本机 IP 匹配
        InetAddress literal = parseLiteral(wanted);
        if (literal instanceof Inet4Address wanted4) {
            for (NetworkInterface nic : allInterfaces()) {
                for (InterfaceAddress interfaceAddress : nic.getInterfaceAddresses()) {
                    if (wanted4.equals(interfaceAddress.getAddress())) {
                        return toCandidate(nic, wanted4);
                    }
                }
            }
            throw new IllegalArgumentException("IPv4 address [" + wanted
                    + "] does not belong to any local network interface.");
        }

        // 3) 都不匹配
        throw new IllegalArgumentException("Network interface [" + specification
                + "] not found. Provide an interface name (for example eth0 or Ethernet) "
                + "or one of this machine's local IPv4 addresses.");
    }

    /**
     * 为组播通告挑选本机地址，按以下优先级：
     * <ol>
     *   <li>{@code nicSpec} 非空：完全按用户指定（网卡名或本机 IP）；</li>
     *   <li>{@code bindAddress} 是一个<b>具体的</b>本机 IP（不是 {@code 0.0.0.0} / {@code ::} / {@code *} / 空）：
     *       就用它。服务只监听这一个地址，通告别的地址只会让代理拿到一个连不上的目标；</li>
     *   <li>否则自动选择一块可用网卡。</li>
     * </ol>
     *
     * <p>注意本方法只做 IPv4 选择：通告报文里的 {@code host} 与 {@code bind} 都是 IPv4 语义。</p>
     *
     * @param nicSpec     {@code advertise_nic} 的取值，可为空
     * @param bindAddress {@code bind} 的取值，可为空
     * @throws IllegalArgumentException 取值非空但匹配不到本机网卡，或本机没有可用网卡
     * @throws SocketException          操作系统层面查询网卡失败
     */
    public static Candidate resolveForAdvertise(String nicSpec, String bindAddress) throws SocketException {
        if (nicSpec != null && !nicSpec.trim().isEmpty()) {
            return resolve(nicSpec);
        }
        if (isConcreteAddress(bindAddress)) {
            return resolve(bindAddress.trim());
        }
        return autoSelect();
    }

    /**
     * 取值是否是一个"具体的"本机 IPv4 地址：排除空、通配绑定地址（{@code 0.0.0.0} / {@code ::} / {@code *}）
     * 与主机名。
     */
    public static boolean isConcreteAddress(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return false;
        }
        if ("0.0.0.0".equals(trimmed) || "::".equals(trimmed)
                || "0:0:0:0:0:0:0:0".equals(trimmed)) {
            return false;
        }
        return isIpv4Literal(trimmed);
    }

    /**
     * 接收组播时应当加入组的网卡列表：所有 up 且支持组播的网卡。
     *
     * <p>与发送方不同，接收端<b>刻意不做"挑一块"</b>——代理并不知道 dbserver 会用哪块网卡发，
     * 而多加入一个 membership 的代价只是可能收到重复报文（接收逻辑本来就是幂等的）。
     * 漏加入一块网卡则表现为"某台机器永远发现不了"。</p>
     *
     * <p>回环网卡也被包含在内：这样把 {@code advertise_nic} 配成 {@code 127.0.0.1} 的本机联调
     * 才能真正收到报文。</p>
     */
    public static List<NetworkInterface> multicastReceiveInterfaces() throws SocketException {
        List<NetworkInterface> result = new ArrayList<>();
        for (NetworkInterface nic : allInterfaces()) {
            if (nic.isUp() && nic.supportsMulticast()) {
                result.add(nic);
            }
        }
        return result;
    }

    private static List<NetworkInterface> allInterfaces() throws SocketException {
        Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
        if (enumeration == null) {
            return Collections.emptyList();
        }
        return Collections.list(enumeration);
    }

    private static Candidate toCandidate(NetworkInterface nic, Inet4Address address) throws SocketException {
        boolean suspicious = isSuspiciousVirtualName(nic.getName())
                || nic.isVirtual() || nic.isPointToPoint();
        return new Candidate(nic, nic.getName(), address, suspicious);
    }

    /**
     * 在一块网卡上挑一个 IPv4：优先"可用"地址（见 {@link #isUsable}）；
     * 若一个都没有但网卡本身是回环网卡，则允许回环地址（本机联调组播用）。
     */
    static Inet4Address pickAddress(NetworkInterface nic) throws SocketException {
        List<Inet4Address> usable = new ArrayList<>();
        Inet4Address loopback = null;
        for (InterfaceAddress interfaceAddress : nic.getInterfaceAddresses()) {
            if (interfaceAddress.getAddress() instanceof Inet4Address address) {
                if (isUsable(address)) {
                    usable.add(address);
                } else if (address.isLoopbackAddress()) {
                    loopback = address;
                }
            }
        }
        if (!usable.isEmpty()) {
            usable.sort(ADDRESS_ORDER);
            return usable.get(0);
        }
        return loopback;
    }

    /** 自动候选可接受的地址：IPv4，且既不是回环、不是 link-local、不是 0.0.0.0、也不是组播。 */
    static boolean isUsable(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        return !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isAnyLocalAddress()
                && !address.isMulticastAddress();
    }

    /** 网卡名是否像一块虚拟/隧道网卡（只在自动选择时用于降权）。 */
    static boolean isSuspiciousVirtualName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : VIRTUAL_NAME_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 把字符串当 IP 字面量解析；不是字面量（含主机名）时返回 {@code null}，绝不做 DNS 查询。 */
    static InetAddress parseLiteral(String value) {
        try {
            if (isIpv4Literal(value)) {
                return InetAddress.getByName(value);
            }
            if (value != null && value.indexOf(':') >= 0) {
                return InetAddress.getByName(value);
            }
        } catch (UnknownHostException ignored) {
            // 落空即"不是本机地址"
        }
        return null;
    }

    /**
     * 严格的 IPv4 点分四段字面量判定。
     *
     * <p>不能直接用 {@code InetAddress.getByName} 的宽松行为：它会把 {@code 1.2.3} 当成
     * {@code 1.2.0.3}，这些都是"配置写错了却不报错"的来源。</p>
     */
    public static boolean isIpv4Literal(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static String safeDisplayName(NetworkInterface nic) {
        String displayName = nic.getDisplayName();
        return displayName == null ? "" : displayName;
    }

    /** 供排序用的地址数值（按无符号解释），避免有类地址（>=128.x）被当成负数。 */
    static int addressKey(Inet4Address address) {
        byte[] bytes = address.getAddress();
        return ((bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }
}
