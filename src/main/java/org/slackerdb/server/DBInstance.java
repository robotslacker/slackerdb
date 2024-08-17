package org.slackerdb.server;

import org.slackerdb.configuration.ServerConfiguration;
import org.slackerdb.exceptions.ServerException;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.protocol.postgres.server.PostgresServerHandler;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

public class DBInstance {
    public static String state = "";
    public static Connection backendSysConnection;
    // 为每个连接创建一个会话ID
    private static int maxSessionId = 1000;
    // 记录会话列表
    public static Map<Integer, DBSession> dbSessions = new HashMap<>();

    public static void abortSession(int sessionId) throws SQLException {
        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        dbSession.abortSession();

        // 移除会话信息
        dbSessions.remove(sessionId);
    }

    public static void closeSession(int sessionId) throws SQLException {
        // 销毁会话保持的数据库信息
        DBSession dbSession = dbSessions.get(sessionId);
        dbSession.closeSession();

        // 移除会话信息
        dbSessions.remove(sessionId);
    }

    public static void init() throws ServerException {
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
        if (ServerConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:"))
        {
            backendConnectString = backendConnectString + ":memory:" + instanceName;
        }
        else
        {
            if (!new File(ServerConfiguration.getData_Dir()).isDirectory())
            {
                throw new ServerException(999,
                        "Data directory [" + ServerConfiguration.getData_Dir() + "] does not exist!");
            }
            File dataFile = new File(ServerConfiguration.getData_Dir(), instanceName + ".db");
            if (!dataFile.canRead() && ServerConfiguration.getAccess_mode().equalsIgnoreCase("READ_ONLY"))
            {
                throw new ServerException(999,
                        "Data [" + dataFile.getAbsolutePath() + "] can't be read!!");
            }
            if (!dataFile.exists() && !new File(ServerConfiguration.getData_Dir()).canWrite())
            {
                throw new ServerException(999,
                        "Data [" + dataFile.getAbsolutePath() + "] can't be create!!");
            }
            if (dataFile.exists() && !dataFile.canWrite() && ServerConfiguration.getAccess_mode().equalsIgnoreCase("READ_WRITE"))
            {
                throw new ServerException(999,
                        "Data [" + dataFile.getAbsolutePath() + "] can't be write!!");
            }
            backendConnectString = backendConnectString + dataFile.getAbsolutePath();
        }

        // 初始化一个DB连接，以保证即使所有客户端都断开连接，服务端会话仍然会继续存在
        try {
            Properties connectProperties = new Properties();
            if (ServerConfiguration.getAccess_mode().equals("READ_ONLY")) {
                connectProperties.setProperty("duckdb.read_only", "true");
            }
            backendSysConnection = DriverManager.getConnection(backendConnectString, connectProperties);
            AppLogger.logger.info("[SERVER] Backend database [{}:{}] opened.",
                    ServerConfiguration.getData_Dir(), ServerConfiguration.getData());
        }
        catch (SQLException e) {
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
}
