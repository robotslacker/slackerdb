package org.slackerdb.common.discovery;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 组播发现报文（Slackerdb Discovery Protocol）的不可变值对象。
 *
 * <p>一个 UDP 报文承载一条 JSON，两类报文：</p>
 * <ul>
 *   <li>{@link #TYPE_HELLO} —— "我在 {@code host:port}，库名 {@code name}，别名 {@code alias}"，
 *       周期性发送；</li>
 *   <li>{@link #TYPE_BYE} —— "我下线了"，只在 dbserver 优雅停止时发送，代理收到后立即摘除条目。</li>
 * </ul>
 *
 * <h3>字段</h3>
 * <table border="1">
 *   <tr><th>键</th><th>必需</th><th>说明</th></tr>
 *   <tr><td>{@code sd}</td><td>是</td><td>协议版本（magic 与版本合一）。不认识的版本直接丢弃，不崩</td></tr>
 *   <tr><td>{@code type}</td><td>是</td><td>{@code HELLO} / {@code BYE}</td></tr>
 *   <tr><td>{@code name}</td><td>是</td><td>数据库名</td></tr>
 *   <tr><td>{@code alias}</td><td>否</td><td>代理侧别名，缺省等于 {@code name}（与旧 REGISTER 的 {@code AS <data>} 等价）</td></tr>
 *   <tr><td>{@code host}</td><td>HELLO</td><td>dbserver 自报的 IPv4</td></tr>
 *   <tr><td>{@code port} / {@code portX}</td><td>HELLO</td><td>服务端口 / 管理端口，{@code portX = -1} 表示未启用</td></tr>
 *   <tr><td>{@code ver}</td><td>否</td><td>dbserver 版本号，仅用于排障</td></tr>
 *   <tr><td>{@code seq} / {@code ts}</td><td>是</td><td>单调序号与发送时刻，用于去重、乱序判断和"多久没收到了"</td></tr>
 * </table>
 *
 * <h3>为什么 {@code host} 不是权威来源</h3>
 * <p>
 * 代理侧默认采用 <b>UDP 报文的源地址</b>而不是这里的 {@code host}：源地址天然是"从代理视角
 * 可达"的地址，而 {@code host} 只是发送方的自我认知，且可被同网段任何人伪造。
 * 这个字段保留下来是为了排障（能直接看出对端认为自己是誰）和将来可能的显式覆盖。
 * </p>
 */
public final class Announcement {
    /** 协议版本。不匹配的报文一律丢弃（记 WARN），而不是猜测。 */
    public static final int PROTOCOL_VERSION = 1;

    public static final String TYPE_HELLO = "HELLO";
    public static final String TYPE_BYE = "BYE";

    /** 单个报文允许的最大字节数：真实报文不到 300 字节，给 2KB 只是为了让伪造的超大报文被早点丢掉。 */
    public static final int MAX_DATAGRAM_BYTES = 2048;

    /** {@code portX} 表示"管理端口未启用"，与旧 REGISTER 报文里的 -1 语义保持一致。 */
    public static final int PORT_X_DISABLED = -1;

    /**
     * 推荐的通告周期（毫秒）。
     *
     * <p>放在协议层而不是发送端：这是发送节奏与接收端失效判定之间的<b>契约</b>。
     * 发送方按它发，接收方按它的若干倍判断"多久没听到就算对方没了"。两边必须用同一个数，
     * 所以不能各自定义。</p>
     */
    public static final long ANNOUNCE_INTERVAL_MILLIS = 5_000L;

    /** 接收方连续错过多少个通告周期后，判定目标失效并摘除条目。 */
    public static final int LEASE_MISSES = 3;

    /** 租约时长：{@link #ANNOUNCE_INTERVAL_MILLIS} × {@link #LEASE_MISSES}。 */
    public static final long LEASE_MILLIS = ANNOUNCE_INTERVAL_MILLIS * LEASE_MISSES;

    private final String type;
    private final String name;
    private final String alias;
    private final String host;
    private final int port;
    private final int portX;
    private final String version;
    private final long seq;
    private final long timestamp;

    private Announcement(String type, String name, String alias, String host,
                         int port, int portX, String version, long seq, long timestamp) {
        this.type = type;
        this.name = name;
        this.alias = alias;
        this.host = host;
        this.port = port;
        this.portX = portX;
        this.version = version;
        this.seq = seq;
        this.timestamp = timestamp;
    }

    /**
     * 构造一条 {@code HELLO}。
     *
     * @param name    数据库名
     * @param alias   代理侧别名，空则等于 {@code name}
     * @param host    本机对外通告的 IPv4
     * @param port    数据服务端口
     * @param portX   管理服务端口，未启用传 -1
     * @param version 版本号，可空
     * @param seq     单调序号
     */
    public static Announcement hello(String name, String alias, String host, int port, int portX,
                                     String version, long seq) {
        String checkedName = requireText(name, "name");
        String checkedHost = requireText(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Announcement port [" + port + "] is out of range 1-65535.");
        }
        if (portX < PORT_X_DISABLED || portX > 65535) {
            throw new IllegalArgumentException(
                    "Announcement portX [" + portX + "] is out of range -1-65535.");
        }
        String effectiveAlias = (alias == null || alias.trim().isEmpty()) ? checkedName : alias.trim();
        return new Announcement(TYPE_HELLO, checkedName, effectiveAlias, checkedHost, port, portX,
                version == null ? "" : version.trim(), seq, System.currentTimeMillis());
    }

    /** 构造一条 {@code BYE}：只需要库名，代理按别名摘除。 */
    public static Announcement bye(String name, long seq) {
        String checkedName = requireText(name, "name");
        return new Announcement(TYPE_BYE, checkedName, checkedName, "", 0, PORT_X_DISABLED, "",
                seq, System.currentTimeMillis());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Announcement field [" + field + "] must not be empty.");
        }
        return value.trim();
    }

    /** 编码为可以直接塞进 {@code DatagramPacket} 的字节数组。 */
    public byte[] encode() {
        JSONObject json = new JSONObject();
        json.put("sd", PROTOCOL_VERSION);
        json.put("type", type);
        json.put("name", name);
        if (isHello()) {
            json.put("alias", alias);
            json.put("host", host);
            json.put("port", port);
            json.put("portX", portX);
            if (!version.isEmpty()) {
                json.put("ver", version);
            }
        }
        json.put("seq", seq);
        json.put("ts", timestamp);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 解析一个收到的报文。
     *
     * <p>凡是"不像我们的报文"的输入都抛 {@link IllegalArgumentException}，消息里带具体原因，
     * 调用方记一条 DEBUG/WARN 后丢弃即可。<b>不抛 RuntimeException 之外的任何东西、也绝不返回
     * 半成品对象</b>——组播端口是开放的，收到的任何字节都不该让接收端崩掉或写进错误的目标。</p>
     */
    public static Announcement decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty announcement datagram.");
        }
        if (data.length > MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("Announcement datagram too large: " + data.length
                    + " bytes (max " + MAX_DATAGRAM_BYTES + ").");
        }

        String text = new String(data, StandardCharsets.UTF_8).trim();
        JSONObject json;
        try {
            json = JSON.parseObject(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("Announcement is not valid JSON: " + e.getMessage());
        }
        if (json == null) {
            throw new IllegalArgumentException("Announcement is not a JSON object.");
        }

        Integer protocol = json.getInteger("sd");
        if (protocol == null) {
            throw new IllegalArgumentException(
                    "Announcement is missing the [sd] marker; not a Slackerdb discovery packet.");
        }
        if (protocol != PROTOCOL_VERSION) {
            throw new UnsupportedProtocolVersionException(protocol, PROTOCOL_VERSION);
        }

        String type = trimmed(json.getString("type"));
        if (!TYPE_HELLO.equals(type) && !TYPE_BYE.equals(type)) {
            throw new IllegalArgumentException("Unsupported announcement type [" + type
                    + "]; expected [" + TYPE_HELLO + "] or [" + TYPE_BYE + "].");
        }

        String name = trimmed(json.getString("name"));
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Announcement is missing the [name] field.");
        }

        long seq = readLong(json, "seq", 0L);
        long timestamp = readLong(json, "ts", 0L);

        if (TYPE_BYE.equals(type)) {
            return new Announcement(TYPE_BYE, name, name, "", 0, PORT_X_DISABLED, "", seq, timestamp);
        }

        String host = trimmed(json.getString("host"));
        if (host.isEmpty()) {
            throw new IllegalArgumentException("HELLO announcement is missing the [host] field.");
        }
        int port = readInt(json, "port", 0);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("HELLO announcement has an invalid [port] value ["
                    + json.get("port") + "].");
        }
        int portX = readInt(json, "portX", PORT_X_DISABLED);
        if (portX < PORT_X_DISABLED || portX > 65535) {
            throw new IllegalArgumentException("HELLO announcement has an invalid [portX] value ["
                    + json.get("portX") + "].");
        }
        String alias = trimmed(json.getString("alias"));
        if (alias.isEmpty()) {
            alias = name;
        }

        return new Announcement(TYPE_HELLO, name, alias, host, port, portX,
                trimmed(json.getString("ver")), seq, timestamp);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 读整数。取值"不是数字"时 fastjson2 会抛出它自己的转换异常——那属于"别人丢进来的脏报文"，
     * 统一换成带字段名的 {@link IllegalArgumentException}，避免接收端把它当成程序缺陷记录成 ERROR。
     */
    private static int readInt(JSONObject json, String key, int fallback) {
        try {
            return json.getIntValue(key, fallback);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Announcement field [" + key + "] is not a number: ["
                    + json.get(key) + "].", e);
        }
    }

    private static long readLong(JSONObject json, String key, long fallback) {
        try {
            return json.getLongValue(key, fallback);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Announcement field [" + key + "] is not a number: ["
                    + json.get(key) + "].", e);
        }
    }

    public boolean isHello() {
        return TYPE_HELLO.equals(type);
    }

    public boolean isBye() {
        return TYPE_BYE.equals(type);
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getAlias() {
        return alias;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getPortX() {
        return portX;
    }

    public String getVersion() {
        return version;
    }

    public long getSeq() {
        return seq;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        if (isBye()) {
            return "BYE name=" + name + " seq=" + seq;
        }
        return "HELLO name=" + name + " alias=" + alias + " host=" + host
                + " port=" + port + " portX=" + portX + " seq=" + seq;
    }

    /**
     * 收到一条协议版本不受支持的报文。
     *
     * <p>单独一个异常类型，是为了让接收端能把"版本不一致"（值得告警：多半是有人升级了一半）
     * 与"别的程序丢进来的脏字节"（只值得 DEBUG：组播端口是开放的）区分开。
     * 否则接收端只能靠匹配异常消息文本来判断，那种写法一改就悄悄失效。</p>
     */
    public static final class UnsupportedProtocolVersionException extends IllegalArgumentException {
        private final int receivedVersion;
        private final int supportedVersion;

        UnsupportedProtocolVersionException(int receivedVersion, int supportedVersion) {
            super("Unsupported announcement protocol version [" + receivedVersion
                    + "]; this build speaks [" + supportedVersion + "].");
            this.receivedVersion = receivedVersion;
            this.supportedVersion = supportedVersion;
        }

        public int getReceivedVersion() {
            return receivedVersion;
        }

        public int getSupportedVersion() {
            return supportedVersion;
        }
    }
}
