package org.slackerdb.server;

import ch.qos.logback.classic.Logger;
import org.h2.jdbcx.JdbcConnectionPool;
import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.utils.Sleeper;
import org.slackerdb.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

public class DBInstance {
    // 服务器启动的时间
    public LocalDateTime bootTime = null;

    // 服务器配置参数
    public ServerConfiguration serverConfiguration;

    // 服务器对应的PG协议转发器
    private PostgresServer protocolServer;

    // 实例对应的日志句柄
    public Logger logger;

    public final Queue<Connection> connectionPool = new ConcurrentLinkedQueue<>();

    // 资源文件，记录各种消息，以及日后可能的翻译信息
    public ResourceBundle resourceBundle;

    public String instanceState = "IDLE";
    public Connection backendSysConnection;

    public JdbcConnectionPool backendSqlHistoryConnectionPool = null;

    public int    activeSessions = 0;

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
    private int maxSessionId = 1000;
    // 记录会话列表
    public final Map<Integer, DBSession> dbSessions = new HashMap<>();

    public void abortSession(int sessionId) throws SQLException {
        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        if (dbSession != null) {
            dbSession.abortSession();
            // 移除会话信息
            dbSessions.remove(sessionId);
        }
    }

    public void closeSession(int sessionId) throws SQLException {
        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        if (dbSession != null) {
            dbSession.closeSession();
            // 移除会话信息
            dbSessions.remove(sessionId);
        }
    }

    private void executeScript(String scriptFileName) throws IOException, SQLException {
        logger.info("Init schema, Executing script {} ...", scriptFileName);

        // 从文件中读取所有内容到字符串
        String sqlFileContents = new String(Files.readAllBytes(Path.of(scriptFileName)));
        // 去除多行注释
        sqlFileContents = sqlFileContents.replaceAll("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/", "");
        // 去除单行注释
        sqlFileContents = sqlFileContents.replaceAll("--.*", "");
        // 去除多余的空白字符（包括多余的换行符和空格）
        sqlFileContents = sqlFileContents.replaceAll("\\s+", " ").trim();

        // 按分号分隔语句
        String[] statements = sqlFileContents.split(";");
        Statement stmt = backendSysConnection.createStatement();
        for (String sql: statements)
        {
            try {
                logger.trace("Init schema, executing sql: {} ...", sql);
                stmt.execute(sql);
            }
            catch (SQLException e) {
                logger.error("Init schema, SQL Error: {}", sql);
                throw e;
            }
        }
        stmt.close();
    }

    public DBInstance(ServerConfiguration pServerConfiguration) throws ServerException
    {
        // 加载数据库的配置信息
        serverConfiguration = pServerConfiguration;

        // 设置消息语言集
        resourceBundle = ResourceBundle.getBundle("message", serverConfiguration.getLocale());

        // 检查是否包含路径分隔符
        String instanceName = serverConfiguration.getData();
        if (instanceName.contains("/") || instanceName.contains("\\")) {
            throw new ServerException("Invalid instance name [" + instanceName + "]");
        }
        // 检查是否包含不合法字符
        if (Pattern.compile("[\\\\/:*?\"<>|]").matcher(instanceName).find()) {
            throw new ServerException("Invalid instance name [" + instanceName + "]");
        }
        // 检查文件名长度（假设文件系统限制为255字符）
        if (instanceName.isEmpty() || instanceName.length() > 255) {
            throw new ServerException("Invalid instance name [" + instanceName + "]");
        }
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
                logger.info("[SERVER] Database first opened, will execute init script if you have ...");
            }
            backendConnectString = backendConnectString + dataFile.getAbsolutePath();
        }

        // 初始化一个DB连接，以保证即使所有客户端都断开连接，服务端会话仍然会继续存在
        try {
            Properties connectProperties = new Properties();
            if (serverConfiguration.getAccess_mode().equals("READ_ONLY")) {
                connectProperties.setProperty("duckdb.read_only", "true");
            }
            // 容许未签名的扩展
            connectProperties.setProperty("allow_unsigned_extensions", "true");
            backendSysConnection = DriverManager.getConnection(backendConnectString, connectProperties);
            logger.info("[SERVER] Backend database [{}:{}] mounted.",
                    serverConfiguration.getData_Dir(), serverConfiguration.getData());

            Statement stmt = backendSysConnection.createStatement();
            if (!serverConfiguration.getTemp_dir().isEmpty()) {
                logger.debug("SET temp_directory = '{}'", serverConfiguration.getTemp_dir());
                stmt.execute("SET temp_directory = '" + serverConfiguration.getTemp_dir() + "'");
            }
            if (!serverConfiguration.getExtension_dir().isEmpty()) {
                logger.debug("SET extension_directory = '{}'", serverConfiguration.getExtension_dir());
                stmt.execute("SET extension_directory = '" + serverConfiguration.getExtension_dir() + "'");
            }
            if (!serverConfiguration.getMemory_limit().isEmpty()) {
                logger.debug("SET Memory_limit = '{}'", serverConfiguration.getMemory_limit());
                stmt.execute("SET memory_limit = '" + serverConfiguration.getMemory_limit() + "'");
            }
            if (serverConfiguration.getThreads() != 0) {
                logger.debug("SET threads = '{}'", serverConfiguration.getThreads());
                stmt.execute("SET threads = " + serverConfiguration.getThreads());
            }
            stmt.close();

            // 处理初始化脚本
            if (!serverConfiguration.getAccess_mode().equals("READ_ONLY")) {
                try {
                    // 虚构一些PG的数据字典，以满足后续各种工具对数据字典的查找
                    SlackerCatalog.createFakeCatalog(this, backendSysConnection);

                    stmt = backendSysConnection.createStatement();
                    // 强制约定在程序推出的时候保存检查点
                    stmt.execute("PRAGMA enable_checkpoint_on_shutdown");
                    stmt.close();
                } catch (SQLException e) {
                    logger.error("[SERVER] Init backend connection error. ", e);
                    throw new ServerException(e);
                }

                // 启用SQLHistory记录
                if (!serverConfiguration.getSqlHistory().trim().isEmpty())
                {
                    String sqlHistoryDir = serverConfiguration.getSqlHistoryDir();
                    if (sqlHistoryDir.trim().isEmpty())
                    {
                        sqlHistoryDir = serverConfiguration.getData_Dir();
                    }
                    String backendSqlHistoryURL;
                    if (sqlHistoryDir.equalsIgnoreCase(":memory:") || sqlHistoryDir.equalsIgnoreCase(":mem:"))
                    {
                        // 内存模式
                        backendSqlHistoryURL = "jdbc:h2:mem:" + this.serverConfiguration.getSqlHistory() + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
                        backendSqlHistoryConnectionPool = JdbcConnectionPool.create(backendSqlHistoryURL, null, null);
                        logger.info("[SERVER] Backend sql history database [mem:{}] opened (memory mode).",
                                this.serverConfiguration.getSqlHistory());
                    }
                    else {
                        String sqlHistoryFile = Path.of(sqlHistoryDir, serverConfiguration.getSqlHistory().trim()).toString();
                        backendSqlHistoryURL = "jdbc:h2:" + sqlHistoryFile + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
                        backendSqlHistoryConnectionPool = JdbcConnectionPool.create(backendSqlHistoryURL, null, null);
                        logger.info("[SERVER] Backend sql history database [{}] opened (disk mode).", sqlHistoryFile);
                    }
                    Connection backendSqlHistoryConnection = backendSqlHistoryConnectionPool.getConnection();
                    stmt = backendSqlHistoryConnection.createStatement();
                    String sql = "CREATE TABLE IF NOT EXISTS SQL_HISTORY\n " +
                            "(\n" +
                            "    ID             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,\n" +
                            "    SessionID      INT,\n" +
                            "    ClientIP       TEXT,\n" +
                            "    StartTime      TIMESTAMP,\n" +
                            "    EndTime        TIMESTAMP,\n" +
                            "    Elapsed        INT GENERATED ALWAYS AS (DATEDIFF('SECOND', StartTime, EndTime)), \n" +
                            "    SqlID          INT,\n" +
                            "    SQL            TEXT,\n" +
                            "    SqlCode        INT,\n" +
                            "    AffectedRows   BIGINT\n," +
                            "    ErrorMsg       TEXT\n" +
                            ")";
                    stmt.execute(sql);
                    stmt.close();
                    backendSqlHistoryConnection.close();
                    // 对外提供TCP连接
                    if (serverConfiguration.getSqlHistoryPort() != -1) {
                        org.h2.tools.Server h2TcpServer = org.h2.tools.Server.createTcpServer(
                                "-tcpPort", String.valueOf(serverConfiguration.getSqlHistoryPort()),
                                "-tcpAllowOthers", "-tcpDaemon"
                        );
                        h2TcpServer.start();
                        logger.info("[SERVER] SQLHistory Server TCP Port started at :{}",
                                serverConfiguration.getSqlHistoryPort());
                    }
                    else
                    {
                        if (serverConfiguration.getSqlHistoryPort() == -1)
                        {
                            logger.info("[SERVER] SQLHistory Server started without port.");
                        }
                    }
                }
            }

            // 执行初始化脚本，如果有必要的话
            // 只有内存数据库或者文件数据库第一次启动的时候需要执行
            if (databaseFirstOpened && !serverConfiguration.getInit_schema().trim().isEmpty()) {
                List<String> initScriptFiles = new ArrayList<>();
                if (new File(serverConfiguration.getInit_schema()).isFile()) {
                    initScriptFiles.add(new File(serverConfiguration.getInit_schema()).getAbsolutePath());
                } else if (new File(serverConfiguration.getInit_schema()).isDirectory()) {
                    File[] files = new File(serverConfiguration.getInit_schema()).listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.isFile() && file.getName().endsWith(".sql")) {
                                initScriptFiles.add(file.getAbsolutePath());
                            }
                        }
                    }
                } else {
                    throw new ServerException("Init schema [" + serverConfiguration.getInit_schema() + "] does not exist!");
                }
                Collections.sort(initScriptFiles);
                for (String initScriptFile : initScriptFiles) {
                    executeScript(initScriptFile);
                }
                logger.info("Init schema completed.");
            }
        }
        catch(SQLException | IOException e){
            logger.error("[SERVER] Init backend connection error. ", e);
            throw new ServerException(e);
        }
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
                Sleeper.sleep(1000);
            }
        }
        else
        {
            logger.info("[SERVER] {}", Utils.getMessage("SLACKERDB-00008"));
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

        // 关闭数据库
        try {
            // 关闭所有的连接
            for (Connection conn : connectionPool)
            {
                if (!conn.isClosed()) {
                    conn.close();
                }
            }
            connectionPool.clear();
            // 关闭BackendSysConnection
            backendSysConnection.close();
        } catch (SQLException e) {
            logger.error("Error closing backend connection", e);
        }

        // 数据库标记为空闲
        this.instanceState = "IDLE";
    }

    public int newSession(DBSession dbSession)
    {
        int currentSessionId;
        synchronized (PostgresServerHandler.class) {
            maxSessionId ++;
            currentSessionId = maxSessionId;
        }
        dbSessions.put(currentSessionId, dbSession);
        return currentSessionId;
    }

    public DBSession getSession(int sessionId)
    {
        return dbSessions.get(sessionId);
    }

    // 强制执行检查点
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
}
