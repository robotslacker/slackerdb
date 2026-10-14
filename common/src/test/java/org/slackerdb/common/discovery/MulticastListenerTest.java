package org.slackerdb.common.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MulticastListener} 的验收：只把"确实是我们的报文"投递出去，其余一律丢弃且不崩。
 *
 * <h3>为什么全部用直接投喂，而不真的加入组播组</h3>
 * <p>
 * 组播在回环网卡上不可靠（Windows 上尤其），把它写进单元测试只会得到一个"在某人机器上偶尔
 * 失败"的用例。而接收逻辑里真正容易出错的部分——脏报文、版本不一致、回调抛异常、
 * 统计计数——全都与 socket 无关，可以直接对 {@code handleDatagram} 投喂字节来覆盖。
 * 真实收发由端到端测试负责。
 * </p>
 *
 * <p>断言消息用英文：surefire 在中文控制台上会把非 ASCII 打成乱码。</p>
 */
class MulticastListenerTest {

    private static final MulticastAddress GROUP = MulticastAddress.parse("239.255.66.66:46666");

    /** 记录回调事件的假处理器。 */
    private static final class RecordingHandler implements MulticastListener.Handler {
        final List<String> events = new ArrayList<>();
        int idleCount;
        boolean failOnHello;

        @Override
        public void onHello(Announcement announcement, InetAddress sourceAddress) {
            if (failOnHello) {
                throw new IllegalStateException("simulated handler failure");
            }
            events.add("HELLO " + announcement.getAlias() + " from " + sourceAddress.getHostAddress());
        }

        @Override
        public void onBye(Announcement announcement, InetAddress sourceAddress) {
            events.add("BYE " + announcement.getAlias() + " from " + sourceAddress.getHostAddress());
        }

        @Override
        public void onIdle() {
            idleCount++;
        }
    }

    private static MulticastListener listenerFor(RecordingHandler handler) {
        return new MulticastListener(LoggerFactory.getLogger("test"), GROUP,
                Collections.emptyList(), handler);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("HELLO 被投递，并带上数据报的源地址（权威可达地址）")
    void deliversHelloWithDatagramSource() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        MulticastListener listener = listenerFor(handler);

        // 报文里自报 10.9.9.9，但真正收到它的源地址是 192.168.1.50——
        // 接收端必须以后者为准，前者可以被同网段任何人伪造
        byte[] payload = Announcement.hello("mydb", "mydb", "10.9.9.9", 5432, -1, "", 1).encode();
        listener.handleDatagram(payload, InetAddress.getByName("192.168.1.50"));

        assertEquals(List.of("HELLO mydb from 192.168.1.50"), handler.events);
        assertEquals(1, listener.getReceivedCount());
        assertEquals(0, listener.getRejectedCount());
    }

    @Test
    @DisplayName("BYE 被投递，用于立即摘除条目而不是等租约超时")
    void deliversBye() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        MulticastListener listener = listenerFor(handler);

        listener.handleDatagram(Announcement.bye("mydb", 2).encode(), InetAddress.getByName("192.168.1.50"));

        assertEquals(List.of("BYE mydb from 192.168.1.50"), handler.events);
        assertEquals(1, listener.getReceivedCount());
    }

    @Test
    @DisplayName("脏报文一律丢弃：别的程序的广播、截断的 JSON、空包、超大包")
    void rejectsForeignTraffic() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        MulticastListener listener = listenerFor(handler);
        InetAddress source = InetAddress.getByName("192.168.1.50");

        List<byte[]> junk = new ArrayList<>();
        junk.add(new byte[0]);
        junk.add(utf8("not json at all"));
        junk.add(utf8("[1,2,3]"));
        junk.add(utf8("{\"some\":\"other program's packet\"}"));
        junk.add(utf8("{\"sd\":1,\"type\":\"HELLO\""));
        junk.add(new byte[Announcement.MAX_DATAGRAM_BYTES + 1]);

        for (byte[] payload : junk) {
            assertDoesNotThrow(() -> listener.handleDatagram(payload, source),
                    "a malformed datagram must never propagate an exception");
        }

        assertTrue(handler.events.isEmpty(), "nothing may reach the handler: " + handler.events);
        assertEquals(0, listener.getReceivedCount());
        assertEquals(junk.size(), listener.getRejectedCount());
    }

    @Test
    @DisplayName("协议版本不一致被丢弃并单独计数，不能按老规则猜着解析")
    void rejectsUnsupportedProtocolVersion() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        MulticastListener listener = listenerFor(handler);

        listener.handleDatagram(utf8("{\"sd\":99,\"type\":\"HELLO\",\"name\":\"mydb\","
                        + "\"host\":\"10.0.0.5\",\"port\":5432}"),
                InetAddress.getByName("192.168.1.50"));

        assertTrue(handler.events.isEmpty());
        assertEquals(1, listener.getRejectedCount());
        assertEquals(0, listener.getReceivedCount());
    }

    @Test
    @DisplayName("回调抛异常不会把接收线程带走，也不会让统计失真")
    void survivesHandlerFailure() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        handler.failOnHello = true;
        MulticastListener listener = listenerFor(handler);

        byte[] payload = Announcement.hello("mydb", "mydb", "10.0.0.5", 5432, -1, "", 1).encode();

        assertDoesNotThrow(() -> listener.handleDatagram(payload, InetAddress.getByName("192.168.1.50")));
        // 报文本身是好的，所以计入 received；只是没能落到状态里
        assertEquals(1, listener.getReceivedCount());
        assertEquals(0, listener.getRejectedCount());
        assertTrue(handler.events.isEmpty());
    }

    @Test
    @DisplayName("空网卡列表 + 未 start：handleDatagram 仍然可用（单测与注入式排障的基础）")
    void worksWithoutStarting() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        MulticastListener listener = listenerFor(handler);

        assertFalse(listener.isRunning());
        listener.handleDatagram(Announcement.hello("mydb", "mydb", "10.0.0.5", 5432, 8000, "", 1).encode(),
                InetAddress.getByName("10.0.0.5"));
        assertEquals(1, handler.events.size());
    }

    @Test
    @DisplayName("没 start 就 close：安全空转，不抛异常（停机路径可能重复调用）")
    void closeBeforeStartIsNoOp() {
        MulticastListener listener = listenerFor(new RecordingHandler());

        assertDoesNotThrow(() -> {
            listener.close();
            listener.close();
        });
        assertFalse(listener.isRunning());
    }

    @Test
    @DisplayName("构造参数校验：组地址与处理器缺一不可")
    void constructorValidatesInput() {
        RecordingHandler handler = new RecordingHandler();
        assertThrows(IllegalArgumentException.class,
                () -> new MulticastListener(LoggerFactory.getLogger("test"), null, null, handler));
        assertThrows(IllegalArgumentException.class,
                () -> new MulticastListener(LoggerFactory.getLogger("test"), GROUP, null, null));
    }
}
