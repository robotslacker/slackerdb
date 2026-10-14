package org.slackerdb.common.discovery;

import org.slf4j.Logger;
import org.slackerdb.common.utils.Sleeper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.concurrent.atomic.AtomicLong;

/**
 * dbserver 侧的组播通告器：周期性把一个 {@code HELLO} 报文发到配置的组播组，
 * 停止时连发两条 {@code BYE}。
 *
 * <h3>为什么通告"无状态"地重复，而不是维护一条注册连接</h3>
 * <p>
 * 这是对原有 TCP 注册机制的替代。旧机制需要 dbserver 预先知道代理的 IP:PORT、需要维护一条
 * 注册连接、而且一旦进程被强杀，代理里的死条目就永远留下来了。组播通告反过来：发送方只管
 * 周期性喊话，接收方按"多久没听到"自行判断存活。代价是接收端必须引入租约超时（见
 * {@code MulticastListener}），收益是两端彻底解耦、且能自动发现服务消失。
 * </p>
 *
 * <h3>TTL 固定为 1</h3>
 * <p>
 * 报文只在本地子网内传播。这既是"发现"这个功能的语义边界（跨子网请用旧的 TCP 注册兜底），
 * 也是一条安全边界：组播报文没有任何认证，同网段的人可以伪造一条 {@code HELLO} 把某个别名
 * 的流量引到自己的机器上。TTL=1 至少不把它扩散到子网之外。
 * </p>
 */
public final class MulticastAdvertiser implements AutoCloseable {
    /** 默认通告周期。比旧 TCP 注册的 10 秒更密一些，缩短服务上线/下线的收敛时间。 */
    public static final long DEFAULT_INTERVAL_MILLIS = Announcement.ANNOUNCE_INTERVAL_MILLIS;

    /** 组播 TTL。固定为 1（仅本地子网），刻意不做成可配置项。 */
    private static final int MULTICAST_TTL = 1;

    /** 停止时 {@code BYE} 的重发次数：UDP 会丢包，只发一条容易漏掉。 */
    private static final int BYE_REPEAT = 2;

    private static final long BYE_GAP_MILLIS = 100L;

    /**
     * 通告参数。
     *
     * <p>必需项走构造函数，可选项走链式 setter——这类"参数很多"的构造如果全塞进一个签名里，
     * 调用点会出现一排无法辨认的字面量。</p>
     */
    public static final class Options {
        private final MulticastAddress multicastAddress;
        private final NetworkInterface networkInterface;
        private final Inet4Address localAddress;
        private final String databaseName;
        private final int port;
        private final int portX;

        private String alias = "";
        private String version = "";
        private long intervalMillis = DEFAULT_INTERVAL_MILLIS;

        public Options(MulticastAddress multicastAddress, NetworkInterface networkInterface,
                       Inet4Address localAddress, String databaseName, int port, int portX) {
            if (multicastAddress == null) {
                throw new IllegalArgumentException("Multicast address is required to advertise.");
            }
            if (networkInterface == null) {
                throw new IllegalArgumentException("Network interface is required to advertise.");
            }
            if (localAddress == null) {
                throw new IllegalArgumentException("Local address is required to advertise.");
            }
            if (databaseName == null || databaseName.trim().isEmpty()) {
                throw new IllegalArgumentException("Database name is required to advertise.");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Advertised port [" + port + "] is out of range 1-65535.");
            }
            if (portX < Announcement.PORT_X_DISABLED || portX > 65535) {
                throw new IllegalArgumentException("Advertised portX [" + portX + "] is out of range -1-65535.");
            }
            this.multicastAddress = multicastAddress;
            this.networkInterface = networkInterface;
            this.localAddress = localAddress;
            this.databaseName = databaseName.trim();
            this.port = port;
            this.portX = portX;
        }

        /** 代理侧别名；留空表示等于库名（与旧 REGISTER 的 {@code AS <data>} 一致）。 */
        public Options alias(String value) {
            this.alias = value == null ? "" : value.trim();
            return this;
        }

        public Options version(String value) {
            this.version = value == null ? "" : value.trim();
            return this;
        }

        /**
         * 覆盖通告周期。<b>仅用于测试</b>。
         *
         * <p>警告：接收端判断"对方还在不在"用的是 {@link Announcement#LEASE_MILLIS}，它按
         * {@link Announcement#ANNOUNCE_INTERVAL_MILLIS}（5 秒 × 3）算死。把周期调得比它还长，
         * 代理就会在两次通告之间把目标判死。真要把它做成配置项，必须同时把租约一起暴露出去。</p>
         */
        public Options interval(long millis) {
            if (millis < 200) {
                throw new IllegalArgumentException(
                        "Advertise interval [" + millis + "] ms is too small; at least 200 ms.");
            }
            this.intervalMillis = millis;
            return this;
        }

        public MulticastAddress getMulticastAddress() {
            return multicastAddress;
        }

        public NetworkInterface getNetworkInterface() {
            return networkInterface;
        }

        public Inet4Address getLocalAddress() {
            return localAddress;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public int getPort() {
            return port;
        }

        public int getPortX() {
            return portX;
        }

        public String getAlias() {
            return alias.isEmpty() ? databaseName : alias;
        }

        public String getVersion() {
            return version;
        }

        public long getIntervalMillis() {
            return intervalMillis;
        }
    }

    private final Logger logger;
    private final Options options;
    private final AtomicLong sequence = new AtomicLong();

    private volatile boolean running = false;
    private MulticastSocket socket;
    private Thread senderThread;

    public MulticastAdvertiser(Logger logger, Options options) {
        this.logger = logger;
        this.options = options;
    }

    /** 是否正在通告。 */
    public boolean isRunning() {
        return running;
    }

    /**
     * 开盘：建 socket 并发出一条通告，然后启动后台周期线程。
     *
     * <p>第一条通告是<b>同步</b>发出的：这样"启动日志说已通告"与"报文真的出去了"是同一时刻的事，
     * 排障时不用怀疑日志。</p>
     *
     * @throws IOException socket 建不起来（例如网卡不支持组播）——属于启动期错误，应当让进程失败
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        socket = new MulticastSocket();
        try {
            socket.setNetworkInterface(options.getNetworkInterface());
            socket.setTimeToLive(MULTICAST_TTL);
        } catch (IOException e) {
            socket.close();
            socket = null;
            throw e;
        }

        running = true;

        // 启动即通告一条，不等第一个周期
        sendHello();

        senderThread = new Thread(this::sendLoop, "Discovery-Advertise");
        senderThread.setDaemon(true);
        senderThread.start();

        logger.info("[DISCOVERY] Advertising [{}] as [{}] at {}:{} (portX={}) to {} via NIC [{}] every {} ms.",
                options.getDatabaseName(), options.getAlias(), options.getLocalAddress().getHostAddress(),
                options.getPort(), options.getPortX(), options.getMulticastAddress(),
                options.getNetworkInterface().getName(), options.getIntervalMillis());
    }

    private void sendLoop() {
        while (running) {
            try {
                Sleeper.sleep(options.getIntervalMillis());
            } catch (InterruptedException e) {
                // 被中断：说明 close() 正在收尾，退出循环，由 close() 负责发 BYE
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                return;
            }
            try {
                sendHello();
            } catch (Exception e) {
                // 单次发送失败（网卡热插拔、路由变化）不应该终止通告，下个周期继续
                logger.warn("[DISCOVERY] Failed to send announcement to {} via NIC [{}]: {}",
                        options.getMulticastAddress(), options.getNetworkInterface().getName(),
                        e.getMessage());
            }
        }
    }

    /** 构造下一条 {@code HELLO}。抽出来是为了能在单测里断言"到底发了什么"，而不必真的发。 */
    Announcement nextHello() {
        return Announcement.hello(
                options.getDatabaseName(),
                options.getAlias(),
                options.getLocalAddress().getHostAddress(),
                options.getPort(),
                options.getPortX(),
                options.getVersion(),
                sequence.incrementAndGet());
    }

    private void sendHello() throws IOException {
        send(nextHello());
    }

    private void send(Announcement announcement) throws IOException {
        byte[] payload = announcement.encode();
        DatagramPacket packet = new DatagramPacket(
                payload, payload.length,
                options.getMulticastAddress().getGroup(),
                options.getMulticastAddress().getPort());
        socket.send(packet);
    }

    /**
     * 收尾：先停周期线程，再连发 {@code BYE}，最后关 socket。
     *
     * <p>顺序很重要——如果先关 socket 再发 BYE，代理要等到租约超时（默认 3 个周期）才会摘除条目，
     * 停机期间客户端会连到一个已经死掉的地址上。</p>
     */
    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;

        if (senderThread != null) {
            senderThread.interrupt();
            try {
                senderThread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            senderThread = null;
        }

        for (int i = 0; i < BYE_REPEAT; i++) {
            try {
                send(Announcement.bye(options.getDatabaseName(), sequence.incrementAndGet()));
            } catch (Exception e) {
                // 停机路径：网卡可能已经不可用，打一条 debug 就够了，不能因此让进程退不出去
                logger.debug("[DISCOVERY] Failed to send BYE for [{}]: {}",
                        options.getDatabaseName(), e.getMessage());
                break;
            }
            if (i < BYE_REPEAT - 1) {
                try {
                    Sleeper.sleep(BYE_GAP_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (socket != null) {
            socket.close();
            socket = null;
        }

        logger.info("[DISCOVERY] Stopped advertising [{}] on {}.",
                options.getDatabaseName(), options.getMulticastAddress());
    }
}
