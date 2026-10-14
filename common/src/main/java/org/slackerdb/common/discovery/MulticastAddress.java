package org.slackerdb.common.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 组播地址（组 IP + 端口）的不可变值对象，形如 {@code 239.255.66.66:46666}；
 * IPv6 必须写成带方括号的形式 {@code [ff02::1]:46666}。
 *
 * <h3>为什么要有这个类，而不是直接存字符串</h3>
 * <p>
 * 组播地址是 dbserver 与 dbproxy 之间<b>唯一</b>的会合点：两端各自配置，配置不一致时
 * 不会报任何错，只会"什么都发现不了"。而且按设计<b>没有默认值</b>——不配置就是不启用。
 * 因此这个字符串必须在校验上尽可能严格、在报错上尽可能具体：把
 * {@code 192.168.1.1:46666}（不是组播地址）、{@code 239.255.66.66}（缺端口）、
 * {@code ff02::1:46666}（缺方括号）这类配置错误在<b>进程启动时</b>就打回去，
 * 而不是留给运维去抓包。
 * </p>
 *
 * <h3>刻意不做的事</h3>
 * <ul>
 *   <li><b>不接受主机名。</b>组地址必须是数字字面量。否则 {@code getByName} 会去做 DNS 查询
 *       （可能阻塞数秒），而且"主机名解析成组播地址"本身没有意义。</li>
 *   <li><b>不接受 IPv4 简写。</b>{@code 1.2.3:46666} 在 {@code InetAddress.getByName} 里会被
 *       解释成 {@code 1.2.0.3}，属于静默变形，直接拒绝。</li>
 *   <li><b>不接受 224.0.0.0/24。</b>这一段是"本地网络控制"保留段（224.0.0.1 是全主机组等），
 *       RFC 规定不转发，配到这里只会得到"看起来配了却没人收到"的效果。</li>
 * </ul>
 *
 * <h3>关于 IPv6</h3>
 * <p>
 * 这里接受 IPv6 组地址（{@code [ff02::1]:46666}），是为了将来扩展时不必改配置格式。
 * 但当前的服务发现<b>只做 IPv4 的本机地址选择</b>（见 {@link NetworkAddressUtil}），
 * 因此用 IPv6 组播组时，报文里通告的服务端点仍然是 IPv4 地址。
 * </p>
 */
public final class MulticastAddress {
    private final InetAddress group;
    private final int port;

    private MulticastAddress(InetAddress group, int port) {
        this.group = group;
        this.port = port;
    }

    /**
     * 解析 {@code <组IP>:<端口>}。
     *
     * @param value 配置值
     * @return 解析后的组播地址
     * @throws IllegalArgumentException 取值非法（空、缺端口、端口越界、非组播地址、主机名等），
     *                                  消息里带上了具体原因与正确示例，可直接展示给用户
     */
    public static MulticastAddress parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Multicast address is empty. Expected <group>:<port>, for example 239.255.66.66:46666.");
        }

        String text = value.trim();
        String host;
        int port;

        if (text.startsWith("[")) {
            // IPv6 字面量：必须以 ']' 收口，且其后紧跟 ':<port>'
            int close = text.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException(
                        "Multicast address [" + text + "] is missing the closing ']'. "
                                + "Expected [<group>]:<port>, for example [ff02::1]:46666.");
            }
            host = text.substring(1, close);
            String rest = text.substring(close + 1);
            if (!rest.startsWith(":")) {
                throw new IllegalArgumentException(
                        "Multicast address [" + text + "] is missing the port. "
                                + "Expected [<group>]:<port>, for example [ff02::1]:46666.");
            }
            port = parsePort(rest.substring(1), text);
        } else {
            int lastColon = text.lastIndexOf(':');
            if (lastColon <= 0 || lastColon == text.length() - 1) {
                throw new IllegalArgumentException(
                        "Multicast address [" + text + "] is missing the port. "
                                + "Expected <group>:<port>, for example 239.255.66.66:46666.");
            }
            if (text.indexOf(':') != lastColon) {
                // 出现多个 ':' 说明用户写的是裸 IPv6，歧义无法消解
                throw new IllegalArgumentException(
                        "IPv6 multicast address [" + text + "] must be wrapped in brackets, "
                                + "for example [ff02::1]:46666.");
            }
            host = text.substring(0, lastColon);
            port = parsePort(text.substring(lastColon + 1), text);
        }

        return new MulticastAddress(parseGroup(host, text), port);
    }

    private static int parsePort(String rawPort, String original) {
        String trimmed = rawPort.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                throw new IllegalArgumentException(
                        "Multicast port [" + rawPort + "] in [" + original + "] is not a number.");
            }
        }
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException(
                    "Multicast port [" + rawPort + "] in [" + original + "] is not a number.");
        }
        if (parsed < 1 || parsed > 65535) {
            throw new IllegalArgumentException(
                    "Multicast port [" + parsed + "] in [" + original + "] is out of range 1-65535.");
        }
        return parsed;
    }

    private static InetAddress parseGroup(String host, String original) {
        if (!NetworkAddressUtil.isIpv4Literal(host) && host.indexOf(':') < 0) {
            throw new IllegalArgumentException(
                    "Multicast group [" + host + "] in [" + original + "] must be a numeric address, "
                            + "not a host name. For example 239.255.66.66:46666.");
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(
                    "Multicast group [" + host + "] in [" + original + "] is not a valid IP address.", e);
        }

        if (!address.isMulticastAddress()) {
            throw new IllegalArgumentException(
                    "Address [" + host + "] in [" + original + "] is not a multicast group. "
                            + "IPv4 groups must be inside 224.0.0.0/4 (for example 239.255.66.66), "
                            + "IPv6 groups inside ff00::/8.");
        }

        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            if ((bytes[0] & 0xFF) == 224 && (bytes[1] & 0xFF) == 0 && (bytes[2] & 0xFF) == 0) {
                throw new IllegalArgumentException(
                        "Address [" + host + "] in [" + original + "] is inside 224.0.0.0/24, "
                                + "which is reserved for local network control and is never forwarded. "
                                + "Pick an address from 239.0.0.0/8 instead.");
            }
        }

        return address;
    }

    public InetAddress getGroup() {
        return group;
    }

    public int getPort() {
        return port;
    }

    /** 组 IP 的文本形式（不含端口）。 */
    public String getGroupHost() {
        return group.getHostAddress();
    }

    /** 规范化后的文本：IPv4 为 {@code 239.255.66.66:46666}，IPv6 为 {@code [ff02::1]:46666}。 */
    @Override
    public String toString() {
        String host = getGroupHost();
        if (group instanceof Inet4Address) {
            return host + ":" + port;
        }
        return "[" + host + "]:" + port;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MulticastAddress that)) {
            return false;
        }
        return port == that.port && group.equals(that.group);
    }

    @Override
    public int hashCode() {
        return 31 * group.hashCode() + port;
    }
}
