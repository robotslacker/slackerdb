package org.slackerdb.dbproxy.configuration;

import ch.qos.logback.classic.Level;
import org.slackerdb.common.discovery.MulticastAddress;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class ServerConfiguration extends Throwable {
    // 设置默认参数
    // 默认日志打印到控制台
    private final String default_log = "console";
    // 默认系统的日志级别为INFO
    private final Level default_log_level = Level.INFO;
    // 默认启动的端口
    private final int default_port = 0;
    // 默认数据库的管理端口
    private final int default_portX = 0;

    // 默认绑定的主机地址
    private final String default_bind = "0.0.0.0";
    // 组播服务发现的监听地址（<组IP>:<端口>）。刻意没有默认值：不配置就是不启用发现，
    // 此时后端一条都不会被登记——旧的单播 REGISTER 注册通道已被删除，没有别的兜底路径。
    //
    // 必须与 dbserver 的 advertise_address 逐字一致，否则表现为"什么都发现不了"。
    private final String default_listener_address = "";
    // 默认使用全部的CPU作为Netty的后台线程数
    private final int default_max_workers = Runtime.getRuntime().availableProcessors();
    // 默认客户端的超时时间
    private final int default_client_timeout = 600;
    // 默认设置系统默认的语言集
    private final Locale default_locale = Locale.getDefault();
    // 系统的PID文件
    private final String default_pid = "";
    // 是否用后台方式启动
    private final boolean defaultDaemonMode = false;

    private String   log;
    private Level    log_level;
    private int      port;
    private int      portX;
    private String   bind;
    private String   listener_address;
    private int      max_workers;
    private int      client_timeout;
    private Locale   locale;
    private String   pid;
    private boolean  daemonMode;

    public ServerConfiguration() throws ServerException
    {
        log = default_log;
        log_level = default_log_level;
        port = default_port;
        portX = default_portX;
        bind = default_bind;
        listener_address = default_listener_address;
        max_workers = default_max_workers;
        client_timeout = default_client_timeout;
        locale = default_locale;
        pid = default_pid;
        daemonMode = defaultDaemonMode;
        // 初始化默认一个系统的临时端口
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new ServerException(Utils.getMessage("SLACKERDB-00007"));
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            portX = socket.getLocalPort();
        } catch (IOException e) {
            throw new ServerException(Utils.getMessage("SLACKERDB-00007"));
        }
    }

    // 读取参数配置文件
    public void loadConfigurationFile(String configurationFileName) throws ServerException {
        Properties appProperties = new Properties();
        File configurationFile;

        // 首先读取参数配置里头的信息
        if (configurationFileName != null)
        {
            configurationFile = new File(configurationFileName);
            try (InputStream input = new FileInputStream(configurationFile)) {
                // 加载属性文件
                appProperties.load(input);
            } catch (Exception ex) {
                throw new ServerException(Utils.getMessage("SLACKERDB-00003", configurationFileName), ex);
            }
        }

        // 加载配置信息，根据参数配置信息修改默认配置
        for (Map.Entry<Object, Object> entry : appProperties.entrySet()) {
            switch (entry.getKey().toString().toUpperCase()) {
                case "DAEMON" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        daemonMode = this.defaultDaemonMode;
                    } else {
                        setDaemon(entry.getValue().toString().trim());
                    }
                }
                case "LOCALE" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        locale = this.default_locale;
                    } else {
                        setLocale(entry.getValue().toString().trim());
                    }
                }
                case "LOG" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        log = this.default_log;
                    } else {
                        setLog(entry.getValue().toString().trim());
                    }
                }
                case "LOG_LEVEL" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        log_level = this.default_log_level;
                    } else {
                        setLog_level(entry.getValue().toString().trim());
                    }
                }
                case "PORT" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        port = this.default_port;
                    } else {
                        setPort(entry.getValue().toString().trim());
                    }
                }
                case "PORT_X" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        portX = this.default_portX;
                    } else {
                        setPortX(entry.getValue().toString().trim());
                    }
                }
                case "BIND" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        bind = this.default_bind;
                    } else {
                        setBindHost(entry.getValue().toString().trim());
                    }
                }
                case "CLIENT_TIMEOUT" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        client_timeout = this.default_client_timeout;
                    } else {
                        setClient_timeout(entry.getValue().toString().trim());
                    }
                }
                case "MAX_WORKERS" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        max_workers = this.default_max_workers;
                    } else {
                        setMax_workers(entry.getValue().toString().trim());
                    }
                }
                case "LISTENER_ADDRESS" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        listener_address = this.default_listener_address;
                    } else {
                        setListenerAddress(entry.getValue().toString().trim());
                    }
                }
                case "PID" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        pid = this.default_pid;
                    } else {
                        setPid(entry.getValue().toString().trim());
                    }
                }
                default ->
                        throw new ServerException(Utils.getMessage("SLACKERDB-00004", entry.getKey().toString(), configurationFileName));
            }
        }
    }

    public String getLog()
    {
        return log;
    }
    public Level getLog_level() { return log_level;}

    public int getPort()
    {
        return port;
    }
    public int getPortX()
    {
        return portX;
    }
    public String getBindHost()
    {
        return bind;
    }
    public int getClient_timeout()
    {
        return client_timeout;
    }
    public int getMax_Workers()
    {
        return max_workers;
    }

    public Locale getLocale()
    {
        return locale;
    }
    public String getPid() {return pid;}
    public boolean getDaemonMode()
    {
        return this.daemonMode;
    }

    /**
     * 组播监听地址（已解析）。未配置时返回 {@code null}。
     *
     * <p>解析结果不在构造期缓存：配置文件可以反复加载，缓存会让"后加载的值不生效"。</p>
     */
    public MulticastAddress getListenerAddress()
    {
        return listener_address.isEmpty() ? null : MulticastAddress.parse(listener_address);
    }

    /** 组播服务发现是否已启用（只看配置，不看 socket 是否真的建起来了）。 */
    public boolean isDiscoveryEnabled()
    {
        return !listener_address.isEmpty();
    }

    /**
     * 设置组播监听地址。
     *
     * <p>与 dbserver 的 {@code advertise_address} 必须逐字一致。格式错误在这里就报错（启动期），
     * 因为组播配错的表现是"静默不发现"，越早失败越好。</p>
     */
    public void setListenerAddress(String pListenerAddress) throws ServerException
    {
        if (pListenerAddress == null || pListenerAddress.trim().isEmpty())
        {
            listener_address = this.default_listener_address;
            return;
        }
        try {
            listener_address = MulticastAddress.parse(pListenerAddress.trim()).toString();
        }
        catch (IllegalArgumentException iae) {
            throw new ServerException("SLACKERDB-00020",
                    Utils.getMessage("SLACKERDB-00020", pListenerAddress, iae.getMessage())
            );
        }
    }

    public void setLog_level(String pLog_level) throws ServerException {
        log_level = Level.valueOf(pLog_level);

        if (!log_level.levelStr.equalsIgnoreCase(pLog_level))
        {
            // 如果LogLevel的参数有错误，设置内容将不会有效
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "log_level", pLog_level)
            );
        }
    }

    public void setLog(String pLog) { log = pLog.trim();}
    public void setBindHost(String pHost)
    {
        bind = pHost;
    }

    public void setPort(String pPort) throws ServerException
    {
        int tempPort;
        try {
            tempPort = Integer.parseInt(pPort);
        }
        catch (NumberFormatException ignored)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "port", pPort)
            );
        }
        if (tempPort == 0) {
            try (ServerSocket socket = new ServerSocket(0)) {
                tempPort = socket.getLocalPort();
            } catch (IOException e) {
                throw new ServerException(Utils.getMessage("SLACKERDB-00007"));
            }
        }

        if (tempPort < -1)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "port", pPort)
            );
        }
        port = tempPort;
    }

    public void setPort(int pPort) throws ServerException
    {
        int tempPort = pPort;
        if (tempPort == 0) {
            try (ServerSocket socket = new ServerSocket(0)) {
                tempPort = socket.getLocalPort();
            } catch (IOException e) {
                throw new ServerException(Utils.getMessage("SLACKERDB-00007"));
            }
        }
        if (tempPort < -1)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "port", pPort)
            );
        }
        port = tempPort;
    }

    public void setPortX(String pPort) throws ServerException
    {
        int tempPort;
        try {
            tempPort = Integer.parseInt(pPort);
        }
        catch (NumberFormatException ignored)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "port", pPort)
            );
        }
        if (tempPort == 0) {
            try (ServerSocket socket = new ServerSocket(0)) {
                tempPort = socket.getLocalPort();
            } catch (IOException e) {
                throw new ServerException(Utils.getMessage("SLACKERDB-00007"));
            }
        }

        if (tempPort < -1)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "port", pPort)
            );
        }
        this.setPortX(tempPort);
    }

    public void setPortX(int pPort) throws ServerException
    {
        int tempPort = pPort;
        if (tempPort == 0) {
            try (ServerSocket socket = new ServerSocket(0)) {
                tempPort = socket.getLocalPort();
            } catch (IOException e) {
                throw new ServerException(Utils.getMessage("SLACKERDB-00007"));
            }
        }
        if (tempPort < -1)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "port", pPort)
            );
        }
        portX = tempPort;
    }


    public void setMax_workers(String pMax_workers) throws ServerException
    {
        int tempMax_workers;
        try {
            tempMax_workers = Integer.parseInt(pMax_workers);
        }
        catch (NumberFormatException ignored)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "max_workers", pMax_workers)
            );
        }
        if (tempMax_workers <= 0)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "max_workers", pMax_workers)
            );
        }
        this.setMax_workers(tempMax_workers);
    }

    public void setDaemon(String pDaemonMode) throws ServerException {
        if (pDaemonMode.trim().equalsIgnoreCase("true"))
        {
            daemonMode = true;
        } else if (pDaemonMode.trim().equalsIgnoreCase("false"))
        {
            daemonMode = false;
        }
        else {
            throw new ServerException("Invalid daemon mode parameter. true/false only.");
        }
    }


    public void setMax_workers(int pMax_workers) throws ServerException
    {
        max_workers = pMax_workers;
        if (max_workers <= 0)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "max_workers", pMax_workers)
            );
        }
    }

    public void setLocale(String pLocale) throws ServerException
    {
        if (pLocale == null || pLocale.isEmpty())
        {
            locale = Locale.getDefault();
        }
        else
        {
            try
            {
                locale = Utils.toLocale(pLocale);
            }
            catch (IllegalArgumentException ignored)
            {
                throw new ServerException(
                        Utils.getMessage("SLACKERDB-00005", "locale", pLocale)
                );
            }
        }
    }

    public void setClient_timeout(String pClient_Timeout) throws ServerException {
        try {
            client_timeout = Integer.parseInt(pClient_Timeout);
        }
        catch (NumberFormatException ignored)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "client_timeout", pClient_Timeout)
            );
        }
        if (client_timeout <= 0)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "client_timeout", pClient_Timeout)
            );
        }
    }

    public void setPid(String pPid)
    {
        pid = pPid;
    }
}

