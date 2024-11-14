package org.slackerdb.dbserver.configuration;

import org.slackerdb.common.exceptions.ServerException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import ch.qos.logback.classic.Level;
import org.slackerdb.common.utils.Utils;

public class ServerConfiguration extends Throwable {
    // 设置默认参数
    // 默认的数据库名称
    private final String default_data = "slackerdb";
    // 默认启动在内存下
    private final String default_data_dir = ":memory:";
    // 内存模式和文件模式的默认值不同
    // 内存模式: 系统的临时目录
    // 文件模式: 等同data_dir
    private final String default_temp_dir = "";
    // 内存模式和文件模式的默认值不同
    // 内存模式: data
    // 文件模式: 等同data_dir
    private final String default_plsql_func_dir = "";

    //  默认使用系统的配置，即不主动配置
    private final String default_extension_dir = "";
    // 默认日志打印到控制台
    private final String default_log = "console";
    // 默认系统的日志级别为INFO
    private final Level default_log_level = Level.INFO;
    // 默认启动的端口
    private final int default_port = 0;
    // 默认绑定的主机地址
    private final String default_bind = "0.0.0.0";
    // 默认不限制内存使用情况
    private final String default_memory_limit = "";
    // 默认使用一半的CPU作为计算线程
    private final int default_threads = (int)(Runtime.getRuntime().availableProcessors() * 0.5);
    // 默认数据库可读可写
    private final String default_access_mode = "READ_WRITE";
    // 默认使用全部的CPU作为Netty的后台线程数
    private final int default_max_workers = Runtime.getRuntime().availableProcessors();
    // 默认客户端的超时时间
    private final int default_client_timeout = 600;
    // 默认不配置初始化脚本
    private final String default_init_schema = "";
    // 默认不记录SQL执行历史信息
    private final String default_sqlHistory = "";
    // 内存模式和文件模式的默认值不同
    // 内存模式: data
    // 文件模式: 等同data_dir
    private final String default_sqlHistoryDir = "";
    // 默认的SqlHistory对外开放端口
    private final int default_sqlHistoryPort = 0;
    // 默认设置系统默认的语言集
    private final Locale default_locale = Locale.getDefault();
    // 系统的PID文件
    private final String default_pid = "";

    private String   data;

    private String   data_dir;
    private String   temp_dir;
    private String   plsql_func_dir;
    private String   sqlHistoryDir;

    private String   extension_dir;
    private String   log;
    private Level    log_level;
    private int      port;
    private String   bind;
    private String   memory_limit;
    private int      threads;
    private String   access_mode;
    private int      max_workers;
    private int      client_timeout;
    private String   init_schema;
    private int      sqlHistoryPort;
    private String   sqlHistory;
    private Locale   locale;
    private String   pid;

    public ServerConfiguration() throws ServerException
    {
        // 系统第一次的值和默认值相同
        data = default_data;

        data_dir = default_data_dir;
        temp_dir = default_temp_dir;
        plsql_func_dir = default_plsql_func_dir;
        sqlHistoryDir = default_sqlHistoryDir;
        extension_dir = default_extension_dir;

        log = default_log;
        log_level = default_log_level;
        port = default_port;
        bind = default_bind;
        memory_limit = default_memory_limit;
        threads = default_threads;
        access_mode = default_access_mode;
        max_workers = default_max_workers;
        client_timeout = default_client_timeout;
        init_schema = default_init_schema;
        sqlHistory = default_sqlHistory;
        sqlHistoryPort = default_sqlHistoryPort;
        locale = default_locale;
        pid = default_pid;

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
                case "DATA":
                    if (entry.getValue().toString().isEmpty())
                    {
                        data = this.default_data;
                    }
                    else {
                        setData(entry.getValue().toString());
                    }
                    break;
                case "DATA_DIR":
                    if (entry.getValue().toString().isEmpty())
                    {
                        data_dir = this.default_data_dir;
                    }
                    else {
                        setData_dir(entry.getValue().toString());
                    }
                    break;
                case "TEMP_DIR":
                    if (entry.getValue().toString().isEmpty())
                    {
                        temp_dir = this.default_temp_dir;
                    }
                    else {
                        setTemp_dir(entry.getValue().toString());
                    }
                    break;
                case "EXTENSION_DIR":
                    if (entry.getValue().toString().isEmpty())
                    {
                        extension_dir = this.default_extension_dir;
                    }
                    else {
                        setExtension_dir(entry.getValue().toString());
                    }
                    break;
                case "PLSQL_FUNC_DIR":
                    if (entry.getValue().toString().isEmpty())
                    {
                        plsql_func_dir= this.default_plsql_func_dir;
                    }
                    else {
                        setPlsql_func_dir(entry.getValue().toString());
                    }
                    break;
                case "LOG":
                    if (entry.getValue().toString().isEmpty())
                    {
                        log= this.default_log;
                    }
                    else {
                        setLog(entry.getValue().toString());
                    }
                    break;
                case "LOG_LEVEL":
                    if (entry.getValue().toString().isEmpty())
                    {
                        log_level= this.default_log_level;
                    }
                    else {
                        setLog_level(entry.getValue().toString());
                    }
                    break;
                case "PORT":
                    if (entry.getValue().toString().isEmpty())
                    {
                        port= this.default_port;
                    }
                    else {
                        setPort(entry.getValue().toString());
                    }
                    break;
                case "BIND":
                    if (entry.getValue().toString().isEmpty())
                    {
                        bind= this.default_bind;
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
                case "ACCESS_MODE":
                    if (entry.getValue().toString().isEmpty())
                    {
                        access_mode= this.default_access_mode;
                    }
                    else {
                        setAccess_mode(entry.getValue().toString());
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
                case "THREADS":
                    if (entry.getValue().toString().isEmpty())
                    {
                        threads= this.default_threads;
                    }
                    else {
                        setThreads(entry.getValue().toString());
                    }
                    break;
                case "MEMORY_LIMIT":
                    if (entry.getValue().toString().isEmpty())
                    {
                        memory_limit= this.default_memory_limit;
                    }
                    else {
                        setMemory_limit(entry.getValue().toString());
                    }
                    break;
                case "INIT_SCHEMA":
                    if (entry.getValue().toString().isEmpty())
                    {
                        init_schema= this.default_init_schema;
                    }
                    else {
                        setInit_schema(entry.getValue().toString());
                    }
                    break;
                case "SQL_HISTORY":
                    if (entry.getValue().toString().isEmpty())
                    {
                        sqlHistory= this.default_sqlHistory;
                    }
                    else {
                        setSqlHistory(entry.getValue().toString());
                    }
                    break;
                case "SQL_HISTORY_PORT":
                    if (entry.getValue().toString().isEmpty())
                    {
                        sqlHistoryPort= this.default_sqlHistoryPort;
                    }
                    else {
                        setSqlHistoryPort(entry.getValue().toString());
                    }
                    break;
                case "SQL_HISTORY_DIR":
                    if (entry.getValue().toString().isEmpty())
                    {
                        sqlHistoryDir= this.default_sqlHistoryDir;
                    }
                    else {
                        setSqlHistoryDir(entry.getValue().toString());
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
    public String getAccess_mode()
    {
        return access_mode;
    }
    public String getData()
    {
        return data;
    }
    public String getData_Dir()
    {
        return data_dir;
    }

    public String getMemory_limit()
    {
        return memory_limit;
    }

    public int getThreads()
    {
        return threads;
    }

    public int getMax_Workers()
    {
        return max_workers;
    }

    public String getTemp_dir()
    {
        if (temp_dir.isEmpty())
        {
            if (data_dir.isEmpty() || data_dir.equalsIgnoreCase(":MEMORY:"))
            {
                return System.getProperty("java.io.tmpdir");
            }
            else
            {
                return data_dir;
            }
        }
        else
        {
            return temp_dir;
        }
    }

    public String getExtension_dir()
    {
        return extension_dir;
    }
    public String getInit_schema()
    {
        return init_schema;
    }

    public String getPlsql_func_dir()
    {
        if (plsql_func_dir.isEmpty())
        {
            if (data_dir.isEmpty() || data_dir.equalsIgnoreCase(":MEMORY:"))
            {
                return "data";
            }
            else
            {
                return data_dir;
            }
        }
        else
        {
            return plsql_func_dir;
        }
    }


    public int getSqlHistoryPort()
    {
        return sqlHistoryPort;
    }

    public String getSqlHistory()
    {
        return sqlHistory;
    }

    public String getSqlHistoryDir()
    {
        if (sqlHistoryDir.isEmpty())
        {
            if (data_dir.isEmpty() || data_dir.equalsIgnoreCase(":MEMORY:"))
            {
                return ":mem:";
            }
            else
            {
                return data_dir;
            }
        }
        else
        {
            return sqlHistoryDir;
        }
    }

    public Locale getLocale()
    {
        return locale;
    }
    public String getPid() {return pid;};

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

    public void setTemp_dir(String pTemp_dir)
    {
        temp_dir = pTemp_dir;
    }

    public void setExtension_dir(String pExtension_dir)
    {
        extension_dir = pExtension_dir;
    }

    public void setData(String pData)
    {
        data = pData;
    }

    public void setData_dir(String pData_dir)
    {
        data_dir = pData_dir;
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

    public void setThreads(String pThreads) throws ServerException
    {
        try {
            threads = Integer.parseInt(pThreads);
        }
        catch (NumberFormatException ignored)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "threads", pThreads)
            );
        }
        if (threads <= 0)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "threads", pThreads)
            );
        }
    }

    public void setThreads(int pThreads) throws ServerException
    {
        threads = pThreads;
        if (threads <= 0)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "threads", pThreads)
            );
        }
    }

    public void setMemory_limit(String pMemory_limit)
    {
        memory_limit = pMemory_limit;
    }
    public void setInit_schema(String pInit_schema)
    {
        init_schema = pInit_schema;
    }
    public void setPlsql_func_dir(String pPlsql_func_dir)
    {
        plsql_func_dir = pPlsql_func_dir;
    }
    public void setSqlHistory(String pSQLHistory)
    {
        sqlHistory = pSQLHistory;
    }

    public void setSqlHistoryPort(String pSQLHistoryPort) throws ServerException
    {
        int tempPort;
        try {
            tempPort = Integer.parseInt(pSQLHistoryPort);
        }
        catch (NumberFormatException ignored)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "sql_history_port", pSQLHistoryPort)
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
                    Utils.getMessage("SLACKERDB-00005", "sql_history_port", pSQLHistoryPort)
            );
        }
        sqlHistoryPort = tempPort;
    }

    public void setSqlHistoryDir(String pSQLHistoryDir)
    {
        sqlHistoryDir = pSQLHistoryDir;
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

    public void setAccess_mode(String pAccessMode) throws ServerException
    {
        if (!pAccessMode.equalsIgnoreCase("READ_WRITE") && !pAccessMode.equalsIgnoreCase("READ_ONLY"))
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "access_mode", pAccessMode)
            );
        }
        access_mode = pAccessMode;
    }

    public void setPid(String pPid) throws ServerException
    {
        pid = pPid;
    }
}
