package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;

import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.*;

public class ProxyInstance {
    // 服务器启动模式
    // 是否为独占模式，默认否
    // 当程序为独占模式的时候，退出端口，意味着程序也将退出
    private boolean exclusiveMode = false;

    // 服务器启动的时间
    public LocalDateTime bootTime = null;

    // 实例的状态
    public String instanceState = "IDLE";

    // 服务器配置参数
    public ServerConfiguration serverConfiguration;

    // 服务器对应的PG协议转发器
    private PostgresProxyServer protocolServer;

    // 实例对应的日志句柄
    public Logger logger;

    // 资源文件，记录各种消息，以及日后可能的翻译信息
    public ResourceBundle resourceBundle;

    // 为每个连接创建一个会话ID
    private int maxSessionId = 1000;

    // 记录会话列表
    public final Map<Integer, ProxySession> proxySessions = new HashMap<>();

    private final Map<String, Boolean> proxyAlias = new HashMap<>();

    private final Map<String, List<PostgresProxyTarget>> proxyTarget = new HashMap<>();

    public ProxyInstance(ServerConfiguration pServerConfiguration) throws ServerException
    {
        // 加载数据库的配置信息
        serverConfiguration = pServerConfiguration;

        // 设置消息语言集
        resourceBundle = ResourceBundle.getBundle("message", serverConfiguration.getLocale());
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

    // 根据参数配置文件启动代理实例
    public synchronized void start() throws ServerException {
        // 初始化日志服务
        logger = AppLogger.createLogger(
                "PROXY",
                serverConfiguration.getLog_level().levelStr,
                serverConfiguration.getLog());

        // 只有在停止的状态下才能启动
        if (!this.instanceState.equalsIgnoreCase("IDLE"))
        {
            throw new ServerException("SLACKERDB-00006", getMessage("SLACKERDB-00006"));
        }

        // 服务器开始启动
        this.instanceState = "STARTING";
        this.bootTime = LocalDateTime.now();

        // 启动PG的协议处理程序
        protocolServer = new PostgresProxyServer();
        protocolServer.setLogger(logger);
        protocolServer.setBindHostAndPort(serverConfiguration.getBindHost(), serverConfiguration.getPort());
        protocolServer.setServerTimeout(serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout());
        protocolServer.setNioEventThreads(serverConfiguration.getMax_Workers());
        protocolServer.setProxyInstance(this);
        protocolServer.start();
        while (protocolServer.isPortReady()) {
            // 等待Netty进程就绪
            Sleeper.sleep(1000);
        }

        // 标记服务已经启动完成
        this.instanceState = "RUNNING";
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

        // 停止对外网络服务
        protocolServer.stop();

        // 服务状态标记为空闲
        this.instanceState = "IDLE";
    }

    // 根据SessionID获取对应的Session信息
    public ProxySession getSession(int sessionId)
    {
        return proxySessions.get(sessionId);
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

    public synchronized void createAlias(String aliasName, boolean checkHeartBeat) throws ServerException
    {
        if (proxyAlias.containsKey(aliasName))
        {
            throw new ServerException(
                    "SLACKERDB-00013",
                    Utils.getMessage("SLACKERDB-00013"));
        }
        proxyAlias.put(aliasName, checkHeartBeat);
        proxyTarget.put(aliasName, new ArrayList<>());
    }

    public synchronized void addAliasTarget(String aliasName, String host, int port, int weight) throws ServerException
    {
        if (!proxyAlias.containsKey(aliasName))
        {
            throw new ServerException(
                    "SLACKERDB-00018",
                    Utils.getMessage("SLACKERDB-00018", aliasName));
        }
        List<PostgresProxyTarget> proxyTargetList = proxyTarget.get(aliasName);
        for (PostgresProxyTarget target : proxyTargetList)
        {
            if (target.getHost().equalsIgnoreCase(host) && target.getPort() == port)
            {
                throw new ServerException(
                        "SLACKERDB-00014",
                        Utils.getMessage("SLACKERDB-00014", aliasName, host, port));
            }
        }
        proxyTargetList.add(new PostgresProxyTarget(host, port, weight));
        proxyTarget.put(aliasName, proxyTargetList);
    }


    public PostgresProxyTarget getAvailableTarget(String aliasName) throws ServerException
    {
        if (!proxyAlias.containsKey(aliasName))
        {
            throw new ServerException("SLACKERDB-00015", Utils.getMessage("SLACKERDB-00015", aliasName));
        }
        if (proxyTarget.get(aliasName).isEmpty())
        {
            throw new ServerException("SLACKERDB-00016", Utils.getMessage("SLACKERDB-00016", aliasName));
        }
        List<PostgresProxyTarget> proxyTargets = proxyTarget.get(aliasName);
        if (proxyTargets.size() == 1)
        {
            // 如果只有一个候选，则返回候选
            PostgresProxyTarget postgresProxyTarget = proxyTargets.get(0);
            if (postgresProxyTarget.getWeight() != 0) {
                return proxyTargets.get(0);
            }
            else
            {
                throw new ServerException("SLACKERDB-00017", Utils.getMessage("SLACKERDB-00017", aliasName));
            }
        }
        // 计算权重，按照权重返回
        int totalWeight = 0;
        for (PostgresProxyTarget proxyTarget : proxyTargets) {
            totalWeight += proxyTarget.getWeight();
        }
        // 生成 1 到 totalWeight 范围的随机数
        int randomValue = new Random().nextInt(totalWeight) + 1;
        // 根据权重分布选择元素
        for (PostgresProxyTarget proxyTarget : proxyTargets) {
            randomValue -= proxyTarget.getWeight();
            if (randomValue <= 0) {
                return proxyTarget;
            }
        }
        throw new ServerException("SLACKERDB-00017", Utils.getMessage("SLACKERDB-00017", aliasName));
    }
}
