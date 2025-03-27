package org.slackerdb.dbserver.server;

import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBAppender;
import org.slackerdb.dbserver.entity.ParsedStatement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class DBSession {
    // 数据库实例
    private final DBInstance dbInstance;
    // TCP连接句柄
    private final ChannelHandlerContext ctx;
    // 数据库连接
    public Connection dbConnection = null;
    // 客户端连接建立时间
    public LocalDateTime connectedTime;
    // 数据库连接创建时间
    public LocalDateTime dbConnectedTime;
    // 客户端连接时候的选项
    public Map<String, String> startupOptions;
    // 当前是否处于事务当中
    public boolean inTransaction = false;
    // 当前会话状态  connected, dbConnected
    public String status = "N/A";
    // 客户端的IP地址
    public String clientAddress = "";
    // 保存的语句解析信息
    public final Map<String, ParsedStatement> parsedStatements = new HashMap<>();
    // 标记客户端是否请求了描述信息（如果请求需要返回RowDescription, 反之不返回)
    public boolean hasDescribeRequest = false;
    // 记录当前COPY操作的表名
    public String copyTableName = "";
    // 记录当前COPY的文件格式
    public String copyTableFormat = "";
    // 记录当前COPY的Appender
    public DuckDBAppender copyTableAppender = null;
    // 记录这个目标表在数据库的实际列名
    public List<Integer> copyTableDbColumnMapPos;
    // 上次由于不完整而没有复制的Copy剩余命令
    public String copyLastRemained = "";
    public long copyAffectedRows = 0;

    // 当前执行任务的语句
    public String executingSQL = "";
    // 当前所处的业务请求
    public String executingFunction = "";
    // 当前调用的开始时间
    public LocalDateTime executingTime = null;
    // 当前正在执行的句柄
    public PreparedStatement executingPreparedStatement = null;
    // SqlId, 考虑到SQL的分批执行情况，这里用SqlId来表示对应的信息
    public final AtomicLong executingSqlId = new AtomicLong();

    public DBSession(DBInstance pDbInstance, ChannelHandlerContext pCtx)
    {
        dbInstance = pDbInstance;
        ctx = pCtx;
    }

    public ChannelHandlerContext getContext()
    {
        return ctx;
    }

    public ParsedStatement getParsedStatement(String portalName) {
        return parsedStatements.get(portalName);
    }

    public void closeSession() throws SQLException
    {
        // 关闭所有连接，并释放所有资源
        // 默认close的时候要执行Commit操作
        for (ParsedStatement parsedStatement : parsedStatements.values())
        {
            if (parsedStatement != null) {
                if (parsedStatement.preparedStatement != null && !parsedStatement.preparedStatement.isClosed()) {
                    parsedStatement.preparedStatement.close();
                }
                if (parsedStatement.resultSet != null && !parsedStatement.resultSet.isClosed()) {
                    parsedStatement.resultSet.close();
                }
            }
        }
        if (copyTableAppender != null)
        {
            copyTableAppender.close();
        }
        if (dbConnection != null && !dbConnection.isClosed())
        {
            if (!dbConnection.isReadOnly())
            {
                try {
                    dbConnection.commit();
                }
                catch (SQLException e) {
                    if (!e.getMessage().contains("no transaction is active"))
                    {
                        throw e;
                    }
                }
            }
            dbInstance.dbDataSourcePool.releaseConnection(dbConnection);
        }
    }

    public void abortSession() throws SQLException
    {
        // 关闭所有连接，并释放所有资源
        // 默认abort的时候要执行Rollback操作
        for (ParsedStatement parsedStatement : parsedStatements.values())
        {
            if (parsedStatement != null) {
                if (parsedStatement.preparedStatement != null && !parsedStatement.preparedStatement.isClosed()) {
                    parsedStatement.preparedStatement.close();
                }
                if (parsedStatement.resultSet != null && !parsedStatement.resultSet.isClosed()) {
                    parsedStatement.resultSet.close();
                }
            }
        }
        if (copyTableAppender != null)
        {
            copyTableAppender.close();
        }
        if (dbConnection != null && !dbConnection.isClosed())
        {
            if (!dbConnection.isReadOnly())
            {
                try {
                    dbConnection.rollback();
                }
                catch (SQLException e) {
                    if (!e.getMessage().contains("no transaction is active"))
                    {
                        throw e;
                    }
                }
            }
            dbInstance.dbDataSourcePool.releaseConnection(dbConnection);
        }
    }

    public void saveParsedStatement(String portalName, ParsedStatement parsedPrepareStatement)
    {
        parsedStatements.put(portalName, parsedPrepareStatement);
    }
    public void clearParsedStatement(String portalName) throws SQLException
    {
        if (parsedStatements.containsKey(portalName))
        {
            PreparedStatement preparedStatement = parsedStatements.get(portalName).preparedStatement;
            if (preparedStatement != null && !preparedStatement.isClosed()) {
                preparedStatement.close();
            }
            parsedStatements.remove(portalName);
        }
    }

}
