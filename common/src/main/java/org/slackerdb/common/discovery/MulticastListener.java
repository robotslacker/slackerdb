package org.slackerdb.common.discovery;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * dbproxy 侧的组播接收器：加入配置的组播组，把收到的 {@code HELLO}/{@code BYE} 交给 {@link Handler}。
 *
 * <h3>接收端口是开放的，所以这里必须"什么脏东西都不怕"</h3>
 * <p>
 * 同一个网段里任何程序、任何人往这个组播组丢字节都会被收到。因此解析失败一律只记 DEBUG
 * （记 WARN 等于把日志变成一个可以被外部触发的放大器），并且<b>绝不</b>因为一条报文而抛异常、
 * 退出线程或修改状态——只有真正解析成功的 {@code HELLO}/{@code BYE} 才会走到 {@link Handler}。
 * 唯一被区别对待的是"协议版本不一致"：那多半是自己人升级了一半，值得一条限流的 WARN。
 * </p>
 *
 * <h3>顺带承担失效扫描的节拍</h3>
 * <p>
 * 接收循环用 1 秒的 socket 超时，超时即调用 {@link Handler#onIdle()}。这样"多久没听到对方了"
 * 的判定不需要额外线程，也天然是"没有任何报文时才需要检查"的时机。
 * </p>
 */
public final class MulticastListener implements AutoCloseable {

    /** 接收循环的轮询间隔：同时也是失效判定的最小精度。 */
    private static final int POLL_TIMEOUT_MILLIS = 1_000;

    /** 启动后多久没收到任何报文就打第一条"可能配错了"的告警。 */
    private static final long SILENCE_WARN_AFTER_MILLIS = 60_000L;

    /** 同一条"一直没收到报文"的告警的最小重复间隔，避免每 60 秒刷一条。 */
    private static final long SILENCE_WARN_REPEAT_MILLIS = 5 * 60_000L;

    /** 版本不一致告警的最小重复间隔。 */
    private static final long VERSION_WARN_REPEAT_MILLIS = 60_000L;

    /** 接收缓冲：真实报文不到 300 字节，留足余量即可。 */
    private static final int RECEIVE_BUFFER_BYTES = Announcement.MAX_DATAGRAM_BYTES;

    /** 收到报文后的回调。 */
    public interface Handler {
        /**
         * @param announcement 解析成功的 HELLO
         * @param sourceAddress UDP 报文的源地址。<b>这是权威的可达地址</b>，不是报文里的 {@code host}
         */
        void onHello(Announcement announcement, InetAddress sourceAddress);

        /** @param announcement 解析成功的 BYE */
        void onBye(Announcement announcement, InetAddress sourceAddress);

        /** 每次接收循环空转（约每秒一次）时调用，用于驱赶过期条目等周期性维护。 */
        default void onIdle() {
        }
    }

    private final Logger logger;
    private final MulticastAddress multicastAddress;
    private final List<NetworkInterface> networkInterfaces;
    private final Handler handler;

    private final AtomicLong receivedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    private volatile boolean running = false;
    private MulticastSocket socket;
    private Thread receiveThread;

    private long lastPacketAt = 0;
    private long lastSilenceWarnAt = 0;
    private long lastVersionWarnAt = 0;

    public MulticastListener(Logger logger, MulticastAddress multicastAddress,
                             List<NetworkInterface> networkInterfaces, Handler handler) {
        if (multicastAddress == null) {
            throw new IllegalArgumentException("Multicast address is required to listen.");
        }
        if (handler == null) {
            throw new IllegalArgumentException("A handler is required to listen.");
        }
        this.logger = logger;
        this.multicastAddress = multicastAddress;
        this.networkInterfaces = networkInterfaces == null
                ? Collections.emptyList() : new ArrayList<>(networkInterfaces);
        this.handler = handler;
    }

    public boolean isRunning() {
        return running;
    }

    /** 已成功解析并投递的报文数（测试与 status 排障用）。 */
    public long getReceivedCount() {
        return receivedCount.get();
    }

    /** 被丢弃的报文数：脏字节、别的程序的广播、版本不一致等。 */
    public long getRejectedCount() {
        return rejectedCount.get();
    }

    /**
     * 加入组播组并开始接收。
     *
     * @throws IOException socket 建不起来、端口被占用、或一个网卡都加入不了
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        // 必须先开 SO_REUSEADDR 再 bind：同一台机器上跑两个代理时需要各自都收到报文。
        // 用 MulticastSocket(null) 拿到未绑定的 socket 正是为了这个顺序。
        MulticastSocket newSocket = new MulticastSocket(null);
        try {
            newSocket.setReuseAddress(true);
            newSocket.bind(new InetSocketAddress(multicastAddress.getPort()));
            newSocket.setSoTimeout(POLL_TIMEOUT_MILLIS);

            List<String> joined = new ArrayList<>();
            IOException lastFailure = null;
            for (NetworkInterface nic : networkInterfaces) {
                try {
                    newSocket.joinGroup(new InetSocketAddress(multicastAddress.getGroup(), multicastAddress.getPort()),
                            nic);
                    joined.add(nic.getName());
                } catch (IOException e) {
                    // 单块网卡加入失败不应该让整个发现功能不可用（比如某块虚拟网卡不支持组播）
                    lastFailure = e;
                    logger.debug("[DISCOVERY] Cannot join {} on NIC [{}]: {}",
                            multicastAddress, nic.getName(), e.getMessage());
                }
            }
            if (joined.isEmpty()) {
                throw lastFailure != null ? lastFailure
                        : new IOException("No network interface could join " + multicastAddress + ".");
            }

            socket = newSocket;
            running = true;
            lastPacketAt = System.currentTimeMillis();

            receiveThread = new Thread(this::receiveLoop, "Discovery-Listen");
            receiveThread.setDaemon(true);
            receiveThread.start();

            logger.info("[DISCOVERY] Listening on {} via NIC{} {}.",
                    multicastAddress, joined.size() > 1 ? "s" : "", String.join(", ", joined));
        } catch (IOException e) {
            newSocket.close();
            throw e;
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handleDatagram(Arrays.copyOf(packet.getData(), packet.getLength()), packet.getAddress());
            } catch (SocketTimeoutException timeout) {
                // 空转：正好用来做失效扫描与"一直没收到报文"的诊断
                warnIfSilent();
                try {
                    handler.onIdle();
                } catch (RuntimeException e) {
                    logger.warn("[DISCOVERY] Idle maintenance failed: {}", e.getMessage());
                }
            } catch (IOException e) {
                if (running) {
                    // 接收失败（例如网卡被拔掉）不应该终止接收：下个循环继续试
                    logger.warn("[DISCOVERY] Failed to receive from {}: {}", multicastAddress, e.getMessage());
                }
            }
        }
    }

    /**
     * 处理一个收到的数据报。抽成包级方法是为了能在单测里直接投喂报文，不依赖真实组播。
     */
    void handleDatagram(byte[] data, InetAddress sourceAddress) {
        Announcement announcement;
        try {
            announcement = Announcement.decode(data);
        } catch (Announcement.UnsupportedProtocolVersionException e) {
            rejectedCount.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastVersionWarnAt > VERSION_WARN_REPEAT_MILLIS) {
                lastVersionWarnAt = now;
                logger.warn("[DISCOVERY] Ignoring announcement from {}: {}. "
                                + "This usually means one side was upgraded and the other was not.",
                        sourceAddress, e.getMessage());
            } else {
                logger.debug("[DISCOVERY] Ignoring announcement from {}: {}", sourceAddress, e.getMessage());
            }
            return;
        } catch (IllegalArgumentException e) {
            // 组播端口是开放的：别的程序的广播、被截断的报文都会到这里，只记 DEBUG
            rejectedCount.incrementAndGet();
            logger.debug("[DISCOVERY] Ignoring datagram from {}: {}", sourceAddress, e.getMessage());
            return;
        }

        lastPacketAt = System.currentTimeMillis();
        receivedCount.incrementAndGet();

        try {
            if (announcement.isBye()) {
                logger.info("[DISCOVERY] Received {} from {}.", announcement, sourceAddress);
                handler.onBye(announcement, sourceAddress);
            } else {
                handler.onHello(announcement, sourceAddress);
            }
        } catch (RuntimeException e) {
            // 处理回调出错不能把接收线程带走；否则一次坏消息就永久失去发现能力
            logger.warn("[DISCOVERY] Failed to apply announcement from {}: {}", sourceAddress, e.getMessage());
        }
    }

    /**
     * 长时间一个报文都没收到时给一条告警。
     *
     * <p>组播配错（两端组地址不一致、防火墙拦了 UDP、交换机没开 IGMP snooping）的表现就是
     * "什么都没有发生"。没有这条日志，运维只能靠抓包。</p>
     */
    private void warnIfSilent() {
        long now = System.currentTimeMillis();
        if (now - lastPacketAt < SILENCE_WARN_AFTER_MILLIS) {
            return;
        }
        if (lastSilenceWarnAt != 0 && now - lastSilenceWarnAt < SILENCE_WARN_REPEAT_MILLIS) {
            return;
        }
        lastSilenceWarnAt = now;
        logger.warn("[DISCOVERY] No announcement received on {} for {} seconds. "
                        + "Check that the dbserver uses the same group in advertise_address, that inbound UDP port {} "
                        + "is allowed by the firewall, and that the switches forward multicast (IGMP snooping).",
                multicastAddress, (now - lastPacketAt) / 1000, multicastAddress.getPort());
    }

    /** 关闭接收器：离开组播组、关 socket、停线程。 */
    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;

        if (receiveThread != null) {
            receiveThread.interrupt();
            try {
                receiveThread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            receiveThread = null;
        }

        if (socket != null) {
            for (NetworkInterface nic : networkInterfaces) {
                try {
                    socket.leaveGroup(
                            new InetSocketAddress(multicastAddress.getGroup(), multicastAddress.getPort()), nic);
                } catch (IOException ignored) {
                    // 停机路径：离开组失败无所谓，进程马上就要退出了
                }
            }
            socket.close();
            socket = null;
        }

        logger.info("[DISCOVERY] Stopped listening on {} (received {}, rejected {}).",
                multicastAddress, receivedCount.get(), rejectedCount.get());
    }
}
