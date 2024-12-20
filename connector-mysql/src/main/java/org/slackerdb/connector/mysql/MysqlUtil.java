package org.slackerdb.connector.mysql;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MysqlUtil {
    public static String getTableDDL(Connection mysqlConn, String schemaName, String tableName) throws SQLException
    {
        String querySQL = "SHOW CREATE TABLE " + schemaName + "." + tableName;
        String ret = null;
        Statement statement = mysqlConn.createStatement();
        ResultSet rs = statement.executeQuery(querySQL);
        if (rs.next())
        {
            ret = rs.getString(2);
        }
        rs.close();
        statement.close();
        return ret;
    }

    public static List<String> getTablePrimaryKeyColumns(Connection mysqlConn, String schemaName, String tableName) throws SQLException
    {
        String querySQL = """
                SELECT
                    kcu.ORDINAL_POSITION,
                    kcu.COLUMN_NAME
                FROM
                    INFORMATION_SCHEMA.TABLE_CONSTRAINTS AS tc
                JOIN
                    INFORMATION_SCHEMA.KEY_COLUMN_USAGE AS kcu
                ON
                    tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
                    AND tc.TABLE_SCHEMA = kcu.TABLE_SCHEMA
                    AND tc.TABLE_NAME = kcu.TABLE_NAME
                WHERE
                    tc.TABLE_SCHEMA = ?
                    AND tc.TABLE_NAME = ?
                    AND tc.CONSTRAINT_TYPE = 'PRIMARY KEY'
                ORDER BY
                    kcu.ORDINAL_POSITION;
                """;
        List<String> ret = new ArrayList<>();
        PreparedStatement preparedStatement = mysqlConn.prepareStatement(querySQL);
        preparedStatement.setString(1, schemaName);
        preparedStatement.setString(2, tableName);
        ResultSet rs = preparedStatement.executeQuery();
        while (rs.next())
        {
            ret.add(rs.getString(2));
        }
        rs.close();
        preparedStatement.close();
        return ret;
    }

    public static List<String> getTableColumns(Connection mysqlConn, String schemaName, String tableName) throws SQLException
    {
        String querySQL = """
                    SELECT  COLUMN_NAME
                    FROM    INFORMATION_SCHEMA.COLUMNS
                    WHERE   TABLE_SCHEMA = ?
                    AND     TABLE_NAME = ?
                    ORDER BY ORDINAL_POSITION;
                """;
        List<String> ret = new ArrayList<>();
        PreparedStatement preparedStatement = mysqlConn.prepareStatement(querySQL);
        preparedStatement.setString(1, schemaName);
        preparedStatement.setString(2, tableName);
        ResultSet rs = preparedStatement.executeQuery();
        while (rs.next())
        {
            ret.add(rs.getString(1));
        }
        rs.close();
        preparedStatement.close();
        return ret;
    }
}
