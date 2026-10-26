package org.slackerdb.common.discovery;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Announcement} 的验收：往返不丢字段，脏报文一律被挡在解析层。
 *
 * <h3>为什么"拒绝"比"接受"更重要</h3>
 * <p>
 * 接收端是一个<b>对全子网开放</b>的 UDP 端口：任何人都能往里丢字节，同网段也能伪造一条
 * 看起来完全合法的 HELLO。所以解析层必须做到"不认识的输入丢出去，而不是崩掉或者写进一个
 * 错误的目标"。下面每个拒绝用例都对应一类会被丢进来的东西：别的程序的广播、
 * 版本不一致的旧/新节点、被人手工敲错的 JSON、以及明显是攻击的超大报文。
 * </p>
 *
 * <p>断言消息用英文：surefire 在中文控制台上会把非 ASCII 打成乱码。</p>
 */
class AnnouncementTest {

    private static Announcement decode(String json) {
        return Announcement.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 正常往返

    @Test
    @DisplayName("HELLO 往返：所有字段一个不丢")
    void helloRoundTrip() {
        Announcement sent = Announcement.hello("mydb", "mydb-alias", "192.168.1.10", 5432, 8000,
                "0.2.2", 137);
        Announcement received = Announcement.decode(sent.encode());

        assertTrue(received.isHello());
        assertFalse(received.isBye());
        assertEquals(Announcement.TYPE_HELLO, received.getType());
        assertEquals("mydb", received.getName());
        assertEquals("mydb-alias", received.getAlias());
        assertEquals("192.168.1.10", received.getHost());
        assertEquals(5432, received.getPort());
        assertEquals(8000, received.getPortX());
        assertEquals("0.2.2", received.getVersion());
        assertEquals(137, received.getSeq());
        assertTrue(received.getTimestamp() > 0, "timestamp must be stamped on construction");
    }

    @Test
    @DisplayName("BYE 往返：只需要库名与序号")
    void byeRoundTrip() {
        Announcement sent = Announcement.bye("mydb", 138);
        Announcement received = Announcement.decode(sent.encode());

        assertTrue(received.isBye());
        assertFalse(received.isHello());
        assertEquals("mydb", received.getName());
        assertEquals("mydb", received.getAlias(), "BYE 按别名摘除，别名缺省等于库名");
        assertEquals(138, received.getSeq());
    }

    @Test
    @DisplayName("portX = -1 原样保留：与旧 REGISTER 报文里的 -1 语义一致")
    void keepsDisabledPortX() {
        Announcement received = Announcement.decode(
                Announcement.hello("mydb", "mydb", "10.0.0.5", 5432, -1, "", 1).encode());
        assertEquals(Announcement.PORT_X_DISABLED, received.getPortX());
    }

    @Test
    @DisplayName("别名为空时缺省等于库名：保证客户端连接串不用改")
    void aliasDefaultsToName() {
        assertEquals("mydb", Announcement.hello("mydb", "", "10.0.0.5", 5432, -1, "", 1).getAlias());
        assertEquals("mydb", Announcement.hello("mydb", null, "10.0.0.5", 5432, -1, "", 1).getAlias());
        assertEquals("mydb", decode("{\"sd\":1,\"type\":\"HELLO\",\"name\":\"mydb\","
                + "\"host\":\"10.0.0.5\",\"port\":5432}").getAlias());
    }

    @Test
    @DisplayName("报文足够小：一个 UDP 报文装得下，不需要分片")
    void staysWithinOneDatagram() {
        int size = Announcement.hello("a-very-long-database-name-for-testing", "alias", "192.168.100.100",
                5432, 8000, "0.2.2", Long.MAX_VALUE).encode().length;
        assertTrue(size < 400, "announcement should stay well below the datagram limit, was " + size);
    }

    @Test
    @DisplayName("未知字段被忽略：新版本多发字段不应该让老版本丢弃整条报文")
    void toleratesUnknownFields() {
        Announcement received = decode("{\"sd\":1,\"type\":\"HELLO\",\"name\":\"mydb\","
                + "\"host\":\"10.0.0.5\",\"port\":5432,\"future\":\"whatever\"}");
        assertEquals("mydb", received.getName());
        assertEquals(5432, received.getPort());
    }

    @Test
    @DisplayName("toString 带上排障需要的字段")
    void toStringCarriesDiagnostics() {
        String text = Announcement.hello("mydb", "mydb", "192.168.1.10", 5432, 8000, "", 7).toString();
        assertTrue(text.contains("mydb"), text);
        assertTrue(text.contains("192.168.1.10"), text);
        assertTrue(text.contains("5432"), text);
        assertTrue(Announcement.bye("mydb", 8).toString().contains("BYE"));
    }

    // ------------------------------------------------------------------ 构造期校验

    @Test
    @DisplayName("构造 HELLO：库名/地址为空、端口越界直接拒绝")
    void helloRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class,
                () -> Announcement.hello("", "a", "10.0.0.5", 5432, -1, "", 1));
        assertThrows(IllegalArgumentException.class,
                () -> Announcement.hello("mydb", "a", "", 5432, -1, "", 1));
        assertThrows(IllegalArgumentException.class,
                () -> Announcement.hello("mydb", "a", "10.0.0.5", 0, -1, "", 1));
        assertThrows(IllegalArgumentException.class,
                () -> Announcement.hello("mydb", "a", "10.0.0.5", 65536, -1, "", 1));
        assertThrows(IllegalArgumentException.class,
                () -> Announcement.hello("mydb", "a", "10.0.0.5", 5432, -2, "", 1));
    }

    @Test
    @DisplayName("构造 BYE：库名为空直接拒绝")
    void byeRejectsEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> Announcement.bye("", 1));
        assertThrows(IllegalArgumentException.class, () -> Announcement.bye(null, 1));
    }

    // ------------------------------------------------------------------ 解析期校验

    @Test
    @DisplayName("空报文与超大报文被拒：前者是噪声，后者多半是攻击")
    void rejectsEmptyAndOversized() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> Announcement.decode(new byte[0]));
        assertTrue(empty.getMessage().contains("Empty"), empty.getMessage());

        IllegalArgumentException oversized = assertThrows(IllegalArgumentException.class,
                () -> Announcement.decode(new byte[Announcement.MAX_DATAGRAM_BYTES + 1]));
        assertTrue(oversized.getMessage().contains("too large"), oversized.getMessage());
    }

    @Test
    @DisplayName("非 JSON / 非对象 / 别的程序的广播都被拒")
    void rejectsNonAnnouncements() {
        for (String payload : new String[]{"not json at all", "{\"hello\":", "[1,2,3]", "12345",
                "{\"some\":\"other packet\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> decode(payload),
                    "payload must be rejected: " + payload);
        }
    }

    @Test
    @DisplayName("协议版本不认识就丢弃，而不是按老规则猜着解析")
    void rejectsUnknownProtocolVersion() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> decode("{\"type\":\"HELLO\",\"name\":\"mydb\"}"));
        assertTrue(missing.getMessage().contains("[sd]"), missing.getMessage());

        IllegalArgumentException wrongVersion = assertThrows(IllegalArgumentException.class,
                () -> decode("{\"sd\":99,\"type\":\"HELLO\",\"name\":\"mydb\"}"));
        assertTrue(wrongVersion.getMessage().contains("99"), wrongVersion.getMessage());
        assertTrue(wrongVersion.getMessage().contains("Unsupported"), wrongVersion.getMessage());
    }

    @Test
    @DisplayName("类型不认识 / 缺库名被拒")
    void rejectsBadTypeAndName() {
        IllegalArgumentException badType = assertThrows(IllegalArgumentException.class,
                () -> decode("{\"sd\":1,\"type\":\"PING\",\"name\":\"mydb\"}"));
        assertTrue(badType.getMessage().contains("PING"), badType.getMessage());

        IllegalArgumentException noName = assertThrows(IllegalArgumentException.class,
                () -> decode("{\"sd\":1,\"type\":\"HELLO\",\"host\":\"10.0.0.5\",\"port\":5432}"));
        assertTrue(noName.getMessage().contains("[name]"), noName.getMessage());
    }

    @Test
    @DisplayName("HELLO 缺 host / port 非法被拒：不能拿一条无法选路的报文去改代理状态")
    void rejectsIncompleteHello() {
        IllegalArgumentException noHost = assertThrows(IllegalArgumentException.class,
                () -> decode("{\"sd\":1,\"type\":\"HELLO\",\"name\":\"mydb\",\"port\":5432}"));
        assertTrue(noHost.getMessage().contains("[host]"), noHost.getMessage());

        for (String port : new String[]{"0", "-1", "65536", "abc"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> decode("{\"sd\":1,\"type\":\"HELLO\",\"name\":\"mydb\","
                            + "\"host\":\"10.0.0.5\",\"port\":" + port + "}"),
                    "port must be rejected: " + port);
        }
    }

    @Test
    @DisplayName("BYE 不要求 host/port：停止时数据库可能已经不可用了")
    void byeNeedsNoEndpoint() {
        Announcement received = decode("{\"sd\":1,\"type\":\"BYE\",\"name\":\"mydb\"}");
        assertTrue(received.isBye());
        assertEquals("mydb", received.getName());
    }

    @Test
    @DisplayName("编码结果是紧凑 JSON 且带 sd 标记：便于抓包肉眼确认")
    void encodeIsCompactAndSelfDescribing() {
        JSONObject json = JSON.parseObject(
                new String(Announcement.hello("mydb", "mydb", "10.0.0.5", 5432, -1, "", 1).encode(),
                        StandardCharsets.UTF_8));
        assertEquals(Announcement.PROTOCOL_VERSION, json.getIntValue("sd"));
        assertEquals("HELLO", json.getString("type"));
        assertFalse(json.containsKey("ver"), "empty optional fields must be omitted to keep packets small");
    }
}
