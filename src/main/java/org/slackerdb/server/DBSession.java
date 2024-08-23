package org.slackerdb.server;

import org.duckdb.DuckDBAppender;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DBSession {
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
    // 当前正在执行的SQL
    public String executeSQL;
    // 当前会话状态  connected, dbConnected
    public String status = "N/A";
    // 客户端的IP地址
    public String clientAddress = "";
    // 最后一次服务请求的命令
    public String LastRequestCommand = null;

    // 保存的语句解析信息
    private final Map<String, PreparedStatement> preparedStatements = new HashMap<>();
    // 保存的语句解析信息，绑定的数据类型
    private final Map<String, int[]> preparedStatementParameterDataTypeIds = new HashMap<>();
    // 保存的结果集，便于分批查询的返回
    private final Map<String, ResultSet> resultSets = new HashMap<>();
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

    public ResultSet getResultSet(String portalName) {
        return resultSets.get(portalName);
    }

    public PreparedStatement getPreparedStatement(String portalName) {
        return preparedStatements.get(portalName);
    }

    public void closeSession() throws SQLException
    {
        // 关闭所有连接，并释放所有资源
        // 默认close的时候要执行Commit操作
        for (ResultSet resultSet : resultSets.values())
        {
            if (resultSet != null && !resultSet.isClosed()) {
                resultSet.close();
            }
        }
        for (PreparedStatement preparedStatement : preparedStatements.values())
        {
            if (preparedStatement != null && !preparedStatement.isClosed()) {
                preparedStatement.close();
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
            dbConnection.close();
            dbConnection = null;
        }
    }

    public void abortSession() throws SQLException
    {
        // 关闭所有连接，并释放所有资源
        // 默认abort的时候要执行Rollback操作
        for (ResultSet resultSet : resultSets.values())
        {
            if (resultSet != null && !resultSet.isClosed()) {
                resultSet.close();
            }
        }
        for (PreparedStatement preparedStatement : preparedStatements.values())
        {
            if (preparedStatement != null && !preparedStatement.isClosed()) {
                preparedStatement.close();
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
            dbConnection.close();
            dbConnection = null;
        }
    }

    public void savePreparedStatement(String portalName, PreparedStatement preparedStatement)
    {
        preparedStatements.put(portalName, preparedStatement);
    }

    public void clearPreparedStatement(String portalName) throws SQLException
    {
        if (preparedStatements.containsKey(portalName))
        {
            PreparedStatement preparedStatement = preparedStatements.get(portalName);
            if (!preparedStatement.isClosed()) {
                preparedStatement.close();
            }
            preparedStatements.remove(portalName);
        }
    }

    public void saveResultSet(String portalName, ResultSet resultSet) throws SQLException
    {
        if (resultSets.containsKey(portalName))
        {
            ResultSet oldResultSet = resultSets.get(portalName);
            if (!oldResultSet.isClosed()) {
                oldResultSet.close();
            }
            oldResultSet.close();
        }
        resultSets.put(portalName, resultSet);
    }

    public void clearResultSet(String portalName) throws SQLException
    {
        if (resultSets.containsKey(portalName))
        {
            ResultSet oldResultSet = resultSets.get(portalName);
            if (!oldResultSet.isClosed()) {
                oldResultSet.close();
            }
            oldResultSet.close();
            resultSets.remove(portalName);
        }
    }

    public void savePreparedStatementParameterDataTypeIds(String portalName, int[] dataTypeIds)
    {
        preparedStatementParameterDataTypeIds.put(portalName, dataTypeIds);
    }

    public void clearPreparedStatementParameterDataTypeIds(String portalName)
    {
        preparedStatementParameterDataTypeIds.remove(portalName);
    }

    public int[] getPreparedStatementParameterDataTypeIds(String portalName)
    {
        return preparedStatementParameterDataTypeIds.get(portalName);
    }
}
