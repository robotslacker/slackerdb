package org.slackerdb.cdb.server;

import ch.qos.logback.classic.Logger;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.cdb.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
