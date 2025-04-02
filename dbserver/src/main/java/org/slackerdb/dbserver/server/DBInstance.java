package org.slackerdb.dbserver.server;

import ch.qos.logback.classic.Logger;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.logger.AppLogger;
import org.slackerdb.common.utils.Sleeper;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.sql.SQLReplacer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class DBInstance {
    // 服务器启动模式
    // 是否为独占模式，默认否
    // 当程序为独占模式的时候，退出端口，意味着程序也将退出
    private boolean exclusiveMode = false;

    // 服务器启动的时间
    public LocalDateTime bootTime = null;

    // 服务器配置参数
    public final ServerConfiguration serverConfiguration;

    // 服务器对应的PG协议转发器
    private PostgresServer protocolServer;

    // 实例对应的日志句柄
    public Logger logger;

    // 实例的名称
    public String instanceName;

    // DuckDB的数据库连接池
    public DBDataSourcePool dbDataSourcePool = null;
    // SQL历史记录的连接池
    public DBDataSourcePool sqlHistoryDataSourcePool = null;

    // 资源文件，记录各种消息，以及日后可能的翻译信息
    public final ResourceBundle resourceBundle;

    // 实例的状态
    public String instanceState = "IDLE";

    // DuckDB对应的后端长数据库连接
    public Connection backendSysConnection;

    // SqlHistoryId 当前SQL历史的主键ID
    public final AtomicLong backendSqlHistoryId = new AtomicLong(1);

    // 系统活动的会话数，指保持在DB侧正在执行语句的会话数
    public final  AtomicInteger  activeSessions = new AtomicInteger(0);

    // 从资源文件中获取消息，为未来的多语言做准备
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

    // 为每个连接创建一个会话ID
    private final AtomicInteger maxSessionId = new AtomicInteger(100000);

    // 记录会话列表
    public final ConcurrentHashMap<Integer, DBSession> dbSessions = new ConcurrentHashMap<>();

    // PID进程锁信息
    private RandomAccessFile pidRandomAccessFile;
    private FileLock pidFileLockHandler;

    // 监控线程
    class DBInstanceMonitorThread extends Thread
    {
        @Override
        public void run()
        {
            // 设置线程名称
            setName("Session-Mon");

            // 每10秒钟检测一下当前负载状况
            while (!isInterrupted())
            {
                // 检查服务器是否过载
                if (getRegisteredConnectionsCount() > serverConfiguration.getMax_Workers()) {
                    logger.warn(
                            "[SERVER] The system is overloaded. " +
                                    "The current number of connection requests is {} " +
                                    "and the current maximum worker setting of the system is {}.",
                            getRegisteredConnectionsCount(), serverConfiguration.getMax_Workers());
                }

                // 每10秒钟检测一次
                try {
                    Sleeper.sleep(10 * 1000);
                }
                catch (InterruptedException interruptedException)
                {
                    this.interrupt();
                }
            }
        }
    }
    private DBInstanceMonitorThread dbInstanceMonitorThread = null;

    // 终止会话
    // 默认回滚所有会话
    public void abortSession(int sessionId) throws SQLException {
        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        if (dbSession != null) {
            // 首先移除会话信息
            dbSessions.remove(sessionId);
            // 放弃会话
            dbSession.abortSession();
        }
    }

    // 关闭会话
    // closeSession默认提交所有未提交内容
    public void closeSession(int sessionId) throws SQLException {
        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        if (dbSession != null) {
            // 移除会话信息
            dbSessions.remove(sessionId);
            // 关闭会话
            dbSession.closeSession();
        }
    }

    // 执行指定的脚本
    private void executeScript(String scriptFileName) throws IOException, SQLException {
        logger.debug("Executing script {} ...", scriptFileName);

        // 从文件中读取所有内容到字符串
        String sqlFileContents = new String(Files.readAllBytes(Path.of(scriptFileName)));

        // 按分号分隔语句
        Statement stmt = backendSysConnection.createStatement();
        List<String> sqlItems = SQLReplacer.splitSQLWithSemicolon(sqlFileContents);
        for (String sqlItem : sqlItems) {
            String sql = SQLReplacer.removeSQLComments(sqlItem);
            if (!sql.isEmpty()) {
                try {
                    logger.debug("  Executing sql: {} ...", sql);
                    stmt.execute(sql);
                } catch (SQLException e) {
                    logger.error("  SQL Error: {}", sql);
                    throw e;
                }
            }
        }
        stmt.close();
    }

    // 构造函数
    public DBInstance(ServerConfiguration pServerConfiguration) throws ServerException
    {
        // 加载数据库的配置信息
        serverConfiguration = pServerConfiguration;

        // 设置消息语言集
        resourceBundle = ResourceBundle.getBundle("message", serverConfiguration.getLocale());

        // 检查是否包含路径分隔符
        String instanceName = serverConfiguration.getData();
        if (instanceName.contains("/") || instanceName.contains("\\")) {
            throw new ServerException("Invalid character. Invalid instance name [" + instanceName + "]");
        }
        // 检查是否包含不合法字符
        if (Pattern.compile("[\\\\/:*?\"<>|]").matcher(instanceName).find()) {
            throw new ServerException("Invalid character. Invalid instance name [" + instanceName + "]");
        }
        // 检查文件名长度（假设文件系统限制为255字符）
        if (instanceName.isEmpty() || instanceName.length() > 255) {
            throw new ServerException("Instance name is too long(>255). Invalid instance name [" + instanceName + "]");
        }
        this.instanceName = instanceName;
    }

    // 根据参数配置文件启动数据库实例
    public synchronized void start() throws ServerException {
        String instanceName = serverConfiguration.getData();

        // 初始化日志服务
        logger = AppLogger.createLogger(
                serverConfiguration.getData(),
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

        // 文件是否为第一次打开
        boolean databaseFirstOpened = false;

        // 建立基础数据库连接
        String backendConnectString = "jdbc:duckdb:";

        if (serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:")) {
            backendConnectString = backendConnectString + ":memory:" + instanceName;
            databaseFirstOpened = true;
        } else {
            if (!new File(serverConfiguration.getData_Dir()).isDirectory()) {
                throw new ServerException("Data directory [" + serverConfiguration.getData_Dir() + "] does not exist!");
            }
            File dataFile = new File(serverConfiguration.getData_Dir(), instanceName + ".db");
            if (!dataFile.canRead() && serverConfiguration.getAccess_mode().equalsIgnoreCase("READ_ONLY")) {
                throw new ServerException("Data [" + dataFile.getAbsolutePath() + "] can't be read!!");
            }
            if (!dataFile.exists() && !new File(serverConfiguration.getData_Dir()).canWrite()) {
                throw new ServerException("Data [" + dataFile.getAbsolutePath() + "] can't be create!!");
            }
            if (dataFile.exists() && !dataFile.canWrite() && serverConfiguration.getAccess_mode().equalsIgnoreCase("READ_WRITE")) {
                throw new ServerException("Data [" + dataFile.getAbsolutePath() + "] can't be write!!");
            }
            if (!dataFile.exists()) {
                // 文件的第一次被使用
                databaseFirstOpened = true;
            }
            backendConnectString = backendConnectString + dataFile.getAbsolutePath();
        }

        // 不可能在内存中启动一个只读数据库
        if (
                serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:") &&
                        serverConfiguration.getAccess_mode().equals("READ_ONLY")
        ) {
            throw new ServerException("Server startup failed. You can't start an in-memory database with read only mode.");
        }

        // 初始化一个DB连接，以保证即使所有客户端都断开连接，服务端会话仍然会继续存在
        try {
            Properties connectProperties = new Properties();
            if (serverConfiguration.getAccess_mode().equals("READ_ONLY")) {
                connectProperties.setProperty("duckdb.read_only", "true");
            }
            // DuckDB并不需要用户名和密码，但是这里也要设置为空，以保证Hikari工作
            connectProperties.setProperty("user", "");
            connectProperties.setProperty("password", "");

            // 容许未签名的扩展
            connectProperties.setProperty("allow_unsigned_extensions", "true");
            // 用后台线程来异步清除未完成的内存分配
            connectProperties.setProperty("allocator_background_threads", "true");
            backendSysConnection = DriverManager.getConnection(backendConnectString, connectProperties);
            if (serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:"))
            {
                logger.info("[SERVER][STARTUP    ] Backend database [{}:{}] mounted with {} mode.",
                        serverConfiguration.getData_Dir(), serverConfiguration.getData(),
                        serverConfiguration.getAccess_mode());
            }
            else {
                logger.info("[SERVER][STARTUP    ] Backend database [{}.db] mounted with {} mode.",
                        Path.of(serverConfiguration.getData_Dir(), serverConfiguration.getData()).toAbsolutePath(),
                        serverConfiguration.getAccess_mode());
            }
            Statement stmt = backendSysConnection.createStatement();
            if (!serverConfiguration.getTemp_dir().isEmpty()) {
                logger.debug("[SERVER][STARTUP    ] SET temp_directory = '{}'", serverConfiguration.getTemp_dir());
                stmt.execute("SET temp_directory = '" + serverConfiguration.getTemp_dir() + "'");
            }
            if (!serverConfiguration.getExtension_dir().isEmpty()) {
                logger.debug("[SERVER][STARTUP    ] SET extension_directory = '{}'", serverConfiguration.getExtension_dir());
                stmt.execute("SET extension_directory = '" + serverConfiguration.getExtension_dir() + "'");
            }
            if (!serverConfiguration.getMemory_limit().isEmpty()) {
                logger.debug("[SERVER][STARTUP    ] SET Memory_limit = '{}'", serverConfiguration.getMemory_limit());
                stmt.execute("SET memory_limit = '" + serverConfiguration.getMemory_limit() + "'");
            }
            if (serverConfiguration.getThreads() != 0) {
                logger.debug("[SERVER][STARTUP    ] SET threads = '{}'", serverConfiguration.getThreads());
                stmt.execute("SET threads = " + serverConfiguration.getThreads());
            }
            stmt.close();

            if (!serverConfiguration.getAccess_mode().equals("READ_ONLY")) {
                try {
                    // 虚构一些PG的数据字典，以满足后续各种工具对数据字典的查找
                    SlackerCatalog.createFakeCatalog(this, backendSysConnection);

                    stmt = backendSysConnection.createStatement();
                    // 强制约定在程序推出的时候保存检查点
                    stmt.execute("PRAGMA enable_checkpoint_on_shutdown");
                    stmt.close();
                } catch (SQLException e) {
                    logger.error("[SERVER][STARTUP    ] Init backend connection error. ", e);
                    throw new ServerException(e);
                }

                // 执行初始化脚本，如果有必要的话
                // 只有内存数据库或者文件数据库第一次启动的时候需要执行
                List<String> initScriptFiles = new ArrayList<>();
                if (databaseFirstOpened && !serverConfiguration.getInit_script().trim().isEmpty()) {
                    if (new File(serverConfiguration.getInit_script()).isFile()) {
                        initScriptFiles.add(new File(serverConfiguration.getInit_script()).getAbsolutePath());
                    } else if (new File(serverConfiguration.getInit_script()).isDirectory()) {
                        File[] files = new File(serverConfiguration.getInit_script()).listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.isFile() && file.getName().endsWith(".sql")) {
                                    initScriptFiles.add(file.getAbsolutePath());
                                }
                            }
                        }
                    } else {
                        logger.warn("[SERVER][STARTUP    ] Init script(s) [{}] does not exist!", serverConfiguration.getInit_script());
                    }
                }
                // 脚本按照名称来排序
                Collections.sort(initScriptFiles);
                for (String initScriptFile : initScriptFiles) {
                    executeScript(initScriptFile);
                }
                logger.debug("[SERVER][STARTUP    ] Init {} script(s) execute completed.", initScriptFiles.size());

                // 执行系统启动脚本，如果有必要的话
                // 每次启动都要执行的部分
                List<String> startupScriptFiles = new ArrayList<>();
                if (!serverConfiguration.getStartup_script().trim().isEmpty()) {
                    if (new File(serverConfiguration.getStartup_script()).isFile()) {
                        startupScriptFiles.add(new File(serverConfiguration.getStartup_script()).getAbsolutePath());
                    } else if (new File(serverConfiguration.getStartup_script()).isDirectory()) {
                        File[] files = new File(serverConfiguration.getStartup_script()).listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.isFile() && file.getName().endsWith(".sql")) {
                                    startupScriptFiles.add(file.getAbsolutePath());
                                }
                            }
                        }
                    } else {
                        logger.warn("[SERVER][STARTUP    ] Startup script(s) [{}] does not exist!", serverConfiguration.getStartup_script());
                    }
                }
                // 脚本按照名称来排序
                Collections.sort(startupScriptFiles);
                for (String startupScriptFile : startupScriptFiles) {
                    executeScript(startupScriptFile);
                }
                logger.debug("[SERVER][STARTUP    ] Startup {} script(s) execute completed.", startupScriptFiles.size());
            }

            // 初始化数据库连接池
            DBDataSourcePoolConfig dbDataSourcePoolConfig = new DBDataSourcePoolConfig();
            dbDataSourcePoolConfig.setMinimumIdle(serverConfiguration.getConnection_pool_minimum_idle());
            dbDataSourcePoolConfig.setMaximumIdle(serverConfiguration.getConnection_pool_maximum_idle());
            dbDataSourcePoolConfig.setMaximumLifeCycleTime(serverConfiguration.getConnection_pool_maximum_lifecycle_time());
            dbDataSourcePoolConfig.setMaximumPoolSize(serverConfiguration.getMax_connections());
            dbDataSourcePoolConfig.setValidationSQL(serverConfiguration.getConnection_pool_validation_sql());
            dbDataSourcePoolConfig.setJdbcURL(backendConnectString);
            dbDataSourcePoolConfig.setConnectProperties(connectProperties);
            try {
                this.dbDataSourcePool = new DBDataSourcePool("DATABASE", dbDataSourcePoolConfig, logger);
            }
            catch (SQLException sqlException)
            {
                throw new ServerException("Init connection pool error [" + instanceName + "]", sqlException);
            }

            // 初始化SQLHistory连接池
            if (!serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                    serverConfiguration.getSqlHistory().equalsIgnoreCase("ON")) {
                DBDataSourcePoolConfig sqlHistoryDataSourcePoolConfig = new DBDataSourcePoolConfig();
                int poolMinHandle = 20;
                if (poolMinHandle > serverConfiguration.getMax_Workers())
                {
                    poolMinHandle = serverConfiguration.getMax_Workers();
                }
                sqlHistoryDataSourcePoolConfig.setMinimumIdle(poolMinHandle);
                sqlHistoryDataSourcePoolConfig.setMaximumIdle(serverConfiguration.getMax_Workers());
                sqlHistoryDataSourcePoolConfig.setMaximumLifeCycleTime(0);
                sqlHistoryDataSourcePoolConfig.setMaximumPoolSize(serverConfiguration.getMax_Workers());
                sqlHistoryDataSourcePoolConfig.setValidationSQL(null);
                sqlHistoryDataSourcePoolConfig.setJdbcURL(backendConnectString);
                sqlHistoryDataSourcePoolConfig.setConnectProperties(connectProperties);
                try {
                    this.sqlHistoryDataSourcePool = new DBDataSourcePool("HISTORY", sqlHistoryDataSourcePoolConfig, logger);
                }
                catch (SQLException sqlException)
                {
                    throw new ServerException("Init sql history connection pool error [" + instanceName + "]", sqlException);
                }

                // 启用SQLHistory记录
                Connection backendSqlHistoryConn = this.sqlHistoryDataSourcePool.getConnection();
                // SQL History会保存在数据库内部。
                Statement sqlHistoryStmt = backendSqlHistoryConn.createStatement();
                sqlHistoryStmt.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
                sqlHistoryStmt.execute(
                        """
                                CREATE TABLE IF NOT EXISTS SYSAUX.SQL_HISTORY
                                (
                                    ID             BIGINT PRIMARY KEY,
                                    ServerID       INT,
                                    SessionID      INT,
                                    ClientIP       TEXT,
                                    StartTime      DateTime,
                                    EndTime        DateTime,
                                    Elapsed        INT GENERATED ALWAYS AS (DATEDIFF('SECOND', StartTime, EndTime)),
                                    SqlID          INT,
                                    SQL            TEXT,
                                    SqlCode        INT,
                                    AffectedRows   BIGINT,
                                    ErrorMsg       TEXT
                                );""");
                sqlHistoryStmt.close();
                logger.info("[SERVER][STARTUP    ] Backend sql history database opened.");

                // 获取之前最大的SqlHistory的ID
                String sql = "Select Max(ID) From sysaux.SQL_HISTORY";
                Statement statement = backendSqlHistoryConn.createStatement();
                ResultSet resultSet = statement.executeQuery(sql);
                if (resultSet.next())
                {
                    this.backendSqlHistoryId.set(resultSet.getLong(1) + 1);
                }
                resultSet.close();
                statement.close();

                // 希望连接池能够复用数据库连接
                this.sqlHistoryDataSourcePool.releaseConnection(backendSqlHistoryConn);
            }
        }
        catch(SQLException | IOException e){
            throw new ServerException("Init backend connection error. ", e);
        }

        // SQL替换
        // DuckDB并不支持所有的PG语法，所以需要进行转义替换，以保证第三方工具能够正确使用
        SQLReplacer.load(this);

        // 标记服务已经挂载成功
        this.instanceState = "MOUNTED";

        // 启动PG的协议处理程序
        if (serverConfiguration.getPort() != -1) {
            protocolServer = new PostgresServer();
            protocolServer.setLogger(logger);
            protocolServer.setBindHostAndPort(serverConfiguration.getBindHost(), serverConfiguration.getPort());
            protocolServer.setServerTimeout(serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout(), serverConfiguration.getClient_timeout());
            protocolServer.setNioEventThreads(serverConfiguration.getMax_Workers());
            protocolServer.setDBInstance(this);
            protocolServer.start();
            while (!protocolServer.isPortReady()) {
                // 等待Netty进程就绪
                try {
                    Sleeper.sleep(1000);
                }
                catch (InterruptedException ignored) {
                    throw new ServerException("Server terminated due to user cancelled.");
                }
            }

            // 启动监控线程
            if (dbInstanceMonitorThread == null)
            {
                dbInstanceMonitorThread = new DBInstanceMonitorThread();
                dbInstanceMonitorThread.start();
            }
            logger.info("[SERVER][STARTUP    ] Listening on {}:{}.",
                    serverConfiguration.getBindHost(), serverConfiguration.getPort());
        }
        else
        {
            logger.info("[SERVER][STARTUP    ] Listener has been disabled.");
        }
        // 标记服务已经启动完成
        this.instanceState = "RUNNING";
    }

    // 停止数据库实例
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

        // 数据库强制进行检查点操作
        forceCheckPoint();

        try {
            // 关闭数据库连接池
            if (this.dbDataSourcePool != null) {
                this.dbDataSourcePool.shutdown();
            }

            // 关闭BackendSysConnection
            backendSysConnection.close();
        }
        catch (SQLException e) {
            logger.error("[SERVER][STARTUP    ] Close backend connection error. ", e);
        }

        // 删除PID文件
        if (pidRandomAccessFile != null )
        {
            try {
                pidFileLockHandler.release();
                pidFileLockHandler = null;

                pidRandomAccessFile.close();
                File pidFile = new File(this.serverConfiguration.getPid());
                if (pidFile.exists()) {
                    var ignored = pidFile.delete();
                }
                pidRandomAccessFile = null;
            }
            catch (IOException ioException) {
                logger.warn("[SERVER][STARTUP    ] Remove pid file failed, reason unknown!", ioException);
            }
        }

        // 关闭监控线程
        if (dbInstanceMonitorThread != null) {
            if (dbInstanceMonitorThread.isAlive()) {
                dbInstanceMonitorThread.interrupt();
            }
            dbInstanceMonitorThread = null;
        }

        // 数据库标记为空闲
        this.instanceState = "IDLE";
    }

    public int getRegisteredConnectionsCount()
    {
        return this.protocolServer.getRegisteredConnectionsCount();
    }

    // 初始化一个新的数据库会话
    public int newSession(DBSession dbSession)
    {
        int currentSessionId = maxSessionId.incrementAndGet();
        dbSessions.put(currentSessionId, dbSession);
        return currentSessionId;
    }

    // 根据SessionID获取对应的Session信息
    public DBSession getSession(int sessionId)
    {
        return dbSessions.get(sessionId);
    }

    // 强制执行检查点
    // 用来在退出时候同步完成所有的WAL文件
    public void forceCheckPoint()
    {
        try {
            if (backendSysConnection != null && !backendSysConnection.isClosed() && !backendSysConnection.isReadOnly()) {
                Statement stmt = backendSysConnection.createStatement();
                stmt.execute("FORCE CHECKPOINT");
                stmt.close();
            }
        }
        catch (SQLException e) {
            logger.error("Force checkpoint failed.", e);
        }
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

    // 获得sqlHistory的数据库连接
    public Connection getSqlHistoryConn() throws SQLException
    {
        if (serverConfiguration.getSqlHistory().equalsIgnoreCase("OFF"))
        {
            // 没有开启日志服务
            return null;
        }
        return this.sqlHistoryDataSourcePool.getConnection();
    }

    public void releaseSqlHistoryConn(Connection sqlHistoryConn)
    {
        this.sqlHistoryDataSourcePool.releaseConnection(sqlHistoryConn);
    }
}
