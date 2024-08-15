package org.slackerdb.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class DBSession {
    public Connection dbConnection = null;
    public LocalDateTime connectedTime;
    public LocalDateTime dbConnectedTime;
    public Map<String, String> startupOptions;
    public boolean inTransaction = false;
    public String executeSQL;
    public String status = "N/A";
    public String clientAddress = "";
    public String LastRequestCommand = null;

    private final Map<String, PreparedStatement> preparedStatements = new HashMap<>();
    private final Map<String, int[]> preparedStatementParameterDataTypeIds = new HashMap<>();
    private final Map<String, ResultSet> resultSets = new HashMap<>();
    public boolean hasDescribeRequest = false;

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
            if (!resultSet.isClosed()) {
                resultSet.close();
            }
        }
        for (PreparedStatement preparedStatement : preparedStatements.values())
        {
            if (!preparedStatement.isClosed()) {
                preparedStatement.close();
            }
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
            if (!resultSet.isClosed()) {
                resultSet.close();
            }
        }
        for (PreparedStatement preparedStatement : preparedStatements.values())
        {
            if (!preparedStatement.isClosed()) {
                preparedStatement.close();
            }
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

    public void savePreparedStatement(String portalName, PreparedStatement preparedStatement) throws SQLException
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
