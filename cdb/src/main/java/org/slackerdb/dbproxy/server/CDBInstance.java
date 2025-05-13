package org.slackerdb.dbproxy.server;

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

class CDBMonitor extends Thread
{
    public ConcurrentHashMap<String, Long> instanceLastActiveTime = new ConcurrentHashMap<>();
    private CDBInstance cdbInstance;
    public void setCDBInstance(CDBInstance pCDBInstance)
    {
        this.cdbInstance = pCDBInstance;
    }

    @Override
    public void run()
    {
        // 检查是否有已经超过闲置时间的数据库，如果有，就关闭
        while (true)
        {
            if (isInterrupted())
            {
                return;
            }
            if (!(this.cdbInstance.serverConfiguration.getAutoClose() && this.cdbInstance.serverConfiguration.getAutoCloseTimeout() > 0))
            {
                // 如果没有开启自动关闭，则直接退出
                return;
            }
            for (Map.Entry<String, PostgresProxyTarget> proxyTargetEntry: this.cdbInstance.proxyTarget.entrySet())
            {
                if (!instanceLastActiveTime.containsKey(proxyTargetEntry.getKey()))
                {
                    instanceLastActiveTime.put(proxyTargetEntry.getKey(), System.currentTimeMillis());
                }
                else
                {
                    if (proxyTargetEntry.getValue().getDatabase().equals(this.cdbInstance.serverConfiguration.getData()))
                    {
                        // 不能停止最基础的数据库信息
                        continue;
                    }
                    if (proxyTargetEntry.getValue().getDbInstance() != null)
                    {
                        DBInstance dbInstance = proxyTargetEntry.getValue().getDbInstance();
                        if (
                                (dbInstance.dbSessions.isEmpty()) &&
                                        (System.currentTimeMillis() - dbInstance.lastActiveTime > this.cdbInstance.serverConfiguration.getAutoCloseTimeout())
                        )
                        {
                            // 数据库已经闲置太久，将被关闭
                            this.cdbInstance.removeAlias(proxyTargetEntry.getKey());
                            dbInstance.stop();
                        }
                    }
                }
            }
            try {
                Sleeper.sleep(30 * 1000);
            }
            catch (InterruptedException ignored) {}
        }
    }
}

public class CDBInstance {
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
    private final CDBMonitor cdbMonitor = new CDBMonitor();

    // 实例对应的日志句柄
    public Logger logger;

    // 资源文件，记录各种消息，以及日后可能的翻译信息
    public ResourceBundle resourceBundle;

    // PID进程锁信息
    private RandomAccessFile pidRandomAccessFile;
    private FileLock pidFileLockHandler;

    // 记录会话列表
    public final Map<Integer, ProxySession> proxySessions = new ConcurrentHashMap<>();

    protected final Map<String, Boolean> proxyAlias = new ConcurrentHashMap<>();

    public final Map<String,PostgresProxyTarget> proxyTarget = new ConcurrentHashMap<>();

    public CDBInstance(ServerConfiguration pServerConfiguration) throws ServerException
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
        // 设置线程名称好保持日志格式
        Thread.currentThread().setName("SERVER_CDB");

        // 初始化日志服务
        logger = AppLogger.createLogger(
                "SERVER_CDB",
                serverConfiguration.getLog_level().levelStr,
                serverConfiguration.getLog());
        // 只有在停止的状态下才能启动
        if (!this.instanceState.equalsIgnoreCase("IDLE"))
        {
            throw new ServerException("SLACKERDB-00006", getMessage("SLACKERDB-00006"));
        }

        // 服务器开始启动
        logger.info("[SERVER_CDB] Starting ...");
        this.instanceState = "STARTING";
        this.bootTime = LocalDateTime.now();

        // 检查PID文件
        if (!this.serverConfiguration.getPid().isEmpty())
        {
            File pidFile = new File(this.serverConfiguration.getPid());
            // 尝试锁定文件
            try {
                pidRandomAccessFile = new RandomAccessFile(pidFile, "rw");
                pidFileLockHandler = pidRandomAccessFile.getChannel().tryLock();
                if (pidFileLockHandler == null)
                {
                    throw new ServerException(
                            "SLACKERDB-00013",
                            Utils.getMessage("SLACKERDB-00013", this.serverConfiguration.getPid()));
                }
                pidRandomAccessFile.writeBytes(String.valueOf(ProcessHandle.current().pid()));
            } catch (IOException e) {
                throw new ServerException(
                        "SLACKERDB-00013",
                        Utils.getMessage("SLACKERDB-00013", this.serverConfiguration.getPid()));
            }
        }

        // 启动一个初始化的DB，并放入到Proxy中
        // 启动一个数据库, 启动在随机端口上
        org.slackerdb.dbserver.configuration.ServerConfiguration instanceConfiguration =
                new org.slackerdb.dbserver.configuration.ServerConfiguration();
        instanceConfiguration.setAccess_mode(serverConfiguration.getAccess_mode());
        instanceConfiguration.setStartup_script(serverConfiguration.getStartup_script());
        instanceConfiguration.setInit_script(serverConfiguration.getInit_script());
        instanceConfiguration.setSqlHistory(serverConfiguration.getSqlHistory());
        instanceConfiguration.setMax_workers(serverConfiguration.getMax_Workers());
        instanceConfiguration.setThreads(serverConfiguration.getThreads());
        instanceConfiguration.setConnection_pool_maximum_lifecycle_time(serverConfiguration.getConnection_pool_maximum_lifecycle_time());
        instanceConfiguration.setConnection_pool_minimum_idle(serverConfiguration.getConnection_pool_minimum_idle());
        instanceConfiguration.setConnection_pool_maximum_idle(serverConfiguration.getConnection_pool_maximum_idle());
        instanceConfiguration.setLog_level(serverConfiguration.getLog_level());
        instanceConfiguration.setLog(serverConfiguration.getLog());
        instanceConfiguration.setExtension_dir(serverConfiguration.getExtension_dir());
        instanceConfiguration.setMemory_limit(serverConfiguration.getMemory_limit());
        instanceConfiguration.setClient_timeout(serverConfiguration.getClient_timeout());
        instanceConfiguration.setTemp_dir(serverConfiguration.getTemp_dir());
        instanceConfiguration.setMax_connections(serverConfiguration.getMax_connections());
        instanceConfiguration.setTemplate(serverConfiguration.getTemplate());
        instanceConfiguration.setBindHost("127.0.0.1");
        instanceConfiguration.setPort(0);
        instanceConfiguration.setData(serverConfiguration.getData());
        DBInstance dbInstance = new DBInstance(instanceConfiguration);
        dbInstance.start();

        // 注册首个数据库进入
        addAlias(dbInstance.instanceName,
                "127.0.0.1:" +
                        dbInstance.serverConfiguration.getPort() + "/" +
                        dbInstance.serverConfiguration.getData(),
                dbInstance);

        // 启动服务监护进程
        cdbMonitor.start();

        // 启动PG的协议处理程序
        protocolServer = new PostgresProxyServer();
        protocolServer.setLogger(logger);
        protocolServer.setBindHostAndPort(serverConfiguration.getBindHost(), serverConfiguration.getPort());
        protocolServer.setServerTimeout(serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout());
        protocolServer.setNioEventThreads(serverConfiguration.getMax_Workers());
        protocolServer.setCdbInstance(this);
        protocolServer.start();
        while (!protocolServer.isPortReady()) {
            // 等待Netty进程就绪
            try {
                Sleeper.sleep(1000);
            }
            catch (InterruptedException ignored) {}
        }

        // 标记服务已经启动完成
        this.instanceState = "RUNNING";
        logger.info("[SERVER_CDB] Server is running.");
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

        // 停止监护进程
        cdbMonitor.interrupt();
        while (cdbMonitor.isAlive())
        {
            try {
                Sleeper.sleep(3 * 1000);
            } catch (InterruptedException ignored) {}
        }
        cdbMonitor.instanceLastActiveTime.clear();

        // 停止对外网络服务
        protocolServer.stop();

        // 删除PID文件
        if (pidRandomAccessFile != null )
        {
            try {
                pidFileLockHandler.release();
                pidFileLockHandler = null;

                pidRandomAccessFile.close();
                File pidFile = new File(this.serverConfiguration.getPid());
                if (pidFile.exists()) {
                    if (!pidFile.delete()) {
                        logger.warn("[SERVER_CDB] Remove pid file [{}] failed, reason unknown!",
                                this.serverConfiguration.getPid());
                    }
                }
                pidRandomAccessFile = null;
            }
            catch (IOException ioe) {
                logger.warn("[SERVER_CDB] Remove pid file [{}] failed, reason unknown!",
                        this.serverConfiguration.getPid(), ioe);
            }
        }

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

    public synchronized void removeAlias(String aliasName) throws ServerException
    {
        if (!proxyAlias.containsKey(aliasName))
        {
            throw new ServerException(
                    "SLACKERDB-00019",
                    Utils.getMessage("SLACKERDB-00019", aliasName));
        }
        proxyAlias.remove(aliasName);
    }

    public synchronized void addAlias(String aliasName, String remoteTarget, DBInstance dbInstance) throws ServerException
    {
        if (proxyAlias.containsKey(aliasName))
        {
            throw new ServerException("[SERVER_CDB] alias {} already exist!", aliasName);
        }
        PostgresProxyTarget proxyTargetList = new PostgresProxyTarget(remoteTarget, dbInstance);
        proxyTarget.put(aliasName, proxyTargetList);
    }
}
