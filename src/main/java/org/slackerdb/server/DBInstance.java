package org.slackerdb.server;

import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.logger.AppLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

public class DBInstance {
    public static PostgresServer protocolServer;
    public static final LocalDateTime bootTime = LocalDateTime.now();
    public static final Queue<Connection> connectionPool = new ConcurrentLinkedQueue<>();

    public static String state = "";
    public static Connection backendSysConnection;
    public static Connection backendSqlHistoryConnection = null;

    public static int    activeSessions = 0;

    // 为每个连接创建一个会话ID
    private static int maxSessionId = 1000;
    // 记录会话列表
    public static final Map<Integer, DBSession> dbSessions = new HashMap<>();

    // 定时保存SQL历史记录（每分钟一次)
    static class SaveSqlHistory extends Thread
    {
        @SuppressWarnings("BusyWait")
        @Override
        public void run()
        {
            while (true) {
                saveSqlHistory();
                try {
                    Thread.sleep(60 * 1000);
                } catch (InterruptedException ignored) {break;}
            }
        }
    }

    public static void abortSession(int sessionId) throws SQLException {
        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        if (dbSession != null) {
            dbSession.abortSession();
            // 移除会话信息
            dbSessions.remove(sessionId);
        }
    }

    public static void closeSession(int sessionId) throws SQLException {
        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        if (dbSession != null) {
            dbSession.closeSession();
            // 移除会话信息
            dbSessions.remove(sessionId);
        }
    }

    private static void executeScript(String scriptFileName) throws IOException, SQLException {
        AppLogger.logger.info("Init schema, Executing script {} ...", scriptFileName);

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
                AppLogger.logger.trace("Init schema, executing sql: {} ...", sql);
                stmt.execute(sql);
            }
            catch (SQLException e) {
                AppLogger.logger.error("Init schema, SQL Error: {}", sql);
                throw e;
            }
        }
        stmt.close();
    }

    public static void init() throws ServerException {
        // 文件是否为第一次打开
        boolean databaseFirstOpened = false;

        // 建立基础数据库连接
        String backendConnectString = "jdbc:duckdb:";

        String instanceName = ServerConfiguration.getData().trim();
        // 检查是否包含路径分隔符
        if (instanceName.contains("/") || instanceName.contains("\\")) {
            throw new ServerException(999,
                    "Invalid instance name [" + instanceName + "]");
        }
        // 检查是否包含不合法字符
        if (Pattern.compile("[\\\\/:*?\"<>|]").matcher(instanceName).find()) {
            throw new ServerException(999,
                    "Invalid instance name [" + instanceName + "]");
        }
        // 检查文件名长度（假设文件系统限制为255字符）
        if (instanceName.isEmpty() || instanceName.length() > 255) {
            throw new ServerException(999,
                    "Invalid instance name [" + instanceName + "]");
        }
        if (ServerConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:")) {
            backendConnectString = backendConnectString + ":memory:" + instanceName;
            databaseFirstOpened = true;
        } else {
            if (!new File(ServerConfiguration.getData_Dir()).isDirectory()) {
                throw new ServerException(999,
                        "Data directory [" + ServerConfiguration.getData_Dir() + "] does not exist!");
            }
            File dataFile = new File(ServerConfiguration.getData_Dir(), instanceName + ".db");
            if (!dataFile.canRead() && ServerConfiguration.getAccess_mode().equalsIgnoreCase("READ_ONLY")) {
                throw new ServerException(999,
                        "Data [" + dataFile.getAbsolutePath() + "] can't be read!!");
            }
            if (!dataFile.exists() && !new File(ServerConfiguration.getData_Dir()).canWrite()) {
                throw new ServerException(999,
                        "Data [" + dataFile.getAbsolutePath() + "] can't be create!!");
            }
            if (dataFile.exists() && !dataFile.canWrite() && ServerConfiguration.getAccess_mode().equalsIgnoreCase("READ_WRITE")) {
                throw new ServerException(999,
                        "Data [" + dataFile.getAbsolutePath() + "] can't be write!!");
            }
            if (!dataFile.exists()) {
                // 文件的第一次被使用
                databaseFirstOpened = true;
                AppLogger.logger.info("[SERVER] Database first opened, will execute init script if you have ...");
            }
            backendConnectString = backendConnectString + dataFile.getAbsolutePath();
        }

        // 初始化一个DB连接，以保证即使所有客户端都断开连接，服务端会话仍然会继续存在
        try {
            Properties connectProperties = new Properties();
            if (ServerConfiguration.getAccess_mode().equals("READ_ONLY")) {
                connectProperties.setProperty("duckdb.read_only", "true");
            }
            // 容许未签名的扩展
            connectProperties.setProperty("allow_unsigned_extensions", "true");
            backendSysConnection = DriverManager.getConnection(backendConnectString, connectProperties);
            AppLogger.logger.info("[SERVER] Backend database [{}:{}] opened.",
                    ServerConfiguration.getData_Dir(), ServerConfiguration.getData());

            Statement stmt = backendSysConnection.createStatement();
            if (!ServerConfiguration.getTemp_dir().isEmpty()) {
                AppLogger.logger.debug("SET temp_directory = '{}'", ServerConfiguration.getTemp_dir());
                stmt.execute("SET temp_directory = '" + ServerConfiguration.getTemp_dir() + "'");
            }
            if (!ServerConfiguration.getExtension_dir().isEmpty()) {
                AppLogger.logger.debug("SET extension_directory = '{}'", ServerConfiguration.getExtension_dir());
                stmt.execute("SET extension_directory = '" + ServerConfiguration.getExtension_dir() + "'");
            }
            if (!ServerConfiguration.getMemory_limit().isEmpty()) {
                AppLogger.logger.debug("SET Memory_limit = '{}'", ServerConfiguration.getMemory_limit());
                stmt.execute("SET memory_limit = '" + ServerConfiguration.getMemory_limit() + "'");
            }
            if (ServerConfiguration.getThreads() != 0) {
                AppLogger.logger.debug("SET threads = '{}'", ServerConfiguration.getThreads());
                stmt.execute("SET threads = " + ServerConfiguration.getThreads());
            }
            stmt.close();

            // 处理初始化脚本
            if (!ServerConfiguration.getAccess_mode().equals("READ_ONLY")) {
                try {
                    // 虚构一些PG的数据字典，以满足后续各种工具对数据字典的查找
                    SlackerCatalog.createFakeCatalog(backendSysConnection);

                    stmt = backendSysConnection.createStatement();
                    // 强制约定在程序推出的时候保存检查点
                    stmt.execute("PRAGMA enable_checkpoint_on_shutdown");
                    stmt.close();
                } catch (SQLException e) {
                    DBInstance.state = "STARTUP FAILED";
                    AppLogger.logger.error("[SERVER] Init backend connection error. ", e);
                    throw new ServerException(e);
                }

                // 启用SQLHistory记录
                if (!ServerConfiguration.getSqlHistory().trim().isEmpty())
                {
                    String sqlHistoryDir = ServerConfiguration.getSqlHistoryDir();
                    if (sqlHistoryDir.trim().isEmpty())
                    {
                        sqlHistoryDir = ServerConfiguration.getData_Dir();
                    }
                    if (sqlHistoryDir.equalsIgnoreCase(":memory:"))
                    {
                        // 内存模式下SQL历史毫无意义，因此直接跳过
                        AppLogger.logger.warn("[SERVER] Skipped backend sql history database in memory. Please set sql_history_dir if you need sql_history feature in memory mode.");
                    }
                    else {
                        String sqlHistoryFile = Path.of(sqlHistoryDir, ServerConfiguration.getSqlHistory().trim()).toString();
                        backendSqlHistoryConnection = DriverManager.getConnection("jdbc:duckdb:" + sqlHistoryFile);
                        backendSqlHistoryConnection.setAutoCommit(false);
                        AppLogger.logger.info("[SERVER] Backend sql history database [{}] opened.", sqlHistoryFile);
                        SaveSqlHistory saveSqlHistoryHandler = new SaveSqlHistory();
                        saveSqlHistoryHandler.start();
                        stmt = backendSqlHistoryConnection.createStatement();
                        stmt.execute("CREATE SEQUENCE IF NOT EXISTS sql_history_id START 1");
                        stmt.execute("CREATE TABLE IF NOT EXISTS SQL_HISTORY " +
                                "(" +
                                "    ID             BIGINT Primary Key ," +
                                "    SessionID      BIGINT," +
                                "    ClientIP       TEXT," +
                                "    StartTime      TIMESTAMP," +
                                "    EndTime        TIMESTAMP," +
                                "    SqlID          BIGINT," +
                                "    SQL            TEXT," +
                                "    SqlCode        INT," +
                                "    AffectedRows   BIGINT," +
                                "    ErrorMsg       TEXT" +
                                ")");
                        stmt.close();
                    }
                }
            }

            // 执行初始化脚本，如果有必要的话
            // 只有内存数据库或者文件数据库第一次启动的时候需要执行
            if (databaseFirstOpened && !ServerConfiguration.getInit_schema().trim().isEmpty()) {
                List<String> initScriptFiles = new ArrayList<>();
                if (new File(ServerConfiguration.getInit_schema()).isFile()) {
                    initScriptFiles.add(new File(ServerConfiguration.getInit_schema()).getAbsolutePath());
                } else if (new File(ServerConfiguration.getInit_schema()).isDirectory()) {
                    File[] files = new File(ServerConfiguration.getInit_schema()).listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.isFile() && file.getName().endsWith(".sql")) {
                                initScriptFiles.add(file.getAbsolutePath());
                            }
                        }
                    }
                } else {
                    throw new ServerException(999,
                            "Init schema [" + ServerConfiguration.getInit_schema() + "] does not exist!");
                }
                Collections.sort(initScriptFiles);
                for (String initScriptFile : initScriptFiles) {
                    executeScript(initScriptFile);
                }
                AppLogger.logger.info("Init schema completed.");
            }
        }
        catch(SQLException | IOException e){
            DBInstance.state = "STARTUP FAILED";
            AppLogger.logger.error("[SERVER] Init backend connection error. ", e);
            throw new ServerException(e);
        }
    }

    public static int newSession(DBSession dbSession)
    {
        int currentSessionId;
        synchronized (PostgresServerHandler.class) {
            maxSessionId ++;
            currentSessionId = maxSessionId;
        }
        dbSessions.put(currentSessionId, dbSession);
        return currentSessionId;
    }

    public static DBSession getSession(int sessionId)
    {
        return dbSessions.get(sessionId);
    }

    // 保存SQL日志信息
    public static void saveSqlHistory()
    {
        try
        {
            if (backendSqlHistoryConnection != null)
            {
                backendSqlHistoryConnection.commit();
                Statement stmt = backendSqlHistoryConnection.createStatement();
                stmt.execute("FORCE CHECKPOINT");
                stmt.close();
            }
        }
        catch (SQLException ignored) {}
    }

    // 强制执行检查点
    public static void forceCheckPoint()
    {
        try {
            if (backendSysConnection != null && !backendSysConnection.isClosed() && !backendSysConnection.isReadOnly()) {
                Statement stmt = backendSysConnection.createStatement();
                stmt.execute("FORCE CHECKPOINT");
                stmt.close();
            }
        }
        catch (SQLException e) {
            AppLogger.logger.error("Force checkpoint failed.", e);
        }
    }
}
