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
import org.slackerdb.dbproxy.instance.BackendView;
import org.slackerdb.dbproxy.instance.InstanceController;
import org.slackerdb.dbproxy.instance.InstanceLauncher;
import org.slackerdb.dbproxy.instance.InstanceProbe;
import org.slackerdb.dbproxy.instance.InstanceRegistry;
import org.slackerdb.dbproxy.instance.InstanceScanner;
import org.slackerdb.dbproxy.instance.InstanceState;
import org.slackerdb.dbproxy.instance.ObservedInstance;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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

    // ------------------------------------------------------------------ 实例管理
    //
    // data_dir 非空时启用。没有 catalog、没有 instance.json：实例目录本身就是事实源，
    // 这里只缓存"探测结果"，代理重启后重新扫描即可恢复，且能反映用户手工启停的结果。
    private InstanceRegistry instanceRegistry = null;
    private InstanceController instanceController = null;

    // 会话号在实例级别唯一。旧实现把它放在 per-connection 的 handler 里，
    // 结果每条连接都从 1000 开始（CancelRoute.clientSessionId 因此会串）。
    private final AtomicLong sessionIds = new AtomicLong(1000);

    /** 数据面选路结果。 */
    public record RouteTarget(String host, int port, String database) {
    }

    /** 管理口选路结果：{@code baseUrl} 为空表示"实例存在但不可用"，{@code state} 为原因。 */
    public record ManagementTarget(String baseUrl, InstanceState state, String alias) {
    }

    // 实例对应的日志句柄
    public Logger logger;

    // 资源文件，记录各种消息，以及日后可能的翻译信息
    public ResourceBundle resourceBundle;

    // PID进程锁信息
    private RandomAccessFile pidRandomAccessFile;
    private FileLock pidFileLockHandler;

    public final ConcurrentHashMap<String,PostgresProxyTarget> proxyTarget = new ConcurrentHashMap<>();

    // 外部通告被内部别名挡掉时的日志节流（同一别名 60 秒最多一条）
    private static final long SHADOW_LOG_INTERVAL_MS = 60_000L;
    private final ConcurrentHashMap<String, Long> shadowedAnnouncementLoggedAt = new ConcurrentHashMap<>();

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
        String alias = announcement.getAlias();
        if (isManagedAlias(alias)) {
            // 别名冲突：内部（目录）优先，外部通告直接丢弃，不写进路由表/状态表
            logShadowedAnnouncement(alias, sourceAddress);
            return;
        }
        String host = (sourceAddress != null && !sourceAddress.isAnyLocalAddress())
                ? sourceAddress.getHostAddress()
                : announcement.getHost();
        registerTarget(alias, announcement.getName(), host,
                announcement.getPort(), announcement.getPortX(),
                System.currentTimeMillis() + Announcement.LEASE_MILLIS,
                "announced by " + sourceAddress);
    }

    /** 别名是否由本地实例目录占用（只看扫描快照，不在注册热路径上触发探测）。 */
    public boolean isManagedAlias(String alias)
    {
        return instanceRegistry != null && instanceRegistry.contains(alias);
    }

    private void logShadowedAnnouncement(String alias, InetAddress sourceAddress)
    {
        long now = System.currentTimeMillis();
        Long previous = shadowedAnnouncementLoggedAt.get(alias);
        if (previous != null && now - previous < SHADOW_LOG_INTERVAL_MS) {
            return;
        }
        shadowedAnnouncementLoggedAt.put(alias, now);
        logger.info("[PROXY][DISCOVERY  ] Alias [{}] is managed locally under data_dir; "
                + "ignoring external announcement from {}.", alias, sourceAddress);
    }

    /**
     * 摘除外部的同名目标（内部实例出现时调用）。
     *
     * <p>组播每 5 秒就会重发一次，所以这里不"抢"也不会长期不一致；但状态表必须立刻
     * 只显示内部实例，不能两个都列出来。</p>
     */
    public void pruneShadowedExternal(String alias)
    {
        if (alias == null) {
            return;
        }
        PostgresProxyTarget removed = proxyTarget.remove(alias);
        if (removed != null) {
            if (proxyInstanceX != null) {
                proxyInstanceX.forwarderPathMappings.remove("/" + alias);
            }
            logger.info("[PROXY][DISCOVERY  ] External alias [{}] ({}:{}) removed: the alias is now managed locally.",
                    alias, removed.host, removed.port);
        }
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
        if (isManagedAlias(alias)) {
            logShadowedAnnouncement(alias, null);
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

    // ------------------------------------------------------------------ 实例管理入口

    /** 是否启用了实例管理（{@code data_dir} 非空）。 */
    public boolean isInstanceManagementEnabled()
    {
        return instanceRegistry != null;
    }

    /** 实例根目录；未启用时为 {@code null}。 */
    public Path getInstanceDataDir()
    {
        return instanceRegistry == null ? null : instanceRegistry.root();
    }

    public InstanceRegistry getInstanceRegistry()
    {
        return instanceRegistry;
    }

    public InstanceController getInstanceController()
    {
        return instanceController;
    }

    /** 会话号（进程内唯一、单调递增）。 */
    public long nextSessionId()
    {
        return sessionIds.incrementAndGet();
    }

    /**
     * 数据面选路：托管实例优先于组播发现的别名。
     *
     * @return 找不到、或实例存在但未运行时返回 {@code null}
     */
    public RouteTarget resolveTarget(String alias)
    {
        if (alias == null || alias.isEmpty()) {
            return null;
        }
        if (instanceRegistry != null) {
            ObservedInstance managed = instanceRegistry.get(alias);
            if (managed != null) {
                if (!managed.routable()) {
                    return null;
                }
                return new RouteTarget("127.0.0.1", managed.pgPort(), managed.dataName());
            }
        }
        PostgresProxyTarget discovered = proxyTarget.get(alias);
        if (discovered == null) {
            return null;
        }
        return new RouteTarget(discovered.host, discovered.port, discovered.database);
    }

    /**
     * 查托管实例的观察态；不是托管实例时返回 {@code null}。
     *
     * <p>数据面用它区分"别名不存在"和"实例存在但没启动"，以便回不同的错误码。</p>
     */
    public ObservedInstance instanceStatus(String alias)
    {
        return instanceRegistry == null ? null : instanceRegistry.get(alias);
    }

    /**
     * 管理口选路。
     *
     * <p>返回 {@code state != null && baseUrl == null} 表示实例存在但当前不可转发
     * （例如 STOPPED），调用方应回 503 而不是 404。</p>
     */
    public ManagementTarget resolveManagementTarget(String alias)
    {
        if (alias == null || alias.isEmpty()) {
            return new ManagementTarget(null, null, alias);
        }
        if (instanceRegistry != null) {
            ObservedInstance managed = instanceRegistry.get(alias);
            if (managed != null) {
                if (managed.routable() && managed.mgmtPort() > 0) {
                    return new ManagementTarget("http://127.0.0.1:" + managed.mgmtPort(), managed.state(), alias);
                }
                return new ManagementTarget(null, managed.state(), alias);
            }
        }
        String baseUrl = proxyInstanceX == null ? null : proxyInstanceX.forwarderPathMappings.get("/" + alias);
        return new ManagementTarget(baseUrl, null, alias);
    }

    /** 内部实例（目录扫描结果）；不存在时返回 {@code null}。 */
    public ObservedInstance findManaged(String alias)
    {
        return instanceRegistry == null ? null : instanceRegistry.get(alias);
    }

    /** 外部后端（组播注册结果）；不存在时返回 {@code null}。 */
    public PostgresProxyTarget findExternal(String alias)
    {
        return alias == null ? null : proxyTarget.get(alias);
    }

    /**
     * 是否是"只能转发、不能治理"的外部后端。
     *
     * <p>同名时内部优先：只要本地目录里有这个别名，就永远按内部实例对待。
     * 这里用 {@link #findManaged}（必要时补探一次），因为生命周期入口是低频路径，
     * 不能因为扫描快照落后 5 秒就把刚手工放进来的实例判成 external。</p>
     */
    public boolean isExternal(String alias)
    {
        if (alias == null || alias.isEmpty()) {
            return false;
        }
        if (findManaged(alias) != null) {
            return false;
        }
        return proxyTarget.containsKey(alias);
    }

    /**
     * 状态列表：内部实例 + 外部注册，统一视图。
     *
     * @param fresh 是否强制重新探测内部实例（CLI/管理口默认 true；{@code /status} 用 false 保持轻量）
     */
    public List<BackendView> listBackends(boolean fresh)
    {
        Map<String, BackendView> merged = new LinkedHashMap<>();

        if (instanceController != null) {
            List<ObservedInstance> managed = fresh
                    ? instanceController.listFresh()
                    : instanceController.list();
            for (ObservedInstance instance : managed) {
                merged.put(instance.alias(), BackendView.management(instance));
            }
        } else if (instanceRegistry != null) {
            for (ObservedInstance instance : instanceRegistry.list()) {
                merged.put(instance.alias(), BackendView.management(instance));
            }
        }

        List<String> shadowed = new ArrayList<>();
        for (Map.Entry<String, PostgresProxyTarget> entry : proxyTarget.entrySet()) {
            if (merged.containsKey(entry.getKey()) || isManagedAlias(entry.getKey())) {
                shadowed.add(entry.getKey());
                continue;
            }
            PostgresProxyTarget target = entry.getValue();
            merged.put(entry.getKey(), BackendView.external(entry.getKey(),
                    target.host, target.port, target.portX, target.database));
        }
        shadowed.forEach(this::pruneShadowedExternal);

        List<BackendView> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(BackendView::alias));
        return result;
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
            File pidFile = new File(this.serverConfiguration.getPid());
            File pidParentFile = pidFile.getParentFile();
            // 如果PID文件目录不存在，则建立这个目录（pid 也可以只是文件名，此时没有父目录）
            if (pidParentFile != null && !pidParentFile.exists())
            {
                var ignored = pidParentFile.mkdirs();
            }

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

        // 实例管理：data_dir 非空即启用。必须在数据面/管理面启动之前就绪，
        // 否则第一波连接会落在"实例管理未初始化"上。
        if (serverConfiguration.isInstanceManagementEnabled()) {
            Path instanceRoot = serverConfiguration.getInstanceDataDir();
            InstanceProbe probe = new InstanceProbe();
            this.instanceRegistry = new InstanceRegistry(instanceRoot, new InstanceScanner(probe));
            this.instanceController = new InstanceController(instanceRoot, this.instanceRegistry, probe,
                    new InstanceLauncher(), this.logger);
            this.instanceRegistry.start();
            logger.info("[PROXY][STARTUP    ] Instance management enabled. data_dir={}", instanceRoot);
        } else {
            logger.info("[PROXY][STARTUP    ] Instance management disabled (data_dir is not set).");
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

        // 停止实例扫描（不级联停止任何实例：代理退出与实例生命周期无关）
        if (this.instanceRegistry != null) {
            this.instanceRegistry.close();
            this.instanceRegistry = null;
            this.instanceController = null;
        }

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
