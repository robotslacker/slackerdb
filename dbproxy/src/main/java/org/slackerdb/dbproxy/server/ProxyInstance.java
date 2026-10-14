package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slackerdb.common.discovery.Announcement;
import org.slackerdb.common.discovery.MulticastAddress;
import org.slackerdb.common.discovery.MulticastListener;
import org.slackerdb.common.discovery.NetworkAddressUtil;
import org.slackerdb.common.utils.OSUtil;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.nio.channels.FileLock;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.common.utils.Utils;
import org.slf4j.LoggerFactory;

public class ProxyInstance {
    // 服务器启动模式
    // 是否为独占模式，默认否
    // 当程序为独占模式的时候，退出端口，意味着程序也将退出
    private boolean exclusiveMode = false;

    // 服务器启动的时间
    public LocalDateTime bootTime = null;

    // 实例的状态
    public String instanceState = "IDLE";

    // 监听器（Netty）启动的最长等待时间。正常情况下 1 秒内就绪；60 秒是给
    // "起不来但也没立刻报错"的情况兜底，保证 start() 最终一定会返回而不是永久挂住。
    private static final long LISTENER_STARTUP_TIMEOUT_MILLIS = 60 * 1000;

    // 服务器配置参数
    public ServerConfiguration serverConfiguration;

    // 服务器对应的PG协议转发器
    private PostgresProxyServer protocolServer;

    // 管理端口服务
    public ProxyInstanceX proxyInstanceX = null;

    // 实例对应的日志句柄
    public Logger logger;

    // 资源文件，记录各种消息，以及日后可能的翻译信息
    public ResourceBundle resourceBundle;

    // PID进程锁信息
    private RandomAccessFile pidRandomAccessFile;
    private FileLock pidFileLockHandler;

    public final ConcurrentHashMap<String,PostgresProxyTarget> proxyTarget = new ConcurrentHashMap<>();

    // 组播服务发现接收器。listener_address 为空时始终为 null——不配置就是不启用。
    private MulticastListener discoveryListener = null;

    // 后端会话号 → 上游 的取消路由表。
    // CancelRequest 报文没有 database，无法按别名选路；代理在握手期从后端的
    // BackendKeyData 学到 pid，取消到达时按 pid 精确回送（详见 CancelRouter）。
    public final CancelRouter cancelRouter = new CancelRouter();

    // 构造函数
    public ProxyInstance(ServerConfiguration pServerConfiguration) throws ServerException
    {
        // 加载数据库的配置信息
        serverConfiguration = pServerConfiguration;

        // 设置消息语言集
        resourceBundle = ResourceBundle.getBundle("message", serverConfiguration.getLocale());

        // 检查是否包含路径分隔符
    }

    private String getMessage(String code, Object... contents) {
        StringBuilder content;
        String pattern;
        try {
            pattern = this.resourceBundle.getString(code);
            content = new StringBuilder(MessageFormat.format(pattern, contents));
        } catch (MissingResourceException me)
        {
            content = new StringBuilder("MSG-" + code + ":");
            for (Object object : contents) {
                if (object != null) {
                    content.append(object).append("|");
                }
                else {
                    content.append("null|");
                }
            }
        }
        return content.toString();
    }
    // 返回程序为独占模式
    public boolean isExclusiveMode()
    {
        return this.exclusiveMode;
    }
    // 设置程序运行的模式，是否为独占模式
    public void setExclusiveMode(boolean exclusiveMode)
    {
        this.exclusiveMode = exclusiveMode;
    }

    // ------------------------------------------------------------------ 目标登记
    //
    // "把某个别名指向某台 dbserver"只有一条路径：组播发现收到的 HELLO。曾经的遗留单播
    // REGISTER 通道已被删除。之所以把登记收敛到下面这几个方法，是因为登记要同时做几件事
    // （写别名、维护 portX 的 HTTP 转发映射、处理重名与搬家），分散在多处迟早会漂移——
    // 历史上 REGISTER 与 UNREGISTER 就各自维护了一份 forwarderPathMappings，漏掉一边就会出现
    // "别名删了但 HTTP 转发还指着旧地址"。

    /**
     * 按别名找目标。转发路径唯一的查表入口。
     *
     * <p>将来若要支持"一个别名对应多台 dbserver 并做负载/故障转移"，改这里一处即可。</p>
     */
    public PostgresProxyTarget lookupTarget(String alias)
    {
        return proxyTarget.get(alias);
    }

    /**
     * 应用一条组播通告（{@code HELLO}）。
     *
     * @param announcement  解析成功的报文
     * @param sourceAddress UDP 报文的源地址；<b>这是权威的可达地址</b>，优先于报文里自报的 host，
     *                      因为源地址天然是"从本机视角可达"的，而同网段任何人都能伪造 host
     */
    public void applyAnnouncement(Announcement announcement, InetAddress sourceAddress)
    {
        String host = (sourceAddress != null && !sourceAddress.isAnyLocalAddress())
                ? sourceAddress.getHostAddress()
                : announcement.getHost();
        registerTarget(announcement.getAlias(), announcement.getName(), host,
                announcement.getPort(), announcement.getPortX(),
                System.currentTimeMillis() + Announcement.LEASE_MILLIS,
                "announced by " + sourceAddress);
    }

    /**
     * 登记/刷新一个目标别名。
     *
     * @param leaseExpireAtMs 租约到期时刻；{@code 0} 表示<b>无租约</b>、不会被动过期
     * @param origin          日志里的来源说明
     */
    private void registerTarget(String alias, String database, String host, int port, int portX,
                                long leaseExpireAtMs, String origin)
    {
        if (alias == null || alias.isEmpty()) {
            return;
        }

        PostgresProxyTarget previous = proxyTarget.get(alias);
        boolean unchangedEndpoint = previous != null
                && host.equals(previous.host)
                && port == previous.port
                && portX == previous.portX
                && database.equals(previous.database);

        PostgresProxyTarget target = new PostgresProxyTarget();
        // 创建时间沿用首次登记的，便于看出"这个别名是什么时候出现的"
        target.createdDateTime = previous != null ? previous.createdDateTime : LocalDateTime.now();
        target.lastActivatedTIme = LocalDateTime.now();
        target.host = host;
        target.port = port;
        target.portX = portX;
        target.database = database;
        target.leaseExpireAtMs = leaseExpireAtMs;

        proxyTarget.put(alias, target);

        if (unchangedEndpoint) {
            // 幂等刷新：组播每 5 秒来一条，这里绝不能打日志，否则日志会被刷爆
            return;
        }

        updateForwarderMapping(alias, target);

        if (previous != null) {
            logger.warn("[PROXY][DISCOVERY  ] Alias [{}] moved from {}:{}/{} to {}:{}/{} ({}).",
                    alias, previous.host, previous.port, previous.database,
                    host, port, database, origin);
        } else {
            logger.info("[PROXY][DISCOVERY  ] Alias [{}] now points to {}:{}/{} ({}).",
                    alias, host, port, database, origin);
        }
    }

    /**
     * 摘除一个别名（收到 BYE、或过期扫描判定对方已消失）。
     *
     * @param reason 日志里的原因说明
     */
    public void removeAnnouncement(String alias, String reason)
    {
        if (alias == null || alias.isEmpty()) {
            return;
        }
        PostgresProxyTarget removed = proxyTarget.remove(alias);
        if (proxyInstanceX != null) {
            proxyInstanceX.forwarderPathMappings.remove("/" + alias);
        }
        if (removed != null) {
            logger.info("[PROXY][DISCOVERY  ] Alias [{}] at {}:{}/{} removed ({}).",
                    alias, removed.host, removed.port, removed.database, reason);
        }
    }

    /**
     * 维护 portX 的 HTTP 转发映射。
     *
     * <p>dbserver 的 portX 未启用时通告 {@code -1}，此时必须把旧映射删掉——否则会出现
     * "别名已经不指向那台机器了，HTTP 转发还指着它的管理端口"这种半死状态。</p>
     */
    private void updateForwarderMapping(String alias, PostgresProxyTarget target)
    {
        if (proxyInstanceX == null) {
            // 代理自身也没有启用管理端口，谈不上转发
            return;
        }
        String path = "/" + alias;
        if (target.portX > 0) {
            proxyInstanceX.forwarderPathMappings.put(path, "http://" + target.host + ":" + target.portX);
        } else {
            proxyInstanceX.forwarderPathMappings.remove(path);
        }
    }

    /**
     * 摘除所有租约已过期的目标。
     *
     * <p>{@code leaseExpireAtMs == 0} 表示"无租约"，不会被清理。组播发现的条目一定带租约，
     * 这个约定是给直接写入 {@link #proxyTarget} 的调用方（插件、将来的管理接口、测试）留的：
     * 否则它们放进来的目标会在 15 秒后被静默摘掉，而原因完全看不出来。</p>
     */
    public void expireLeases()
    {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PostgresProxyTarget> entry : proxyTarget.entrySet()) {
            PostgresProxyTarget target = entry.getValue();
            if (target.leaseExpireAtMs > 0 && target.leaseExpireAtMs <= now) {
                removeAnnouncement(entry.getKey(), "no announcement for "
                        + Announcement.LEASE_MILLIS + " ms");
            }
        }
    }

    // ------------------------------------------------------------------ 组播发现生命周期

    /**
     * 启动组播服务发现。{@code listener_address} 为空时完全跳过（不建 socket、不起线程）。
     */
    private void startDiscovery() throws ServerException
    {
        if (!serverConfiguration.isDiscoveryEnabled()) {
            logger.info("[PROXY][DISCOVERY  ] Service discovery is disabled (listener_address is not set).");
            return;
        }

        MulticastAddress address = serverConfiguration.getListenerAddress();
        try {
            discoveryListener = new MulticastListener(
                    logger,
                    address,
                    NetworkAddressUtil.multicastReceiveInterfaces(),
                    new MulticastListener.Handler() {
                        @Override
                        public void onHello(Announcement announcement, InetAddress sourceAddress) {
                            applyAnnouncement(announcement, sourceAddress);
                        }

                        @Override
                        public void onBye(Announcement announcement, InetAddress sourceAddress) {
                            removeAnnouncement(announcement.getAlias(), "BYE from " + sourceAddress);
                        }

                        @Override
                        public void onIdle() {
                            expireLeases();
                        }
                    });
            discoveryListener.start();
        }
        catch (IOException | IllegalArgumentException e) {
            throw new ServerException("SLACKERDB-00021",
                    Utils.getMessage("SLACKERDB-00021", address, e.getMessage()));
        }
    }

    private void stopDiscovery()
    {
        if (discoveryListener != null) {
            discoveryListener.close();
            discoveryListener = null;
        }
    }

    // 根据参数配置文件启动代理实例
    public synchronized void start(org.slf4j.Logger appLogger) throws ServerException {
        // 设置线程名称好保持日志格式
        Thread.currentThread().setName("PROXY");

        // 使用外部的日志，而不是自己创建日志
        this.logger = (ch.qos.logback.classic.Logger) appLogger;
        if (this.logger.getLevel() == null)
        {
            this.logger.setLevel(Level.INFO);
        }

        // 只有在停止的状态下才能启动
        if (!this.instanceState.equalsIgnoreCase("IDLE"))
        {
            throw new ServerException("SLACKERDB-00006", getMessage("SLACKERDB-00006"));
        }

        // 服务器开始启动
        logger.info("[PROXY] Starting ...");
        this.instanceState = "STARTING";
        this.bootTime = LocalDateTime.now();

        // 检查PID文件
        if (!this.serverConfiguration.getPid().isEmpty())
        {
            File pidParentFile = new File(this.serverConfiguration.getPid()).getParentFile();
            // 如果PID文件目录不存在，则建立这个目录
            if (!pidParentFile.exists())
            {
                var ignored = pidParentFile.mkdirs();
            }

            File pidFile = new File(this.serverConfiguration.getPid());
            // 尝试锁定文件
            try {
                pidRandomAccessFile = new RandomAccessFile(pidFile, "rw");
                if (!OSUtil.isWindows()) {
                    // Windows上锁定后，会导致其他进程无法查看PID文件
                    pidFileLockHandler = pidRandomAccessFile.getChannel().tryLock();
                    if (pidFileLockHandler == null) {
                        throw new ServerException(
                                "SLACKERDB-00013",
                                Utils.getMessage("SLACKERDB-00013", this.serverConfiguration.getPid()));
                    }
                }
                pidRandomAccessFile.setLength(0);
                pidRandomAccessFile.writeBytes(String.valueOf(ProcessHandle.current().pid()));
            } catch (IOException e) {
                throw new ServerException(
                        "SLACKERDB-00013",
                        Utils.getMessage("SLACKERDB-00013", this.serverConfiguration.getPid()));
            }
        }

        // 启动PG的协议处理程序
        if (serverConfiguration.getPort() != -1) {
            protocolServer = new PostgresProxyServer();
            protocolServer.setLogger(logger);
            protocolServer.setBindHostAndPort(serverConfiguration.getBindHost(), serverConfiguration.getPort());
            protocolServer.setServerTimeout(serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout());
            protocolServer.setNioEventThreads(serverConfiguration.getMax_Workers());
            protocolServer.setProxyInstance(this);
            protocolServer.start();
            try {
                // 等待 Netty 就绪。监听器启动失败（典型：端口被占用）或长时间无进展时
                // 直接失败 —— 不能无限等待，否则 start() 会永远挂住，调用方只看到"卡死"。
                protocolServer.waitUntilReady(LISTENER_STARTUP_TIMEOUT_MILLIS);
            } catch (Exception listenerException) {
                logger.error("[PROXY][STARTUP    ] Listener startup failed, rolling back startup.", listenerException);
                // 回滚已经完成的挂载，避免失败的实例继续占着端口/线程/文件句柄。
                try {
                    stop();
                } catch (Exception stopException) {
                    logger.warn("[PROXY][STARTUP    ] Cleanup after failed startup also failed.", stopException);
                }
                if (listenerException instanceof ServerException serverException) {
                    // 已是带明确原因（端口被占用/超时）的启动失败，直接上抛，不要套娃
                    throw serverException;
                }
                throw new ServerException("Start listener error. " + listenerException.getMessage(), listenerException);
            }
        }
        else
        {
            logger.info("[PROXY][STARTUP    ] Listener has been disabled.");
        }

        // 如果需要，启动管理端口
        if (serverConfiguration.getPortX() != -1) {
            // 关闭Javalin, 如果不是在trace下
            Logger javalinLogger = (Logger) LoggerFactory.getLogger("io.javalin.Javalin");
            Logger jettyLogger = (Logger) LoggerFactory.getLogger("org.eclipse.jetty");
            if (!this.logger.getLevel().equals(Level.TRACE)) {
                javalinLogger.setLevel(Level.OFF);
                jettyLogger.setLevel(Level.OFF);
            }
            this.proxyInstanceX = new ProxyInstanceX(this.logger, this);
            this.proxyInstanceX.start(serverConfiguration);
        }
        else
        {
            logger.info("[PROXY][STARTUP    ] Management server listener has been disabled.");
        }

        // 启动组播服务发现（未配置 listener_address 时完全跳过）
        startDiscovery();

        // 标记服务已经启动完成
        this.instanceState = "RUNNING";
        logger.info("[PROXY] Server is running.");
    }

    public synchronized void start() throws ServerException {
        // 初始化日志服务
        Logger appLogger = AppLogger.createLogger(
                "PROXY",
                serverConfiguration.getLog_level().levelStr,
                serverConfiguration.getLog());

        // 启动应用
        this.start(appLogger);
    }

    // 停止代理服务实例
    public synchronized void stop() throws ServerException
    {
        if (this.instanceState.equalsIgnoreCase("IDLE"))
        {
            // 已经在停止中，不需要重复停止
            return;
        }

        this.instanceState = "SHUTTING DOWN";

        // 停止组播服务发现
        stopDiscovery();

        // 停止管理服务
        if (this.proxyInstanceX != null)
        {
            this.proxyInstanceX.stop();
            this.proxyInstanceX = null;
        }

        // 停止对外网络服务
        // 注意：port=-1（禁用监听）时 protocolServer 从未创建，这里必须判空，否则停止会 NPE
        if (protocolServer != null) {
            protocolServer.stop();
            protocolServer = null;
        }

        // 清理取消路由表（进程即将退出，避免残留"陈旧路由"影响后续判断）
        cancelRouter.clear();

        // 删除PID文件
        if (pidRandomAccessFile != null )
        {
            try {
                if (pidFileLockHandler != null) {
                    pidFileLockHandler.release();
                    pidFileLockHandler = null;
                }
                pidRandomAccessFile.close();
                File pidFile = new File(this.serverConfiguration.getPid());
                if (pidFile.exists()) {
                    if (!pidFile.delete()) {
                        logger.warn("[PROXY] Remove pid file [{}] failed, reason unknown!",
                                this.serverConfiguration.getPid());
                    }
                }
                pidRandomAccessFile = null;
            }
            catch (IOException ioe) {
                logger.warn("[PROXY] Remove pid file [{}] failed, reason unknown!",
                        this.serverConfiguration.getPid(), ioe);
            }
        }

        // 服务状态标记为空闲
        this.instanceState = "IDLE";
    }

}
