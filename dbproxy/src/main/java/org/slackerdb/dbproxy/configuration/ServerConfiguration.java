package org.slackerdb.dbproxy.configuration;

import ch.qos.logback.classic.Level;
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
    // 默认日志打印到控制台
    private final String default_log = "console";
    // 默认系统的日志级别为INFO
    private final Level default_log_level = Level.INFO;
    // 默认启动的端口
    private final int default_port = 0;
    // 默认绑定的主机地址
    private final String default_bind = "0.0.0.0";
    // 默认使用全部的CPU作为Netty的后台线程数
    private final int default_max_workers = Runtime.getRuntime().availableProcessors();
    // 默认客户端的超时时间
    private final int default_client_timeout = 600;
    // 默认设置系统默认的语言集
    private final Locale default_locale = Locale.getDefault();
    // 系统的PID文件
    private final String default_pid = "";

    private final boolean default_autoCreate = false;
    private final boolean default_autoOpen = false;
    private final boolean default_autoClose = false;
    private final int default_autoCloseTimeout = 900;
    private final String default_instance_template = "";

    private String   log;
    private Level    log_level;
    private int      port;
    private String   bind;
    private int      max_workers;
    private int      client_timeout;
    private Locale   locale;
    private String   pid;

    private boolean autoCreate;
    private boolean autoOpen;
    private boolean autoClose;
    private int autoCloseTimeout;
    private String instance_template;

    public ServerConfiguration() throws ServerException
    {
        // 系统第一次的值和默认值相同
        log = default_log;
        log_level = default_log_level;
        port = default_port;
        bind = default_bind;
        max_workers = default_max_workers;
        client_timeout = default_client_timeout;
        locale = default_locale;
        pid = default_pid;
        autoCreate = default_autoCreate;
        autoOpen = default_autoOpen;
        autoClose = default_autoClose;
        autoCloseTimeout = default_autoCloseTimeout;
        instance_template = default_instance_template;

        // 初始化默认一个系统的临时端口
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new ServerException(Utils.getMessage("SLACKERDB-00007"));
        }
    }

    // 读取参数配置文件
    public void LoadConfigurationFile(String configurationFileName) throws ServerException {
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
            switch (entry.getKey().toString().toUpperCase())
            {
                case "LOCALE":
                    if (entry.getValue().toString().isEmpty())
                    {
                        locale = this.default_locale;
                    }
                    else {
                        setLocale(entry.getValue().toString());
                    }
                    break;
                case "LOG":
                    if (entry.getValue().toString().isEmpty())
                    {
                        log = this.default_log;
                    }
                    else {
                        setLog(entry.getValue().toString());
                    }
                    break;
                case "LOG_LEVEL":
                    if (entry.getValue().toString().isEmpty())
                    {
                        log_level = this.default_log_level;
                    }
                    else {
                        setLog_level(entry.getValue().toString());
                    }
                    break;
                case "PORT":
                    if (entry.getValue().toString().isEmpty())
                    {
                        port = this.default_port;
                    }
                    else {
                        setPort(entry.getValue().toString());
                    }
                    break;
                case "BIND":
                    if (entry.getValue().toString().isEmpty())
                    {
                        bind = this.default_bind;
                    }
                    else {
                        setBindHost(entry.getValue().toString());
                    }
                    break;
                case "CLIENT_TIMEOUT":
                    if (entry.getValue().toString().isEmpty())
                    {
                        client_timeout= this.default_client_timeout;
                    }
                    else {
                        setClient_timeout(entry.getValue().toString());
                    }
                    break;
                case "MAX_WORKERS":
                    if (entry.getValue().toString().isEmpty())
                    {
                        max_workers= this.default_max_workers;
                    }
                    else {
                        setMax_workers(entry.getValue().toString());
                    }
                    break;
                case "PID":
                    if (entry.getValue().toString().isEmpty())
                    {
                        pid = this.default_pid;
                    }
                    else {
                        setPid(entry.getValue().toString());
                    }
                    break;
                case "AUTOCREATE":
                    if (entry.getValue().toString().isEmpty())
                    {
                        autoCreate = this.default_autoCreate;
                    }
                    else {
                        setAutoCreate(entry.getValue().toString());
                    }
                    break;
                case "AUTOOPEN":
                    if (entry.getValue().toString().isEmpty())
                    {
                        autoOpen = this.default_autoOpen;
                    }
                    else {
                        setAutoOpen(entry.getValue().toString());
                    }
                    break;
                case "AUTOCLOSE":
                    if (entry.getValue().toString().isEmpty())
                    {
                        autoClose = this.default_autoClose;
                    }
                    else {
                        setAutoClose(entry.getValue().toString());
                    }
                    break;
                case "AUTOCLOSETIMEOUT":
                    if (entry.getValue().toString().isEmpty())
                    {
                        autoCloseTimeout = this.default_autoCloseTimeout;
                    }
                    else {
                        setAutoCloseTimeout(entry.getValue().toString());
                    }
                    break;
                case "INSTANCE_TEMPLATE":
                    if (entry.getValue().toString().isEmpty())
                    {
                        instance_template = this.default_instance_template;
                    }
                    else {
                        setInstance_Template(entry.getValue().toString());
                    }
                    break;
                default:
                    throw new ServerException(Utils.getMessage("SLACKERDB-00004", entry.getKey().toString(), configurationFileName));
            }
        }
    }

    public String getLog()
    {
        return log;
    }
    public Level getLog_level() { return log_level;}
    public String getPid() {return pid;}

    public int getPort()
    {
        return port;
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

    public void setMax_workers(String pMax_workers) throws ServerException
    {
        try {
            max_workers = Integer.parseInt(pMax_workers);
        }
        catch (NumberFormatException ignored)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "max_workers", pMax_workers)
            );
        }
        if (max_workers <= 0)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "max_workers", pMax_workers)
            );
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

    public void setPid(String pPid) throws ServerException
    {
        pid = pPid;
    }

    public void setAutoCreate(String pAutoCreate) throws ServerException
    {
        autoCreate = Boolean.parseBoolean(pAutoCreate.toUpperCase());
    }

    public void setAutoClose(String pAutoClose) throws ServerException
    {
        autoClose = Boolean.parseBoolean(pAutoClose.toUpperCase());
    }

    public void setAutoOpen(String pAutoOpen) throws ServerException
    {
        autoOpen = Boolean.parseBoolean(pAutoOpen.toUpperCase());
    }

    public void setAutoCloseTimeout(String pAutoCloseTimeout) throws ServerException
    {
        autoCloseTimeout = Integer.parseInt(pAutoCloseTimeout);
    }

    public void setInstance_Template(String pInstance_Template)
    {
        instance_template = pInstance_Template;
    }

    public boolean getAutoCreate()
    {
        return autoCreate;
    }

    public boolean getAutoClose()
    {
        return autoClose;
    }

    public boolean getAutoOpen()
    {
        return autoOpen;
    }

    public int getAutoCloseTimeout()
    {
        return autoCloseTimeout;
    }

    public String getInstance_Template()
    {
        return instance_template;
    }
}
