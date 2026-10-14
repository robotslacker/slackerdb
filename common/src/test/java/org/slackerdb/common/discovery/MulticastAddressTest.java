package org.slackerdb.common.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MulticastAddress} 的验收：合法的要能解析，非法的要在启动阶段就被挡下来。
 *
 * <h3>为什么把"拒绝"测得这么细</h3>
 * <p>
 * 组播地址是 dbserver 与 dbproxy 之间唯一的会合点，而且按设计<b>没有默认值</b>。
 * 两端配置写得不一致时，程序不会报任何错——只是"什么都发现不了"，运维只能去抓包。
 * 所以凡是能在解析阶段判定为错的写法，都必须在这里变成一条带具体原因的启动期报错。
 * 下面每组用例都对应一种真实会被写出来的错误配置。
 * </p>
 *
 * <p>断言消息用英文：surefire 在中文控制台上会把非 ASCII 打成乱码，而失败信息正是最需要看清的部分。</p>
 */
class MulticastAddressTest {

    @Test
    @DisplayName("合法 IPv4 组地址：解析出组 IP 与端口，toString 原样还原")
    void parsesIpv4Group() {
        MulticastAddress address = MulticastAddress.parse("239.255.66.66:46666");

        assertEquals("239.255.66.66", address.getGroupHost());
        assertEquals(46666, address.getPort());
        assertEquals("239.255.66.66:46666", address.toString());
        assertTrue(address.getGroup().isMulticastAddress(), "the parsed group must be multicast");
    }

    @Test
    @DisplayName("取值两端的空白被忽略：从 conf 里读出来的字符串常带着空格")
    void ignoresSurroundingWhitespace() {
        MulticastAddress address = MulticastAddress.parse("  239.255.66.66:46666  ");
        assertEquals("239.255.66.66", address.getGroupHost());
        assertEquals(46666, address.getPort());
    }

    @Test
    @DisplayName("合法 IPv6 组地址：必须带方括号，端口与组地址都能取到")
    void parsesIpv6Group() throws Exception {
        MulticastAddress address = MulticastAddress.parse("[ff02::1]:46666");

        assertEquals(46666, address.getPort());
        assertTrue(address.getGroup().isMulticastAddress(), "the parsed group must be multicast");
        assertEquals(InetAddress.getByName("ff02::1").getHostAddress(), address.getGroupHost());
        assertTrue(address.toString().startsWith("["), "IPv6 form must be bracketed: " + address);
        assertTrue(address.toString().endsWith("]:46666"), "IPv6 form must be bracketed: " + address);
    }

    @Test
    @DisplayName("边界端口 1 与 65535 合法")
    void acceptsBoundaryPorts() {
        assertEquals(1, MulticastAddress.parse("239.255.66.66:1").getPort());
        assertEquals(65535, MulticastAddress.parse("239.255.66.66:65535").getPort());
    }

    @Test
    @DisplayName("224.0.1.0 这类非保留段组地址合法（只有 224.0.0.0/24 被拒）")
    void acceptsMulticastOutsideReservedControlBlock() {
        assertEquals("224.0.1.0", MulticastAddress.parse("224.0.1.0:46666").getGroupHost());
    }

    @Test
    @DisplayName("空值：提示必须形如 <组IP>:<端口>")
    void rejectsEmpty() {
        for (String empty : new String[]{null, "", "   "}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> MulticastAddress.parse(empty));
            assertTrue(e.getMessage().contains("empty"), e.getMessage());
            assertTrue(e.getMessage().contains("239.255.66.66:46666"),
                    "the message must show a correct example: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("缺端口：239.255.66.66 与 239.255.66.66: 都被拒")
    void rejectsMissingPort() {
        for (String value : new String[]{"239.255.66.66", "239.255.66.66:"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> MulticastAddress.parse(value));
            assertTrue(e.getMessage().contains("missing the port"), e.getMessage());
        }
    }

    @Test
    @DisplayName("端口非数字 / 越界：0、65536、abc 都被拒")
    void rejectsBadPort() {
        IllegalArgumentException notNumber = assertThrows(IllegalArgumentException.class,
                () -> MulticastAddress.parse("239.255.66.66:abc"));
        assertTrue(notNumber.getMessage().contains("not a number"), notNumber.getMessage());

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> MulticastAddress.parse("239.255.66.66:0"));
        assertTrue(zero.getMessage().contains("out of range"), zero.getMessage());

        IllegalArgumentException tooLarge = assertThrows(IllegalArgumentException.class,
                () -> MulticastAddress.parse("239.255.66.66:65536"));
        assertTrue(tooLarge.getMessage().contains("out of range"), tooLarge.getMessage());
    }

    @Test
    @DisplayName("单播地址：192.168.1.1:46666 被拒，并说明组地址范围")
    void rejectsUnicastAddress() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MulticastAddress.parse("192.168.1.1:46666"));
        assertTrue(e.getMessage().contains("not a multicast group"), e.getMessage());
        assertTrue(e.getMessage().contains("224.0.0.0/4"), e.getMessage());
    }

    @Test
    @DisplayName("本地控制保留段：224.0.0.1:46666 被拒（配到这里只会静默收不到）")
    void rejectsLocalNetworkControlBlock() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MulticastAddress.parse("224.0.0.1:46666"));
        assertTrue(e.getMessage().contains("224.0.0.0/24"), e.getMessage());
    }

    @Test
    @DisplayName("裸 IPv6：ff02::1:46666 被拒，并提示要加方括号")
    void rejectsUnbracketedIpv6() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MulticastAddress.parse("ff02::1:46666"));
        assertTrue(e.getMessage().contains("brackets"), e.getMessage());
    }

    @Test
    @DisplayName("方括号没闭合：[ff02::1:46666 被拒")
    void rejectsUnclosedBracket() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MulticastAddress.parse("[ff02::1:46666"));
        assertTrue(e.getMessage().contains("']'"), e.getMessage());
    }

    @Test
    @DisplayName("主机名被拒：不做 DNS 查询，也不接受非数字组地址")
    void rejectsHostName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MulticastAddress.parse("example.com:46666"));
        assertTrue(e.getMessage().contains("numeric address"), e.getMessage());
    }

    @Test
    @DisplayName("IPv4 简写与越界段被拒：1.2.3 与 239.255.66.256 都不能静默变形")
    void rejectsLooseIpv4Forms() {
        for (String value : new String[]{"1.2.3:46666", "239.255.66.256:46666", "239.255.66.66.1:46666"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> MulticastAddress.parse(value));
            assertTrue(e.getMessage().contains("numeric address"), value + " -> " + e.getMessage());
        }
    }

    @Test
    @DisplayName("值对象语义：相同取值相等且 hashCode 一致，不同取值不相等")
    void valueSemantics() {
        MulticastAddress first = MulticastAddress.parse("239.255.66.66:46666");
        MulticastAddress second = MulticastAddress.parse("239.255.66.66:46666");
        MulticastAddress other = MulticastAddress.parse("239.255.66.66:46667");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, other);
    }
}
