package org.slackerdb.dbserver.configuration;

import org.slackerdb.common.exceptions.ServerException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import ch.qos.logback.classic.Level;
import org.slackerdb.common.utils.Utils;

public class ServerConfiguration {
    // 设置默认参数
    // 默认的数据库名称
    private final String default_data = "";
    // 默认启动在内存下
    private final String default_data_dir = ":memory:";
    // 内存模式和文件模式的默认值不同
    // 内存模式: 系统的临时目录
    // 文件模式: 等同data_dir
    private final String default_temp_dir = "";

    // 是否开启数据库的加密特性
    private final boolean default_data_encrypt = false;

    //  默认使用系统的配置，即不主动配置
    private final String default_extension_dir = "";

    // 默认日志打印到控制台
    private final String default_log = "console";

    // 默认系统的日志级别为INFO
    private final Level default_log_level = Level.INFO;

    // 默认数据库访问服务的端口
    private final int default_port = 0;

    // 默认数据库的管理端口
    private final int default_portX = -1;

    // 默认的插件目录
    private final String default_plugins_dir = "";

    // 默认绑定的主机地址
    private final String default_bind = "0.0.0.0";
    // 外部监听地址，用来多个数据库服务在统一的外部服务下进行消息转发
    private final String default_remote_listener = "";
    // 默认不限制内存使用情况
    private final String default_memory_limit;
    // 默认使用一半的CPU作为计算线程
    private final int default_threads = (int)(Runtime.getRuntime().availableProcessors() * 0.5);
    // 默认数据库可读可写
    private final String default_access_mode = "READ_WRITE";

    // 默认使用全部的CPU作为Netty的后台线程数
    private final int default_max_workers = Runtime.getRuntime().availableProcessors();
    // 默认客户端的超时时间
    private final int default_client_timeout = 600;
    // 默认不配置初始化脚本
    private final String default_init_script = "";
    private final String default_startup_script = "";
    // 默认不记录SQL执行历史信息
    private final String default_sqlHistory = "OFF";
    // 默认不记录API执行历史信息
    private final String default_data_service_history = "OFF";
    // 默认设置系统默认的语言集
    private final Locale default_locale = Locale.getDefault();
    // 系统的PID文件
    private final String default_pid = "";
    // 系统支持的最大同时客户端连接
    private final int    default_max_connections = 256;
    // 系统的启动模板文件
    private final String default_template = "";

    // 是否用后台方式启动
    private final boolean defaultDaemonMode = false;
    // 设置数据库连接池的各项参数
    private final int default_connection_pool_minimum_idle = 3;
    private final int default_connection_pool_maximum_idle = 10;
    private final int default_connection_pool_maximum_lifecycle_time = 15*60*1000;

    // 数据库API查询结果缓存区大小
    private final long default_query_result_cache_size = 1024 * 1024 * 1024;

    // 数据服务初始化加载文件
    private final String default_data_service_schema = "";
    // MCP配置路径
    private final String default_mcp_config = "";

    private String   data;

    private String   data_dir;
    private String   temp_dir;

    private String   extension_dir;
    private String   plugins_dir;
    private String   log;
    private Level    log_level;
    private int      port;
    private int      portX;
    private String   bind;
    private String   remote_listener;
    private String   memory_limit;
    private int      threads;
    private String   access_mode;
    private int      max_workers;
    private int      client_timeout;
    private String   init_script;
    private String   startup_script;
    private String   sqlHistory;
    private String   data_service_history;
    private Locale   locale;
    private String   pid;
    private int      max_connections;
    private String   template;
    private boolean  daemonMode;
    private boolean  data_encrypt;
    private int connection_pool_minimum_idle;
    private int connection_pool_maximum_idle;
    private int connection_pool_maximum_lifecycle_time;
    private long query_result_cache_size;
    private String data_service_schema;
    private String mcp_config;

    public ServerConfiguration() throws ServerException
    {
        // 系统第一次的值和默认值相同
        data = default_data;

        data_dir = default_data_dir;
        temp_dir = default_temp_dir;
        extension_dir = default_extension_dir;

        log = default_log;
        log_level = default_log_level;
        port = default_port;
        portX = default_portX;
        bind = default_bind;
        remote_listener = default_remote_listener;
        threads = default_threads;
        access_mode = default_access_mode;
        max_workers = default_max_workers;
        client_timeout = default_client_timeout;
        init_script = default_init_script;
        startup_script = default_startup_script;
        sqlHistory = default_sqlHistory;
        locale = default_locale;
        pid = default_pid;
        max_connections = default_max_connections;
        connection_pool_minimum_idle = default_connection_pool_minimum_idle;
        connection_pool_maximum_idle = default_connection_pool_maximum_idle;
        connection_pool_maximum_lifecycle_time = default_connection_pool_maximum_lifecycle_time;
        template = default_template;
        daemonMode = defaultDaemonMode;
        query_result_cache_size = default_query_result_cache_size;
        data_service_schema = default_data_service_schema;
        data_service_history = default_data_service_history;
        mcp_config = default_mcp_config;
        data_encrypt = default_data_encrypt;
        plugins_dir = default_plugins_dir;

        // 初始化默认一个系统的临时端口
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new ServerException(Utils.getMessage("SLACKERDB-00007"));
        }

        // 系统默认内存为系统可用内存的60%
        OperatingSystemMXBean operatingSystemMXBean = (OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        default_memory_limit =
                (int) ((operatingSystemMXBean.getTotalMemorySize() * 0.6 )/ 1024 / 1024 /1024) + "G";
        memory_limit = default_memory_limit;
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
                case "DATA" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        data = this.default_data;
                    } else {
                        setData(entry.getValue().toString().trim());
                    }
                }
                case "DATA_DIR" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        data_dir = this.default_data_dir;
                    } else {
                        setData_dir(entry.getValue().toString().trim());
                    }
                }
                case "TEMP_DIR" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        temp_dir = this.default_temp_dir;
                    } else {
                        setTemp_dir(entry.getValue().toString().trim());
                    }
                }
                case "EXTENSION_DIR" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        extension_dir = this.default_extension_dir;
                    } else {
                        setExtension_dir(entry.getValue().toString().trim());
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
                case "REMOTE_LISTENER" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        remote_listener = this.default_remote_listener;
                    } else {
                        setRemoteListener(entry.getValue().toString().trim());
                    }
                }
                case "CLIENT_TIMEOUT" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        client_timeout = this.default_client_timeout;
                    } else {
                        setClient_timeout(entry.getValue().toString().trim());
                    }
                }
                case "ACCESS_MODE" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        access_mode = this.default_access_mode;
                    } else {
                        setAccess_mode(entry.getValue().toString().trim());
                    }
                }
                case "MAX_CONNECTIONS" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        max_connections = this.default_max_connections;
                    } else {
                        setMax_connections(entry.getValue().toString().trim());
                    }
                }
                case "MAX_WORKERS" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        max_workers = this.default_max_workers;
                    } else {
                        setMax_workers(entry.getValue().toString().trim());
                    }
                }
                case "THREADS" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        threads = this.default_threads;
                    } else {
                        setThreads(entry.getValue().toString().trim());
                    }
                }
                case "MEMORY_LIMIT" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        memory_limit = this.default_memory_limit;
                    } else {
                        setMemory_limit(entry.getValue().toString().trim());
                    }
                }
                case "INIT_SCRIPT" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        init_script = this.default_init_script;
                    } else {
                        setInit_script(entry.getValue().toString().trim());
                    }
                }
                case "STARTUP_SCRIPT" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        startup_script = this.default_startup_script;
                    } else {
                        setStartup_script(entry.getValue().toString().trim());
                    }
                }
                case "TEMPLATE" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        template = this.default_template;
                    } else {
                        setTemplate(entry.getValue().toString().trim());
                    }
                }
                case "SQL_HISTORY" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        sqlHistory = this.default_sqlHistory;
                    } else {
                        setSqlHistory(entry.getValue().toString().trim());
                    }
                }
                case "DATA_SERVICE_SCHEMA" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        data_service_schema = this.default_data_service_schema;
                    } else {
                        setDataServiceSchema(entry.getValue().toString().trim());
                    }
                }
                case "DATA_SERVICE_HISTORY" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        data_service_history = this.default_data_service_history;
                    } else {
                        setDataServiceHistory(entry.getValue().toString().trim());
                    }
                }
                case "PID" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        pid = this.default_pid;
                    } else {
                        setPid(entry.getValue().toString().trim());
                    }
                }
                case "CONNECTION_POOL_MINIMUM_IDLE" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        connection_pool_minimum_idle = this.default_connection_pool_minimum_idle;
                    } else {
                        setConnection_pool_minimum_idle(Integer.parseInt(entry.getValue().toString().trim()));
                    }
                }
                case "CONNECTION_POOL_MAXIMUM_IDLE" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        connection_pool_maximum_idle = this.default_connection_pool_maximum_idle;
                    } else {
                        setConnection_pool_maximum_idle(Integer.parseInt(entry.getValue().toString().trim()));
                    }
                }
                case "CONNECTION_POOL_MAXIMUM_LIFECYCLE_TIME" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        connection_pool_maximum_lifecycle_time = this.default_connection_pool_maximum_lifecycle_time;
                    } else {
                        setConnection_pool_maximum_lifecycle_time(Integer.parseInt(entry.getValue().toString().trim()));
                    }
                }
                case "QUERY_RESULT_CACHE_SIZE" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        query_result_cache_size = this.default_query_result_cache_size;
                    } else {
                        setQuery_result_cache_size(Long.parseLong(entry.getValue().toString().trim()));
                    }
                }
                case "PLUGINS_DIR" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        plugins_dir = this.default_plugins_dir;
                    } else {
                        setPlugins_dir(entry.getValue().toString().trim());
                    }
                }
                case "DATA_ENCRYPT" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        data_encrypt = this.default_data_encrypt;
                    } else {
                        setDataEncrypt(entry.getValue().toString().trim());
                    }
                }
                case "MCP_CONFIG" -> {
                    if (entry.getValue().toString().isEmpty()) {
                        mcp_config = this.default_mcp_config;
                    } else {
                        setMcpConfig(entry.getValue().toString().trim());
                    }
                }
                default ->
                        throw new ServerException(Utils.getMessage("SLACKERDB-00004", entry.getKey().toString(), configurationFileName));
            }
        }

        // 替换log中可能包含的data信息
        this.log = this.log.replace("${data}", this.data);
    }

    public void setConnection_pool_maximum_idle(int connection_pool_maximum_idle) {
        this.connection_pool_maximum_idle = connection_pool_maximum_idle;
    }

    public void setConnection_pool_minimum_idle(int connection_pool_minimum_idle) {
        this.connection_pool_minimum_idle = connection_pool_minimum_idle;
    }

    public void setConnection_pool_maximum_lifecycle_time(int connection_pool_maximum_lifecycle_time) {
        this.connection_pool_maximum_lifecycle_time = connection_pool_maximum_lifecycle_time;
    }
    public void setQuery_result_cache_size(String query_result_cache_size)
    {
        try {
            this.query_result_cache_size = Long.parseLong(query_result_cache_size);
        }
        catch (NumberFormatException ignored)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "query_result_cache_size", query_result_cache_size)
            );
        }
    }

    public void setQuery_result_cache_size(long query_result_cache_size)
    {
        this.query_result_cache_size = query_result_cache_size;
    }
    public int getConnection_pool_maximum_idle() {
        return connection_pool_maximum_idle;
    }

    public int getConnection_pool_minimum_idle() {
        return connection_pool_minimum_idle;
    }

    public long getQuery_result_cache_size()
    {
        return this.query_result_cache_size;
    }

    public int getConnection_pool_maximum_lifecycle_time() {
        return connection_pool_maximum_lifecycle_time;
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
    public String getRemoteListener()
    {
        return remote_listener;
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

    public int getMax_connections() { return max_connections; }

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
    public String getInit_script()
    {
        return init_script;
    }
    public String getStartup_script()
    {
        return startup_script;
    }
    public String getTemplate()
    {
        return template;
    }

    public String getSqlHistory()
    {
        return sqlHistory;
    }

    public String getDataServiceHistory()
    {
        return data_service_history;
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

    public void setLog_level(String pLog_level) throws ServerException {
        log_level = Level.valueOf(pLog_level);

        if (!log_level.levelStr.equalsIgnoreCase(pLog_level))
        {
            // 如果LogLevel的参数有错误，设置内容将不会有效
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "log_level", pLog_level)
            );
        }
        this.setLog_level(log_level);
    }
    public void setLog_level(Level pLog_level) throws ServerException {
        log_level = pLog_level;
    }


    public void setLog(String pLog) { log = pLog.trim();}
    public void setBindHost(String pHost)
    {
        bind = pHost;
    }

    public void setRemoteListener(String pRemote_Listener)
    {
        if (pRemote_Listener.contains(":")) {
            var ignoredVar1 = pRemote_Listener.substring(0, pRemote_Listener.lastIndexOf(':'));
            try {
                Integer.parseInt(pRemote_Listener.substring(pRemote_Listener.indexOf(':') + 1));
            }
            catch (NumberFormatException ignored)
            {
                throw new ServerException("Invalid remote listener parameter [" + pRemote_Listener + "]. Example: '1.1.1.1:1000' .");
            }
        }
        else
        {
            throw new ServerException("Invalid remote listener parameter [" + pRemote_Listener + "]. Example: '1.1.1.1:1000' .");
        }
        remote_listener = pRemote_Listener;
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
        this.setPort(tempPort);
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

    public void setMax_connections(String pMax_connections) throws ServerException
    {
        int tempMax_connections;
        try {
            tempMax_connections = Integer.parseInt(pMax_connections);
        }
        catch (NumberFormatException ignored)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "max_connections", pMax_connections)
            );
        }
        if (tempMax_connections <= 0)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "max_connections", pMax_connections)
            );
        }
        this.setMax_connections(tempMax_connections);
    }

    public void setMax_connections(int pMax_connections) throws ServerException
    {
        if (pMax_connections <= 0)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "max_connections", pMax_connections)
            );
        }
        max_connections = pMax_connections;
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

    public void setInit_script(String pInit_script)
    {
        init_script = pInit_script;
    }

    public void setStartup_script(String pStartup_script)
    {
        startup_script = pStartup_script;
    }

    public void setTemplate(String pTemplate)
    {
        this.template = pTemplate;
    }

    public void setSqlHistory(String pSQLHistory)
    {
        if (pSQLHistory.trim().equalsIgnoreCase("ON") || (pSQLHistory.trim().equalsIgnoreCase("OFF"))) {
            sqlHistory = pSQLHistory.trim().toUpperCase();
        }
        else
        {
            throw new ServerException("[SERVER] Invalid config of sqlHistory. ON or OFF only.");
        }
    }

    public void setDataServiceHistory(String val)
    {
        if (val.trim().equalsIgnoreCase("ON") || (val.trim().equalsIgnoreCase("OFF"))) {
            data_service_history = val.trim().toUpperCase();
        }
        else
        {
            throw new ServerException("[SERVER] Invalid config of data_service_history. ON or OFF only.");
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

    public void setClient_timeout(String pClient_Timeout) throws ServerException {
        int  temp_client_timeout;
        try {
            temp_client_timeout = Integer.parseInt(pClient_Timeout);
        }
        catch (NumberFormatException ignored)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "client_timeout", pClient_Timeout)
            );
        }
        if (temp_client_timeout <= 0)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "client_timeout", pClient_Timeout)
            );
        }
        this.setClient_timeout(temp_client_timeout);
    }

    public void setClient_timeout(int pClient_Timeout) throws ServerException {
        if (pClient_Timeout <= 0)
        {
            throw new ServerException(
                    Utils.getMessage("SLACKERDB-00005", "client_timeout", pClient_Timeout)
            );
        }
        client_timeout = pClient_Timeout;
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

    public void setPid(String pPid)
    {
        pid = pPid;
    }

    public void setDataServiceSchema(String val)
    {
        this.data_service_schema = val;
    }

    public String getData_service_schema()
    {
        return this.data_service_schema;
    }

    public void setDataEncrypt(String val)
    {
        if (val.trim().equalsIgnoreCase("true") || (val.trim().equalsIgnoreCase("false"))) {
            data_encrypt = Boolean.parseBoolean(val.trim().toLowerCase());
        }
        else
        {
            throw new ServerException("[SERVER] Invalid config of data_encrypt. true or false only.");
        }
    }

    public void setDataEncrypt(boolean val)
    {
        data_encrypt = val;
    }

    public boolean getDataEncrypt()
    {
        return data_encrypt;
    }

    public void setPlugins_dir(String val)
    {
        this.plugins_dir = val;
    }

    public String getPlugins_dir()
    {
        return this.plugins_dir;
    }

    public void setMcpConfig(String val)
    {
        this.mcp_config = val;
    }

    public String getMcpConfig()
    {
        return this.mcp_config;
    }
}
